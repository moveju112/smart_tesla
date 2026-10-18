package com.wemade.teslable

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BleWriteCancellationTest {
    /** 취소 시 링크를 먼저 무효화하고 늦은 콜백으로 다시 유효해지지 않는다. */
    @Test fun cancelledWriteInvalidatesBeforeLateCompletion() = runTest {
        val response = CompletableDeferred<Unit>()
        var invalidations = 0
        val write = launch { awaitBleWriteResult(response, 5_000) { invalidations++ } }
        runCurrent()
        write.cancel()
        write.join()
        assertTrue(write.isCancelled)
        assertEquals(1, invalidations)
        response.complete(Unit)
        runCurrent()
        assertEquals(1, invalidations)
    }

    /** 정상 완료된 쓰기는 다음 요청이 같은 링크를 사용할 수 있다. */
    @Test fun completedWritePreservesLink() = runTest {
        val response = CompletableDeferred(Unit)
        awaitBleWriteResult(response, 5_000) { fail("Completed write invalidated link") }
    }

    /** 쓰기 타임아웃은 링크를 무효화하고 기존 실패 계약을 유지한다. */
    @Test fun timeoutInvalidatesLinkAndReportsFailure() = runTest {
        var invalidations = 0
        val result = runCatching {
            awaitBleWriteResult(CompletableDeferred(), 100) { invalidations++ }
        }
        assertEquals(1, invalidations)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }
}
