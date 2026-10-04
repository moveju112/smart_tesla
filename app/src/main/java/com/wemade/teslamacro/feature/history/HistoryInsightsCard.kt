package com.wemade.teslamacro.feature.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.wemade.teslamacro.data.history.HistoryInsights
import com.wemade.teslamacro.data.history.HistorySample
import com.wemade.teslamacro.data.history.HistorySession
import com.wemade.teslamacro.data.history.VehicleHistory
import com.wemade.teslamacro.data.history.historyEfficiencyChange
import com.wemade.teslamacro.ui.component.ButtonTone
import com.wemade.teslamacro.ui.component.DraftMark
import com.wemade.teslamacro.ui.component.HelpTitle
import com.wemade.teslamacro.ui.component.PickerRow
import com.wemade.teslamacro.ui.component.PickerSheet
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.ui.component.TCard
import com.wemade.teslamacro.ui.theme.Radius
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.Stroke
import com.wemade.teslamacro.ui.theme.T
import java.time.format.DateTimeFormatter
import kotlin.math.cos

/** 선택 기간의 큰 전비와 거리·시간을 한 카드에 모으고 계산 설명은 도움말로 분리한다. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun HistoryOverviewCard(insights: HistoryInsights, onPeriod: (Int) -> Unit) {
    var choosingPeriod by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("내 주행 요약", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = T.Ink)
            TButton("최근 ${insights.days}일", ButtonTone.Ghost, fillWidth = false,
                icon = DraftMark.ArrowDown, onClick = { choosingPeriod = true })
        }
        TCard {
            Column(Modifier.padding(vertical = Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                HelpTitle("평균전비", "거리와 전력이 함께 수신된 15초 이내 구간의 총거리 ÷ 총소비전력량이에요. 회생 전력도 포함한 추정값이에요.\n관측 거리 1 km·시간 1분 이상이고 순소비전력이 양수일 때 표시해요.\n주행 시작일을 기준으로 오늘을 포함한 최근 ${insights.days}일과 직전 ${insights.days}일을 비교해요. 그래프는 날짜별 평균이며 기록이 없는 날은 잇지 않아요.\n전비 계산에 사용한 거리 ${historyNumber(insights.energy.distanceKm)} km · ${historyDuration(insights.energy.coveredMillis)}\n누적 주행거리는 거리계 값이 있는 기록을, 시간은 기록된 주행 구간을 합산해요.",
                    style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(historyNumber(insights.energy.efficiency), color = T.Electric,
                        style = MaterialTheme.typography.displayMedium, modifier = Modifier.alignByBaseline())
                    Text("km/kWh", style = MaterialTheme.typography.titleMedium, color = T.Ink,
                        modifier = Modifier.alignByBaseline())
                }
                insights.changePercent?.let { change ->
                    Text("지난 ${insights.days}일보다 ${historyNumber(kotlin.math.abs(change))}% ${if (change >= 0) "↑" else "↓"}",
                        style = MaterialTheme.typography.bodySmall, color = T.Electric)
                }
                insights.error?.let { Text(it, color = T.Danger, style = MaterialTheme.typography.bodySmall) }
                HistoryEfficiencyChart(insights)
                HorizontalDivider(color = T.Hairline, thickness = Stroke.thin)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Text("주행거리", color = T.InkMuted, style = MaterialTheme.typography.bodySmall)
                        Text("${historyNumber(insights.distanceKm)} km", color = T.Ink, style = MaterialTheme.typography.headlineMedium)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Text("주행시간", color = T.InkMuted, style = MaterialTheme.typography.bodySmall)
                        Text(historyDuration(insights.durationMillis), color = T.Ink, style = MaterialTheme.typography.headlineMedium)
                    }
                }
            }
        }
    }
    if (choosingPeriod) PickerSheet("조회 기간", onDismiss = { choosingPeriod = false }) {
        listOf(7, 30, 90).forEach { days ->
            PickerRow("최근 ${days}일", value = if (days == insights.days) "선택됨" else null,
                onClick = { onPeriod(days); choosingPeriod = false })
        }
    }
}

/** 날짜별 전비를 실제 값으로 그리며 값이 없는 날짜를 임의의 추세로 연결하지 않는다. */
@Composable
private fun HistoryEfficiencyChart(insights: HistoryInsights) {
    val values = insights.trend.mapNotNull { it.efficiency }
    if (values.isEmpty()) return
    val minimum = values.min()
    val span = (values.max() - minimum).coerceAtLeast(1.0)
    val color = T.Electric
    val lineWidth = Stroke.bold
    val inset = Space.xs
    Column {
        Canvas(Modifier.fillMaxWidth().height(Space.xxl + Space.md).semantics {
            contentDescription = "날짜별 평균전비, 최저 ${historyNumber(values.min())}, 최고 ${historyNumber(values.max())} km/kWh"
        }) {
            val points = insights.trend.mapIndexed { index, day -> day.efficiency?.let { value ->
                Offset(inset.toPx() + index.toFloat() / (insights.trend.size - 1).coerceAtLeast(1) * (size.width - inset.toPx() * 2),
                    size.height - inset.toPx() - ((value - minimum) / span).toFloat() * (size.height - inset.toPx() * 2))
            } }
            points.zipWithNext().forEach { (first, second) ->
                if (first != null && second != null) drawLine(color, first, second, lineWidth.toPx(), StrokeCap.Round)
            }
            points.filterNotNull().forEach { drawCircle(color, lineWidth.toPx(), it) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(insights.startDate.format(DateTimeFormatter.ofPattern("M.d")), style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
            Text(insights.endDate.format(DateTimeFormatter.ofPattern("M.d")), style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
        }
    }
}

/** 최근 주행도 요약과 같은 전비 계산을 사용하고 가장 최근 기록에만 경로 미리보기를 둔다. */
@Composable
internal fun HistoryTripCard(session: HistorySession, insights: HistoryInsights, onClick: () -> Unit) {
    val efficiency = insights.trips[session.id]?.efficiency
    val change = historyEfficiencyChange(efficiency, insights.energy.efficiency)
    TCard(onClick = onClick) {
        Column(Modifier.padding(vertical = Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(java.text.SimpleDateFormat("M월 d일 · a h:mm", java.util.Locale.KOREA).format(java.util.Date(session.start)), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = T.Ink)
                Icon(DraftMark.ChevronRight, null, tint = T.InkMuted)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                Text("${historyNumber(session.distanceKm)} km", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, color = T.Ink)
                Text(historyDuration(session.end - session.start), Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium,
                    color = T.Ink, textAlign = TextAlign.End)
            }
            HorizontalDivider(color = T.Hairline, thickness = Stroke.thin)
            efficiency?.let { HistoryValueRow("평균전비", "${historyNumber(it)} km/kWh") }
            change?.let { HistoryValueRow("내 평균 대비", "${if (it > 0) "+" else ""}${historyNumber(it)}%", badge = true) }
            HistoryValueRow("배터리", "${session.firstBattery?.let { "$it%" } ?: "--"} → ${session.lastBattery?.let { "$it%" } ?: "--"}")
            if (insights.previewSessionId == session.id) HistoryRoutePreview(insights.previewSamples)
        }
    }
}

/** 보조값은 왼쪽 이름·오른쪽 값으로 맞추고 증감은 부호와 색을 함께 표시한다. */
@Composable
private fun HistoryValueRow(label: String, value: String, badge: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
        Text(value, modifier = Modifier.weight(1f).then(if (badge)
            Modifier.background(T.ElectricFaint, RoundedCornerShape(Radius.button)).padding(horizontal = Space.sm, vertical = Space.xs)
            else Modifier), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium,
            color = if (badge) T.Electric else T.Ink)
    }
}

/** 목록은 저장된 경로 윤곽만 가볍게 그리고 상세에서 배경 지도를 연다. */
@Composable
private fun HistoryRoutePreview(samples: List<HistorySample>) {
    val points = remember(samples) { samples.filter { it.latitude != null && it.longitude != null } }
    if (points.size < 2) return
    val latitudeScale = cos(Math.toRadians(points.first().latitude!!)).coerceAtLeast(0.01)
    val minLatitude = points.minOf { it.latitude!! }
    val maxLatitude = points.maxOf { it.latitude!! }
    val minLongitude = points.minOf { it.longitude!! }
    val maxLongitude = points.maxOf { it.longitude!! }
    val color = T.Electric
    val stroke = Stroke.bold
    val padding = Space.md
    Canvas(Modifier.fillMaxWidth().height(Space.xxl * 2).clip(RoundedCornerShape(Radius.button))
        .background(T.ElectricFaint).semantics { contentDescription = "저장된 이동 경로 미리보기, 기록을 누르면 지도 상세" }) {
        val width = ((maxLongitude - minLongitude) * latitudeScale).coerceAtLeast(0.00001)
        val height = (maxLatitude - minLatitude).coerceAtLeast(0.00001)
        val scale = minOf((size.width - padding.toPx() * 2) / width, (size.height - padding.toPx() * 2) / height)
        val offsets = points.map { point -> Offset(
            ((point.longitude!! - minLongitude) * latitudeScale * scale + (size.width - width * scale) / 2).toFloat(),
            ((maxLatitude - point.latitude!!) * scale + (size.height - height * scale) / 2).toFloat()) }
        points.indices.drop(1).forEach { index ->
            if (VehicleHistory.contiguous(points[index - 1], points[index]))
                drawLine(color, offsets[index - 1], offsets[index], stroke.toPx() * 2, StrokeCap.Round)
        }
        drawCircle(color, stroke.toPx() * 3, offsets.first())
        drawCircle(color, stroke.toPx() * 3, offsets.last())
    }
}
