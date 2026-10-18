package com.wemade.teslamacro.data.nav

import app.cash.paparazzi.Paparazzi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NavigatorCancellationTest {
    @get:Rule val paparazzi = Paparazzi()

    /** 실행 준비 취소를 일반 실패로 삼키지 않고 호출자에게 전달하며 잠금도 반환한다. */
    @Test fun preparationCancellationPropagatesAndReleasesLock() = runTest {
        val navigator = NaverNavigator(paparazzi.context)
        val cancelled = CancellationException("test cancellation")
        repeat(2) {
            val result = runCatching {
                navigator.navigate("서울역", "서울역", beforeLaunch = { throw cancelled })
            }
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertEquals(cancelled.message, result.exceptionOrNull()?.message)
        }
    }
}
