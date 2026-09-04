package com.wemade.teslamacro.data.poll

import com.wemade.teslamacro.domain.model.VehicleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

/** 고정 폴링 전략 검증 — 깊은 유휴(잠김+빈차+비충전)와 충전 중 예외가 핵심 */
class PollIntervalTest {

    private fun snapshot(locked: Boolean?, present: Boolean?, charging: Boolean?) =
        VehicleSnapshot(
            timestampMillis = 0L,
            isLocked = locked,
            isUserPresent = present,
            isCharging = charging,
        )

    private fun interval(locked: Boolean?, present: Boolean?, charging: Boolean?, window: Boolean = false) =
        nextIntervalSeconds(
            inActiveWindow = window,
            snapshot = snapshot(locked, present, charging),
            activeSeconds = 2,
            idleSeconds = 30,
        )

    @Test
    fun `잠기고 비었고 충전 아니면 깊은 유휴로 간다`() {
        assertEquals(DEEP_IDLE_SECONDS, interval(locked = true, present = false, charging = false))
    }

    @Test
    fun `충전 중이면 깊은 유휴로 가지 않는다`() {
        // 스텔스 충전·배터리 감시가 신선한 값을 원한다
        assertEquals(30, interval(locked = true, present = false, charging = true))
    }

    @Test
    fun `타고 있으면 평상시 주기다`() {
        assertEquals(30, interval(locked = false, present = true, charging = false))
    }

    @Test
    fun `상태를 모르면(null) 깊은 유휴로 가지 않는다`() {
        // 연결 직후 등 아직 못 읽은 상태에서 폴링을 늦추면 첫 화면이 굼떠진다
        assertEquals(30, interval(locked = null, present = null, charging = null))
    }

    @Test
    fun `집중 창이 열려 있으면 무조건 짧은 주기다`() {
        assertEquals(2, interval(locked = true, present = false, charging = false, window = true))
    }

    @Test
    fun `평상시 주기가 깊은 유휴보다 길면 그 값을 따른다`() {
        val result = nextIntervalSeconds(
            inActiveWindow = false,
            snapshot = snapshot(locked = true, present = false, charging = false),
            activeSeconds = 2,
            idleSeconds = 300,
        )
        assertEquals(300, result)
    }
}
