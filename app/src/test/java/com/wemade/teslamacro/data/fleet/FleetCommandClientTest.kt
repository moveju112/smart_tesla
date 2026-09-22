package com.wemade.teslamacro.data.fleet

import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.service.QuickActionRequests
import com.wemade.teslable.CommandDeadline
import com.wemade.teslable.CommandExpiredException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FleetCommandClientTest {
    private val vin = "5YJS0000000000000"

    private class FakeApi : FleetApi {
        val states = ArrayDeque<FleetApi.VehicleState>()
        var reads = 0
        var wakes = 0
        var sends = 0
        var requestId = ""
        var remaining = 0L
        var command: VehicleCommand? = null
        var response = FleetApi.CommandResult.Confirmed
        var sendError: Exception? = null
        var readError: Exception? = null
        var wakeGate: CompletableDeferred<Unit>? = null

        /** 각 조회에서 차량 응답을 하나씩 소비하고 기본은 온라인으로 둔다. */
        override suspend fun vehicleState(vin: String): FleetApi.VehicleState {
            reads++
            readError?.let { throw it }
            return states.removeFirstOrNull() ?: FleetApi.VehicleState.Online
        }
        /** 취소 이후 늦게 끝나는 서버 깨우기 응답도 모형화한다. */
        override suspend fun wake(vin: String) {
            wakes++
            wakeGate?.let { gate -> withContext(NonCancellable) { gate.await() } }
        }
        /** 응답 불명확·실패여도 한 번만 제출되는지 센다. */
        override suspend fun execute(requestId: String, vin: String, command: VehicleCommand, remainingMillis: Long): FleetApi.CommandResult {
            sends++
            this.requestId = requestId
            this.remaining = remainingMillis
            this.command = command
            sendError?.let { throw it }
            return response
        }
    }

    /** 이미 깨어 있는 차량에는 깨우기 없이 단 한 번 전송하고 성공음 콜백도 한 번만 호출한다. */
    @Test
    fun `online command sends once with remaining lifetime`() = runTest {
        val api = FakeApi()
        var tones = 0
        var dispatches = 0
        val result = withContext(CommandDeadline(120_000) { testScheduler.currentTime }) {
            FleetCommandClient(api) { tones++ }.execute(vin, VehicleCommand.OpenFrunk, { dispatches++ }, { fail("no wake") })
        }
        assertEquals(FleetApi.CommandResult.Confirmed, result)
        assertEquals(0, api.wakes)
        assertEquals(1, api.sends)
        assertEquals(1, dispatches)
        assertEquals(1, tones)
        assertEquals(120_000L, api.remaining)
        assertTrue(api.requestId.isNotBlank())
        assertEquals(VehicleCommand.OpenFrunk, api.command)
    }

    /** 깨우기 접수 뒤 오프라인이면 조회만 반복하고 준비 완료 뒤에만 개폐한다. */
    @Test
    fun `sleeping car wakes once and waits until online`() = runTest {
        val api = FakeApi().apply { states.addAll(listOf(FleetApi.VehicleState.Asleep, FleetApi.VehicleState.Offline, FleetApi.VehicleState.Online)) }
        var waking = 0
        withContext(CommandDeadline(120_000) { testScheduler.currentTime }) {
            FleetCommandClient(api).execute(vin, VehicleCommand.OpenTrunk, { assertEquals(3, api.reads) }, { waking++ })
        }
        assertEquals(1, waking)
        assertEquals(1, api.wakes)
        assertEquals(1, api.sends)
        assertEquals(118_000L, api.remaining)
    }

    /** 깨우기 중 취소하면 늦게 온라인 응답이 와도 개폐와 성공음은 모두 차단한다. */
    @Test
    fun `cancel while waking never dispatches even after late wake response`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = FakeApi().apply { states.add(FleetApi.VehicleState.Asleep); wakeGate = gate }
        val requests = QuickActionRequests(diagnosticLogger = {})
        var tones = 0
        val job = launch {
            requests.trackWithProgress("Fleet 보닛 열기") { dispatch, waking ->
                withContext(CommandDeadline(120_000) { testScheduler.currentTime }) {
                    FleetCommandClient(api) { tones++ }.execute(vin, VehicleCommand.OpenFrunk, dispatch, waking)
                }
            }
        }
        runCurrent()
        try {
            assertEquals(QuickActionRequests.Status.Waking, requests.requests.value.single().status)
            assertTrue(requests.cancel(requests.requests.value.single().id))
        } finally { gate.complete(Unit) }
        job.join()
        assertEquals(0, api.sends)
        assertEquals(0, tones)
        assertEquals(QuickActionRequests.Status.Cancelled, requests.requests.value.single().status)
    }

    /** 온라인으로 바뀌었더라도 요청 수명이 먼저 끝났으면 개폐하지 않는다. */
    @Test
    fun `expiry during wake prevents submission`() = runTest {
        val api = FakeApi().apply { states.add(FleetApi.VehicleState.Offline) }
        try {
            withContext(CommandDeadline(500) { testScheduler.currentTime }) {
                FleetCommandClient(api).execute(vin, VehicleCommand.OpenFrunk, { fail("expired dispatch") }, {})
            }
            fail("must expire")
        } catch (_: CommandExpiredException) { }
        assertEquals(1, api.wakes)
        assertEquals(0, api.sends)
    }

    /** 거절·접수만 된 응답·네트워크 단절은 성공음이나 자동 재전송으로 이어지지 않는다. */
    @Test
    fun `rejected unknown and transport failure are never retried`() = runTest {
        var tones = 0
        for (response in listOf(FleetApi.CommandResult.Rejected, FleetApi.CommandResult.Unknown)) {
            val api = FakeApi().apply { this.response = response }
            withContext(CommandDeadline(120_000) { testScheduler.currentTime }) {
                assertEquals(response, FleetCommandClient(api) { tones++ }.execute(vin, VehicleCommand.OpenTrunk, {}, {}))
            }
            assertEquals(1, api.sends)
        }
        val api = FakeApi().apply { sendError = java.io.IOException("response lost") }
        try {
            withContext(CommandDeadline(120_000) { testScheduler.currentTime }) {
                FleetCommandClient(api) { tones++ }.execute(vin, VehicleCommand.OpenTrunk, {}, {})
            }
            fail("must fail")
        } catch (_: java.io.IOException) { }
        assertEquals(1, api.sends)
        assertEquals(0, tones)
    }

    /** 서버 미설정이나 인증 실패는 조회 단계에서 종료하고 깨우기·개폐를 시도하지 않는다. */
    @Test
    fun `unconfigured and authorization errors fail closed`() = runTest {
        for (api in listOf(UnconfiguredFleetApi, FakeApi().apply { readError = SecurityException("unauthorized") })) {
            try {
                withContext(CommandDeadline(120_000) { testScheduler.currentTime }) {
                    FleetCommandClient(api).execute(vin, VehicleCommand.OpenFrunk, { fail("no dispatch") }, { fail("no wake") })
                }
                fail("must fail")
            } catch (_: IllegalStateException) { } catch (_: SecurityException) { }
        }
    }
}
