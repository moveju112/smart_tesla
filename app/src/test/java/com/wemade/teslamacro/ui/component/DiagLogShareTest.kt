package com.wemade.teslamacro.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 파일 첨부 실패 시 공유 Intent 본문이 커지지 않는지 검증 */
class DiagLogShareTest {

    @Test
    fun `짧은 로그는 그대로 보낸다`() {
        assertEquals("최근 로그", fallbackShareText("최근 로그"))
    }

    @Test
    fun `큰 로그는 최근 32000자만 보낸다`() {
        val text = "앞".repeat(10_000) + "뒤".repeat(40_000)
        val result = fallbackShareText(text)

        assertEquals(32_000, result.length)
        assertTrue(result.all { it == '뒤' })
    }
}
