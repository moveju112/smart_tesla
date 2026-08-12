package com.wemade.teslamacro.data.poll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 탑승 시간 측정 검증 — 특히 BLE 공백 중 하차한 경우의 과다 계산 방지 */
class RideSessionMeterTest {

    private var clock = 0L
    private val meter = RideSessionMeter { clock }

    private fun minutes(m: Int) = m * 60_000L

    @Test
    fun `타는 동안은 흐른 시간, 내리면 세션 길이로 고정된다`() {
        clock = 0; meter.update(true)
        clock = minutes(35)
        assertEquals(35.0, meter.update(true)!!, 0.01)
        assertEquals(35.0, meter.update(false)!!, 0.01)
        // 하차 직후(유예 10분 안)엔 직전 세션 길이가 유지된다 — 하차 트리거의 조건 판정용
        clock = minutes(40)
        assertEquals(35.0, meter.update(false)!!, 0.01)
    }

    @Test
    fun `하차 10분이 지나면 세션 길이가 만료된다`() {
        // 어제 45분 탄 기록이 오늘 문 열 때 "30분 이상 탔음"으로 오인되면 안 된다
        clock = 0; meter.update(true)
        clock = minutes(45); meter.update(false)
        clock = minutes(45 + 11)
        assertNull(meter.update(false))
    }

    @Test
    fun `BLE 공백 중 하차 - 마지막으로 확인한 시각까지만 센다`() {
        // 40분까지 타는 걸 확인 → 밤새 끊김(null) → 아침에 하차 판정
        clock = 0; meter.update(true)
        clock = minutes(40); meter.update(true)
        clock = minutes(300); meter.update(null)   // 끊김 — 판단 보류
        clock = minutes(600)
        // 600분이 아니라 40분이어야 한다
        assertEquals(40.0, meter.update(false)!!, 0.01)
    }

    @Test
    fun `읽기 전에는 null`() {
        assertNull(meter.update(null))
    }

    @Test
    fun `다시 타면 0부터 센다`() {
        clock = 0; meter.update(true)
        clock = minutes(30); meter.update(false)
        clock = minutes(60); meter.update(true)
        clock = minutes(65)
        assertEquals(5.0, meter.update(true)!!, 0.01)
    }
}
