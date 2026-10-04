package com.wemade.teslamacro.data.history

import java.time.LocalDate
import java.time.ZoneId

/** 거리와 전력을 같은 구간에서 얻은 합계만 전비의 분자·분모로 사용한다. */
data class HistoryEnergy(
    val distanceKm: Double = 0.0,
    val energyKwh: Double = 0.0,
    val coveredMillis: Long = 0,
) {
    val efficiency: Double? get() = if (distanceKm >= 1.0 && coveredMillis >= 60_000 && energyKwh > 0)
        (distanceKm / energyKwh).takeIf { it.isFinite() } else null

    /** 회생 구간도 부호 그대로 더해 소비량을 부풀리지 않는다. */
    operator fun plus(other: HistoryEnergy) = HistoryEnergy(distanceKm + other.distanceKm,
        energyKwh + other.energyKwh, coveredMillis + other.coveredMillis)
}

data class HistoryDay(val date: LocalDate, val efficiency: Double?)

data class HistoryInsights(
    val days: Int = 30,
    val startDate: LocalDate = LocalDate.now().minusDays(29),
    val endDate: LocalDate = LocalDate.now(),
    val distanceKm: Double? = null,
    val durationMillis: Long = 0,
    val energy: HistoryEnergy = HistoryEnergy(),
    val previousEnergy: HistoryEnergy = HistoryEnergy(),
    val trend: List<HistoryDay> = emptyList(),
    val trips: Map<String, HistoryEnergy> = emptyMap(),
    val previewSessionId: String? = null,
    val previewSamples: List<HistorySample> = emptyList(),
    val error: String? = null,
) {
    val changePercent: Double? get() = historyEfficiencyChange(energy.efficiency, previousEnergy.efficiency)
}

/** 이전 평균이 없거나 0이면 비교 배지를 만들지 않는다. */
fun historyEfficiencyChange(current: Double?, previous: Double?): Double? =
    if (current != null && previous != null && previous > 0)
        ((current / previous - 1) * 100).takeIf { it.isFinite() } else null

/** 누락·시간 역행·계기판 초기화 구간은 거리와 에너지를 함께 제외한다. */
fun historyEnergy(samples: List<HistorySample>): HistoryEnergy {
    var result = HistoryEnergy()
    samples.zipWithNext().forEach { (first, second) ->
        if (!VehicleHistory.contiguous(first, second)) return@forEach
        val firstDistance = first.odometer ?: return@forEach
        val secondDistance = second.odometer ?: return@forEach
        val firstPower = first.powerKw ?: return@forEach
        val secondPower = second.powerKw ?: return@forEach
        if (secondDistance < firstDistance) return@forEach
        val duration = second.time - first.time
        result += HistoryEnergy((secondDistance.toLong() - firstDistance) * 0.01609344,
            (firstPower.toDouble() + secondPower) / 2 * duration / 3_600_000, duration)
    }
    return result
}

/** 주행 시작일 기준으로 같은 길이의 두 기간을 집계하며 목록 페이지 크기에 의존하지 않는다. */
fun historyInsights(sessions: List<HistorySession>, trips: Map<String, HistoryEnergy>, days: Int,
    today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): HistoryInsights {
    require(days in setOf(7, 30, 90))
    val start = today.minusDays(days.toLong() - 1)
    val previousStart = start.minusDays(days.toLong())
    val dated = sessions.filter { it.kind == HistoryKind.DRIVE }
        .groupBy { java.time.Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() }
    val current = dated.filterKeys { it >= start && it <= today }.values.flatten()
    val previous = dated.filterKeys { it >= previousStart && it < start }.values.flatten()
    val distances = current.mapNotNull { it.distanceKm }
    return HistoryInsights(days, start, today,
        if (distances.isEmpty()) null else distances.sum(),
        current.sumOf { (it.end - it.start).coerceAtLeast(0) },
        current.fold(HistoryEnergy()) { total, session -> total + (trips[session.id] ?: HistoryEnergy()) },
        previous.fold(HistoryEnergy()) { total, session -> total + (trips[session.id] ?: HistoryEnergy()) },
        (0 until days).map { offset ->
            val date = start.plusDays(offset.toLong())
            HistoryDay(date, dated[date].orEmpty().fold(HistoryEnergy()) { total, session ->
                total + (trips[session.id] ?: HistoryEnergy())
            }.efficiency)
        }, trips)
}
