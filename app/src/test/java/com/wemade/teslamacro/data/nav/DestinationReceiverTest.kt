package com.wemade.teslamacro.data.nav

import com.wemade.teslamacro.data.poll.needsBoardingNavigation
import com.wemade.teslamacro.data.settings.AppSettings
import com.wemade.teslamacro.data.settings.DeviceMode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class DestinationReceiverTest {
    private val place = DestinationPlace("서울시청", "서울 중구 세종대로 110", 37.5663, 126.9779)

    /** 업데이트 전 기록은 불명확 상태로 보존하고 새 실행 전 기록은 재시작 후에도 구별한다. */
    @Test fun receiptStageSurvivesSerializationAndLegacyUpgrade() {
        val legacy = Json.decodeFromString<DestinationReceipt>("""{"requestId":"previous-request"}""")
        assertTrue(legacy.launchAttempted)
        assertNull(legacy.delivered)
        val beforeClaim = DestinationReceipt("current-request", launchAttempted = false)
        val restored = Json.decodeFromString<DestinationReceipt>(Json.encodeToString(beforeClaim))
        assertEquals(beforeClaim, restored)
        assertFalse(restored.launchAttempted)
    }

    /** 실제 인계 상태 전환과 결과 응답 유실을 주입하는 작은 서버 대역이다. */
    private inner class Scenario {
        var destination = place
        var launchedDestination: DestinationPlace? = null
        var elapsed = 1_000L
        var createdAt = 100_000L
        var ready = true
        var status = "pending"
        var receipt: DestinationReceipt? = null
        var launches = 0
        var claims = 0
        var failComplete = false
        var failClaim = false
        var loseClaimReply = false
        var selfTest = false
        var before: () -> Unit = {}
        var launchFailure = false
        var uncertainLaunch = false
        var declineUnlock = false
        var attempts = 0
        val request get() = DestinationRequest("00000000-0000-4000-8000-000000000001", destination, createdAt, createdAt + 60_000, status, selfTest)

        /** 인계 이후 수신함에서 제거하고 완료 응답 유실도 재현한다. */
        suspend fun call(operation: String, fields: JsonObjectBuilder.() -> Unit): DestinationReply {
            when (operation) {
                "inbox" -> return DestinationReply(100_000 + elapsed - 1_000, request.takeIf { status == "pending" })
                "claim" -> {
                    if (failClaim || status != "pending") throw DestinationApiException(409, "cancelled")
                    status = "claimed"
                    claims++
                    if (loseClaimReply) throw DestinationApiException(503, "reply lost")
                }
                "complete" -> {
                    if (failComplete) throw DestinationApiException(503, "offline")
                    status = if (receipt?.delivered == true) "delivered" else "failed"
                }
            }
            return DestinationReply(100_000 + elapsed - 1_000, request)
        }

        /** 실제 지도 실행 직전 훅과 영속 기록 순서를 검사한다. */
        fun receiver() = DestinationReceiver(::call, { receipt }, { receipt = it }, { receipt = null }, { elapsed },
            { destination, guard -> runCatching {
                attempts++
                if (declineUnlock) throw DestinationUnlockDeclinedException()
                before()
                guard()
                assertNotNull(receipt)
                if (launchFailure) error("launch failed")
                if (uncertainLaunch) throw DestinationLaunchException("unknown", uncertain = true)
                launches++
                launchedDestination = destination
                Unit
            }.onFailure { if (it is DestinationLaunchException && it.uncertain) throw it } }, {})
    }

    /** ADB 응답 유실은 실패로 확정하거나 다음 수신에서 다시 실행하지 않는다. */
    @Test fun uncertainShellDeliveryKeepsClaimedReceipt() = runTest {
        val scenario = Scenario().apply { uncertainLaunch = true }
        val receiver = scenario.receiver()
        receiver.receive { true }
        receiver.receive { true }
        assertEquals("claimed", scenario.status)
        assertNotNull(scenario.receipt)
        assertNull(scenario.receipt?.delivered)
        assertEquals(1, scenario.claims)
    }

    /** 검색어만 직렬화·복원해도 주소나 가짜 좌표를 만들어 넣지 않는다. */
    @Test fun searchPayloadRoundTripsWithoutCoordinates() {
        val destination = DestinationPlace("서울시청")
        val encoded = Json.encodeToString(destination)
        assertEquals("""{"name":"서울시청"}""", encoded)
        assertEquals(destination, Json.decodeFromString<DestinationPlace>(encoded))
        assertTrue(destination.valid())
        assertTrue(Json.decodeFromString<DestinationPlace>(Json.encodeToString(place)).valid())
        assertFalse(place.isSearch)
    }

    /** 빈 검색어·제어문자·길이 초과와 불완전한 기존 목적지를 차단한다. */
    @Test fun malformedSearchAndPartialCoordinatesAreRejected() {
        listOf(DestinationPlace(""), DestinationPlace("   "), DestinationPlace("서울\n시청"),
            DestinationPlace("가".repeat(121)), DestinationPlace("회사", latitude = 37.5),
            DestinationPlace("회사", address = "주소"), place.copy(latitude = Double.NaN),
            place.copy(longitude = 0.0), place.copy(address = "")).forEach { assertFalse(it.valid()) }
        assertTrue(DestinationPlace("집").valid())
        assertTrue(DestinationPlace("가".repeat(120)).valid())
    }

    /** 검색어 요청도 기존 인계·완료·중복 차단 경로를 한 번만 통과한다. */
    @Test fun searchRequestUsesExistingHandoffOnce() = runTest {
        val scenario = Scenario().apply { destination = DestinationPlace("서울시청") }
        val receiver = scenario.receiver()
        assertEquals(DestinationReceiveResult.DISPATCHED, receiver.receive { true })
        assertEquals(scenario.destination, scenario.launchedDestination)
        assertEquals("delivered", scenario.status)
        assertEquals(DestinationReceiveResult.EMPTY, receiver.receive { true })
        assertEquals(1, scenario.launches)
    }

    /** 탑승 중 빈 수신함을 5분 확인한 뒤 도착해도 재탑승 없이 한 번 전달한다. */
    @Test fun lateDestinationDuringSameRideIsDeliveredOnce() = runTest {
        val scenario = Scenario().apply { status = "empty"; destination = DestinationPlace("서울시청") }
        val receiver = scenario.receiver()
        repeat(60) {
            assertEquals(DestinationReceiveResult.EMPTY, receiver.receive {
                destinationReady(true, true, scenario.elapsed - 1_000, scenario.elapsed)
            })
            scenario.elapsed += 5_000
        }
        assertEquals(0, scenario.launches)
        scenario.createdAt = 100_000 + scenario.elapsed - 1_000
        scenario.status = "pending"
        assertEquals(DestinationReceiveResult.DISPATCHED, receiver.receive {
            destinationReady(true, true, scenario.elapsed - 1_000, scenario.elapsed)
        })
        assertEquals(scenario.destination, scenario.launchedDestination)
        assertEquals("delivered", scenario.status)
        assertEquals(DestinationReceiveResult.EMPTY, receiver.receive { true })
        assertEquals(1, scenario.launches)
        assertEquals(1, scenario.claims)
    }

    /** 한 번 인계한 요청은 동시에 수신해도 한 번만 실행한다. */
    @Test fun concurrentReceiveLaunchesOnce() = runTest {
        val scenario = Scenario()
        val receiver = scenario.receiver()
        List(4) { async { receiver.receive { scenario.ready } } }.awaitAll()
        assertEquals(1, scenario.launches)
        assertEquals(1, scenario.claims)
        assertEquals("delivered", scenario.status)
        assertNull(scenario.receipt)
    }

    /** 완료 응답이 유실되어 재시작해도 기록만 재전송한다. */
    @Test fun completionLossSurvivesReceiverRestart() = runTest {
        val scenario = Scenario().apply { failComplete = true }
        scenario.receiver().receive { true }
        assertEquals(true, scenario.receipt?.delivered)
        scenario.failComplete = false
        assertEquals(DestinationReceiveResult.EMPTY, scenario.receiver().receive { true })
        assertEquals(1, scenario.launches)
        assertEquals("delivered", scenario.status)
    }

    /** 기록은 있지만 실행 결과가 없으면 재시작 뒤 자동 재실행하지 않는다. */
    @Test fun unknownHandoffNeverReplays() = runTest {
        val scenario = Scenario().apply { status = "claimed"; receipt = DestinationReceipt(request.id) }
        assertEquals(DestinationReceiveResult.UNKNOWN, scenario.receiver().receive { true })
        assertEquals(0, scenario.launches)
        assertNull(scenario.receipt?.delivered)
    }

    /** 잠금 해제 대기 중 만료되면 인계 요청조차 보내지 않는다. */
    @Test fun expiresWhileUnlocking() = runTest {
        val scenario = Scenario().apply { before = { elapsed = 61_000 } }
        scenario.receiver().receive { scenario.ready }
        assertEquals(0, scenario.claims)
        assertEquals(0, scenario.launches)
    }

    /** 잠금 대기 중 하차하면 실행을 보류하고 서버 대기를 유지한다. */
    @Test fun leavesBeforeUnlocking() = runTest {
        val scenario = Scenario().apply { before = { ready = false } }
        scenario.receiver().receive { scenario.ready }
        assertEquals("pending", scenario.status)
        assertEquals(0, scenario.launches)
    }

    /** 조회와 실행 사이 취소 성공은 최종 인계에서 걸러진다. */
    @Test fun cancelledAfterInbox() = runTest {
        val scenario = Scenario().apply { failClaim = true }
        scenario.receiver().receive { true }
        assertEquals(0, scenario.launches)
        assertNull(scenario.receipt)
    }

    /** 지도 실행 실패도 한 번 인계한 요청을 다시 자동 실행하지 않는다. */
    @Test fun launchFailureIsReportedOnce() = runTest {
        val scenario = Scenario().apply { launchFailure = true }
        val receiver = scenario.receiver()
        receiver.receive { true }
        assertEquals("failed", scenario.status)
        assertEquals(DestinationReceiveResult.EMPTY, receiver.receive { true })
        assertEquals(1, scenario.claims)
    }

    /** 구버전 시험 요청을 일반 목적지로 실행하지 않는다. */
    @Test fun legacyRequestCannotOpenNavigation() = runTest {
        val scenario = Scenario()
        scenario.selfTest = true
        scenario.receiver().receive { true }
        assertEquals(0, scenario.claims)
        assertEquals(0, scenario.launches)
    }

    /** 수신은 기기 모드와 무관하지만 오래된 착석값이나 꺼진 설정으로 실행하지 않는다. */
    @Test fun freshPresenceAndReceiveSettingRequired() {
        assertTrue(destinationReady(true, true, 1_000, 31_000))
        assertFalse(destinationReady(true, true, 1_000, 31_001))
        assertFalse(destinationReady(true, null, 1_000, 1_000))
        assertFalse(destinationReady(true, false, 1_000, 1_000))
        assertFalse(destinationReady(false, true, 1_000, 1_000))
        assertTrue(AppSettings(deviceMode = DeviceMode.PORTABLE, destinationReceiveEnabled = true).needsBoardingNavigation)
        assertTrue(AppSettings(deviceMode = DeviceMode.MOUNTED, destinationReceiveEnabled = true).needsBoardingNavigation)
        assertFalse(AppSettings(deviceMode = DeviceMode.PORTABLE, destinationReceiveEnabled = false).needsBoardingNavigation)
    }

    /** 기기 벽시계 대신 서버 잔여시간과 요청 시작의 단조시계를 사용한다. */
    @Test fun deadlineIncludesNetworkDelayAndExactExpiry() {
        assertEquals(61_000L, destinationDeadline(100_000, 160_000, 1_000))
        assertNull(destinationDeadline(160_000, 160_000, 1_000))
        assertNull(destinationDeadline(0, 160_000, 1_000))
        assertNull(destinationDeadline(1, 9_000_000, 1_000))
    }

    /** 오래된 착석이어도 수신함을 읽고 요청이 있을 때만 차량을 다시 확인한다. */
    @Test fun pendingRequestRefreshesPresenceAfterFortyAndNinetySeconds() = runTest {
        for (delayMillis in listOf(40_000L, 90_000L)) {
            val scenario = Scenario().apply { elapsed = delayMillis + 1_000; createdAt += delayMillis }
            var observedAt = 1_000L
            var confirmations = 0
            val result = scenario.receiver().receive(prepare = {
                confirmations++
                observedAt = scenario.elapsed
                true
            }) { destinationReady(true, true, observedAt, scenario.elapsed) }
            assertEquals(DestinationReceiveResult.DISPATCHED, result)
            assertEquals(1, scenario.launches)
            assertEquals(2, confirmations)
        }
    }

    /** 인증에 35초가 걸려도 claim 직전 새 착석으로 안전하게 이어간다. */
    @Test fun unlockRefreshesExpiredPresenceBeforeClaim() = runTest {
        val scenario = Scenario().apply { before = { elapsed += 35_000 } }
        var observedAt = scenario.elapsed
        val result = scenario.receiver().receive(prepare = {
            observedAt = scenario.elapsed
            true
        }) { destinationReady(true, true, observedAt, scenario.elapsed) }
        assertEquals(DestinationReceiveResult.DISPATCHED, result)
        assertEquals(1, scenario.claims)
    }

    /** 차량 미응답은 목적지 발견·전달로 소비하지 않으며 요청을 그대로 남긴다. */
    @Test fun failedPresenceConfirmationKeepsRequestPending() = runTest {
        val scenario = Scenario()
        assertEquals(DestinationReceiveResult.WAITING_FOR_CONDITIONS,
            scenario.receiver().receive(prepare = { false }) { false })
        assertEquals("pending", scenario.status)
        assertEquals(0, scenario.claims)
    }

    /** 빈 수신함 확인에는 BLE 연결이나 착석을 요구하지 않는다. */
    @Test fun emptyInboxDoesNotConnectVehicle() = runTest {
        val scenario = Scenario().apply { status = "empty" }
        assertEquals(DestinationReceiveResult.EMPTY, scenario.receiver().receive(prepare = {
            error("빈 수신함은 차량을 확인하면 안 됨")
        }) { false })
    }

    /** claim 응답 유실 전의 기록으로 미실행을 실패 처리하고 중복 실행하지 않는다. */
    @Test fun lostClaimReplyRecoversWithoutLaunching() = runTest {
        val scenario = Scenario().apply { loseClaimReply = true }
        assertEquals(DestinationReceiveResult.FAILED, scenario.receiver().receive { true })
        assertEquals("failed", scenario.status)
        assertEquals(0, scenario.launches)
        assertNull(scenario.receipt)
    }

    /** 이전 전달 결과가 불명확하면 새 요청으로 영속 기록을 덮어쓰지 않는다. */
    @Test fun unresolvedReceiptBlocksNewRequest() = runTest {
        val scenario = Scenario().apply { receipt = DestinationReceipt("previous-request") }
        assertEquals(DestinationReceiveResult.UNKNOWN, scenario.receiver().receive { true })
        assertEquals("previous-request", scenario.receipt?.requestId)
        assertEquals(0, scenario.claims)
    }

    /** 지도 전달 이후 complete 응답이 없어도 실행 실패로 되돌리지 않는다. */
    @Test fun lostCompletionIsStillDispatched() = runTest {
        val scenario = Scenario().apply { failComplete = true }
        assertEquals(DestinationReceiveResult.DISPATCHED, scenario.receiver().receive { true })
        assertEquals(true, scenario.receipt?.delivered)
        assertEquals(1, scenario.launches)
    }

    /** 사용자 확인으로만 불명확한 기록을 완료하고 이전 목적지를 재실행하지 않는다. */
    @Test fun explicitReceiptConfirmationDoesNotReplay() = runTest {
        for (delivered in listOf(true, false)) {
            val scenario = Scenario().apply { status = "claimed"; receipt = DestinationReceipt(request.id) }
            val receiver = scenario.receiver()
            assertNotNull(receiver.unresolvedRequestId())
            receiver.resolveReceipt(delivered)
            assertNull(receiver.unresolvedRequestId())
            assertEquals(if (delivered) "delivered" else "failed", scenario.status)
            assertEquals(0, scenario.launches)
            assertEquals(DestinationReceiveResult.EMPTY, receiver.receive { true })
        }
    }

    /** 잠금 해제를 거절한 요청은 사용자가 잠금을 풀 때까지 5초마다 인증 화면을 다시 띄우지 않는다. */
    @Test fun declinedUnlockWaitsForUserUnlock() = runTest {
        val scenario = Scenario().apply { declineUnlock = true }
        val receiver = scenario.receiver()
        assertEquals(DestinationReceiveResult.WAITING_FOR_CONDITIONS, receiver.receive { true })
        assertEquals(DestinationReceiveResult.WAITING_FOR_CONDITIONS, receiver.receive { true })
        assertEquals(1, scenario.attempts)
        assertEquals("pending", scenario.status)
        receiver.retryDeclined()
        scenario.declineUnlock = false
        assertEquals(DestinationReceiveResult.DISPATCHED, receiver.receive { true })
        assertEquals(2, scenario.attempts)
        assertEquals(1, scenario.launches)
        assertEquals("delivered", scenario.status)
    }
}
