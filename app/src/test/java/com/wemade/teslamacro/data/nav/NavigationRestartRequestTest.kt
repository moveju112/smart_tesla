package com.wemade.teslamacro.data.nav

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationRestartRequestTest {
    /** 종료 응답을 기다리는 실제 취소 작업이 끝난 뒤 새 요청을 한 번만 실행한다. */
    @Test
    fun restartWaitsForCleanupAndCoalescesRepeatedStarts() = runTest {
        val request = NavigationRestartRequest(this)
        val finished = CompletableDeferred<Unit>()
        val operation = operationWithCleanup(this, finished)
        var starts = 0
        runCurrent()
        operation.cancel()
        runCurrent()

        repeat(3) { request.afterCancellation(operation) { starts++ } }
        runCurrent()
        assertFalse(operation.isCompleted)
        assertEquals(0, starts)

        finished.complete(Unit)
        runCurrent()
        assertEquals(1, starts)
        runCurrent()
        assertEquals(1, starts)
    }

    /** 실행 중 중복 탭은 종료 원인과 무관하게 다음 실행 예약으로 남지 않는다. */
    @Test
    fun runningOperationDoesNotRestartAfterCompletionFailureOrCancellation() = runTest {
        val request = NavigationRestartRequest(this)
        var starts = 0
        repeat(3) { outcome ->
            val operation = Job()
            request.afterCancellation(operation) { starts++ }
            when (outcome) {
                0 -> operation.complete()
                1 -> operation.completeExceptionally(IllegalStateException("실행 실패"))
                else -> operation.cancel()
            }
            runCurrent()
            assertEquals(0, starts)
        }
    }

    /** OFF·하차·서비스 종료가 폐기한 요청의 콜백은 새 요청까지 지우면 안 된다. */
    @Test
    fun revokedRequestCannotLaunchOrConsumeANewerRequest() = runTest {
        val request = NavigationRestartRequest(this)
        val firstFinished = CompletableDeferred<Unit>()
        val first = operationWithCleanup(this, firstFinished)
        val nextFinished = CompletableDeferred<Unit>()
        val next = operationWithCleanup(this, nextFinished)
        var starts = 0
        runCurrent()
        first.cancel()
        next.cancel()
        runCurrent()
        request.afterCancellation(first) { starts += 100 }
        request.cancel()
        request.afterCancellation(next) { starts++ }

        firstFinished.complete(Unit)
        runCurrent()
        assertEquals(0, starts)
        nextFinished.complete(Unit)
        runCurrent()
        assertEquals(1, starts)
    }

    /** 종료 콜백이 이미 예약됐어도 사용자 취소가 먼저 처리되면 실행하지 않는다. */
    @Test
    fun cancellationRevokesAnAlreadyScheduledCompletionCallback() = runTest {
        val request = NavigationRestartRequest(this)
        var starts = 0
        val finished = CompletableDeferred<Unit>()
        val cleanup = operationWithCleanup(this, finished)
        runCurrent()
        cleanup.cancel()
        runCurrent()
        request.afterCancellation(cleanup) { starts++ }
        cleanup.invokeOnCompletion { request.cancel() }
        finished.complete(Unit)
        runCurrent()
        assertEquals(0, starts)
    }

    /** 장치 종료 응답을 받기 전까지 취소 완료가 지연되는 운영 코드의 정리 경계를 만든다. */
    private fun operationWithCleanup(scope: CoroutineScope, finished: CompletableDeferred<Unit>): Job = scope.launch {
        try { awaitCancellation() }
        finally { withContext(NonCancellable) { finished.await() } }
    }
}
