package com.wemade.teslamacro.domain.safety

import org.junit.Assert.*
import java.time.LocalDate
import kotlinx.serialization.json.Json
import org.junit.Test

class CameraIndexTest {
    private val index = CameraIndex(listOf(OfflineCamera("test", 37.003, 127.0, 50)))

    /** 전방 거리와 후보 제한속도를 함께 반환한다. */
    @Test fun approaching() {
        val alert = index.nearest(37.0, 127.0, 0.0, 60.0, 10.0)
        assertEquals(50, alert?.speedLimitKph)
        assertTrue(alert!!.distanceMeters!! in 330..340)
    }

    /** 반대 방향·너무 먼 횡방향 후보·저정밀·정지·범위 밖은 안내하지 않는다. */
    @Test fun rejectedFixes() {
        assertNull(index.nearest(37.0, 127.0, 180.0, 60.0, 10.0))
        assertNull(index.nearest(37.0, 127.0031, 0.0, 60.0, 10.0))
        assertNull(index.nearest(37.0, 127.0, 0.0, 60.0, 31.0))
        assertNull(index.nearest(37.0, 127.0, 0.0, 0.0, 10.0))
        assertNull(index.nearest(36.99, 127.0, 0.0, 60.0, 10.0))
        assertNull(index.nearest(37.0, 127.0, Double.NaN, 60.0, 10.0))
    }

    /** 완만한 굴곡은 받아들이되 현 위치에서 거의 가로질러야 하는 후보와 지난 후보는 버린다. */
    @Test fun gentleCurveAndDepartingCamera() {
        val curved = CameraIndex(listOf(OfflineCamera("curve", 37.003, 127.00023, 30)))
        assertEquals(30, curved.nearest(37.0, 127.0, 4.0, 60.0, 10.0)?.speedLimitKph)
        assertNull(curved.nearest(37.0, 127.0, 90.0, 60.0, 10.0))
        assertNull(index.nearest(37.004, 127.0, 0.0, 60.0, 10.0))
        val farther = CameraIndex(listOf(OfflineCamera("farther", 37.006, 127.0, 60)))
        assertEquals(60, farther.nearest(37.0, 127.0, 0.0, 60.0, 10.0)?.speedLimitKph)
    }

    /** 현재 후보 뒤 1km 안의 같은 방향 카메라만 연속 구간으로 본다. 같은 지점 중복·반대편·1km 밖은 제외한다. */
    @Test fun followingCameraWithinOneKilometer() {
        val pair = CameraIndex(listOf(OfflineCamera("a", 37.003, 127.0, 50), OfflineCamera("b", 37.006, 127.0, 50)))
        assertTrue(pair.hasFollowing(37.0, 127.0, 0.0, afterMeters = 332))
        assertFalse(pair.hasFollowing(37.0, 127.0, 180.0, afterMeters = 332))
        val duplicate = CameraIndex(listOf(OfflineCamera("a", 37.003, 127.0, 50), OfflineCamera("a2", 37.0031, 127.0, 60)))
        assertFalse(duplicate.hasFollowing(37.0, 127.0, 0.0, afterMeters = 332))
        val far = CameraIndex(listOf(OfflineCamera("a", 37.003, 127.0, 50), OfflineCamera("c", 37.0135, 127.0, 50)))
        assertFalse(far.hasFollowing(37.0, 127.0, 0.0, afterMeters = 332))
    }

    /** 50 간선도로 주행 중 회랑 안에 든 옆 골목 30 카메라는 매칭 도로명이 다르면 거르고, 같은 도로 카메라는 남긴다. */
    @Test fun matchedRoadNameRejectsSideStreetCamera() {
        val side = OfflineCamera("side", 37.002, 127.0001, 30, roadName = "중앙로10번길")
        val inline = OfflineCamera("inline", 37.004, 127.0, 50, roadName = "중앙로 (시청 앞)")
        val both = CameraIndex(listOf(side, inline))
        assertEquals(30, both.nearest(37.0, 127.0, 0.0, 50.0, 10.0)?.speedLimitKph)
        val matched = both.nearest(37.0, 127.0, 0.0, 50.0, 10.0, matchedRoadName = "중앙로")
        assertEquals(50, matched?.speedLimitKph)
        assertEquals("중앙로 (시청 앞)", matched?.cameraRoadName)
        assertFalse(CameraIndex(listOf(side)).hasFollowing(37.0, 127.0, 0.0, afterMeters = 100, matchedRoadName = "중앙로"))
    }

    /** 한쪽 도로명이 없거나 노선번호 표기면 실제 카메라를 놓치지 않게 기존 회랑 판정을 유지한다. */
    @Test fun unknownRoadNameKeepsCorridorCandidate() {
        val unnamed = CameraIndex(listOf(OfflineCamera("unnamed", 37.003, 127.0, 30, roadName = "")))
        assertEquals(30, unnamed.nearest(37.0, 127.0, 0.0, 50.0, 10.0, matchedRoadName = "중앙로")?.speedLimitKph)
        val route = CameraIndex(listOf(OfflineCamera("route", 37.003, 127.0, 60, roadName = "4번 국도")))
        assertEquals(60, route.nearest(37.0, 127.0, 0.0, 50.0, 10.0, matchedRoadName = "중앙로")?.speedLimitKph)
        val named = CameraIndex(listOf(OfflineCamera("named", 37.003, 127.0, 30, roadName = "옆길")))
        for (roadName in listOf(null, "", "Main Street")) {
            assertEquals(30, named.nearest(37.0, 127.0, 0.0, 50.0, 10.0, matchedRoadName = roadName)?.speedLimitKph)
        }
    }

    /** 연속 안내도 주 경보와 같은 회랑을 써서 보정 좌표가 바뀌어도 옆 도로를 오인하지 않는다. */
    @Test fun followingCameraIgnoresParallelRoad() {
        val side = OfflineCamera("parallel", 37.005, 127.001, 30)
        val inline = OfflineCamera("following", 37.006, 127.0, 60)
        val onlySide = CameraIndex(listOf(side))
        val withInline = CameraIndex(listOf(side, inline))
        for (longitude in listOf(127.0, 127.0001)) {
            assertFalse(onlySide.hasFollowing(37.0, longitude, 0.0, afterMeters = 333))
            assertTrue(withInline.hasFollowing(37.0, longitude, 0.0, afterMeters = 333))
        }
    }

    /** 선택한 시작 거리 바깥은 화면·과속음 후보에서 빼되 기본 최대 거리는 유지한다. */
    @Test fun configuredAlertDistance() {
        val farther = CameraIndex(listOf(OfflineCamera("farther", 37.006, 127.0, 50)))
        assertNull(farther.nearest(37.0, 127.0, 0.0, 60.0, 10.0, maxDistanceMeters = 500))
        assertNotNull(farther.nearest(37.0, 127.0, 0.0, 60.0, 10.0, maxDistanceMeters = 700))
        assertNull(index.nearest(37.0, 127.0, 0.0, 60.0, 10.0, maxDistanceMeters = 300))
        assertNotNull(index.nearest(37.001, 127.0, 0.0, 60.0, 10.0, maxDistanceMeters = 300))
    }

    /** 도로 매칭용 1km 탐색은 경보 후보의 700m 범위와 분리해 유지한다. */
    @Test fun roadMatchProximity() {
        assertTrue(index.hasNearby(36.995, 127.0, 0.0))
        assertTrue(index.hasNearby(37.0036, 127.0, 0.0))
        assertTrue(index.hasNearby(37.0, 127.0, 90.0))
        assertFalse(index.hasNearby(37.004, 127.0, 0.0))
        assertFalse(index.hasNearby(37.0, 127.0, 180.0))
        assertFalse(index.hasNearby(36.98, 127.0, 0.0))
        assertFalse(index.hasNearby(Double.NaN, 127.0, 0.0))
        assertFalse(index.hasNearby(37.0, 127.0, Double.NaN))
    }

    /** 비정상 데이터만 든 목록을 정상 로드로 취급하지 않는다. */
    @Test fun invalidDatasetIsEmpty() {
        assertTrue(CameraIndex(emptyList()).isEmpty)
        assertTrue(CameraIndex(listOf(
            OfflineCamera("bad-coordinate", Double.NaN, 127.0, 50),
            OfflineCamera("bad-limit", 37.0, 127.0, 0),
            OfflineCamera("foreign", 0.0, 0.0, 50),
        )).isEmpty)
        assertFalse(index.isEmpty)
    }

    /** 제한 경계·음수·비정상 입력은 후보 판정을 우회하지 못한다. */
    @Test fun motionAndAccuracyBoundaries() {
        assertNotNull(index.nearest(37.0, 127.0, 0.0, 5.0, 30.0))
        assertNull(index.nearest(37.0, 127.0, 0.0, 4.999, 30.0))
        assertNull(index.nearest(37.0, 127.0, 0.0, 60.0, -1.0))
        assertNull(index.nearest(37.0, 127.0, 0.0, 60.0, Double.NaN))
        assertNull(index.nearest(37.0, 127.0, 0.0, Double.NaN, 10.0))
        assertNull(index.nearest(Double.NaN, 127.0, 0.0, 60.0, 10.0))
        assertEquals(index.nearest(37.0, 127.0, 0.0, 60.0, 10.0),
            index.nearest(37.0, 127.0, 360.0, 60.0, 10.0))
    }

    /** 이웃 격자 경계에서도 695m 후보를 놓치지 않고 705m는 제외한다. */
    @Test fun allHeadingsAcrossCells() {
        for (latitude in listOf(33.0199, 37.0199, 38.9799)) {
            for (bearing in 0 until 360 step 15) {
                for (distance in listOf(9, 11, 695, 705)) {
                    val angle = Math.toRadians(bearing.toDouble())
                    val north = distance * kotlin.math.cos(angle) / 111_195.0
                    val east = distance * kotlin.math.sin(angle) /
                        (111_195.0 * kotlin.math.cos(Math.toRadians(latitude)))
                    val local = CameraIndex(listOf(OfflineCamera("edge", latitude + north, 127.0199 + east, 50)))
                    val alert = local.nearest(latitude, 127.0199, bearing.toDouble(), 60.0, 10.0)
                    assertEquals("$latitude / $bearing / $distance", distance in 10..700, alert != null)
                }
            }
        }
    }

    /** GPS·매칭 좌표가 번갈아도 양쪽 옆 도로 30은 빼고 실제 전방 30·50·60은 유지한다. */
    @Test fun correctedAndRawFixesKeepSameAlertCorridor() {
        for (limit in listOf(30, 50, 60)) {
            val ahead = CameraIndex(listOf(OfflineCamera("ahead", 37.004, 127.0, limit)))
            for (longitude in listOf(127.0, 127.0001)) {
                assertEquals(limit, ahead.nearest(37.0, longitude, 0.0, 60.0, 10.0)?.speedLimitKph)
            }
        }
        for (offset in listOf(-0.0017, -0.0009, 0.0009, 0.0017)) {
            val adjacent = CameraIndex(listOf(OfflineCamera("adjacent", 37.004, 127.0 + offset, 30)))
            assertTrue(adjacent.hasNearby(37.0, 127.0, 0.0))
            for (speed in listOf(50.0, 60.0)) for (longitude in listOf(127.0, 127.0001)) {
                assertNull(adjacent.nearest(37.0, longitude, 0.0, speed, 10.0))
            }
        }
    }

    /** 더 가까운 평행도로 30 제한을 건너뛰고 같은 진행선의 50 제한을 안내한다. */
    @Test fun sideRoadCannotHideForwardCamera() {
        val cameras = CameraIndex(listOf(
            OfflineCamera("side", 37.003, 127.0009, 30),
            OfflineCamera("ahead", 37.005, 127.0, 50),
        ))
        assertEquals(50, cameras.nearest(37.0, 127.0, 0.0, 60.0, 10.0)?.speedLimitKph)
        assertEquals(50, cameras.nearest(37.0, 127.0001, 0.0, 60.0, 10.0)?.speedLimitKph)
    }

    /** 가장 가까운 유효 후보와 구간 카메라 표기를 확인한다. */
    @Test fun closestCandidateAndSection() {
        val local = CameraIndex(listOf(
            OfflineCamera("far", 37.004, 127.0, 80),
            OfflineCamera("near", 37.002, 127.0, 30, section = true),
            OfflineCamera("behind", 36.999, 127.0, 50),
        ))
        val alert = local.nearest(37.0, 127.0, 0.0, 60.0, 10.0)
        assertEquals(30, alert?.speedLimitKph)
        assertEquals(SafetyKind.SECTION_CAMERA, alert?.kind)
    }

    /** 같은 좌표의 30/50 제한을 파일 순서로 택하지 않고 불확실성을 표시한다. */
    @Test fun conflictingLimitsRequireRoadSign() {
        val cameras = listOf(
            OfflineCamera("first", 37.003, 127.0, 30),
            OfflineCamera("second", 37.003, 127.0, 50),
        )
        val first = CameraIndex(cameras).nearest(37.0, 127.0, 0.0, 60.0, 10.0)
        val reversed = CameraIndex(cameras.reversed()).nearest(37.0, 127.0, 0.0, 60.0, 10.0)
        assertEquals(first, reversed)
        assertTrue(first!!.limitConflict)
        assertNull(first.speedLimitKph)
        assertFalse(SafetyState(ready = true, alert = first, speedKph = 60.0).isOverSpeed())
        assertFalse(SafetyState(ready = true, alert = first.copy(speedLimitKph = 30), speedKph = 60.0).isOverSpeed())
        val duplicates = CameraIndex(listOf(cameras[0], cameras[0]))
            .nearest(37.0, 127.0, 0.0, 60.0, 10.0)
        assertEquals(30, duplicates?.speedLimitKph)
        assertFalse(duplicates!!.limitConflict)
    }

    /** 번들의 수집일과 개별 기관 기준일을 분리해 오래된 자료만 경고한다. */
    @Test fun sourceDatesAndDatasetCompatibility() {
        val today = LocalDate.of(2026, 9, 23)
        val dataset = Json { ignoreUnknownKeys = true }.decodeFromString<CameraDataset>(
            """{"retrievedAt":"2026-09-22T07:28:01+00:00","cameras":[{"id":"dated","latitude":37.003,"longitude":127.0,"speedLimitKph":50,"referenceDate":"2022-12-15"}]}"""
        )
        assertNull(sourceDateWarning(dataset.retrievedAt, today, 6, "목록 수집일"))
        val alert = CameraIndex(dataset.cameras).nearest(37.0, 127.0, 0.0, 60.0, 10.0, today)
        assertEquals("자료 기준일 2022-12-15 · 갱신 확인", alert?.dateWarning)
        assertEquals("37.003,127.0", alert?.cameraKey)
        assertEquals(alert?.cameraKey, CameraIndex(dataset.cameras.reversed())
            .nearest(37.0, 127.0, 0.0, 60.0, 10.0, today)?.cameraKey)
        assertEquals("목록 수집일 2026-03-22 · 갱신 확인",
            sourceDateWarning("2026-03-22T00:00:00Z", today, 6, "목록 수집일"))
        assertNull(sourceDateWarning("2026-03-23T00:00:00Z", today, 6, "목록 수집일"))
        assertNull(sourceDateWarning("2025-09-23", today, 12, "자료 기준일"))
        assertEquals("자료 기준일 2025-09-22 · 갱신 확인",
            sourceDateWarning("2025-09-22", today, 12, "자료 기준일"))
        assertEquals("자료 기준일 2026-09-24 · 갱신 확인",
            sourceDateWarning("2026-09-24", today, 12, "자료 기준일"))
        assertEquals("자료 기준일 확인 필요", sourceDateWarning(null, today, 12, "자료 기준일"))
        assertEquals("자료 기준일 확인 필요", sourceDateWarning("bad-date", today, 12, "자료 기준일"))
        assertEquals("자료 기준일 확인 필요", sourceDateWarning("2026-09-23x", today, 12, "자료 기준일"))
        assertNull(Json.decodeFromString<CameraDataset>("""{"cameras":[]}""").retrievedAt)
    }

    /** 한 좌표에 다른 기준일이 있으면 목록 순서와 무관하게 가장 오래된 값을 경고한다. */
    @Test fun duplicateCoordinateKeepsOldestReferenceDate() {
        val cameras = listOf(
            OfflineCamera("new", 37.003, 127.0, 50, referenceDate = "2026-08-11"),
            OfflineCamera("old", 37.003, 127.0, 50, referenceDate = "2022-12-15"),
        )
        val today = LocalDate.of(2026, 9, 23)
        val first = CameraIndex(cameras).nearest(37.0, 127.0, 0.0, 60.0, 10.0, today)
        val second = CameraIndex(cameras.reversed()).nearest(37.0, 127.0, 0.0, 60.0, 10.0, today)
        assertEquals("2022-12-15", first?.referenceDate)
        assertEquals(first, second)
    }

    /** 좌표 중복에서 한 기관의 날짜가 잘못되면 다른 기관의 정상 날짜로 문제를 감추지 않는다. */
    @Test fun invalidDuplicateReferenceDateNeedsConfirmation() {
        val today = LocalDate.of(2026, 9, 23)
        val cameras = listOf(
            OfflineCamera("dated", 37.003, 127.0, 50, referenceDate = "2026-09-22"),
            OfflineCamera("invalid", 37.003, 127.0, 50, referenceDate = "not-a-date"),
        )
        for (records in listOf(cameras, cameras.reversed())) {
            val alert = CameraIndex(records).nearest(37.0, 127.0, 0.0, 60.0, 10.0, today)
            assertEquals("자료 기준일 확인 필요", alert?.dateWarning)
        }
        assertNull(CameraIndex(cameras.take(1)).nearest(37.0, 127.0, 0.0, 60.0, 10.0, today)?.dateWarning)
    }

    /** GPS 단절이나 미준비 상태에 남은 카메라로 경보를 만들지 않는다. */
    @Test fun unavailableState() {
        val alert = SafetyAlert(SafetyKind.SPEED_CAMERA, 300, 50)
        assertFalse(SafetyState(alert = alert).isOverSpeed(100.0))
        assertFalse(SafetyState(ready = true, stalled = true, alert = alert).isOverSpeed(100.0))
        assertFalse(SafetyState(ready = true, alert = alert).isOverSpeed(Double.POSITIVE_INFINITY))
    }
}
