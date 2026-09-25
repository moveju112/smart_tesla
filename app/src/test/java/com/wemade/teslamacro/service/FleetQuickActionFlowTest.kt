package com.wemade.teslamacro.service

import com.wemade.teslamacro.data.fleet.*
import com.wemade.teslamacro.data.gateway.ExternalQuickActionSound
import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslable.CommandDeadline
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FleetQuickActionFlowTest {
    private val vin = "5YJS0000000000000"

    /** 외부 음성은 최종 성공에도 기존 Fleet 단발음을 생략하고 실패·접수에는 완료음을 내지 않는다. */
    @Test
    fun `external quick action defers fleet confirmation to service`() = runTest {
        var defaultTones = 0
        val transport = FleetHttpTransport { method, _, _, _, _ ->
            val status = if (method == "POST") "queued" else "succeeded"
            FleetHttpResponse(if (method == "POST") 202 else 200,
                """{"id":"dummy-id","vin":"$vin","name":"door_lock","status":"$status","result":null}""")
        }
        val client = FleetQueuedClient(transport, { "dummy-token" }) { defaultTones++ }
        withContext(CommandDeadline(120_000) { testScheduler.currentTime } + ExternalQuickActionSound) {
            assertEquals(FleetQueueStatus.Succeeded, client.execute(vin, VehicleCommand.Lock, {}).status)
        }
        assertEquals(0, defaultTones)
        withContext(CommandDeadline(120_000) { testScheduler.currentTime }) {
            assertEquals(FleetQueueStatus.Succeeded, client.execute(vin, VehicleCommand.Lock, {}).status)
        }
        assertEquals(1, defaultTones)
    }

    /** 서버 접수 뒤에는 취소 대신 조회만 중단하고 늦은 성공 응답의 효과음을 막는다. */
    @Test
    fun `accepted fleet request can stop observing without pretending cancellation`() = runTest {
        var posts = 0
        var tones = 0
        val transport = FleetHttpTransport { method, _, _, _, _ ->
            if (method == "POST") posts++
            FleetHttpResponse(if (method == "POST") 202 else 200,
                """{"id":"dummy-id","vin":"$vin","name":"actuate_trunk","status":"queued","result":null}""")
        }
        val client = FleetQueuedClient(transport, { "dummy-token" }) { tones++ }
        val tracker = QuickActionRequests { }
        val job = launch {
            tracker.trackWithFleetProgress("트렁크 닫기") { before, _, submitted ->
                withContext(CommandDeadline(120_000) { testScheduler.currentTime }) {
                    client.execute(vin, VehicleCommand.CloseTrunk, before) { if (it.pending) submitted(it.id!!) }
                }
            }
        }
        runCurrent()
        val request = tracker.requests.value.single()
        assertEquals(QuickActionRequests.Status.Observing, request.status)
        assertEquals("dummy-id", request.commandId)
        assertFalse(tracker.cancel(request.id))
        assertTrue(tracker.stopObserving(request.id))
        job.join()
        assertEquals(QuickActionRequests.Status.ObservationStopped, tracker.requests.value.single().status)
        assertEquals("dummy-id", tracker.requests.value.single().commandId)
        assertEquals(1, posts)
        assertEquals(0, tones)
        assertFalse(tracker.stopObserving(request.id))
    }

    /** 토큰 조회 중에는 여전히 미전송 취소이며 이후 서버에 POST를 보내지 않는다. */
    @Test
    fun `cancelling before submission does not send a request`() = runTest {
        var networkCalls = 0
        val token = CompletableDeferred<String>()
        val client = FleetQueuedClient(FleetHttpTransport { _, _, _, _, _ ->
            networkCalls++; error("must not send")
        }, { token.await() })
        val tracker = QuickActionRequests { }
        val job = launch {
            tracker.trackWithFleetProgress("보닛 열기") { before, _, submitted ->
                withContext(CommandDeadline(120_000) { testScheduler.currentTime }) {
                    client.execute(vin, VehicleCommand.OpenFrunk, before) { if (it.pending) submitted(it.id!!) }
                }
            }
        }
        runCurrent()
        assertTrue(tracker.cancel(tracker.requests.value.single().id))
        token.complete("dummy-token")
        job.join()
        assertEquals(0, networkCalls)
        assertEquals(QuickActionRequests.Status.Cancelled, tracker.requests.value.single().status)
    }

    /** 성공 응답 직후 중단이 먼저 확정돼도 효과음을 뒤늦게 재생하지 않는다. */
    @Test
    fun `stopping at terminal update suppresses late confirmation tone`() = runTest {
        var tones = 0
        val tracker = QuickActionRequests { }
        val client = FleetQueuedClient(FleetHttpTransport { method, _, _, _, _ ->
            val status = if (method == "POST") "queued" else "succeeded"
            FleetHttpResponse(if (method == "POST") 202 else 200,
                """{"id":"dummy-id","vin":"$vin","name":"actuate_trunk","status":"$status","result":null}""")
        }, { "dummy-token" }) { tones++ }
        val job = launch {
            tracker.trackWithFleetProgress("보닛 열기") { before, _, submitted ->
                withContext(CommandDeadline(120_000) { testScheduler.currentTime }) {
                    client.execute(vin, VehicleCommand.OpenFrunk, before) {
                        if (it.pending) submitted(it.id!!)
                        else tracker.stopObserving(tracker.requests.value.single().id)
                    }
                }
            }
        }
        job.join()
        assertEquals(QuickActionRequests.Status.ObservationStopped, tracker.requests.value.single().status)
        assertEquals(0, tones)
    }

    /** 전송 진입했지만 접수 응답을 못 받은 상태는 취소/조회 중단 가능 상태로 오인하지 않는다. */
    @Test
    fun `in flight submission cannot claim cancellation`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val tracker = QuickActionRequests { }
        val job = launch {
            tracker.trackWithFleetProgress("보닛 열기") { before, _, _ -> before(); gate.await() }
        }
        runCurrent()
        val id = tracker.requests.value.single().id
        assertFalse(tracker.cancel(id))
        assertFalse(tracker.stopObserving(id))
        job.cancel(); job.join()
        assertEquals(QuickActionRequests.Status.Failed, tracker.requests.value.single().status)
    }
}
