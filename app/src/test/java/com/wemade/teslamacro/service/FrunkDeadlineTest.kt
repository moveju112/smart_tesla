package com.wemade.teslamacro.service

import com.wemade.teslable.CommandDeadline
import com.wemade.teslable.CommandExpiredException
import com.wemade.teslable.ensureCommandActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FrunkDeadlineTest {
    // 서비스 시작이 지연돼도 수신 때 정한 만료를 연장하지 않는다.
    @Test fun `수신 30초 뒤 시작하면 90초만 남는다`() {
        val deadline = CommandDeadline(120_000L) { 30_000L }
        assertEquals(90_000L, deadline.remainingMillis())
    }

    // 30초 연결 실패 뒤 접근한 차에는 같은 요청으로 한 번 전송할 수 있다.
    @Test fun `늦은 연결과 깨우기가 2분 안에 끝나면 전송한다`() = runTest {
        val deadline = CommandDeadline(120_000L) { testScheduler.currentTime }
        var sends = 0
        withContext(deadline) {
            delay(90_000L)
            delay(29_999L)
            ensureCommandActive()
            sends++
        }
        assertEquals(1, sends)
    }

    // 타이머가 실행되지 않은 상황도 절대 시각으로 차단한다.
    @Test fun `절전 후 2분 경계에서는 전송하지 않는다`() = runTest {
        var elapsed = 0L
        val deadline = CommandDeadline(120_000L) { elapsed }
        var sends = 0
        try {
            withContext(deadline) {
                elapsed = 120_000L
                ensureCommandActive()
                sends++
            }
            fail("만료 예외 필요")
        } catch (_: CommandExpiredException) { }
        assertEquals(0, sends)
    }

    // 하위 연결 코드가 취소를 Result로 바꿔도 이후 명령은 보내지 않는다.
    @Test fun `연결 취소를 삼켜도 전송은 차단하고 정리한다`() = runTest {
        val deadline = CommandDeadline(120_000L) { testScheduler.currentTime }
        var sends = 0
        var cleaned = false
        val result = withTimeoutOrNull(deadline.remainingMillis()) {
            withContext(deadline) {
                try {
                    runCatching { delay(180_000L) }
                    ensureCommandActive()
                    sends++
                } finally { cleaned = true }
            }
        }
        assertNull(result)
        assertEquals(0, sends)
        assertTrue(cleaned)
        assertEquals(120_000L, testScheduler.currentTime)
    }
}
