package com.wemade.teslamacro.data.charge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `충전이 확인되기 전에는 주기의 앞부분에서만 연결한다`() {
        val period = STEALTH_PROBE_PERIOD_MILLIS
        // 창 안: 확인하러 붙는다
        assertTrue(
            needsConnection(nowMillis = period * 3),
        )
        assertTrue(
            needsConnection(nowMillis = period * 3 + STEALTH_PROBE_ON_MILLIS - 1),
        )
        // 창 밖: 놓아 준다 — 밤새 붙잡으면 배터리와 휴대폰 키 근접 판정을 해친다
        assertFalse(
            needsConnection(nowMillis = period * 3 + STEALTH_PROBE_ON_MILLIS),
        )
        // 충전이 확인되면 창과 무관하게 계속 유지한다
        assertTrue(
            needsConnection(
                nowMillis = period * 3 + STEALTH_PROBE_ON_MILLIS,
                chargingConfirmed = true,
            ),
        )
    }

    @Test
    fun `전류를 바꿨으면 시간대 밖이어도 원복까지 연결을 유지한다`() {
        assertTrue(
            stealthChargeNeedsConnection(
                modified = true,
                enabled = false,
                inWindow = false,
                chargingConfirmed = false,
                nowMillis = STEALTH_PROBE_PERIOD_MILLIS,
            ),
        )
    }

    @Test
    fun `꺼져 있거나 시간대 밖이면 확인 창에도 붙지 않는다`() {
        assertFalse(needsConnection(enabled = false))
        assertFalse(needsConnection(inWindow = false))
    }

    /** 확인 창 판정만 바꿔 가며 부르는 기본값 묶음. */
    private fun needsConnection(
        enabled: Boolean = true,
        inWindow: Boolean = true,
        chargingConfirmed: Boolean = false,
        nowMillis: Long = STEALTH_PROBE_PERIOD_MILLIS,
    ): Boolean = stealthChargeNeedsConnection(
        modified = false,
        enabled = enabled,
        inWindow = inWindow,
        chargingConfirmed = chargingConfirmed,
        nowMillis = nowMillis,
    )

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
