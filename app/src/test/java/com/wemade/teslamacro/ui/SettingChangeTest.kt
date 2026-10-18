package com.wemade.teslamacro.ui

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SettingChangeTest {
    /** 정상 저장은 후속 작업을 한 번만 실행하고 실패를 알리지 않는다. */
    @Test fun successRunsOnce() = runTest {
        var changes = 0
        applySettingChange({ changes++ }) { fail("Unexpected error: $it") }
        assertEquals(1, changes)
    }

    /** 저장 실패는 호출자에게 한 번 알리고 저장 뒤의 부수 효과를 실행하지 않는다. */
    @Test fun storageFailureStopsFollowupWithoutRetry() = runTest {
        val failure = IOException("disk full")
        var attempts = 0
        var followedUp = false
        var reported: Exception? = null
        val write: suspend () -> Unit = { attempts++; throw failure }
        applySettingChange({ write(); followedUp = true }) { reported = it }
        assertSame(failure, reported)
        assertEquals(1, attempts)
        assertFalse(followedUp)
    }

    /** 화면 종료 취소는 저장 오류로 바꾸지 않고 호출자에게 전달한다. */
    @Test fun cancellationStillPropagates() = runTest {
        val cancellation = CancellationException("screen closed")
        val result = runCatching {
            applySettingChange({ throw cancellation }) { fail("Cancellation reported as failure") }
        }
        assertSame(cancellation, result.exceptionOrNull())
    }
}
