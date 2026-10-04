package com.wemade.teslamacro.data.nav

import android.app.Application
import android.util.AtomicFile
import app.cash.paparazzi.Paparazzi
import com.wemade.teslamacro.data.safety.DeviceApiClient
import com.wemade.teslamacro.data.safety.RoadDeviceIdentity
import com.wemade.teslamacro.data.safety.RoadHttpResponse
import com.wemade.teslable.DiagLog
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DestinationClientTest {
    @get:Rule val paparazzi = Paparazzi()
    @get:Rule val temporary = TemporaryFolder()

    /** 실제 인증 흐름을 지나되 네트워크 대신 요청 본문과 응답을 관찰한다. */
    private fun client(reply: (JsonObject) -> RoadHttpResponse): DestinationClient {
        val key = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val identity = RoadDeviceIdentity(AtomicFile(File(temporary.root, "device.txt"))) { key }
        return DestinationClient(DeviceApiClient(Application(), "test-bootstrap", identity) { path, body, _ ->
            when (path) {
                "/v1/devices" -> RoadHttpResponse(200, """{"certificate":"dc1.test.signature"}""")
                "/v1/session" -> RoadHttpResponse(200, """{"accessToken":"rm1.test.signature","expiresInSeconds":1800}""")
                else -> {
                    assertEquals("/v1/destinations", path)
                    reply(Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject)
                }
            }
        })
    }

    /** 통신 실패·서버 오류를 구분하고 상태 재확인은 전송을 반복하지 않는다. */
    @Test fun statusFailureThenRecovery() = runBlocking {
        var code = 0
        val operations = mutableListOf<String>()
        val client = client { body ->
            operations += body.getValue("operation").jsonPrimitive.content
            RoadHttpResponse(code, """{"serverNow":1000,"receiverName":"차량 태블릿"}""")
        }
        val disconnected = runCatching { client.call("status") }.exceptionOrNull() as DestinationApiException
        assertEquals(0, disconnected.code)
        assertEquals("연결을 확인하지 못했어요", disconnected.message)
        code = 503
        val unavailable = runCatching { client.call("status") }.exceptionOrNull() as DestinationApiException
        assertEquals(503, unavailable.code)
        assertTrue(unavailable.message!!.contains("503"))
        code = 200
        assertEquals("차량 태블릿", client.call("status").receiverName)
        assertEquals(listOf("status", "status", "status"), operations)
    }

    /** 설정의 최소·최대 유효시간과 검색어만 전송하고 인증 만료시간과 섞지 않는다. */
    @Test fun sendsConfiguredMinutesWithSearchOnly() = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val client = client { body ->
            requests += body
            RoadHttpResponse(200, """{"serverNow":1000}""")
        }
        client.send(DestinationPlace("서울역"), 1, false)
        client.send(DestinationPlace("서울역"), 120, false)
        assertEquals(listOf(1, 120), requests.map { it.getValue("validityMinutes").jsonPrimitive.int })
        requests.forEach {
            assertEquals(buildJsonObject { put("name", "서울역") }, it["destination"])
            assertEquals("send", it.getValue("operation").jsonPrimitive.content)
        }
        assertNotEquals(requests[0]["requestId"], requests[1]["requestId"])
    }

    /** 미연결은 새로고침으로 복구할 수 없으므로 연결 안내 뒤 새 전송의 정상 응답을 확인한다. */
    @Test fun unpairedThenPairedSendSucceeds() = runBlocking {
        var paired = false
        val client = client { body ->
            assertEquals("send", body.getValue("operation").jsonPrimitive.content)
            if (!paired) RoadHttpResponse(409, """{"error":"receiver_not_paired"}""")
            else RoadHttpResponse(200, buildJsonObject {
                put("serverNow", 1000)
                putJsonObject("request") {
                    put("id", body.getValue("requestId"))
                    put("destination", body.getValue("destination"))
                    put("createdAt", 1000)
                    put("expiresAt", 601000)
                    put("status", "pending")
                    put("selfTest", false)
                }
            }.toString())
        }
        val error = runCatching { client.send(DestinationPlace("서울역"), 10, false) }.exceptionOrNull() as DestinationApiException
        assertEquals(409, error.code)
        assertTrue(error.message!!.contains("받는 기기 연결"))
        assertFalse(error.message!!.contains("새로고침"))
        paired = true
        val sent = client.send(DestinationPlace("서울역"), 10, false)
        assertEquals("pending", sent.request?.status)
        assertEquals("서울역", sent.request?.destination?.name)
    }

    /** 상태 충돌을 연결 누락으로 오인하거나 서버 원문을 사용자에게 노출하지 않는다. */
    @Test fun conflictReasonsAreSeparatedAndUnknownBodiesStayPrivate() = runBlocking {
        for ((body, expected) in listOf(
            """{"error":"request_conflict"}""" to "충돌",
            """{"error":"request_not_pending"}""" to "이미 처리",
            """{"error":"private-server-detail"}""" to "요청 상태",
            "<html>private-server-detail</html>" to "요청 상태",
        )) {
            val client = client { RoadHttpResponse(409, body) }
            val error = runCatching { client.call("status") }.exceptionOrNull() as DestinationApiException
            assertTrue(error.message!!.contains(expected))
            assertFalse(error.message!!.contains("private-server-detail"))
            assertFalse(error.message!!.contains("받는 기기 연결"))
        }
    }

    /** 기기 키 실패는 서버 장애와 구분해 기록하되 예외 본문과 비밀값은 남기지 않는다. */
    @Test fun identityFailureIsDiagnosableWithoutSecrets() = runBlocking {
        val identity = RoadDeviceIdentity(AtomicFile(File(temporary.root, "broken.txt"))) {
            error("private-test-secret")
        }
        val api = DeviceApiClient(Application(), "private-test-bootstrap", identity) { _, _, _ ->
            fail("기기 키 생성 실패 뒤 HTTP 요청이 발생하면 안 됨")
            RoadHttpResponse()
        }
        val before = DiagLog.lines.value.size
        assertEquals(0, api.authenticatedPost("/v1/destinations", byteArrayOf()).code)
        assertEquals(0, api.authenticatedPost("/v1/destinations", byteArrayOf()).code)
        val lines = DiagLog.lines.value.drop(before)
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("공개키 IllegalStateException"))
        assertFalse(lines.single().contains("private-test"))
    }
}
