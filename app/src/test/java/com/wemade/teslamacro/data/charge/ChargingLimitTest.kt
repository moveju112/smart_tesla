package com.wemade.teslamacro.data.charge

import org.junit.Assert.assertEquals
import org.junit.Test

/** 스텔스 충전이 차량 상한과 누락 폴백을 정확히 선택하는지 검증 */
class ChargingLimitTest {

    @Test
    fun `차량이 보고한 상한을 폴백보다 우선한다`() {
        assertEquals(16, effectiveMaxChargingAmps(16, currentAmps = 12, fallbackMaxAmps = 32, minAmps = 5))
    }

    @Test
    fun `차량 상한이 없으면 현재 전류를 폴백보다 우선한다`() {
        assertEquals(13, effectiveMaxChargingAmps(null, currentAmps = 13, fallbackMaxAmps = 16, minAmps = 5))
    }

    @Test
    fun `상한과 현재 전류가 없으면 16A 폴백을 쓴다`() {
        assertEquals(16, effectiveMaxChargingAmps(null, currentAmps = null, fallbackMaxAmps = 16, minAmps = 5))
    }

    @Test
    fun `비정상적으로 낮은 상한은 명령 최솟값으로 올린다`() {
        assertEquals(5, effectiveMaxChargingAmps(2, currentAmps = 2, fallbackMaxAmps = 16, minAmps = 5))
    }
}
