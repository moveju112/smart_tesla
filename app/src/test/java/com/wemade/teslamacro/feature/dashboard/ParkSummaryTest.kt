package com.wemade.teslamacro.feature.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 주차 경과와 배터리 소모 요약.
 *
 * 이 앱이 제일 걱정하는 건 밤새 빠지는 전기다 — 주차 시작 시점의 배터리를
 * 기억해야만 "12시간 동안 3% 줄었다"를 말할 수 있다.
 */
class ParkSummaryTest {

    private val minute = 60_000L

    @Test
    fun `타고 있으면 주차 줄이 없다`() {
        assertNull(parkSummaryOf(parkStart = null, batteryNow = 80))
    }

    @Test
    fun `막 내렸으면 아직 적지 않는다`() {
        // 1분도 안 된 "주차 0분"은 정보가 아니다
        assertNull(parkSummaryOf(0L to 80, batteryNow = 80, nowMillis = 30_000L))
    }

    @Test
    fun `한 시간 미만은 분으로 적는다`() {
        assertEquals("45분", parkSummaryOf(0L to 80, batteryNow = 80, nowMillis = 45 * minute))
    }

    @Test
    fun `한 시간이 넘으면 시간과 분으로 나눈다`() {
        assertEquals(
            "12시간 30분",
            parkSummaryOf(0L to 80, batteryNow = 80, nowMillis = 750 * minute),
        )
        // 딱 떨어지면 분을 안 붙인다
        assertEquals("3시간", parkSummaryOf(0L to 80, batteryNow = 80, nowMillis = 180 * minute))
    }

    @Test
    fun `배터리가 줄었으면 소모를 함께 적는다`() {
        assertEquals(
            "12시간 · -3%",
            parkSummaryOf(0L to 80, batteryNow = 77, nowMillis = 720 * minute),
        )
    }

    /** 충전 중이면 배터리가 오른다. 그걸 "-(-5)%"로 적으면 안 된다 */
    @Test
    fun `배터리가 늘었으면 시간만 적는다`() {
        assertEquals(
            "2시간",
            parkSummaryOf(0L to 60, batteryNow = 80, nowMillis = 120 * minute),
        )
    }

    @Test
    fun `배터리를 못 읽었으면 시간만 적는다`() {
        assertEquals("2시간", parkSummaryOf(0L to null, batteryNow = 80, nowMillis = 120 * minute))
        assertEquals("2시간", parkSummaryOf(0L to 80, batteryNow = null, nowMillis = 120 * minute))
    }
}
