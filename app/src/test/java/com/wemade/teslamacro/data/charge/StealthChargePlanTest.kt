package com.wemade.teslamacro.data.charge

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 스텔스 충전 계획 검증 — 값이 밴드를 벗어나지 않고, 실제로 위아래로 흔들리는지 */
class StealthChargePlanTest {

    @Test
    fun `전류는 항상 밴드 안이다`() {
        val random = Random(42)
        var current = 20
        repeat(500) {
            val step = StealthChargePlan.next(current, minAmps = 5, maxAmps = 32, random = random)
            assertTrue("전류 ${step.amps}가 밴드를 벗어났다", step.amps in 5..32)
            current = step.amps
        }
    }

    @Test
    fun `간격은 30~120초 사이다`() {
        val random = Random(7)
        repeat(200) {
            val step = StealthChargePlan.next(20, 5, 32, random)
            assertTrue("간격 ${step.holdSeconds}", step.holdSeconds in 30..120)
        }
    }

    @Test
    fun `한쪽으로 눌러앉지 않고 위아래로 배회한다`() {
        // 사용자의 원래 아이디어(계속 낮추기)의 실패를 피했는지 — 오르는 스텝이 충분히 나와야 한다
        val random = Random(1)
        var current = 18
        var wentUp = 0
        var wentDown = 0
        repeat(1000) {
            val step = StealthChargePlan.next(current, 5, 32, random)
            if (step.amps > current) wentUp++
            if (step.amps < current) wentDown++
            current = step.amps
        }
        assertTrue("오르는 스텝이 너무 적다 ($wentUp)", wentUp > 150)
        assertTrue("내리는 스텝이 너무 적다 ($wentDown)", wentDown > 150)
    }

    @Test
    fun `가끔 쉬는 구간으로 떨어진다 - 연속성 깨기`() {
        val random = Random(99)
        var current = 30
        var pauses = 0
        repeat(1000) {
            val step = StealthChargePlan.next(current, 5, 32, random)
            if (step.amps == 5) pauses++
            current = step.amps
        }
        // 15% 확률이므로 1000회에 최소 수십 번은 최저로 떨어져야 한다
        assertTrue("쉬는 구간이 없다 ($pauses)", pauses > 50)
    }

    @Test
    fun `밴드가 한 점으로 좁아도 터지지 않는다`() {
        val step = StealthChargePlan.next(5, minAmps = 5, maxAmps = 5, random = Random(0))
        assertTrue(step.amps == 5)
    }
}
