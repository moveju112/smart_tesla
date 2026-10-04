package com.wemade.teslamacro.data.history

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class HistoryInsightsTest {
    private val today = LocalDate.of(2026, 10, 5)
    private val zone = ZoneId.of("Asia/Seoul")

    /** 전력 누락·긴 공백·계기판 역행에서 거리만 분자에 섞이지 않아야 한다. */
    @Test fun `efficiency uses only jointly observed intervals`() {
        val points = listOf(
            point(0, 100, 36), point(10_000, 110, 36), point(20_000, 200, null),
            point(30_000, 300, 36), point(50_000, 400, 36), point(60_000, 390, 36),
            point(70_000, 400, -36), point(80_000, 410, -36))
        val energy = historyEnergy(points)
        assertEquals(30 * 0.01609344, energy.distanceKm, 0.000001)
        assertEquals(0.0, energy.energyKwh, 0.000001)
        assertEquals(30_000, energy.coveredMillis)
        assertNull(energy.efficiency)
    }

    /** 단순 평균 대신 총거리/총전력량을 써서 짧은 주행이 과대 반영되지 않아야 한다. */
    @Test fun `period totals are energy weighted and independent from paging`() {
        val sessions = (0..59).map { trip("trip-$it", today) }
        val energy = sessions.associate { it.id to HistoryEnergy(10.0, 2.0, 60_000) } +
            (sessions.last().id to HistoryEnergy(100.0, 10.0, 60_000))
        val insights = historyInsights(sessions, energy, 30, today, zone)
        assertEquals(690.0 / 128.0, insights.energy.efficiency!!, 0.000001)
        assertEquals(60 * 100 * 0.01609344, insights.distanceKm!!, 0.000001)
        assertEquals(60 * 60_000L, insights.durationMillis)
        assertEquals(insights.energy.efficiency!!, insights.trend.last().efficiency!!, 0.000001)
    }

    /** 자정·이전 기간·미래 기록·충전 기록이 서로 섞이지 않아야 한다. */
    @Test fun `calendar period boundaries and empty days remain distinct`() {
        val sessions = listOf(trip("first", today.minusDays(29)), trip("last", today),
            trip("previous", today.minusDays(30)), trip("old", today.minusDays(60)),
            trip("future", today.plusDays(1)), trip("charge", today).copy(kind = HistoryKind.CHARGE))
        val energy = sessions.associate { it.id to HistoryEnergy(10.0, 2.0, 60_000) } +
            ("previous" to HistoryEnergy(10.0, 4.0, 60_000))
        val insights = historyInsights(sessions, energy, 30, today, zone)
        assertEquals(20.0, insights.energy.distanceKm, 0.0)
        assertEquals(10.0, insights.previousEnergy.distanceKm, 0.0)
        assertEquals(100.0, insights.changePercent!!, 0.000001)
        assertEquals(30, insights.trend.size)
        assertEquals(2, insights.trend.count { it.efficiency != null })
        assertNull(insights.trend[1].efficiency)
    }

    /** 짧은 표본·순회생·빈 기록을 정상 전비나 비교율로 꾸미지 않는다. */
    @Test fun `insufficient observations do not produce efficiency`() {
        assertNull(HistoryEnergy(0.9, 0.1, 60_000).efficiency)
        assertNull(HistoryEnergy(10.0, 1.0, 59_999).efficiency)
        assertNull(HistoryEnergy(10.0, -1.0, 60_000).efficiency)
        assertNull(historyEfficiencyChange(6.0, null))
        assertNull(historyEfficiencyChange(6.0, 0.0))
        val insights = historyInsights(emptyList(), emptyMap(), 7, today, zone)
        assertNull(insights.distanceKm)
        assertNull(insights.energy.efficiency)
        assertTrue(insights.trend.all { it.efficiency == null })
    }

    /** 회생 전력의 음수 부호를 유지한 채 정상 인접 표본을 만든다. */
    private fun point(time: Long, odometer: Int, power: Int?) = HistorySample(time, odometer = odometer, powerKw = power)

    /** 기기 시간대의 시작일을 고정해 기간 테스트를 실행 날짜와 분리한다. */
    private fun trip(id: String, date: LocalDate): HistorySession {
        val time = date.atStartOfDay(zone).toInstant().toEpochMilli()
        return HistorySession(id, HistoryKind.DRIVE, time, time + 60_000, firstOdometer = 100, lastOdometer = 200)
    }
}
