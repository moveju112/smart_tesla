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

    /** 굽은 도로와 600m 밖 후보는 수용하되, 전방 40도 밖은 경보하지 않는다. */
    @Test fun forwardCandidatesWithinNarrowedBounds() {
        val curved = CameraIndex(listOf(OfflineCamera("curve", 37.003, 127.002, 50)))
        assertNotNull(curved.nearest(37.0, 127.0, 0.0, 60.0, 10.0))
        assertNotNull(index.nearest(37.0, 127.001, 0.0, 60.0, 10.0))
        assertNotNull(index.nearest(37.0, 127.0, 35.0, 60.0, 10.0))
        val close = CameraIndex(listOf(OfflineCamera("close", 37.001, 127.0, 50)))
        assertNotNull(close.nearest(37.0, 127.0, 39.0, 60.0, 10.0))
        assertNull(close.nearest(37.0, 127.0, 41.0, 60.0, 10.0))
        val farther = CameraIndex(listOf(OfflineCamera("farther", 37.006, 127.0, 50)))
        assertNotNull(farther.nearest(37.0, 127.0, 0.0, 60.0, 10.0))
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

    /** 방향 안쪽이어도 횡방향 200m를 넘는 옆 도로 카메라는 제외한다. */
    @Test fun lateralDistanceBoundary() {
        val inside = CameraIndex(listOf(OfflineCamera("inside", 37.003, 127.0022, 50)))
        val outside = CameraIndex(listOf(OfflineCamera("outside", 37.003, 127.00235, 50)))
        assertNotNull(inside.nearest(37.0, 127.0, 0.0, 60.0, 10.0))
        assertNull(outside.nearest(37.0, 127.0, 0.0, 60.0, 10.0))
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
