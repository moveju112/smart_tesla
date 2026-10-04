package com.wemade.teslamacro.data.safety

import android.app.Application
import android.location.Location
import android.util.AtomicFile
import app.cash.paparazzi.Paparazzi
import com.wemade.teslamacro.domain.safety.CameraIndex
import com.wemade.teslamacro.domain.safety.OfflineCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/** 매칭 도로명이 늦게 오거나 매칭 간격 사이에 비어도 옆 골목 카메라를 먼저 읽지 않는지 확인한다. */
@OptIn(ExperimentalCoroutinesApi::class)
class RoadNameGuideTest {
    @get:Rule val paparazzi = Paparazzi()
    @get:Rule val temporary = TemporaryFolder()

    /** 50 간선(중앙로) 주행 중 전방 옆 골목(중앙로10번길) 30 카메라는 매칭 도로명이 오면 끝까지 안내하지 않는다. */
    @Test fun sideStreetCameraWaitsForRoadNameAndIsDropped() = runTest {
        val spoken = drive(roadName = "중앙로", observe = { second, guide ->
            if (second >= 2) assertTrue("다른 도로가 확인되면 화면에서도 제외", guide.state.value.alert == null)
        })
        assertTrue("옆 골목 카메라를 읽으면 안 된다: $spoken", spoken.isEmpty())
    }

    /** 다른 도로로 제외한 후보는 매칭 공백 뒤 돌아와도 이전 대기 만료를 재사용하지 않는다. */
    @Test fun rejectedSideStreetWaitsAgainDuringMatchingGap() = runTest {
        val spoken = drive(roadName = "중앙로", roadNameAtSecond = { second ->
            "중앙로".takeIf { second == 4 || second >= 13 }
        })
        assertTrue("도로명이 복구되기 전 옆 골목을 성급히 안내하면 안 된다: $spoken", spoken.isEmpty())
    }

    /** 방향이 알려진 재등장 후보는 도로명을 계속 못 받으면 새 대기 시작부터 6초 뒤 기존 안내로 복귀한다. */
    @Test fun rejectedCameraStillFallsBackWhenMatchingDoesNotRecover() = runTest {
        val spoken = drive(roadName = "중앙로", cameraDirection = 0, roadNameAtSecond = { second ->
            "중앙로".takeIf { second == 4 }
        })
        assertTrue("10초에 다시 나타난 후보는 6초 대기와 접근 확인 뒤 안내: $spoken",
            spoken.firstOrNull()?.second == 17)
    }

    /** 같은 도로 카메라는 매칭 좌표가 3초마다만 갱신돼도 접근 확인을 통과해 제때 안내한다. */
    @Test fun sameRoadCameraIsAnnouncedWhileMatched() = runTest {
        val spoken = drive(roadName = "중앙로", cameraRoadName = "중앙로")
        assertTrue("같은 도로 카메라는 곧바로 안내: $spoken", spoken.firstOrNull()?.let { it.second <= 3 } == true)
    }

    /** 방향이 알려진 카메라는 매칭이 계속 불확실하면 6초 뒤 기존 판정대로 안내해 실제 카메라를 놓치지 않는다. */
    @Test fun unknownRoadNameFallsBackAfterWait() = runTest {
        val spoken = drive(roadName = null, cameraDirection = 0)
        // 200m 안 과속 감속 요청이 뒤따를 수 있으므로 첫 진입 안내만 본다.
        val first = spoken.first()
        assertTrue("6초 대기 뒤 안내: $spoken", first.second >= 6)
        assertTrue(first.first.contains("시속 30킬로미터"))
    }

    /** 방향 미상 교차도로는 매칭 실패 뒤에도 도로 축으로 제외해 음성을 되살리지 않는다. */
    @Test fun crossingRoadDoesNotAnnounceWhenMatchingFails() = runTest {
        val spoken = drive(roadName = null, cameraRoadName = "", cameraRoadAxis = 90)
        assertTrue("교차도로 안내가 없어야 한다: $spoken", spoken.isEmpty())
    }

    /** 30m 안의 반대 차로로 붙어도 실제 GPS 진행방향의 카메라를 숨기지 않는다. */
    @Test fun oppositeMatchedHeadingCannotReplaceCurrentTravelDirection() = runTest {
        val spoken = drive(roadName = "중앙로", cameraRoadName = "", reverseMatchedHeading = true,
            cameraLatitude = 37.005, cameraDirection = 0)
        assertTrue("매칭 이후 500m에 진입해도 실제 전방을 제때 안내: $spoken",
            spoken.firstOrNull()?.let { it.second <= 6 } == true)
    }

    /** 회전 전 도로명이 남아도 진행방향이 상충하면 새 도로의 카메라를 영구 차단하지 않는다. */
    @Test fun staleOppositeRoadNameCannotSuppressActualCamera() = runTest {
        val spoken = drive(roadName = "이전길", cameraRoadName = "중앙로", reverseMatchedHeading = true,
            cameraLatitude = 37.005, cameraDirection = 0)
        assertTrue("상충한 옛 도로명을 버리고 제한된 대기 뒤 안내: $spoken",
            spoken.firstOrNull()?.let { it.second <= 12 } == true)
    }

    /** 도로 축만 같아도 방향과 도로명이 불명확하면 소리를 허용하지 않는다. */
    @Test fun sameRoadAxisAloneDoesNotAllowAudio() = runTest {
        val spoken = drive(roadName = null, cameraRoadName = "", cameraRoadAxis = 0)
        assertTrue("축만으로 같은 도로라고 확정하면 안 된다: $spoken", spoken.isEmpty())
    }

    /** 단속 방향을 아는 가까운 카메라는 6초 이름 대기를 하다 지나치지 않는다. */
    @Test fun closeCameraKeepsTimeForApproachConfirmation() = runTest {
        val spoken = drive(roadName = null, cameraRoadName = "중앙로", cameraLatitude = 37.0006, cameraDirection = 0)
        assertTrue("가까운 실제 카메라를 지나기 전에 안내: $spoken",
            spoken.firstOrNull()?.let { it.second == 1 && it.first.contains("시속 30킬로미터") } == true)
    }

    /** 매칭을 사용하지 않아도 화면 후보는 남기되 시간 경과·근거리로 소리를 허용하지 않는다. */
    @Test fun unknownDirectionStaysVisibleAndSilentOffline() = runTest {
        var visible = 0
        val spoken = drive(roadName = null, matching = false, cameraLatitude = 37.002,
            observe = { _, guide ->
                if (guide.state.value.alert != null) visible++
                assertTrue("방향 미상 오프라인 경고음 차단", (field("warningJob").get(guide) as Job?)?.isActive != true)
            })
        assertTrue("화면 후보 유지", visible > 0)
        assertTrue("시간 경과나 근거리에서도 음성 보류: $spoken", spoken.isEmpty())
    }

    /** 카메라 도로명이 없으면 매칭 성공만으로 같은 도로라고 간주하지 않는다. */
    @Test fun missingCameraRoadNameStaysSilentWhileMatching() = runTest {
        val spoken = drive(roadName = "중앙로", cameraRoadName = "")
        assertTrue("도로명 누락 후보 음성 보류: $spoken", spoken.isEmpty())
    }

    /** 인증 거절 뒤 오프라인 복귀도 방향 미상 음성을 다시 허용하지 않는다. */
    @Test fun unknownDirectionStaysSilentAfterAuthenticationRejection() = runTest {
        val spoken = drive(roadName = null, rejectAuthentication = true)
        assertTrue("인증 거절 뒤에도 음성 보류: $spoken", spoken.isEmpty())
    }

    /** 바로 앞 카메라를 지날 때까지 매칭이 없으면 화면만 표시하며 뒤늦은 결과로 소리를 내지 않는다. */
    @Test fun lateRoadEvidenceDoesNotAnnouncePassedUnknownCamera() = runTest {
        var visible = false
        val spoken = drive(roadName = "중앙로", cameraRoadName = "중앙로", cameraLatitude = 37.0006,
            roadNameAtSecond = { second -> "중앙로".takeIf { second >= 7 } },
            observe = { _, guide ->
                visible = visible || guide.state.value.alert != null
                assertTrue("근거리·늦은 매칭에도 경고음 보류", (field("warningJob").get(guide) as Job?)?.isActive != true)
            })
        assertTrue("지나기 전 화면 후보 유지", visible)
        assertTrue("지난 카메라를 늦게 읽지 않음: $spoken", spoken.isEmpty())
    }

    /** 유효 도로명 만료 시 반복음을 끊고, 복구 후 연속 접근을 다시 확인한다. */
    @Test fun unknownDirectionStopsAudioWhenRoadEvidenceExpires() = runTest {
        val sounding = mutableSetOf<Int>()
        var visibleDuringGap = false
        val spoken = drive(roadName = "중앙로", cameraRoadName = "중앙로",
            roadNameAtSecond = { second -> "중앙로".takeIf { second <= 4 || second >= 16 } },
            observe = { second, guide ->
                if ((field("warningJob").get(guide) as Job?)?.isActive == true) sounding += second
                if (second in 10..17 && guide.state.value.alert != null) visibleDuringGap = true
                if (second in 10..17) assertTrue("근거 만료 뒤 합성·재생 중인 음성 취소",
                    field("activeSpeechRequest").get(guide) == null)
            })
        assertTrue("같은 도로 접근 음성 허용: $spoken", spoken.isNotEmpty())
        assertTrue("근거가 있을 때 경고음", sounding.any { it < 10 })
        assertTrue("매칭 공백에도 화면 유지", visibleDuringGap)
        assertTrue("만료된 근거로 반복음 금지: $sounding", sounding.none { it in 10..17 })
        assertTrue("복구 후 재확인하면 경고음 재개: $sounding", sounding.any { it >= 18 })
        assertTrue("만료 구간 감속 음성 금지: $spoken", spoken.none { it.second in 10..17 })
    }

    // 1초마다 북쪽으로 14m씩 가며 매칭은 요청마다 같은 도로명을 돌려준다(null이면 불확실 응답).
    private fun TestScope.drive(roadName: String?, cameraRoadName: String = "중앙로10번길",
                               cameraRoadAxis: Int? = null, reverseMatchedHeading: Boolean = false,
                               cameraLatitude: Double = 37.004, cameraDirection: Int? = null,
                               matching: Boolean = true, rejectAuthentication: Boolean = false,
                               observe: (Int, SafeDriveGuide) -> Unit = { _, _ -> },
                               roadNameAtSecond: (Int) -> String? = { roadName }): List<Pair<String, Int>> {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var second = 0
        val spoken = mutableListOf<Pair<String, Int>>()
        val guide = SafeDriveGuide(Application(), voiceOutput = { spoken += it to second },
            wallClockMillis = { 1_900_000_000_000L + second * 1_000L }) { 100_000_000_000L + second * 1_000_000_000L }
        field("index").set(guide, CameraIndex(listOf(OfflineCamera("side", cameraLatitude, 127.0002, 30,
            roadName = cameraRoadName, roadAxisDegrees = cameraRoadAxis, direction = cameraDirection))))
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val identity = RoadDeviceIdentity(AtomicFile(File(temporary.root, "device.txt"))) { key }
        field("roadMatcher").set(guide, RoadMatcher(Application(), bootstrapToken = "test", identityOverride = identity) { path, _, _ ->
            val currentRoadName = roadNameAtSecond(second)
            when (path) {
                "/v1/devices" -> RoadHttpResponse(if (rejectAuthentication) 401 else 200, """{"certificate":"dc1.test"}""")
                "/v1/session" -> RoadHttpResponse(200, """{"accessToken":"rm1.test.token","expiresInSeconds":1800}""")
                // 매칭 끝점은 현재 GPS와 같게 두고, 도로명 유무만 바꾼다.
                else -> RoadHttpResponse(200, if (currentRoadName == null) """{"status":"uncertain","matchings":[],"unmatchedCount":0}"""
                else """{"status":"matched","roadName":"$currentRoadName","matchings":[{"confidence":0.95,"geometry":{"type":"LineString",""" +
                    """"coordinates":[[127.0,${37.0 + (second + if (reverseMatchedHeading) 1 else -1) * 0.000126}],[127.0,${37.0 + second * 0.000126}]]}}],"unmatchedCount":0}""")
            }
        })
        try {
            guide.setSound(true, 2)
            guide.start()
            runCurrent()
            guide.setAutomaticAlertsAllowed(true)
            guide.setRoadMatchEnabled(matching)
            for (step in 0..20) {
                second = step
                guide.onLocation(Location("gps").apply {
                    latitude = 37.0 + step * 0.000126; longitude = 127.0
                    speed = 14f; bearing = 0f; accuracy = 8f
                    time = 1_900_000_000_000L + step * 1_000L
                    elapsedRealtimeNanos = 100_000_000_000L + step * 1_000_000_000L
                })
                while ((field("matchJob").get(guide) as Job?)?.isActive == true) {
                    Thread.sleep(1)
                    runCurrent()
                }
                // 딩동 뒤 음성처럼 지연 실행되는 안내도 다음 측위 전에 끝나게 한다.
                advanceTimeBy(999)
                runCurrent()
                observe(second, guide)
            }
        } finally {
            guide.stop()
            runCurrent()
            Dispatchers.resetMain()
        }
        return spoken
    }

    private fun field(name: String) = SafeDriveGuide::class.java.getDeclaredField(name).apply { isAccessible = true }
}
