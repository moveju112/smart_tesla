package com.wemade.teslamacro.service

import com.wemade.teslable.CommandDeadline
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class QuickActionRequestsTest {
    /** 순수 JVM 테스트에서는 Android 네이티브 로그 대신 메모리 상태만 검증한다. */
    private fun QuickActionRequests() = com.wemade.teslamacro.service.QuickActionRequests(diagnosticLogger = {})

    /** 연결이 늦게 성공해도 취소된 요청은 전송 경계에서 차단한다. */
    @Test
    fun `cancel while connecting prevents late send`() = runTest {
        val requests = QuickActionRequests()
        val connected = CompletableDeferred<Unit>()
        var sent = 0
        val job = launch {
            requests.track("트렁크 열기") { beforeDispatch ->
                withContext(NonCancellable) { connected.await() }
                beforeDispatch()
                sent++
            }
        }
        runCurrent()
        val id = requests.requests.value.single().id
        try {
            assertTrue(requests.cancel(id))
            assertFalse(requests.cancel(id))
        } finally {
            // 취소 검증 자체가 실패해도 비취소 연결 모형을 해제해 원래 오류를 보고한다.
            connected.complete(Unit)
        }
        job.join()
        assertEquals(0, sent)
        assertEquals(QuickActionRequests.Status.Cancelled, requests.requests.value.single().status)
    }

    /** 전송 처리가 시작된 뒤의 취소는 성공으로 표시하지 않는다. */
    @Test
    fun `sending cannot be cancelled or dismissed`() = runTest {
        val requests = QuickActionRequests()
        val finish = CompletableDeferred<Unit>()
        val job = launch { requests.track("트렁크 열기") { dispatch -> dispatch(); finish.await() } }
        runCurrent()
        val id = requests.requests.value.single().id
        assertFalse(requests.cancel(id))
        requests.dismiss(id)
        assertEquals(QuickActionRequests.Status.Sending, requests.requests.value.single().status)
        finish.complete(Unit)
        job.join()
        assertEquals(QuickActionRequests.Status.Finished, requests.requests.value.single().status)
        requests.dismiss(id)
        assertTrue(requests.requests.value.isEmpty())
    }

    /** 같은 문구도 별개 요청으로 취급해 취소가 다른 요청에 번지지 않는다. */
    @Test
    fun `cancel affects only selected request`() = runTest {
        val requests = QuickActionRequests()
        val finish = CompletableDeferred<Unit>()
        val first = launch { requests.track("트렁크 열기") { awaitCancellation() } }
        val second = launch { requests.track("트렁크 열기") { dispatch -> finish.await(); dispatch() } }
        runCurrent()
        val records = requests.requests.value
        assertTrue(requests.cancel(records[0].id))
        requests.dismiss(records[1].id)
        assertEquals(QuickActionRequests.Status.Waiting, requests.requests.value.last().status)
        finish.complete(Unit)
        first.join(); second.join()
        assertEquals(listOf(QuickActionRequests.Status.Cancelled, QuickActionRequests.Status.Finished), requests.requests.value.map { it.status })
    }

    /** 절대 수명 만료와 예외를 별도 종료 상태로 남긴다. */
    @Test
    fun `expired and failed requests never dispatch`() = runTest {
        val requests = QuickActionRequests()
        requests.track("만료") { CommandDeadline(100) { 100 }.check(); fail("must not dispatch") }
        requests.track("실패") { error("connection failure") }
        assertEquals(listOf(QuickActionRequests.Status.Expired, QuickActionRequests.Status.Failed), requests.requests.value.map { it.status })
    }

    /** 서비스 종료가 이미 전송된 명령을 취소했다고 오인시키지 않는다. */
    @Test
    fun `service cancellation distinguishes waiting from sending`() = runTest {
        val requests = QuickActionRequests()
        val waiting = launch { requests.track("연결 중") { awaitCancellation() } }
        val sending = launch { requests.track("전송 중") { dispatch -> dispatch(); awaitCancellation() } }
        runCurrent()
        waiting.cancel(); sending.cancel()
        waiting.join(); sending.join()
        assertEquals(listOf(QuickActionRequests.Status.Cancelled, QuickActionRequests.Status.Failed), requests.requests.value.map { it.status })
    }

    /** 종료 이력 제한이 아직 대기 중인 취소 버튼을 제거하지 않는다. */
    @Test
    fun `terminal history is bounded without dropping pending requests`() = runTest {
        val requests = QuickActionRequests()
        val waiting = launch { requests.track("대기") { awaitCancellation() } }
        runCurrent()
        repeat(10) { requests.track("종료 $it") { dispatch -> dispatch() } }
        assertEquals(4, requests.requests.value.size)
        assertTrue(requests.cancel(requests.requests.value.first().id))
        waiting.join()
        assertEquals(3, requests.requests.value.size)
    }

    /** 서로 다른 스레드에서 취소와 전송이 경합해도 취소 성공과 실제 전송은 공존하지 않는다. */
    @Test
    fun `cancel and dispatch race has exactly one winner`() = runTest {
        repeat(100) {
            val requests = QuickActionRequests()
            val ready = CompletableDeferred<Long>()
            val start = CompletableDeferred<Unit>()
            var sent = false
            val execution = launch(Dispatchers.Default) {
                requests.track("트렁크 열기") { dispatch ->
                    ready.complete(requests.requests.value.single().id)
                    start.await()
                    dispatch()
                    sent = true
                }
            }
            val id = ready.await()
            val cancellation = async(Dispatchers.Default) { start.await(); requests.cancel(id) }
            start.complete(Unit)
            val cancelled = cancellation.await()
            execution.join()
            assertNotEquals(cancelled, sent)
        }
    }
}
