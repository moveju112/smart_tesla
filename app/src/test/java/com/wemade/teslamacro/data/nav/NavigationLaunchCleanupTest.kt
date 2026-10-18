package com.wemade.teslamacro.data.nav

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test

class NavigationLaunchCleanupTest {
    /** 정리 실패와 취소 모두 원래 예외를 유지하고 다음 지도가 잠금을 얻을 수 있다. */
    @Test fun cleanupFailureAndCancellationReleaseLaunchLock() {
        for (failure in listOf(IllegalStateException("cleanup failed"), CancellationException("cancelled"))) {
            val lock = Mutex(locked = true)
            val result = runCatching { finishNavigationLaunch(lock) { throw failure } }
            assertSame(failure, result.exceptionOrNull())
            assertTrue(lock.tryLock())
            lock.unlock()
        }
    }

    /** 정상 종료에서는 정리를 한 번 실행한 뒤 잠금을 반환한다. */
    @Test fun successfulCleanupRunsOnceAndReleasesLock() {
        val lock = Mutex(locked = true)
        var cleanups = 0
        finishNavigationLaunch(lock) { cleanups++ }
        assertEquals(1, cleanups)
        assertTrue(lock.tryLock())
        lock.unlock()
    }
}
