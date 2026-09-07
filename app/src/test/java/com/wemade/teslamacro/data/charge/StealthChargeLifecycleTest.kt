package com.wemade.teslamacro.data.charge

import org.junit.Assert.assertEquals
import org.junit.Test

/** 스텔스 충전 1회가 대기·실행·완료·수동 해제를 정확히 구분하는지 검증한다. */
class StealthChargeLifecycleTest {

    @Test
    fun `충전을 시작하기 전 종료 상태는 다음 충전을 계속 기다린다`() {
        assertEquals(
            StealthChargeAction.WAIT,
            action(enabled = true, started = false, isCharging = false),
        )
    }

    @Test
    fun `충전을 시작한 뒤 종료가 확인되면 1회를 완료한다`() {
        assertEquals(
            StealthChargeAction.COMPLETE,
            action(enabled = true, started = true, isCharging = false),
        )
    }

    @Test
    fun `사용자가 끄면 원래 전류 복구를 먼저 한다`() {
        assertEquals(
            StealthChargeAction.RESTORE_DISABLED,
            action(enabled = false, started = true, modified = true, isCharging = true),
        )
    }

    @Test
    fun `연결이 끊긴 동안은 복구를 완료로 오인하지 않는다`() {
        assertEquals(
            StealthChargeAction.WAIT,
            action(enabled = true, started = true, linked = false, isCharging = null),
        )
    }

    /** 각 테스트가 바꾸는 상태만 이름으로 넘겨 기대 동작을 읽기 쉽게 만든다. */
    private fun action(
        enabled: Boolean,
        started: Boolean,
        modified: Boolean = false,
        linked: Boolean = true,
        isCharging: Boolean?,
    ): StealthChargeAction = stealthChargeAction(
        enabled = enabled,
        started = started,
        modified = modified,
        linked = linked,
        isCharging = isCharging,
    )
}
