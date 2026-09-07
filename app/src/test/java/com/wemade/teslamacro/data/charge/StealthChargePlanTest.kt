package com.wemade.teslamacro.data.charge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 16A 이하에서도 상한 주변을 유지하고 시간대가 정확히 적용되는지 검증한다. */
class StealthChargePlanTest {

    @Test
    fun `16A 충전기는 12A 이상 상한 안에서만 조절한다`() {
        val random = Random(42)
        var current = 16
        repeat(500) {
            val step = StealthChargePlan.next(current, minAmps = 5, maxAmps = 16, random = random)
            assertTrue("전류 ${step.amps}가 고전류 밴드를 벗어났다", step.amps in 12..16)
            current = step.amps
        }
    }

    @Test
    fun `난수는 상한 쪽에 가중되면서도 여러 전류를 만든다`() {
        val random = Random(7)
        var current = 14
        val values = buildList {
            repeat(500) {
                val step = StealthChargePlan.next(current, 5, 16, random)
                add(step.amps)
                current = step.amps
            }
        }
        assertTrue("평균 전류가 너무 낮다 (${values.average()})", values.average() >= 14.0)
        assertTrue("전류가 한 값에 고정됐다 (${values.toSet()})", values.toSet().size >= 3)
    }

    @Test
    fun `보고 상한이 10A면 8~10A로 자동 축소한다`() {
        val random = Random(99)
        repeat(200) {
            val step = StealthChargePlan.next(10, 5, 10, random)
            assertTrue(step.amps in 8..10)
        }
    }

    @Test
    fun `간격은 기존 30~120초 사이다`() {
        val random = Random(11)
        repeat(200) {
            val step = StealthChargePlan.next(14, 5, 16, random)
            assertTrue("간격 ${step.holdSeconds}", step.holdSeconds in 30..120)
        }
    }

    @Test
    fun `자정을 넘는 시간대와 하루 종일 설정을 판정한다`() {
        assertTrue(isWithinStealthChargeWindow(23 * 60, true, 22 * 60, 6 * 60))
        assertTrue(isWithinStealthChargeWindow(5 * 60, true, 22 * 60, 6 * 60))
        assertFalse(isWithinStealthChargeWindow(12 * 60, true, 22 * 60, 6 * 60))
        assertTrue(isWithinStealthChargeWindow(12 * 60, false, 22 * 60, 6 * 60))
        assertTrue(isWithinStealthChargeWindow(12 * 60, true, 7 * 60, 7 * 60))
    }

    @Test
    fun `밴드가 한 점으로 좁아도 터지지 않는다`() {
        val step = StealthChargePlan.next(5, minAmps = 5, maxAmps = 5, random = Random(0))
        assertEquals(5, step.amps)
    }
}
