package com.wemade.teslamacro.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.domain.gateway.LinkState
import com.wemade.teslamacro.domain.model.Level
import com.wemade.teslamacro.domain.model.SeatPosition
import com.wemade.teslamacro.domain.model.SeatMode
import com.wemade.teslamacro.domain.model.SeatClimate
import com.wemade.teslamacro.ui.component.ButtonTone
import com.wemade.teslamacro.ui.component.IndeterminateBar
import com.wemade.teslamacro.ui.component.InlineBanner
import com.wemade.teslamacro.ui.component.LevelSelector
import com.wemade.teslamacro.ui.component.MetricBlock
import com.wemade.teslamacro.ui.component.SectionHeader
import com.wemade.teslamacro.ui.component.Hairline
import com.wemade.teslamacro.ui.component.SkeletonBlock
import com.wemade.teslamacro.ui.component.StatusPill
import com.wemade.teslamacro.ui.component.softShadow
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.ui.component.TCard
import com.wemade.teslamacro.ui.layout.LocalPane
import com.wemade.teslamacro.ui.theme.Radius
import com.wemade.teslamacro.ui.theme.MetricTextStyle
import com.wemade.teslamacro.ui.theme.Grad
import com.wemade.teslamacro.ui.theme.Elevation
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

/**
 * 제어 화면. "차에 타서 3초 안에 원하는 걸 누른다"가 목표다.
 * 위에서부터 읽는 값 -> 자주 쓰는 조작 -> 가끔 쓰는 조작 순으로 배치한다.
 */
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onCommand: (VehicleCommand) -> Unit,
    onRetryConnect: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
    onSeatClimate: (SeatPosition, SeatMode, Level) -> Unit = { _, _, _ -> },
) {
    val compact = LocalPane.current.isCompact

    Column(modifier = modifier.fillMaxSize()) {

        // 명령이 오가는 동안 화면 맨 위에 얇은 선이 흐른다. 누른 게 먹었는지 즉시 안다
        IndeterminateBar(active = state.isBusy)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = if (compact) Space.md else Space.lg,
                    vertical = Space.lg,
                ),
        ) {
            ConnectionHeader(state, onRetryConnect)

            Spacer(Modifier.height(Space.md))
            InlineBanner(message = state.errorMessage, onDismiss = onDismissError)

            // 넓으면 두 단, 좁으면 한 단으로 이어 붙인다.
            // 폰에서 두 단을 유지하면 세그먼트 버튼이 손가락보다 좁아진다
            TwoPane(
                compact = compact,
                modifier = Modifier.fillMaxSize().padding(top = Space.md),
                first = {
                    HeroClimateCard(state)

                    SectionHeader("공조")
                    TCard {
                        // 드래그 중에는 화면 값만 바뀌고, 손을 떼는 순간 한 번 전송한다.
                        // 틱마다 보내면 BLE 직렬 큐가 밀려 마지막 값이 늦게 도착한다
                        var draftTemp by remember(state.targetTempValue) {
                            mutableStateOf(state.targetTempValue ?: 22.0)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (state.isClimateOn) "작동 중" else "꺼짐",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (state.isClimateOn) T.Ink else T.InkFaint,
                                )
                                Text(
                                    text = "목표 ${"%.1f".format(draftTemp)}℃",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = T.InkFaint,
                                )
                            }
                            TButton(
                                text = if (state.isClimateOn) "끄기" else "켜기",
                                tone = if (state.isClimateOn) ButtonTone.Secondary
                                else ButtonTone.Primary,
                                fillWidth = false,
                                enabled = state.isReady,
                                onClick = {
                                    onCommand(
                                        if (state.isClimateOn) VehicleCommand.ClimateOff
                                        else VehicleCommand.ClimateOn
                                    )
                                },
                            )
                        }
                        Spacer(Modifier.height(Space.sm))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("15℃", style = MaterialTheme.typography.labelSmall, color = T.InkFaint)
                            Slider(
                                value = draftTemp.toFloat(),
                                // 0.5도 단위로 끊는다 — 차량이 받는 최소 단위다
                                onValueChange = { draftTemp = (it * 2).roundToInt() / 2.0 },
                                onValueChangeFinished = {
                                    onCommand(VehicleCommand.SetTemperature(draftTemp))
                                },
                                valueRange = 15f..28f,
                                enabled = state.isReady,
                                colors = SliderDefaults.colors(
                                    thumbColor = T.Electric,
                                    activeTrackColor = T.Electric,
                                    inactiveTrackColor = T.Slate,
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = Space.sm),
                            )
                            Text("28℃", style = MaterialTheme.typography.labelSmall, color = T.InkFaint)
                        }
                    }

                    // 차 앞에서 "지금 잘 돌고 있나"를 판단할 때 보는 값들.
                    // 마지막 갱신 시각이 안 보이면 멈춘 건지 값이 그대로인 건지 구분이 안 된다
                    SectionHeader("상태")
                    TCard {
                        StatusLine("마지막 갱신", state.lastUpdatedLabel)
                        StatusLine("배터리", state.batteryLabel)
                        StatusLine("잠금", if (state.isLocked) "잠김" else "열림")
                        StatusLine("자동화", state.automationLabel)
                    }

                    SectionHeader("빠른 동작")
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        TButton(
                            text = if (state.isLocked) "잠금 해제" else "잠금",
                            tone = ButtonTone.Secondary,
                            enabled = state.isReady,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onCommand(
                                    if (state.isLocked) VehicleCommand.Unlock
                                    else VehicleCommand.Lock
                                )
                            },
                        )
                        TButton(
                            text = "창문 환기",
                            tone = ButtonTone.Secondary,
                            enabled = state.isReady,
                            modifier = Modifier.weight(1f),
                            onClick = { onCommand(VehicleCommand.VentWindows) },
                        )
                        TButton(
                            text = "라이트",
                            tone = ButtonTone.Secondary,
                            enabled = state.isReady,
                            modifier = Modifier.weight(1f),
                            onClick = { onCommand(VehicleCommand.FlashLights) },
                        )
                    }
                },
                second = {
                    SectionHeader("시트")
                    TCard {
                        // 운전석/동승석은 따로. 통풍이냐 열선이냐만 토글 하나로 합친다
                        SeatControl(
                            seatLabel = "운전석",
                            climate = state.seatClimate[SeatPosition.FRONT_LEFT] ?: SeatClimate(),
                            enabled = state.isReady,
                            onChange = { mode, level ->
                                onSeatClimate(SeatPosition.FRONT_LEFT, mode, level)
                            },
                        )
                        Spacer(Modifier.height(Space.md))
                        Hairline()
                        Spacer(Modifier.height(Space.md))
                        SeatControl(
                            seatLabel = "동승석",
                            climate = state.seatClimate[SeatPosition.FRONT_RIGHT] ?: SeatClimate(),
                            enabled = state.isReady,
                            onChange = { mode, level ->
                                onSeatClimate(SeatPosition.FRONT_RIGHT, mode, level)
                            },
                        )
                    }
                },
            )
        }
    }
}

/**
 * 두 덩어리를 폭에 맞춰 배치한다.
 * 좁으면 위아래로 이어 한 번에 스크롤하고, 넓으면 좌우로 나눠 각각 스크롤한다.
 */
@Composable
private fun TwoPane(
    compact: Boolean,
    first: @Composable ColumnScope.() -> Unit,
    second: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    if (compact) {
        Column(modifier = modifier.verticalScroll(rememberScrollState())) {
            first()
            second()
            Spacer(Modifier.height(Space.xxl))
        }
        return
    }

    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            first()
            Spacer(Modifier.height(Space.xxl))
        }
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            second()
            Spacer(Modifier.height(Space.xxl))
        }
    }
}

/**
 * 좌석 한 자리의 통풍/열선 컨트롤.
 *
 * 통풍과 열선은 동시에 켤 수 없으므로 모드 토글 하나로 합쳤다.
 * 위: 좌석 이름 + 통풍/열선 토글, 아래: 단계 세그먼트(모드 색으로 강조).
 */
@Composable
private fun SeatControl(
    seatLabel: String,
    climate: SeatClimate,
    enabled: Boolean,
    onChange: (SeatMode, Level) -> Unit,
) {
    val accent = if (climate.mode == SeatMode.COOL) T.Cool else T.Heat

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(seatLabel, style = MaterialTheme.typography.titleSmall, color = T.Ink)
            SeatModeToggle(
                mode = climate.mode,
                enabled = enabled,
                // 모드를 바꾸면 지금 단계를 그대로 새 모드로 옮긴다 (반대 모드는 꺼진다)
                onSelect = { newMode -> onChange(newMode, climate.level) },
            )
        }
        Spacer(Modifier.height(Space.sm))
        LevelSelector(
            label = "",
            selected = climate.level,
            accent = accent,
            enabled = enabled,
            onSelect = { level -> onChange(climate.mode, level) },
        )
    }
}

/** 통풍 ↔ 열선 2단 토글. 선택한 쪽이 그 모드 색으로 채워진다 */
@Composable
private fun SeatModeToggle(
    mode: SeatMode,
    enabled: Boolean,
    onSelect: (SeatMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(T.Slate)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SeatMode.entries.forEach { m ->
            val selected = m == mode
            val fill = if (m == SeatMode.COOL) T.Cool else T.Heat
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(if (selected) fill else Color.Transparent)
                    .clickable(enabled = enabled) { onSelect(m) }
                    .padding(horizontal = Space.md, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = m.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) Color.White else T.InkMuted,
                )
            }
        }
    }
}

/** 이름 - 값 한 줄. 상태 카드 안에서만 쓴다 */
@Composable
private fun StatusLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Space.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = T.InkFaint)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = T.InkMuted)
    }
}

/**
 * 제어 화면 상단 히어로. 실내 온도를 크게 앞세우고,
 * 외부·목표 온도와 공조 상태를 아래에 묶는다.
 * 그라데이션 + 그림자로 "떠 있는 대시보드" 느낌을 준다.
 */
@Composable
private fun HeroClimateCard(state: DashboardUiState) {
    val shape = RoundedCornerShape(Radius.hero)
    // 실내 온도에 따라 배경 톤을 미세하게 바꾼다 (더우면 난방톤 X, 여기선 냉/중립)
    val bg = when {
        !state.isClimateOn -> Grad.hero
        state.insideTempAccent == T.Heat -> Grad.heat
        else -> Grad.cool
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .softShadow(Elevation.hero, Radius.hero)
            .clip(shape)
            .background(bg, shape)
            .border(1.dp, T.HairlineSoft, shape)
            .padding(Space.lg),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("실내 온도", style = MaterialTheme.typography.titleSmall, color = T.InkMuted)
                StatusPill(
                    text = if (state.isClimateOn) "공조 켜짐" else "공조 꺼짐",
                    color = if (state.isClimateOn) T.Cool else T.InkFaint,
                )
            }

            Spacer(Modifier.height(Space.sm))

            if (state.hasReading) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = state.insideTemp,
                        style = MetricTextStyle,
                        color = state.insideTempAccent,
                    )
                    Text(
                        text = "℃",
                        style = MaterialTheme.typography.titleMedium,
                        color = T.InkMuted,
                        modifier = Modifier.padding(start = Space.xs, bottom = Space.sm),
                    )
                }
            } else {
                Spacer(Modifier.height(Space.xs))
                SkeletonBlock(width = 150, height = 52)
                Spacer(Modifier.height(Space.xs))
            }

            Spacer(Modifier.height(Space.md))
            Hairline()
            Spacer(Modifier.height(Space.md))

            Row(modifier = Modifier.fillMaxWidth()) {
                HeroStat("외부", if (state.hasReading) "${state.outsideTemp}℃" else "--", Modifier.weight(1f))
                HeroStat("목표", "${state.targetTemp}℃", Modifier.weight(1f))
                HeroStat("배터리", state.batteryLabel, Modifier.weight(1f))
            }
        }
    }
}

/** 히어로 하단의 작은 지표 한 칸 */
@Composable
private fun HeroStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = T.InkFaint)
        Spacer(Modifier.height(Space.xs))
        Text(value, style = MaterialTheme.typography.titleMedium, color = T.Ink)
    }
}

/** 값을 아직 못 읽은 계측 자리 */
@Composable
private fun PendingMetric(label: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = T.InkFaint,
        )
        Spacer(Modifier.height(Space.sm))
        SkeletonBlock(width = 116, height = 44)
    }
}

@Composable
private fun ConnectionHeader(
    state: DashboardUiState,
    onRetryConnect: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = state.vehicleName,
                style = MaterialTheme.typography.headlineMedium,
                color = T.Ink,
            )
            Text(
                text = state.connectionDetail,
                style = MaterialTheme.typography.bodySmall,
                color = T.InkFaint,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 음성 관련 UI는 설정 탭으로 옮겼다. 헤더엔 연결 상태만 남긴다
            StatusPill(text = state.connectionLabel, color = state.connectionColor)
            if (state.link is LinkState.Failed) {
                Spacer(Modifier.width(Space.sm))
                TButton("다시 연결", ButtonTone.Ghost, fillWidth = false, onClick = onRetryConnect)
            }
        }
    }
}

/** 화면이 그리는 데 필요한 값만 담는다. 도메인 모델을 그대로 노출하지 않는다 */
data class DashboardUiState(
    val link: LinkState,
    val vehicleName: String,
    val insideTemp: String,
    val outsideTemp: String,
    val targetTemp: String,
    val targetTempValue: Double?,
    val isClimateOn: Boolean,
    val isLocked: Boolean,
    val seatCooler: Map<SeatPosition, Level>,
    val seatHeater: Map<SeatPosition, Level>,
    /** 좌석별 통풍/열선 설정 (클라 저장값). 화면은 이걸 기준으로 그린다 */
    val seatClimate: Map<SeatPosition, SeatClimate> = emptyMap(),
    val isSimulated: Boolean,
    /** 상태를 한 번이라도 읽었는지. false면 스켈레톤을 보여준다 */
    val hasReading: Boolean,
    val pendingCommand: VehicleCommand?,
    val errorMessage: String?,
    /** 마지막으로 차량 상태를 읽은 뒤 흐른 초. 못 읽었으면 null */
    val secondsSinceReading: Long? = null,
    val batteryPercent: Int? = null,
    val automationEnabled: Boolean = true,
    val runningMacroCount: Int = 0,
) {
    val isReady: Boolean get() = link is LinkState.Ready

    val lastUpdatedLabel: String
        get() = when (val seconds = secondsSinceReading) {
            null -> "아직 없음"
            in 0..4 -> "방금"
            in 5..59 -> "${seconds}초 전"
            else -> "${seconds / 60}분 전"
        }

    val batteryLabel: String get() = batteryPercent?.let { "$it%" } ?: "--"

    val automationLabel: String
        get() = when {
            !automationEnabled -> "꺼짐"
            runningMacroCount > 0 -> "실행 중 ${runningMacroCount}개"
            else -> "감시 중"
        }

    /** 연결 중이거나 명령이 오가는 중 */
    val isBusy: Boolean
        get() = pendingCommand != null ||
            link is LinkState.Scanning ||
            link is LinkState.Connecting

    val connectionLabel: String
        get() = when (link) {
            is LinkState.Idle -> "대기"
            is LinkState.Scanning -> "검색 중"
            is LinkState.Connecting -> "연결 중"
            is LinkState.Ready -> if (isSimulated) "시뮬레이터" else "연결됨"
            is LinkState.Failed -> "연결 실패"
        }

    val connectionColor: Color
        get() = when (link) {
            is LinkState.Ready -> if (isSimulated) T.Warn else T.Ok
            is LinkState.Failed -> T.Danger
            else -> T.InkMuted
        }

    val connectionDetail: String
        get() = when {
            pendingCommand != null -> "${pendingCommand.label} 전송 중"
            link is LinkState.Connecting -> "신호 ${link.rssi}dBm"
            link is LinkState.Failed -> link.reason
            link is LinkState.Ready && isSimulated -> "차량 미등록 — 가상 차량으로 동작 중"
            link is LinkState.Ready -> "BLE 직결"
            link is LinkState.Scanning -> "차량을 찾는 중"
            else -> "대기 중"
        }

    /** 27℃를 넘으면 색으로 경고한다. 매크로 발동 임계값과 같은 기준 */
    val insideTempAccent: Color
        get() = insideTemp.toDoubleOrNull()?.let {
            when {
                it >= 27.0 -> T.Heat
                it <= 5.0 -> T.Cool
                else -> T.Ink
            }
        } ?: T.Ink
}
