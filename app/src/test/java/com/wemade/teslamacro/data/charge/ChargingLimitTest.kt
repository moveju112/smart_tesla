package com.wemade.teslamacro.data.charge

import org.junit.Assert.assertEquals
import org.junit.Test

/** 스텔스 충전이 차량 상한과 누락 폴백을 정확히 선택하는지 검증 */
class ChargingLimitTest {

    @Test
    fun `차량이 보고한 상한을 폴백보다 우선한다`() {
        assertEquals(16, effectiveMaxChargingAmps(16, fallbackMaxAmps = 32, minAmps = 5))
    }

    @Test
    fun `차량 상한이 없으면 폴백을 쓴다`() {
        assertEquals(32, effectiveMaxChargingAmps(null, fallbackMaxAmps = 32, minAmps = 5))
    }

    @Test
    fun `비정상적으로 낮은 상한은 명령 최솟값으로 올린다`() {
        assertEquals(5, effectiveMaxChargingAmps(2, fallbackMaxAmps = 32, minAmps = 5))
    }
}
