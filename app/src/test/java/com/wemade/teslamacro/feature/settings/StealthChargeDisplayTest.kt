package com.wemade.teslamacro.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** 스텔스 충전의 남은 시간이 분·초 경계에서 정확히 표시되는지 검증한다. */
class StealthChargeDisplayTest {

    @Test
    fun `남은 시간을 분과 초로 표시한다`() {
        assertEquals("2분 14초", formatStealthCountdown(134))
        assertEquals("59초", formatStealthCountdown(59))
        assertEquals("0초", formatStealthCountdown(-1))
    }
}
