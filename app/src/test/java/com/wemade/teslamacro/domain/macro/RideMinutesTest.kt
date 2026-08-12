package com.wemade.teslamacro.domain.macro

import com.wemade.teslamacro.data.macro.MacroPresets
import com.wemade.teslamacro.domain.model.ShiftState
import com.wemade.teslamacro.domain.model.VehicleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "탑승 시간" 조건 검증 — 30분 이상 타고 내렸을 때만 애프터블로우.
 * 값 자체는 폴러가 재서 스냅샷에 싣고, 여기서는 판정 규칙을 본다.
 */
class RideMinutesTest {

    private val engine = MacroEngine()
    private val rule = MacroPresets.afterBlow().copy(enabled = true)

    private fun reading(userPresent: Boolean, rideMinutes: Double?) = Reading(
        snapshot = VehicleSnapshot(
            timestampMillis = 0L,
            isUserPresent = userPresent,
            shiftState = ShiftState.PARK,
            rideMinutes = rideMinutes,
        ),
        time = TimeContext(0L, minutesOfDay = 19 * 60, dayOfWeek = 1),
    )

    private fun fires(rideMinutes: Double?): Boolean = engine.evaluate(
        listOf(rule),
        reading(userPresent = true, rideMinutes = rideMinutes),
        // 하차 순간 — 폴러가 세션 길이를 고정해서 실어준다
        reading(userPresent = false, rideMinutes = rideMinutes),
        emptyMap(),
    ).isNotEmpty()

    @Test
    fun `35분 타고 내리면 애프터블로우가 발동한다`() {
        assertTrue(fires(rideMinutes = 35.0))
    }

    @Test
    fun `10분만 타고 내리면 발동하지 않는다`() {
        assertEquals(false, fires(rideMinutes = 10.0))
    }

    @Test
    fun `탑승 시간을 모르면 발동하지 않는다 - fail closed`() {
        assertEquals(false, fires(rideMinutes = null))
    }
}
