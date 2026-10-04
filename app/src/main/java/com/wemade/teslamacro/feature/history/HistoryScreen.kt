package com.wemade.teslamacro.feature.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.wemade.teslamacro.data.history.HistoryKind
import com.wemade.teslamacro.data.history.HistoryOverview
import com.wemade.teslamacro.data.history.HistorySample
import com.wemade.teslamacro.data.history.HistorySession
import com.wemade.teslamacro.data.history.VehicleHistory
import com.wemade.teslamacro.data.settings.DeviceMode
import com.wemade.teslamacro.ui.component.ButtonTone
import com.wemade.teslamacro.ui.component.DraftMark
import com.wemade.teslamacro.ui.component.EmptyState
import com.wemade.teslamacro.ui.component.HelpTitle
import com.wemade.teslamacro.ui.component.SectionTabs
import com.wemade.teslamacro.ui.component.SettingActionRow
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.ui.component.TCard
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.Stroke
import com.wemade.teslamacro.ui.theme.T
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HistoryDetail(
    val session: HistorySession? = null,
    val samples: List<HistorySample> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

data class HistoryUiState(
    val enabled: Boolean = false,
    val ready: Boolean = false,
    val mode: DeviceMode = DeviceMode.PORTABLE,
    val overview: HistoryOverview = HistoryOverview(),
    val detail: HistoryDetail = HistoryDetail(),
)

/** 목록·상세 모두 같은 저장소 상태를 사용해 화면에서 별도 집계를 만들지 않는다. */
@Composable
fun HistoryRoute(viewModel: HistoryViewModel) {
    val state by viewModel.state.collectAsState()
    HistoryScreen(state, viewModel::setEnabled, viewModel::select, viewModel::loadMore, viewModel::setPeriod)
}

/** 주행 중 조작을 요구하지 않고 정차 후 기록과 수집 상태를 확인한다. */
@Composable
internal fun HistoryScreen(state: HistoryUiState, onEnabled: (Boolean) -> Unit,
    onSelect: (HistorySession?) -> Unit, onMore: () -> Unit, onPeriod: (Int) -> Unit = {}) {
    var tab by rememberSaveable { mutableStateOf(HistoryKind.DRIVE) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    BackHandler(state.detail.session != null) { onSelect(null) }
    LazyColumn(contentPadding = PaddingValues(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.sm + Space.xs)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                HelpTitle("주행 기록", "이 기기에 저장된 이동 경로와 배터리·충전 기록이에요.\n앱 삭제 시 기록도 삭제되며 설정 백업에는 포함되지 않아요.\n오른쪽 스위치로 기록을 켜고 꺼요. 앱에서 연결한 뒤 주행이 확인되면 화면 밖에서도 기록해요.",
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                if (state.ready && state.detail.session == null) com.wemade.teslamacro.ui.component.DraftToggle(
                    state.enabled, onEnabled, modifier = Modifier.semantics { contentDescription = "주행·충전 기록" })
            }
        }
        if (state.detail.session != null) {
            item { TButton("기록 목록", ButtonTone.Ghost, icon = DraftMark.ArrowLeft,
                fillWidth = false, onClick = { onSelect(null) }) }
            item { HistorySessionSummary(state.detail.session, state.overview.insights.trips[state.detail.session.id]?.efficiency) }
            item {
                when {
                    state.detail.loading -> Text("기록을 읽고 있어요", color = T.InkMuted)
                    state.detail.error != null -> Text(state.detail.error, color = T.Danger)
                    state.detail.session.kind == HistoryKind.DRIVE -> TCard {
                        HelpTitle("이동 경로", "배경 지도는 인터넷을 사용하며 지도 제공자에 표시 영역이 전달돼요.\n연결이 끊긴 구간은 선으로 잇지 않아요.",
                            style = MaterialTheme.typography.titleMedium)
                        if (state.detail.samples.any { it.latitude != null && it.longitude != null }) {
                            if (state.detail.samples.count { it.latitude != null && it.longitude != null } == 1) {
                                Text("위치 1곳", style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
                            }
                            HistoryMap(state.detail.samples, Modifier.fillMaxWidth().height(Space.xxl * 7))
                        } else Text("위치 기록 없음", style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
                    }
                    else -> TCard {
                        Text("충전 전력 변화", style = MaterialTheme.typography.titleMedium, color = T.Ink)
                        HistoryChargeChart(state.detail.samples)
                    }
                }
            }
        } else {
            if (!state.ready) item { Text("차량 등록 후 기록을 켤 수 있어요", color = T.InkMuted) }
            state.overview.error?.let { error -> item { Text(error, style = MaterialTheme.typography.bodySmall, color = T.Danger) } }
            item { SectionTabs(listOf(HistoryKind.DRIVE, HistoryKind.CHARGE), tab,
                label = { if (it == HistoryKind.DRIVE) "주행일지" else "충전 기록" }, onSelect = { tab = it }) }
            val insights = state.overview.insights
            if (tab == HistoryKind.DRIVE) {
                item { HistoryOverviewCard(insights, onPeriod) }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(if (showAll) "주행일지" else "최근 주행", modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge, color = T.Ink)
                        TButton(if (showAll) "최근만 보기" else "전체 보기", ButtonTone.Ghost,
                            fillWidth = false, icon = DraftMark.ChevronRight, onClick = { showAll = !showAll })
                    }
                }
            }
            val sessions = state.overview.sessions.filter { it.kind == tab }
            if (sessions.isEmpty()) item { EmptyState("아직 ${tab.label} 기록이 없어요",
                when {
                    !state.ready -> "차량과 키를 등록하면 기록을 시작할 수 있어요."
                    !state.enabled -> "기록을 켜면 차량 연결 중 자동으로 모아요."
                    else -> "차량 연결 중 ${tab.label}하면 여기에 표시돼요."
                }) }
            val visible = if (tab == HistoryKind.DRIVE && !showAll) sessions.take(3) else sessions
            items(visible, key = { it.id }) { session ->
                if (tab == HistoryKind.DRIVE) HistoryTripCard(session, insights, onClick = { onSelect(session) })
                else TCard(onClick = { onSelect(session) }) {
                    Text(historyTime(session.start), style = MaterialTheme.typography.titleMedium, color = T.Ink)
                    Text("관측 충전량 ${historyNumber(session.chargedKwh)} kWh", style = MaterialTheme.typography.bodyMedium, color = T.Ink)
                    Text("배터리 ${session.firstBattery ?: "--"}% → ${session.lastBattery ?: "--"}%", style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
                }
            }
            if (state.overview.hasMore && (showAll || tab == HistoryKind.CHARGE || sessions.size < 3))
                item { TButton("이전 기록 더 보기", ButtonTone.Ghost, onClick = onMore) }
        }
    }
}

/** 거리·시간을 먼저 읽고 세부 수치는 정렬하며 측정 한계는 도움말에서만 보여준다. */
@Composable
private fun HistorySessionSummary(session: HistorySession, efficiency: Double? = null) {
    val drive = session.kind == HistoryKind.DRIVE
    val help = buildString {
        append("${session.kind.label} 표본 ${session.samples}개")
        if (drive) {
            append("\n효율은 주행거리 ÷ 배터리 감소율이에요. 정수 %를 사용해 짧은 주행은 오차가 커요.")
            append("\n추정 전력량은 수신한 주행 전력을 적산한 값이며 음수는 회생을 뜻해요.")
            append("\n전력 관측 시간 ${historyDuration(session.powerCoveredMillis)}")
        } else append("\n충전량은 차량이 보고한 추가량의 차이로, 충전기 청구량과 다를 수 있어요.")
        if (session.hasGaps) append("\n수신 공백이 있어 해당 경로와 전력은 추정하지 않았어요.")
        append(if (session.complete) "\n상태 전환까지 관측한 구간이에요." else "\n기록 중이거나 시작·종료를 확인하지 못한 구간이에요.")
    }
    TCard {
        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            HelpTitle(historyTime(session.start), help, style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                HistoryMetric(if (drive) "주행거리" else "충전량",
                    if (drive) "${historyNumber(session.distanceKm)} km" else "${historyNumber(session.chargedKwh)} kWh",
                    Modifier.weight(1f))
                HistoryMetric(if (drive) "주행시간" else "충전시간",
                    historyDuration(session.end - session.start), Modifier.weight(1f))
            }
            HorizontalDivider(color = T.Hairline, thickness = Stroke.thin)
            Column {
                SettingActionRow("배터리") {
                    Text("${session.firstBattery?.let { "$it%" } ?: "--"} → ${session.lastBattery?.let { "$it%" } ?: "--"}",
                        style = MaterialTheme.typography.titleMedium, color = T.Ink, textAlign = TextAlign.End)
                }
                if (drive) {
                    efficiency?.let { value ->
                        SettingActionRow("평균전비") {
                            Text("${historyNumber(value)} km/kWh", color = T.Ink, textAlign = TextAlign.End)
                        }
                    }
                    session.kilometersPerPercent?.let { efficiency ->
                        SettingActionRow("배터리 효율") {
                            Text("${historyNumber(efficiency)} km/%", color = T.Ink, textAlign = TextAlign.End)
                        }
                    }
                    if (session.powerCoveredMillis > 0) {
                        SettingActionRow("추정 전력량") {
                            Text("${historyNumber(session.estimatedDriveKwh)} kWh", color = T.Ink, textAlign = TextAlign.End)
                        }
                    }
                }
            }
        }
    }
}

/** 핵심 수치를 같은 폭에 놓고 큰 글씨에서는 값의 줄바꿈을 허용한다. */
@Composable
private fun HistoryMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
        Text(value, style = MaterialTheme.typography.headlineMedium, color = T.Ink)
    }
}

/** 누락 구간을 건너뛰어 충전이 계속됐다고 오해하는 평탄선을 만들지 않는다. */
@Composable
private fun HistoryChargeChart(samples: List<HistorySample>) {
    val points = samples.filter { it.chargerPowerKw != null }
    if (points.size < 2) { Text("충전 전력 기록 부족", color = T.InkMuted); return }
    val observedMaximum = points.maxOf { it.chargerPowerKw!! }
    val maximum = observedMaximum.coerceAtLeast(1)
    val minimumTime = points.first().time
    val duration = (points.last().time - minimumTime).coerceAtLeast(1)
    val color = T.Electric
    val width = Stroke.thin
    Text("최대 관측 ${observedMaximum} kW", color = T.InkMuted)
    Canvas(Modifier.fillMaxWidth().height(Space.xxl * 3).semantics { contentDescription = "충전 전력 변화, 최대 $observedMaximum kW" }) {
        points.zipWithNext().forEach { (first, second) ->
            if (VehicleHistory.contiguous(first, second)) drawLine(color,
                Offset((first.time - minimumTime).toFloat() / duration * size.width, size.height * (1f - first.chargerPowerKw!!.toFloat() / maximum)),
                Offset((second.time - minimumTime).toFloat() / duration * size.width, size.height * (1f - second.chargerPowerKw!!.toFloat() / maximum)),
                strokeWidth = width.toPx() * 2)
        }
    }
}

/** 표시만 반올림하고 저장된 원본은 보존한다. */
internal fun historyNumber(value: Double?): String = value?.takeIf { it.isFinite() }
    ?.let {
        val rounded = String.format(Locale.KOREA, "%.1f", it)
        if (rounded == "-0.0") "0.0" else rounded
    } ?: "--"

/** 짧은 주행은 초, 긴 주행은 분·시간으로 보여 소수 분을 해석할 필요를 없앤다. */
internal fun historyDuration(millis: Long): String {
    val seconds = millis.coerceAtLeast(0L) / 1_000
    return when {
        seconds < 60 -> "${seconds}초"
        seconds < 3_600 -> "${seconds / 60}분" + if (seconds % 60 > 0) " ${seconds % 60}초" else ""
        else -> "${seconds / 3_600}시간" + if (seconds % 3_600 / 60 > 0) " ${seconds % 3_600 / 60}분" else ""
    }
}

/** 기록 시각은 기기 시간대에 맞춰 보여준다. */
internal fun historyTime(value: Long): String = SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.KOREA).format(Date(value))
