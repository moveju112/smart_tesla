package com.wemade.teslamacro.feature.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.wemade.teslamacro.ui.component.NumberSettingRow
import com.wemade.teslamacro.ui.component.SettingToggleRow
import com.wemade.teslamacro.ui.component.SettingRow
import com.wemade.teslamacro.ui.component.PickerSheet
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
    val batteryCapacityKwh: Double = 0.0,
    val overview: HistoryOverview = HistoryOverview(),
    val detail: HistoryDetail = HistoryDetail(),
)

/** 목록·상세 모두 같은 저장소 상태를 사용해 화면에서 별도 집계를 만들지 않는다. */
@Composable
fun HistoryRoute(viewModel: HistoryViewModel) {
    val state by viewModel.state.collectAsState()
    HistoryScreen(state, viewModel::setEnabled, viewModel::setCapacity, viewModel::select, viewModel::loadMore)
}

/** 주행 중 조작을 요구하지 않고 정차 후 기록과 수집 상태를 확인한다. */
@Composable
internal fun HistoryScreen(state: HistoryUiState, onEnabled: (Boolean) -> Unit, onCapacity: (Double) -> Unit,
    onSelect: (HistorySession?) -> Unit, onMore: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(HistoryKind.DRIVE) }
    var showOptions by rememberSaveable { mutableStateOf(false) }
    BackHandler(state.detail.session != null) { onSelect(null) }
    LazyColumn(contentPadding = PaddingValues(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.sm + Space.xs)) {
        item {
            HelpTitle("주행 기록", "이 기기에 저장된 이동 경로와 배터리·충전 기록을 확인해요.\n수신하지 못한 값은 --로 표시해요.",
                style = MaterialTheme.typography.headlineSmall)
        }
        if (state.detail.session != null) {
            item { TButton("기록 목록", ButtonTone.Ghost, icon = DraftMark.ArrowLeft,
                fillWidth = false, onClick = { onSelect(null) }) }
            item { HistorySessionSummary(state.detail.session, state.batteryCapacityKwh) }
            item {
                when {
                    state.detail.loading -> Text("기록을 읽고 있어요", color = T.InkMuted)
                    state.detail.error != null -> Text(state.detail.error, color = T.Danger)
                    state.detail.session.kind == HistoryKind.DRIVE -> TCard {
                        HelpTitle("이동 경로", "배경 지도는 인터넷을 사용하며 지도 제공자에 표시 영역이 전달돼요.\n연결이 끊긴 구간은 선으로 잇지 않아요.",
                            style = MaterialTheme.typography.titleMedium)
                        if (state.detail.samples.any { it.latitude != null && it.longitude != null }) {
                            HistoryMap(state.detail.samples, Modifier.fillMaxWidth().height(Space.xxl * 7))
                        } else Text("차량 위치를 수신하지 못한 주행이에요", style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
                    }
                    else -> TCard {
                        Text("충전 전력 변화", style = MaterialTheme.typography.titleMedium, color = T.Ink)
                        HistoryChargeChart(state.detail.samples)
                        Text("차량이 보고한 충전 전력 · 연결 중 관측한 구간", style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
                    }
                }
            }
        } else {
            item { TCard {
                if (state.ready) SettingToggleRow("기록", state.enabled, onEnabled,
                    description = if (state.mode == DeviceMode.PORTABLE)
                        "앱 화면·직접 명령으로 차량에 연결된 동안 5초마다 주행과 충전을 기록해요."
                    else "차량에 연결된 동안 5초마다 주행과 충전을 기록해요.")
                else Text("차량 등록 후 기록을 켤 수 있어요", style = MaterialTheme.typography.bodyMedium, color = T.InkMuted)
            } }
            item { TCard { SettingRow("설정", onClick = { showOptions = true }) } }
            state.overview.latest?.let { latest -> item {
                HelpTitle("마지막 배터리 ${latest.batteryPercent?.let { "$it%" } ?: "--"}",
                    "${historyTime(latest.time)}에 확인한 값이에요. 현재 차량 상태와 다를 수 있어요.")
            } }
            state.overview.error?.let { error -> item { Text(error, style = MaterialTheme.typography.bodySmall, color = T.Danger) } }
            item { SectionTabs(listOf(HistoryKind.DRIVE, HistoryKind.CHARGE), tab,
                label = { if (it == HistoryKind.DRIVE) "주행일지" else "충전 기록" }, onSelect = { tab = it }) }
            val sessions = state.overview.sessions.filter { it.kind == tab }
            if (sessions.isEmpty()) item { EmptyState("아직 ${tab.label} 기록이 없어요",
                when {
                    !state.ready -> "차량과 키를 등록하면 기록을 시작할 수 있어요."
                    !state.enabled -> "기록을 켜면 차량 연결 중 자동으로 모아요."
                    else -> "차량 연결 중 ${tab.label}하면 여기에 표시돼요."
                }) }
            items(sessions, key = { it.id }) { session ->
                TCard(onClick = { onSelect(session) }) {
                    Text(historyTime(session.start), style = MaterialTheme.typography.titleMedium, color = T.Ink)
                    Text(if (tab == HistoryKind.DRIVE) "${historyNumber(session.distanceKm)} km · ${historyNumber((session.end - session.start) / 60_000.0)}분"
                        else "관측 충전량 ${historyNumber(session.chargedKwh)} kWh", style = MaterialTheme.typography.bodyMedium, color = T.Ink)
                    Text("배터리 ${session.firstBattery ?: "--"}% → ${session.lastBattery ?: "--"}%", style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
                }
            }
            if (state.overview.hasMore) item { TButton("이전 기록 더 보기", ButtonTone.Ghost, onClick = onMore) }
        }
    }
    if (showOptions) PickerSheet("기록 설정", onDismiss = { showOptions = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            NumberSettingRow("사용 가능 배터리 용량", state.batteryCapacityKwh, 0.0, 200.0, 0.5, "kWh", onCapacity)
            Text("${state.overview.sampleCount}개 표본 · 저장공간 ${historyNumber(state.overview.storageBytes / 1_048_576.0)} MB",
                style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
            Text("0은 미설정이에요. 입력하면 배터리 감소율로 소모량·전비를 추정해요. 차량 표시값과 다를 수 있어요.", color = T.InkMuted)
            Text("기존 BLE 연결 중만 기록해요. 기록 때문에 빈 차를 깨우거나 연결을 유지하지 않아요.", color = T.InkMuted)
            Text("응답 지연·연결 해제 구간은 누락될 수 있어요.", color = T.InkMuted)
            Text("기록은 무손실 압축하며 자동 삭제하지 않아요. 지도 캐시는 표시 용량과 별도예요.", color = T.InkMuted)
            Text("이 기기에만 보관돼요. 기존 설정 백업에 포함되지 않으며 앱 삭제 시 사라져요.", color = T.InkMuted)
        }
    }
}

/** SOC 기반 계산과 전력 적산은 근거가 달라 서로 대체하지 않고 명시한다. */
@Composable
private fun HistorySessionSummary(session: HistorySession, capacity: Double) {
    TCard {
        Text(historyTime(session.start), style = MaterialTheme.typography.titleMedium, color = T.Ink)
        Text("${session.kind.label} · ${historyNumber((session.end - session.start) / 60_000.0)}분 · ${session.samples}개 표본", color = T.InkMuted)
        Text("배터리 ${session.firstBattery ?: "--"}% → ${session.lastBattery ?: "--"}%", color = T.Ink)
        if (session.kind == HistoryKind.DRIVE) {
            Text("주행거리 ${historyNumber(session.distanceKm)} km", color = T.Ink)
            Text("배터리 기준 효율 ${historyNumber(session.kilometersPerPercent)} km/%", color = T.Ink)
            val energy = session.batteryUsedPercent?.takeIf { it > 0 && capacity > 0 }?.let { capacity * it / 100 }
            Text("배터리 소모량 추정 ${historyNumber(energy)} kWh", color = T.Ink)
            Text("전비 추정 ${historyNumber(energy?.let { session.distanceKm?.div(it) })} km/kWh", color = T.Ink)
            if (session.powerCoveredMillis > 0) {
                Text("전력 적산 추정 ${historyNumber(session.estimatedDriveKwh)} kWh · 관측 ${historyNumber(session.powerCoveredMillis / 60_000.0)}분", color = T.InkMuted)
            }
            Text("배터리 잔량은 정수 %라 짧은 주행의 전비 오차가 커요.", color = T.InkMuted)
        } else {
            Text("관측 충전량 ${historyNumber(session.chargedKwh)} kWh", style = MaterialTheme.typography.titleLarge, color = T.Ink)
            Text("차량의 충전 추가량 차이예요. 충전기 청구 전력량과 다를 수 있어요.", color = T.InkMuted)
        }
        Text(if (session.complete) "상태 전환까지 관측한 구간" else "기록 중이거나 시작·종료를 확인하지 못한 구간", color = T.InkMuted)
    }
}

/** 누락 구간을 건너뛰어 충전이 계속됐다고 오해하는 평탄선을 만들지 않는다. */
@Composable
private fun HistoryChargeChart(samples: List<HistorySample>) {
    val points = samples.filter { it.chargerPowerKw != null }
    if (points.size < 2) { Text("그래프를 그릴 충전 전력이 부족해요", color = T.InkMuted); return }
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
    ?.let { String.format(Locale.KOREA, "%.1f", it) } ?: "--"

/** 기록 시각은 기기 시간대에 맞춰 보여준다. */
internal fun historyTime(value: Long): String = SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.KOREA).format(Date(value))
