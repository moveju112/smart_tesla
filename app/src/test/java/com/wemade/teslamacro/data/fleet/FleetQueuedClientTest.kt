package com.wemade.teslamacro.data.fleet

import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslable.CommandDeadline
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FleetQueuedClientTest {
    private val vin = "5YJS0000000000000"
    private data class Call(val method: String, val path: String, val body: String?, val key: String?)
    private class FakeTransport : FleetHttpTransport {
        val calls = mutableListOf<Call>()
        val responses = ArrayDeque<FleetHttpResponse>()
        var postError = false
        var gate: CompletableDeferred<Unit>? = null
        var fallback = FleetHttpResponse(503, "")
        /** 인터넷 없이 접수·조회 호출과 본문만 기록한다. 토큰은 기록하지 않는다. */
        override suspend fun request(method: String, path: String, token: String, body: String?, key: String?): FleetHttpResponse {
            calls += Call(method, path, body, key)
            if (method == "POST" && postError) throw IOException("lost response")
            gate?.await()
            return responses.removeFirstOrNull() ?: fallback
        }
    }

    /** 문서의 응답 구조를 더미 차량만 사용해 만든다. */
    private fun response(status: String, code: Int = 202, id: String = "command-id", result: String? = null): FleetHttpResponse =
        FleetHttpResponse(code, buildJsonObject {
            put("id", id); put("vin", vin); put("name", "door_lock"); put("status", status)
            put("result", result?.let(::JsonPrimitive) ?: JsonNull)
            put("created", 1800000000.0); put("expires", 1800000060.0)
        }.toString())

    /** 현 서버의 7가지 명령 이름·필드만 보내며 개폐/해제/깨우기는 추정하지 않는다. */
    @Test
    fun `supported command mapping and unsupported closures`() {
        val cases = listOf(
            VehicleCommand.Lock to "door_lock", VehicleCommand.ClimateOn to "auto_conditioning_start",
            VehicleCommand.ClimateOff to "auto_conditioning_stop", VehicleCommand.SetCharging(true) to "charge_start",
            VehicleCommand.SetCharging(false) to "charge_stop", VehicleCommand.SetTemperature(22.0) to "set_temps",
            VehicleCommand.SetChargeLimit(80) to "set_charge_limit")
        for ((command, type) in cases) {
            val body = fleetCommandBody(command, 60)
            assertEquals(type, body.getValue("type").jsonPrimitive.content)
            assertFalse(body.containsKey("command"))
            assertEquals(60, body.getValue("expiresInSeconds").jsonPrimitive.int)
        }
        val temps = fleetCommandBody(VehicleCommand.SetTemperature(22.0), 60).getValue("parameters").jsonObject
        assertEquals(22.0, temps.getValue("driver_temp").jsonPrimitive.double, 0.0)
        assertEquals(temps["driver_temp"], temps["passenger_temp"])
        assertEquals(80, fleetCommandBody(VehicleCommand.SetChargeLimit(80), 60).getValue("parameters").jsonObject.getValue("percent").jsonPrimitive.int)
        for (command in listOf(VehicleCommand.OpenFrunk, VehicleCommand.OpenTrunk, VehicleCommand.CloseTrunk, VehicleCommand.Unlock)) {
            assertThrows(IllegalArgumentException::class.java) { fleetCommandBody(command, 60) }
        }
    }

    /** 범위를 벗어나는 값을 몰래 보정해 다른 차량 동작을 만들지 않는다. */
    @Test
    fun `parameter bounds are enforced`() {
        listOf(14.9, 28.1, Double.NaN, Double.POSITIVE_INFINITY).forEach {
            assertThrows(IllegalArgumentException::class.java) { fleetCommandBody(VehicleCommand.SetTemperature(it), 60) }
        }
        listOf(49, 101).forEach { assertThrows(IllegalArgumentException::class.java) { fleetCommandBody(VehicleCommand.SetChargeLimit(it), 60) } }
        listOf(4, 301).forEach { assertThrows(IllegalArgumentException::class.java) { fleetCommandBody(VehicleCommand.Lock, it) } }
    }

    /** 202 queued는 완료가 아니며 GET 성공 후에만 효과음이 난다. */
    @Test
    fun `queued running succeeded polls without another submission`() = runTest {
        val transport = FakeTransport().apply {
            responses.addAll(listOf(response("queued"), response("running", 200),
                response("succeeded", 200, result = "tesla_acknowledged_not_state_verified")))
        }
        var tones = 0
        var dispatched = false
        val states = mutableListOf<FleetQueueStatus>()
        val client = FleetQueuedClient(transport, { "dummy-token" }) { tones++ }
        val result = withContext(CommandDeadline(120_000) { testScheduler.currentTime }) {
            client.execute(vin, VehicleCommand.Lock, { dispatched = true }) {
                assertTrue(dispatched); states += it.status
                if (it.pending) assertEquals(0, tones)
            }
        }
        assertEquals(FleetQueueStatus.Succeeded, result.status)
        assertEquals(listOf(FleetQueueStatus.Queued, FleetQueueStatus.Running, FleetQueueStatus.Succeeded), states)
        assertEquals(listOf("POST", "GET", "GET"), transport.calls.map { it.method })
        assertEquals(1, tones)
        assertTrue(transport.calls.first().key!!.isNotBlank())
        assertEquals("/v1/commands/command-id", transport.calls.last().path)
    }

    /** 202가 기존 종료 결과를 반환해도 추가 조회/접수 없이 최종 상태를 사용한다. */
    @Test
    fun `all terminal states in acceptance response stop polling`() = runTest {
        for ((name, status) in listOf("succeeded" to FleetQueueStatus.Succeeded, "failed" to FleetQueueStatus.Failed,
            "unknown" to FleetQueueStatus.Unknown, "expired" to FleetQueueStatus.Expired, "future_status" to FleetQueueStatus.Unknown)) {
            val transport = FakeTransport().apply { responses.add(response(name)) }
            var tones = 0
            val result = withContext(CommandDeadline(120_000) { testScheduler.currentTime }) {
                FleetQueuedClient(transport, { "dummy-token" }) { tones++ }.execute(vin, VehicleCommand.Lock, {})
            }
            assertEquals(status, result.status)
            assertEquals(1, transport.calls.size)
            assertEquals(if (status == FleetQueueStatus.Succeeded) 1 else 0, tones)
        }
    }

    /** 202 응답 유실은 취소나 실패 확정이 아니라 결과 미확인이며 새 UUID 재전송을 하지 않는다. */
    @Test
    fun `lost acceptance is unknown and not retried`() = runTest {
        val transport = FakeTransport().apply { postError = true }
        val result = withContext(CommandDeadline(120_000) { testScheduler.currentTime }) {
            FleetQueuedClient(transport, { "dummy-token" }).execute(vin, VehicleCommand.Lock, {})
        }
        assertEquals(FleetQueueStatus.Unknown, result.status)
        assertNull(result.id)
        assertEquals(1, transport.calls.size)
    }

    /** 새 사용자 실행마다 UUID를 분리하고 수신 유효시간을 늘리지 않는다. */
    @Test
    fun `new requests get new keys and bounded integer lifetime`() = runTest {
        val transport = FakeTransport().apply { responses.addAll(listOf(response("failed"), response("failed"))) }
        val client = FleetQueuedClient(transport, { "dummy-token" })
        for (remaining in listOf(600_000L, 5_999L)) {
            withContext(CommandDeadline(remaining) { 0L }) { client.submit(vin, VehicleCommand.Lock, {}) }
        }
        assertNotEquals(transport.calls[0].key, transport.calls[1].key)
        assertEquals(listOf(300, 5), transport.calls.map { Json.parseToJsonElement(it.body!!).jsonObject.getValue("expiresInSeconds").jsonPrimitive.int })
        try {
            withContext(CommandDeadline(4_999) { 0L }) { client.submit(vin, VehicleCommand.Lock, {}) }
            fail("short lifetime must be rejected")
        } catch (_: IllegalArgumentException) { }
        assertEquals(2, transport.calls.size)
    }

    /** 미지원 보닛은 토큰 조회/네트워크보다 먼저 차단한다. */
    @Test
    fun `unsupported frunk has no network or token access`() = runTest {
        val transport = FakeTransport()
        val client = FleetQueuedClient(transport, { error("must not load token") })
        try {
            withContext(CommandDeadline(60_000) { 0L }) { client.submit(vin, VehicleCommand.OpenFrunk, { fail("no dispatch") }) }
            fail("unsupported")
        } catch (_: IllegalArgumentException) { }
        assertTrue(transport.calls.isEmpty())
    }

    /** 조회 중단은 GET 루프만 멈추며 서버 취소/새 명령을 생성하지 않는다. */
    @Test
    fun `stopping result observation never sends cancellation or resubmission`() = runTest {
        val transport = FakeTransport().apply { responses.add(response("queued")) }
        val client = FleetQueuedClient(transport, { "dummy-token" })
        val job = launch {
            withContext(CommandDeadline(120_000) { testScheduler.currentTime }) { client.execute(vin, VehicleCommand.Lock, {}) }
        }
        runCurrent()
        job.cancel(); job.join()
        assertEquals(listOf("POST"), transport.calls.map { it.method })
    }

    /** 조회 실패 후에도 원래 ID를 보존해 사용자가 조회만 다시 할 수 있다. */
    @Test
    fun `failed result lookup retains receipt identity`() = runTest {
        val transport = FakeTransport().apply { responses.add(response("queued")); responses.add(FleetHttpResponse(503, "")) }
        val client = FleetQueuedClient(transport, { "dummy-token" })
        val result = withContext(CommandDeadline(60_000) { testScheduler.currentTime }) { client.execute(vin, VehicleCommand.Lock, {}) }
        assertEquals(FleetQueueStatus.Unknown, result.status)
        assertEquals("command-id", result.id)
        transport.responses.add(response("succeeded", 200))
        assertEquals(FleetQueueStatus.Succeeded, client.refresh(result).status)
        assertEquals(1, transport.calls.count { it.method == "POST" })
    }

    /** 결과 관찰 시간 초과는 서버 expired가 아니며 이미 받은 ID를 보존한다. */
    @Test
    fun `observation timeout is unknown not server expiry`() = runTest {
        val transport = FakeTransport().apply { responses.add(response("queued")); fallback = response("running", 200) }
        val client = FleetQueuedClient(transport, { "dummy-token" })
        val result = withContext(CommandDeadline(10_000) { testScheduler.currentTime }) {
            client.execute(vin, VehicleCommand.Lock, {})
        }
        assertEquals(60_000L, testScheduler.currentTime)
        assertEquals(FleetQueueStatus.Unknown, result.status)
        assertEquals("command-id", result.id)
        assertEquals(1, transport.calls.count { it.method == "POST" })
    }

    /** 전송 진입 직전 시간이 흘렀다면 5초로 억지 연장해 제출하지 않는다. */
    @Test
    fun `lifetime is rechecked after dispatch boundary`() = runTest {
        var now = 0L
        val transport = FakeTransport()
        try {
            withContext(CommandDeadline(6_000) { now }) {
                FleetQueuedClient(transport, { "dummy-token" }).submit(vin, VehicleCommand.Lock) { now = 2_000 }
            }
            fail("must reject under five seconds")
        } catch (_: IllegalArgumentException) { }
        assertTrue(transport.calls.isEmpty())
    }

    /** 차량 목록 응답을 읽되 상태를 깨우기 완료로 가정하지 않는다. */
    @Test
    fun `vehicle list uses server contract`() = runTest {
        val transport = FakeTransport().apply { responses.add(FleetHttpResponse(200,
            """{"vehicles":[{"vin":"$vin","display_name":"Dummy","state":"asleep"}]}""")) }
        val vehicles = FleetQueuedClient(transport, { "dummy-token" }).vehicles()
        assertEquals(listOf(FleetVehicle(vin, "Dummy", "asleep")), vehicles)
        assertEquals("/v1/vehicles", transport.calls.single().path)
        assertEquals("GET", transport.calls.single().method)
    }

    /** 실제 네트워크를 열기 전에 헤더 주입과 비허용 경로를 차단한다. */
    @Test
    fun `https transport rejects unsafe headers and paths without connecting`() = runTest {
        val transport = FleetHttpsTransport()
        for ((path, token) in listOf("/v1/vehicles" to "invalid\r\nheader", "https://other.invalid/" to "dummy-token",
            "/v1/commands/../vehicles" to "dummy-token")) {
            try {
                transport.request("GET", path, token, null, null)
                fail("must reject before URL connection")
            } catch (_: IllegalArgumentException) { }
        }
    }

    /** 접수 전 확정 거절과 접수 여부를 모르는 서버 장애를 구분한다. */
    @Test
    fun `http rejection and ambiguous failure do not retry`() = runTest {
        for ((code, status) in listOf(400 to FleetQueueStatus.Failed, 401 to FleetQueueStatus.Failed,
            403 to FleetQueueStatus.Failed, 500 to FleetQueueStatus.Unknown, 302 to FleetQueueStatus.Unknown)) {
            val transport = FakeTransport().apply { responses.add(FleetHttpResponse(code, "")) }
            val result = withContext(CommandDeadline(60_000) { 0L }) {
                FleetQueuedClient(transport, { "dummy-token" }).execute(vin, VehicleCommand.Lock, {})
            }
            assertEquals(status, result.status)
            assertEquals(1, transport.calls.size)
        }
    }

    /** 조회 응답의 다른 차량/명령/ID나 외부 URL은 성공으로 신뢰하지 않는다. */
    @Test
    fun `malformed or mismatched receipt is never success`() = runTest {
        for (body in listOf("not json", response("succeeded").body.replace("door_lock", "charge_start"),
            response("succeeded", id = "https://other.invalid").body,
            response("succeeded").body.replace(vin, "different-vehicle"))) {
            val transport = FakeTransport().apply { responses.add(FleetHttpResponse(202, body)) }
            val result = withContext(CommandDeadline(60_000) { 0L }) { FleetQueuedClient(transport, { "dummy-token" }).execute(vin, VehicleCommand.Lock, {}) }
            assertEquals(FleetQueueStatus.Unknown, result.status)
            assertEquals(1, transport.calls.size)
        }
    }
}
