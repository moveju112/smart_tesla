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
        val spoken = drive(roadName = "중앙로")
        assertTrue("옆 골목 카메라를 읽으면 안 된다: $spoken", spoken.isEmpty())
    }

    /** 매칭이 계속 불확실하면 6초 뒤 기존 판정대로 안내해 실제 카메라를 놓치지 않는다. */
    @Test fun unknownRoadNameFallsBackAfterWait() = runTest {
        val spoken = drive(roadName = null)
        // 200m 안 과속 감속 요청이 뒤따를 수 있으므로 첫 진입 안내만 본다.
        val first = spoken.first()
        assertTrue("6초 대기 뒤 안내: $spoken", first.second >= 6)
        assertTrue(first.first.contains("시속 30킬로미터"))
    }

    // 1초마다 북쪽으로 14m씩 가며 매칭은 요청마다 같은 도로명을 돌려준다(null이면 불확실 응답).
    private fun TestScope.drive(roadName: String?): List<Pair<String, Int>> {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var second = 0
        val spoken = mutableListOf<Pair<String, Int>>()
        val guide = SafeDriveGuide(Application(), voiceOutput = { spoken += it to second },
            wallClockMillis = { 1_900_000_000_000L + second * 1_000L }) { 100_000_000_000L + second * 1_000_000_000L }
        field("index").set(guide, CameraIndex(listOf(OfflineCamera("side", 37.004, 127.0002, 30, roadName = "중앙로10번길"))))
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val identity = RoadDeviceIdentity(AtomicFile(File(temporary.root, "device.txt"))) { key }
        field("roadMatcher").set(guide, RoadMatcher(Application(), bootstrapToken = "test", identityOverride = identity) { path, _, _ ->
            when (path) {
                "/v1/devices" -> RoadHttpResponse(200, """{"certificate":"dc1.test"}""")
                "/v1/session" -> RoadHttpResponse(200, """{"accessToken":"rm1.test.token","expiresInSeconds":1800}""")
                // 매칭 끝점은 현재 GPS와 같게 두고, 도로명 유무만 바꾼다.
                else -> RoadHttpResponse(200, if (roadName == null) """{"status":"uncertain","matchings":[],"unmatchedCount":0}"""
                else """{"status":"matched","roadName":"$roadName","matchings":[{"confidence":0.95,"geometry":{"type":"LineString",""" +
                    """"coordinates":[[127.0,${37.0 + (second - 1) * 0.000126}],[127.0,${37.0 + second * 0.000126}]]}}],"unmatchedCount":0}""")
            }
        })
        try {
            guide.setSound(true, 2)
            guide.start()
            runCurrent()
            guide.setAutomaticAlertsAllowed(true)
            guide.setRoadMatchEnabled(true)
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
