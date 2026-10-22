package com.wemade.teslamacro.data.fleet

import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslable.CommandDeadline
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** 로그인·진행 단계·토큰 교체의 경계는 실제 계정이나 차량 없이 검사한다. */
class FleetConnectionTest {
    private val vin = "5YJS0000000000000"

    /** OAuth 시작은 명령 키 없이 한 번만 호출하고 인증 URL을 보존하지 않는다. */
    @Test fun oauthUsesAuthenticatedStartEndpointWithoutCommandSubmission() = runTest {
        val url = "https://auth.tesla.com/oauth2/v3/authorize?client_id=dummy&state=dummy"
        var calls = 0
        val client = FleetQueuedClient(FleetHttpTransport { method, path, token, body, key ->
            calls++
            assertEquals("POST", method)
            assertEquals("/v1/oauth/start", path)
            assertEquals("dummy-token", token)
            assertEquals("{}", body)
            assertNull(key)
            FleetHttpResponse(200, """{"authorizationUrl":"$url","expiresInSeconds":600}""")
        }, { "dummy-token" })
        assertEquals(url, client.startOAuth())
        assertEquals(1, calls)
    }

    /** Tesla와 유사한 호스트·평문·사용자 정보·다른 경로는 브라우저로 전달하지 않는다. */
    @Test fun oauthRejectsUnsafeUrlsAndSanitizesFailures() = runTest {
        for (url in listOf(
            "http://auth.tesla.com/oauth2/v3/authorize?state=dummy",
            "https://auth.tesla.com.evil.invalid/oauth2/v3/authorize?state=dummy",
            "https://auth.tesla.com@evil.invalid/oauth2/v3/authorize?state=dummy",
            "https://user@auth.tesla.com/oauth2/v3/authorize?state=dummy",
            "https://auth.tesla.com:8443/oauth2/v3/authorize?state=dummy",
            "https://auth.tesla.com/logout?state=dummy",
            "https://auth.tesla.com/oauth2/v3/authorize",
            "https://auth.tesla.com/oauth2/v3/authorize?state=dummy#fragment",
            "intent://login", "invalid",
        )) {
            assertFalse(isTeslaAuthorizationUrl(url))
            val client = FleetQueuedClient(FleetHttpTransport { _, _, _, _, _ ->
                FleetHttpResponse(200, """{"authorizationUrl":"$url"}""")
            }, { "secret-test-token" })
            val error = runCatching { client.startOAuth() }.exceptionOrNull()
            assertNotNull(error)
            assertFalse(error!!.message.orEmpty().contains(url))
            assertFalse(error.message.orEmpty().contains("secret-test-token"))
        }
    }

    /** 알려진 서버 진행 코드만 표시하고 차량 성공과 접수를 혼동하지 않는다. */
    @Test fun wakeAndSendProgressArePreservedWithoutEchoingUnknownServerText() = runTest {
        val messages = mutableListOf<String>()
        for (result in listOf("waiting_for_vehicle_online", "sending_command", "secret-server-response")) {
            val client = FleetQueuedClient(FleetHttpTransport { _, _, _, _, _ ->
                FleetHttpResponse(202, """{"id":"dummy-id","vin":"$vin","name":"door_lock","status":"running","result":"$result"}""")
            }, { "dummy-token" })
            val receipt = withContext(CommandDeadline(60_000) { 0 }) { client.submit(vin, VehicleCommand.Lock, {}) }
            assertTrue(receipt.pending)
            if (result == "secret-server-response") assertNull(receipt.result) else assertEquals(result, receipt.result)
            messages += receipt.statusMessage()
        }
        assertTrue(messages[0].contains("깨우는 중"))
        assertTrue(messages[1].contains("전송 중"))
        assertFalse(messages.any { it.contains("성공") || it.contains("secret-server-response") })
    }

    /** 다른 토큰으로 교체한 뒤에는 이전 명령을 HTTP에 노출하거나 다시 제출하지 않는다. */
    @Test fun refreshWithChangedTokenStopsBeforeNetwork() = runTest {
        var calls = 0
        val client = FleetQueuedClient(FleetHttpTransport { _, _, _, _, _ ->
            calls++; error("must not connect")
        }, { "replacement-token" })
        val receipt = FleetReceipt("dummy-id", "dummy-key", vin, "door_lock", FleetQueueStatus.Unknown,
            accountFingerprint = fleetAccountFingerprint("original-token"))
        assertEquals("credential_changed", client.refresh(receipt).result)
        assertEquals(0, calls)
    }
}
