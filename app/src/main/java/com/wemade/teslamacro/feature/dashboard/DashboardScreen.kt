package com.wemade.teslamacro.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.domain.gateway.LinkState
import com.wemade.teslamacro.domain.model.Level
import com.wemade.teslamacro.domain.model.SeatClimate
import com.wemade.teslamacro.domain.model.SeatMode
import com.wemade.teslamacro.domain.model.SeatPosition
import com.wemade.teslamacro.ui.component.BreathingBar
import com.wemade.teslamacro.ui.component.ButtonTone
import com.wemade.teslamacro.ui.component.Hairline
import com.wemade.teslamacro.ui.component.IndeterminateBar
import com.wemade.teslamacro.ui.component.InlineBanner
import com.wemade.teslamacro.ui.component.LevelSelector
import com.wemade.teslamacro.ui.component.NumberStepper
import com.wemade.teslamacro.ui.component.PickerSheet
import com.wemade.teslamacro.ui.component.StatusPill
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.ui.component.TileTone
import com.wemade.teslamacro.ui.component.ToggleRow
import com.wemade.teslamacro.ui.layout.LocalPane
import com.wemade.teslamacro.ui.layout.Pane
import com.wemade.teslamacro.ui.theme.ColorRole
import com.wemade.teslamacro.ui.theme.HeroValueStyle
import com.wemade.teslamacro.ui.theme.Radius
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

/** 목표 도달로 볼 여유 폭(℃). 차 온도계가 0.1씩 흔들려 딱 맞을 때만 도달로 보면 색이 깜빡인다 */
private const val REACHED_MARGIN_C = 1.0

/** 지금 열려 있는 조작 시트. 홈은 읽기만 하고, 조작은 전부 여기로 내려간다 */
private enum class Sheet { NONE, CLIMATE, LOCK, CHARGE, SEAT_LEFT, SEAT_RIGHT, OPENINGS }

/**
 * 제어 화면 — 계기판이지 조작판이 아니다.
 *
 * 이 화면은 대부분의 시간 동안 아무도 안 만진다. 매크로가 알아서 다 하기 때문이다.
 * 그래서 목표는 "빨리 누르기"가 아니라 **"안 만지고 알아채기"** 다:
 *
 * - 스크롤이 없다. 차에 고정된 화면에서 스크롤은 못 본 정보를 만든다
 * - 값이 크고 라벨이 작다. 위치가 고정이라 라벨은 한 번만 읽으면 된다
 * - **정상이면 무채색이다.** 색은 차가 일하는 중이거나 사람이 봐야 할 때만 쓴다.
 *   그래야 글자를 안 읽어도 "색이 없다 = 괜찮다"를 안다
 * - 조작은 전부 타일을 눌러야 나온다. 홈에 버튼을 늘어놓지 않는다
 */
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onCommand: (VehicleCommand) -> Unit,
    onRetryConnect: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
    onSeatClimate: (SeatPosition, SeatMode, Level) -> Unit = { _, _, _ -> },
    onStealthCharging: (Boolean) -> Unit = {},
) {
    val compact = LocalPane.current.isCompact
    var sheet by remember { mutableStateOf(Sheet.NONE) }

    Column(modifier = modifier.fillMaxSize()) {
        // 명령이 오가는 동안 맨 위에 얇은 선이 흐른다. 누른 게 먹었는지 즉시 안다
        IndeterminateBar(active = state.isBusy)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = if (compact) Space.md else Space.lg, vertical = Space.md),
        ) {
            StatusBand(state, onRetryConnect)
            InlineBanner(message = state.errorMessage, onDismiss = onDismissError)
            Spacer(Modifier.height(Space.md))

            // 숫자와 지표 줄을 한 덩어리로 묶어 세로 가운데에 둔다.
            // 위아래 끝으로 벌리면 가운데가 통째로 비어 화면이 고장난 것처럼 보인다
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
            ) {
                HeroValue(state) { sheet = Sheet.CLIMATE }
                Spacer(Modifier.height(Space.xxl))
                MetricStrip(state) { sheet = it }
            }
        }
    }

    SheetHost(
        sheet = sheet,
        state = state,
        onDismiss = { sheet = Sheet.NONE },
        onCommand = onCommand,
        onSeatClimate = onSeatClimate,
        onStealthCharging = onStealthCharging,
    )
}

/**
 * 화면 맨 위 한 줄 — 차 이름과 연결 상태.
 *
 * 예전엔 여기에 갱신 시각·자동화 상태까지 세 줄로 쌓여 있었다.
 * 매일 보는 화면에서 매일 같은 글자를 세 줄 읽을 이유가 없어 한 줄로 눕혔다.
 */
@Composable
private fun StatusBand(state: DashboardUiState, onRetryConnect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = state.vehicleName,
            style = MaterialTheme.typography.titleMedium,
            color = T.Ink,
        )
        Spacer(Modifier.width(Space.md))
        Text(
            text = "${state.lastUpdatedLabel} · 자동화 ${state.automationLabel}",
            style = MaterialTheme.typography.bodySmall,
            color = T.InkFaint,
            modifier = Modifier.weight(1f),
        )
        if (state.link is LinkState.Failed) {
            TButton(
                text = "다시 연결",
                tone = ButtonTone.Secondary,
                fillWidth = false,
                small = true,
                onClick = onRetryConnect,
            )
            Spacer(Modifier.width(Space.sm))
        }
        StatusPill(
            text = state.connectionLabel,
            color = state.connectionRole.color,
            textColor = state.connectionTextRole.color,
        )
    }
}

/**
 * 히어로 — 실내 온도 하나가 화면을 지배한다.
 *
 * 이 화면에서 알고 싶은 건 결국 "지금 차 안이 어떤가" 하나다.
 * 나머지 값은 아래 한 줄로 눕히고, 여기에만 크기를 몰아준다 —
 * 멀리서 곁눈으로 볼 때 읽히는 건 어차피 가장 큰 것 하나뿐이라서다.
 */
@Composable
private fun HeroValue(state: DashboardUiState, onClick: () -> Unit) {
    val running = state.isClimateOn && state.hasClimateReading
    // 색은 "아직 목표에 못 갔다"는 뜻이다. 도달해 유지만 하는 중이면 무채색으로 돌아온다
    val inside = state.insideTemp.toDoubleOrNull()
    val target = state.targetTempValue
    val gap = if (inside != null && target != null) inside - target else 0.0
    val tone = when {
        !running -> TileTone.Calm
        gap > REACHED_MARGIN_C -> TileTone.Cool
        gap < -REACHED_MARGIN_C -> TileTone.Warm
        else -> TileTone.Calm
    }
    val valueColor = when (tone) {
        TileTone.Cool -> T.Cool
        TileTone.Warm -> T.Heat
        else -> T.Ink
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.tile))
            .clickable(onClick = onClick)
            .padding(vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = if (state.hasReading) "${state.insideTemp}°" else "--",
                style = heroStyle(),
                color = valueColor,
                maxLines = 1,
            )
            // 앱에서 유일하게 움직이는 것 — 공조가 실제로 도는 동안만
            if (running) {
                BreathingBar(
                    color = when (tone) {
                        TileTone.Cool -> T.Cool
                        TileTone.Warm -> T.Heat
                        else -> T.InkFaint
                    },
                    modifier = Modifier.width(heroBarWidth()),
                )
            }
        }
        Spacer(Modifier.width(Space.xl))
        Column {
            Text(
                text = "실내",
                style = MaterialTheme.typography.labelSmall,
                color = T.InkFaint,
            )
            Spacer(Modifier.height(Space.xs))
            Text(
                text = when {
                    !state.hasClimateReading -> "공조 확인 중"
                    state.isClimateOn -> "공조 켜짐 · 목표 ${state.targetTemp}°"
                    else -> "공조 꺼짐"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = T.InkMuted,
            )
            Text(
                text = "외부 ${state.outsideTemp}°",
                style = MaterialTheme.typography.bodyMedium,
                color = T.InkFaint,
            )
        }
    }
}

/** 히어로 숫자 크기 — 폭이 좁으면 줄인다. 넘치면 읽히지도 않는다 */
@Composable
private fun heroStyle() = HeroValueStyle.copy(
    fontSize = when (LocalPane.current) {
        Pane.Compact -> 64.sp
        Pane.Medium -> 88.sp
        Pane.Expanded -> 112.sp
    }
)

@Composable
private fun heroBarWidth() = when (LocalPane.current) {
    Pane.Compact -> 140.dp
    Pane.Medium -> 190.dp
    Pane.Expanded -> 240.dp
}

/**
 * 아래 한 줄 — 나머지 값 전부.
 *
 * 각 항목을 누르면 그 조작 시트가 열린다. 홈에는 버튼을 늘어놓지 않는다.
 * 좁아지면 줄바꿈되므로 폭이 바뀌어도 눌리지 않는다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetricStrip(state: DashboardUiState, onOpen: (Sheet) -> Unit) {
    val climateLeft = state.seatClimate[SeatPosition.FRONT_LEFT] ?: SeatClimate()
    val climateRight = state.seatClimate[SeatPosition.FRONT_RIGHT] ?: SeatClimate()
    // 문이 열려 있으면 잠금 해제는 당연한 결과라 새 소식이 아니다 — 경보는 한 곳만
    val openings = state.openings

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Metric("배터리", state.batteryLabel) { onOpen(Sheet.CHARGE) }
        if (state.rangeKm != null) {
            Metric("주행", "${state.rangeKm}km") { onOpen(Sheet.CHARGE) }
        }
        Metric(
            label = "잠금",
            value = when {
                !state.hasBodyReading -> "--"
                state.isLocked -> "잠김"
                else -> "열림"
            },
            alert = state.hasBodyReading && !state.isLocked && openings.isEmpty(),
        ) { onOpen(Sheet.LOCK) }
        Metric(
            label = "운전석",
            value = if (climateLeft.level != Level.OFF) {
                "${climateLeft.mode.label} ${climateLeft.level.label}"
            } else "끔",
            accent = seatAccent(climateLeft),
            pending = state.pendingSeat == SeatPosition.FRONT_LEFT,
        ) { onOpen(Sheet.SEAT_LEFT) }
        Metric(
            label = "동승석",
            value = if (climateRight.level != Level.OFF) {
                "${climateRight.mode.label} ${climateRight.level.label}"
            } else "끔",
            accent = seatAccent(climateRight),
            pending = state.pendingSeat == SeatPosition.FRONT_RIGHT,
        ) { onOpen(Sheet.SEAT_RIGHT) }
        Metric(
            label = "문 · 적재함",
            value = when {
                !state.hasBodyReading -> "--"
                openings.isEmpty() -> "모두 닫힘"
                else -> openings.joinToString(" · ")
            },
            alert = openings.isNotEmpty(),
        ) { onOpen(Sheet.OPENINGS) }
    }
}

/** 시트가 실제로 돌고 있을 때만 색을 준다 */
@Composable
private fun seatAccent(climate: SeatClimate): Color = when {
    climate.level == Level.OFF -> T.Ink
    climate.mode == SeatMode.COOL -> T.Cool
    else -> T.Heat
}

/**
 * 지표 한 칸. 라벨은 작고 값이 크다.
 *
 * [alert]면 값을 빨간 면에 얹는다 — 이 줄의 글씨는 히어로보다 작아서
 * 색만 바꾸면 곁눈에 안 걸린다. 면으로 깔아야 안 읽고도 보인다.
 */
@Composable
private fun Metric(
    label: String,
    value: String,
    accent: Color = T.Ink,
    alert: Boolean = false,
    pending: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.button))
            .clickable(onClick = onClick)
            // 주행 중 조작이라 손가락보다 넉넉하게
            .heightIn(min = 56.dp)
            .padding(horizontal = Space.sm, vertical = Space.xs),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (pending) T.Electric else T.InkFaint,
        )
        Spacer(Modifier.height(Space.xs))
        if (alert) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                color = T.OnDanger,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.button))
                    .background(T.Danger)
                    .padding(horizontal = Space.sm, vertical = Space.xs),
            )
        } else {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                color = accent,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun RowScope.OpeningButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    TButton(
        text = text,
        tone = ButtonTone.Secondary,
        modifier = Modifier.weight(1f),
        enabled = enabled,
        onClick = onClick,
    )
}

/** 타일을 누르면 열리는 조작 시트들. 홈을 읽기 전용으로 두기 위한 장치 */
@Composable
private fun SheetHost(
    sheet: Sheet,
    state: DashboardUiState,
    onDismiss: () -> Unit,
    onCommand: (VehicleCommand) -> Unit,
    onSeatClimate: (SeatPosition, SeatMode, Level) -> Unit,
    onStealthCharging: (Boolean) -> Unit,
) {
    when (sheet) {
        Sheet.NONE -> Unit

        Sheet.CLIMATE -> PickerSheet(title = "공조", onDismiss = onDismiss) {
            Column(modifier = Modifier.padding(horizontal = Space.lg)) {
                Text(
                    text = "목표 온도",
                    style = MaterialTheme.typography.bodySmall,
                    color = T.InkMuted,
                )
                Spacer(Modifier.height(Space.sm))
                NumberStepper(
                    value = state.targetTempValue ?: 22.0,
                    min = 15.0,
                    max = 28.0,
                    step = 0.5,
                    unit = "℃",
                    onChange = { onCommand(VehicleCommand.SetTemperature(it)) },
                )
                Spacer(Modifier.height(Space.lg))
                if (state.isClimateOn) {
                    TButton("공조 끄기", tone = ButtonTone.Secondary) {
                        onCommand(VehicleCommand.ClimateOff)
                        onDismiss()
                    }
                } else {
                    TButton("공조 켜기") {
                        onCommand(VehicleCommand.ClimateOn)
                        onDismiss()
                    }
                }
                Spacer(Modifier.height(Space.lg))
            }
        }

        Sheet.LOCK -> PickerSheet(title = "잠금", onDismiss = onDismiss) {
            Column(modifier = Modifier.padding(horizontal = Space.lg)) {
                // 잠금 해제는 실수로 눌리면 안 되는 동작이라 타일 탭으로 바로 걸지 않았다
                TButton("잠그기") {
                    onCommand(VehicleCommand.Lock)
                    onDismiss()
                }
                Spacer(Modifier.height(Space.sm))
                TButton("잠금 해제", tone = ButtonTone.Secondary) {
                    onCommand(VehicleCommand.Unlock)
                    onDismiss()
                }
                Spacer(Modifier.height(Space.lg))
            }
        }

        Sheet.CHARGE -> PickerSheet(title = "충전", onDismiss = onDismiss) {
            Column(modifier = Modifier.padding(horizontal = Space.lg)) {
                Text(
                    text = "충전 한도",
                    style = MaterialTheme.typography.bodySmall,
                    color = T.InkMuted,
                )
                Spacer(Modifier.height(Space.sm))
                NumberStepper(
                    value = (state.chargeLimitPercent ?: 80).toDouble(),
                    min = 50.0,
                    max = 100.0,
                    step = 5.0,
                    unit = "%",
                    onChange = { onCommand(VehicleCommand.SetChargeLimit(it.toInt())) },
                )
                Spacer(Modifier.height(Space.md))
                Text(
                    text = "충전 전류",
                    style = MaterialTheme.typography.bodySmall,
                    color = T.InkMuted,
                )
                Spacer(Modifier.height(Space.sm))
                NumberStepper(
                    value = (state.chargingAmps ?: 32).toDouble(),
                    min = 5.0,
                    max = 48.0,
                    step = 1.0,
                    unit = "A",
                    onChange = { onCommand(VehicleCommand.SetChargingAmps(it.toInt())) },
                )
                Spacer(Modifier.height(Space.md))
                Hairline()
                Spacer(Modifier.height(Space.md))
                ToggleRow(
                    title = "스텔스 충전",
                    subtitle = "전류를 조금씩 흔들어 눈에 덜 띄게 해요",
                    checked = state.stealthCharging,
                    onCheckedChange = onStealthCharging,
                )
                Spacer(Modifier.height(Space.lg))
            }
        }

        Sheet.OPENINGS -> PickerSheet(title = "문 · 적재함", onDismiss = onDismiss) {
            Column(modifier = Modifier.padding(horizontal = Space.lg)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    OpeningButton("창문 환기", state.isReady) {
                        onCommand(VehicleCommand.VentWindows)
                    }
                    OpeningButton("창문 닫기", state.isReady) {
                        onCommand(VehicleCommand.CloseWindows)
                    }
                }
                Spacer(Modifier.height(Space.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    OpeningButton("트렁크 열기", state.isReady) {
                        onCommand(VehicleCommand.OpenTrunk)
                    }
                    // 열기만 있고 닫기가 없었다. 프렁크는 전동이 아니라 손으로 닫아야 해서 없다
                    OpeningButton("트렁크 닫기", state.isReady) {
                        onCommand(VehicleCommand.CloseTrunk)
                    }
                }
                Spacer(Modifier.height(Space.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    OpeningButton("프렁크 열기", state.isReady) {
                        onCommand(VehicleCommand.OpenFrunk)
                    }
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(Space.lg))
            }
        }

        Sheet.SEAT_LEFT -> SeatSheet(state, SeatPosition.FRONT_LEFT, onDismiss, onSeatClimate)
        Sheet.SEAT_RIGHT -> SeatSheet(state, SeatPosition.FRONT_RIGHT, onDismiss, onSeatClimate)
    }
}

@Composable
private fun SeatSheet(
    state: DashboardUiState,
    seat: SeatPosition,
    onDismiss: () -> Unit,
    onSeatClimate: (SeatPosition, SeatMode, Level) -> Unit,
) {
    val climate = state.seatClimate[seat] ?: SeatClimate()
    var mode by remember(seat) { mutableStateOf(climate.mode) }

    PickerSheet(title = seat.label, onDismiss = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = Space.lg)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                SeatMode.entries.forEach { candidate ->
                    TButton(
                        text = candidate.label,
                        tone = if (candidate == mode) ButtonTone.Primary else ButtonTone.Secondary,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            mode = candidate
                            // 이미 켜져 있으면 모드 전환을 그 자리에서 보낸다.
                            // 안 보내면 통풍→열선을 눌러도 아무 일이 없어 고장으로 보인다
                            if (climate.level != Level.OFF) {
                                onSeatClimate(seat, candidate, climate.level)
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(Space.md))
            LevelSelector(
                label = "단계",
                selected = climate.level,
                accent = if (mode == SeatMode.COOL) T.Cool else T.Heat,
                onSelect = { onSeatClimate(seat, mode, it) },
                enabled = state.isReady,
            )
            Spacer(Modifier.height(Space.lg))
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
    /** 좌석별 통풍/열선 설정 (클라 저장값). 화면은 이걸 기준으로 그린다 */
    val seatClimate: Map<SeatPosition, SeatClimate> = emptyMap(),
    val isSimulated: Boolean,
    /** 상태를 한 번이라도 읽었는지. false면 값 자리에 --를 보여준다 */
    val hasReading: Boolean,
    /** VCSEC(잠금·탑승)를 실제로 읽었는가 — 전역 hasReading은 다른 카테고리 성공으로도 참이 된다 */
    val hasBodyReading: Boolean = false,
    /** CLIMATE를 실제로 읽었는가 — 읽기 전 "공조 꺼짐" 단정을 막는다 */
    val hasClimateReading: Boolean = false,
    val pendingCommand: VehicleCommand?,
    val errorMessage: String?,
    /** 마지막으로 차량 상태를 읽은 뒤 흐른 초. 못 읽었으면 null */
    val secondsSinceReading: Long? = null,
    val batteryPercent: Int? = null,
    /** 충전 상태. 못 읽었으면 null — 타일이 "확인 중"으로 보인다 */
    val isCharging: Boolean? = null,
    val chargeLimitPercent: Int? = null,
    val chargingAmps: Int? = null,
    val stealthCharging: Boolean = false,
    val automationEnabled: Boolean = true,
    val runningMacroCount: Int = 0,
    /** 주행 가능 거리(km). 배터리 %만으론 실감이 안 나 함께 보여준다 */
    val rangeKm: Int? = null,
    /** 지금 열려 있는 문·트렁크 이름. 차는 읽고 있었는데 화면이 안 쓰던 값이다 */
    val openings: List<String> = emptyList(),
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

    /** 지금 차량으로 전송 중인 시트 명령의 좌석. 그 좌석 타일에만 표시를 단다 */
    val pendingSeat: SeatPosition?
        get() = when (val command = pendingCommand) {
            is VehicleCommand.SetSeatCooler -> command.seat
            is VehicleCommand.SetSeatHeater -> command.seat
            else -> null
        }

    val connectionLabel: String
        get() = when (link) {
            is LinkState.Idle -> "대기"
            is LinkState.Scanning -> "검색 중"
            is LinkState.Connecting -> "연결 중"
            is LinkState.Ready -> if (isSimulated) "시뮬레이터" else "연결됨"
            is LinkState.Failed -> "연결 실패"
        }

    val connectionRole: ColorRole
        get() = when (link) {
            is LinkState.Ready -> if (isSimulated) ColorRole.Warn else ColorRole.Ok
            is LinkState.Failed -> ColorRole.Danger
            else -> ColorRole.InkMuted
        }

    /** 배지 글자색 — 밝은 상태색(Warn/Ok)은 옅은 틴트 위에서 안 읽혀 진한 색으로 분리한다 */
    val connectionTextRole: ColorRole
        get() = when (link) {
            is LinkState.Ready -> if (isSimulated) ColorRole.WarnText else ColorRole.OkText
            is LinkState.Failed -> ColorRole.Danger
            else -> ColorRole.InkMuted
        }

    /** 27℃를 넘으면 더운 상태로 본다. 매크로 발동 임계값과 같은 기준 */
    val insideTempRole: ColorRole
        get() = insideTemp.toDoubleOrNull()?.let {
            when {
                it >= 27.0 -> ColorRole.Heat
                it <= 5.0 -> ColorRole.Cool
                else -> ColorRole.Ink
            }
        } ?: ColorRole.Ink
}
