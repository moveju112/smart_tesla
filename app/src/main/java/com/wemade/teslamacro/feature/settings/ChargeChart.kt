package com.wemade.teslamacro.feature.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.wemade.teslamacro.data.charge.ChargeBucket
import com.wemade.teslamacro.data.charge.ChargeHistory
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToInt

/** 한 칸이 그려질 위치와 높이. 그리기와 계산을 갈라 놔야 값 검증을 눈이 아니라 테스트로 한다. */
internal data class ChargeBarPlan(
    val slot: Int,
    val heightRatio: Double,
    val faded: Boolean,
)

/**
 * 24시간을 15분 96칸으로 펴고, 기록이 있는 칸만 막대 계획으로 바꾼다.
 *
 * 기록이 없는 칸은 아예 빼서 바닥선만 보이게 한다 — 0A와 "모르는 시간"은 다르다.
 * 관측이 절반도 안 되는 칸은 [ChargeBarPlan.faded]로 표시해 흐리게 그린다.
 */
internal fun planChargeBars(
    buckets: List<ChargeBucket>,
    nowMillis: Long,
    maxAmps: Double,
): List<ChargeBarPlan> {
    if (maxAmps <= 0.0) return emptyList()
    val firstSlotStart = ChargeHistory.bucketStart(nowMillis) - (SLOT_COUNT - 1) * ChargeHistory.BUCKET_MILLIS
    return buckets.mapNotNull { bucket ->
        val slot = ((bucket.startMillis - firstSlotStart) / ChargeHistory.BUCKET_MILLIS).toInt()
        if (slot !in 0 until SLOT_COUNT || bucket.coveredMillis <= 0) return@mapNotNull null
        ChargeBarPlan(
            slot = slot,
            heightRatio = (bucket.averageAmps / maxAmps).coerceIn(0.0, 1.0),
            faded = bucket.coverage < 0.5,
        )
    }
}

/**
 * 그래프 아래에 붙는 한 줄 요약.
 *
 * 전류만 보면 같은 10A도 220V냐 110V냐에 따라 들어간 전력이 두 배 차이 난다.
 * 그래서 전류와 전력을 함께 적고, 실제로 들어간 양(kWh)도 같이 낸다.
 */
internal fun chargeSummary(buckets: List<ChargeBucket>): String? {
    val charging = buckets.filter { it.coveredMillis > 0 && it.ampsMillis > 0 }
    if (charging.isEmpty()) return null

    val coveredMillis = charging.sumOf { it.coveredMillis }
    val averageAmps = charging.sumOf { it.ampsMillis }.toDouble() / coveredMillis
    val averageKilowatts = charging.sumOf { it.wattMillis }.toDouble() / coveredMillis / 1_000
    val energyKilowattHours = charging.sumOf { it.energyWattHours } / 1_000
    val hours = coveredMillis.toDouble() / 3_600_000

    val power = if (averageKilowatts > 0) " · 평균 %.1fkW".format(averageKilowatts) else ""
    val energy = if (energyKilowattHours > 0) " · 합계 %.1fkWh".format(energyKilowattHours) else ""
    return "충전 %.1f시간 · 평균 %.1fA".format(hours, averageAmps) + power + energy
}

/** 세로 눈금 꼭대기 값. 관측 최댓값을 1A 단위로 올림하되 최소 5A는 잡는다. */
internal fun chartTopAmps(buckets: List<ChargeBucket>): Double {
    val observed = buckets.maxOfOrNull { it.averageAmps } ?: 0.0
    return maxOf(5.0, kotlin.math.ceil(observed))
}

/** 최근 24시간에 실제 충전이 있을 때만 표시한다. 0A 관측만 있으면 빈 차트도 숨긴다. */
internal fun recentChargeBuckets(buckets: List<ChargeBucket>, nowMillis: Long): List<ChargeBucket> =
    ChargeHistory.prune(buckets, nowMillis)
        .filter { it.startMillis <= nowMillis && it.coveredMillis > 0 }
        .takeIf { recent -> recent.any { it.ampsMillis > 0 } }.orEmpty()

internal const val SLOT_COUNT = 96

/**
 * 15분 단위 충전 전류 그래프.
 *
 * 값은 표본 수가 아니라 시간으로 가중한 평균이라, 10분 10A + 5분 5A는 7.5A가 아니라 8.3A로 선다.
 */
@Composable
internal fun ChargeChart(
    buckets: List<ChargeBucket>,
    nowMillis: Long,
    modifier: Modifier = Modifier,
) {
    val recentBuckets = recentChargeBuckets(buckets, nowMillis)
    if (recentBuckets.isEmpty()) return
    val top = chartTopAmps(recentBuckets)
    val bars = planChargeBars(recentBuckets, nowMillis, top)
    if (bars.none { it.heightRatio > 0.0 }) return
    val barColor = T.Electric
    val fadedColor = T.ElectricFaint
    val lineColor = T.Hairline

    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                text = "충전 전류 · 최근 24시간",
                style = MaterialTheme.typography.labelSmall,
                color = T.InkFaint,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = topLabel(recentBuckets, top),
                style = MaterialTheme.typography.labelSmall,
                color = T.InkFaint,
            )
        }
        Spacer(Modifier.height(Space.sm))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(CHART_HEIGHT),
        ) {
            val slotWidth = size.width / SLOT_COUNT
            // 칸 사이를 띄우면 96칸에서는 막대가 1px 밑으로 내려간다. 붙여 그리고 색으로만 구분한다
            val barWidth = slotWidth
            val baseline = size.height

            drawLine(
                color = lineColor,
                start = Offset(0f, baseline),
                end = Offset(size.width, baseline),
                strokeWidth = 1f,
            )
            bars.forEach { bar ->
                val barHeight = (baseline * bar.heightRatio).toFloat().coerceAtLeast(1f)
                drawRect(
                    color = if (bar.faded) fadedColor else barColor,
                    topLeft = Offset(bar.slot * slotWidth, baseline - barHeight),
                    size = Size(barWidth, barHeight),
                )
            }
        }
        Spacer(Modifier.height(Space.xs))
        Row(Modifier.fillMaxWidth()) {
            hourLabels(nowMillis).forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = T.InkFaint,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        val summary = chargeSummary(recentBuckets)
        if (summary != null) {
            Spacer(Modifier.height(Space.xs))
            Text(
                text = summary,
                style = MaterialTheme.typography.labelMedium,
                color = T.Ink,
            )
        }
        if (bars.isEmpty()) {
            Spacer(Modifier.height(Space.xs))
            Text(
                text = "아직 기록이 없어요. 충전을 시작하면 15분마다 한 칸씩 쌓여요.",
                style = MaterialTheme.typography.bodySmall,
                color = T.InkFaint,
            )
        }
    }
}

/** 눈금 꼭대기 글자. 전압을 읽은 차는 kW까지 함께 적는다. */
internal fun topLabel(buckets: List<ChargeBucket>, topAmps: Double): String {
    val peakKilowatts = buckets.maxOfOrNull { it.averageKilowatts } ?: 0.0
    val amps = "최대 ${topAmps.roundToInt()}A"
    return if (peakKilowatts > 0) amps + " · %.1fkW".format(peakKilowatts) else amps
}

/** 6시간 간격 눈금 글자. 왼쪽이 24시간 전, 오른쪽이 지금이다. */
internal fun hourLabels(nowMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): List<String> {
    val calendar = Calendar.getInstance(timeZone)
    return (0 until 4).map { index ->
        calendar.timeInMillis = nowMillis - (24L - index * 6) * 60 * 60_000
        "${calendar.get(Calendar.HOUR_OF_DAY)}시"
    }
}

private val CHART_HEIGHT = 72.dp
