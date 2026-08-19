package com.wemade.teslamacro.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.wemade.teslamacro.ui.component.StatusTile
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.ui.component.TileTone
import com.wemade.teslamacro.ui.component.ToggleRow
import com.wemade.teslamacro.ui.layout.LocalPane
import com.wemade.teslamacro.ui.theme.ColorRole
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

/** 목표 도달로 볼 여유 폭(℃). 차 온도계가 0.1씩 흔들려 딱 맞을 때만 도달로 보면 색이 깜빡인다 */
private const val REACHED_MARGIN_C = 1.0

/** 타일 밭이 자랄 수 있는 최대 높이. 넘어가면 타일 속이 비어 보인다 */
private val TILE_FIELD_MAX_HEIGHT = 440.dp

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

            // 넓으면 화면을 정확히 채우고, 좁으면(폰) 그때만 스크롤을 허용한다
            if (compact) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    TileField(state, compact = true) { sheet = it }
                }
            } else {
                TileField(state, compact = false) { sheet = it }
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
 * 타일 밭 — 크기가 곧 중요도다.
 *
 * 실내 온도가 가장 크고, 배터리·잠금·시트가 그다음이다.
 * 이 크기와 위치는 상태가 변해도 절대 안 움직인다 — 눈이 길을 외우게 하려는 것.
 */
@Composable
private fun TileField(
    state: DashboardUiState,
    compact: Boolean,
    onOpen: (Sheet) -> Unit,
) {
    if (compact) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            ClimateTile(state, Modifier.fillMaxWidth()) { onOpen(Sheet.CLIMATE) }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                BatteryTile(state, Modifier.weight(1f)) { onOpen(Sheet.CHARGE) }
                LockTile(state, Modifier.weight(1f)) { onOpen(Sheet.LOCK) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                SeatTile(state, SeatPosition.FRONT_LEFT, Modifier.weight(1f)) {
                    onOpen(Sheet.SEAT_LEFT)
                }
                SeatTile(state, SeatPosition.FRONT_RIGHT, Modifier.weight(1f)) {
                    onOpen(Sheet.SEAT_RIGHT)
                }
            }
            OpeningTile(state, Modifier.fillMaxWidth()) { onOpen(Sheet.OPENINGS) }
        }
        return
    }

    // 화면 높이를 끝까지 채우면 타일이 내용보다 훨씬 커져 속이 텅 빈다.
    // 높이를 묶고 위로 붙인 뒤 남는 공간은 여백으로 둔다 —
    // 테두리 안의 빈 공간은 고장으로 읽히지만, 테두리 밖 여백은 의도로 읽힌다
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = TILE_FIELD_MAX_HEIGHT),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        // 4칸 × 2줄. 실내와 문·적재함이 두 칸씩 먹어 크기로 중요도를 고정한다
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            ClimateTile(state, Modifier.weight(2f).fillMaxHeight()) { onOpen(Sheet.CLIMATE) }
            BatteryTile(state, Modifier.weight(1f).fillMaxHeight()) { onOpen(Sheet.CHARGE) }
            LockTile(state, Modifier.weight(1f).fillMaxHeight()) { onOpen(Sheet.LOCK) }
        }
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            SeatTile(state, SeatPosition.FRONT_LEFT, Modifier.weight(1f).fillMaxHeight()) {
                onOpen(Sheet.SEAT_LEFT)
            }
            SeatTile(state, SeatPosition.FRONT_RIGHT, Modifier.weight(1f).fillMaxHeight()) {
                onOpen(Sheet.SEAT_RIGHT)
            }
            OpeningTile(state, Modifier.weight(2f).fillMaxHeight()) { onOpen(Sheet.OPENINGS) }
        }
    }
}

/**
 * 실내 온도 + 공조를 한 타일에 합쳤다.
 *
 * 예전엔 "실내 온도 카드"와 "공조 카드"와 "공조 끄기 버튼"이 따로 있었는데,
 * 사실 이건 하나의 질문이다 — "지금 차 안이 어떻고, 차가 뭘 하고 있나".
 * 합치니 중복 조작도 같이 사라졌다.
 */
@Composable
private fun ClimateTile(state: DashboardUiState, modifier: Modifier, onClick: () -> Unit) {
    val running = state.isClimateOn && state.hasClimateReading
    // 색은 "아직 목표에 못 갔다"는 뜻이다. 도달해서 유지만 하는 중이면 무채색으로 돌아온다 —
    // 22.5℃가 목표 22.5℃에 닿았는데도 주황이면 색이 거짓말을 하고, 그러면 색 전체가 의미를 잃는다
    val inside = state.insideTemp.toDoubleOrNull()
    val target = state.targetTempValue
    val gap = if (inside != null && target != null) inside - target else 0.0
    val tone = when {
        !running -> TileTone.Calm
        gap > REACHED_MARGIN_C -> TileTone.Cool   // 아직 더워서 식히는 중
        gap < -REACHED_MARGIN_C -> TileTone.Warm  // 아직 추워서 데우는 중
        else -> TileTone.Calm                     // 목표 도달 — 조용히 유지
    }
    // 목표·공조는 아래 수치줄이 이미 말한다. 여기서 또 쓰면 같은 걸 두 번 읽힌다
    val detail = if (state.hasClimateReading) null else "확인 중"

    StatusTile(
        label = "실내",
        value = if (state.hasReading) "${state.insideTemp}°" else "--",
        modifier = modifier,
        detail = detail,
        tone = tone,
        big = true,
        onClick = onClick,
        content = {
            // 앱에서 유일하게 움직이는 것 — 공조가 실제로 도는 동안만
            if (running) {
                BreathingBar(
                    color = when (tone) {
                        TileTone.Cool -> T.Cool
                        TileTone.Warm -> T.Heat
                        // 도달 후 유지 — 돌고는 있다는 사실만 옅게 남긴다
                        else -> T.InkFaint
                    }
                )
            }
        },
        footer = {
            Spacer(Modifier.height(Space.sm))
            Row(modifier = Modifier.fillMaxWidth()) {
                HeroMetric("외부", "${state.outsideTemp}°", Modifier.weight(1f))
                HeroMetric("목표", "${state.targetTemp}°", Modifier.weight(1f))
                HeroMetric(
                    label = "공조",
                    value = if (!state.hasClimateReading) "--"
                    else if (state.isClimateOn) "켜짐" else "꺼짐",
                    modifier = Modifier.weight(1f),
                )
            }
        },
    )
}

/** 히어로 바닥에 깔리는 보조 수치. 값이 주인공인 규칙은 여기서도 같다 */
@Composable
private fun HeroMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = T.InkFaint,
        )
        Spacer(Modifier.height(Space.xs))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = T.Ink,
        )
    }
}

@Composable
private fun BatteryTile(state: DashboardUiState, modifier: Modifier, onClick: () -> Unit) {
    val charging = state.isCharging == true
    StatusTile(
        label = "배터리",
        value = state.batteryLabel,
        modifier = modifier,
        detail = when {
            state.isCharging == null -> "확인 중"
            charging -> "충전 중 · ${state.chargingAmps ?: "--"}A"
            state.rangeKm != null -> "${state.rangeKm}km · 한도 ${state.chargeLimitPercent ?: "--"}%"
            else -> "한도 ${state.chargeLimitPercent ?: "--"}%"
        },
        // 충전은 차가 일하는 중이라 색을 준다
        tone = if (charging) TileTone.Cool else TileTone.Calm,
        onClick = onClick,
    )
}

@Composable
private fun LockTile(state: DashboardUiState, modifier: Modifier, onClick: () -> Unit) {
    // 문이 열려 있으면 잠금 해제는 당연한 결과라 새 소식이 아니다.
    // 문 타일이 이미 더 정확히 말하고 있으니 빨간 면을 두 개 만들지 않는다
    val unlocked = state.hasBodyReading && !state.isLocked && state.openings.isEmpty()
    StatusTile(
        label = "잠금",
        // 열려 있는 건 사람이 봐야 하는 상태다. 이 화면에서 면이 물드는 유일한 경우
        value = when {
            !state.hasBodyReading -> "--"
            state.isLocked -> "잠김"
            else -> "열림"
        },
        modifier = modifier,
        detail = if (unlocked) "눌러서 잠그기" else null,
        tone = if (unlocked) TileTone.Alert else TileTone.Calm,
        onClick = onClick,
    )
}

@Composable
private fun SeatTile(
    state: DashboardUiState,
    seat: SeatPosition,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val climate = state.seatClimate[seat] ?: SeatClimate()
    val on = climate.level != Level.OFF
    StatusTile(
        label = seat.label,
        value = if (on) climate.level.label else "끔",
        modifier = modifier,
        detail = if (on) climate.mode.label else null,
        tone = when {
            !on -> TileTone.Calm
            climate.mode == SeatMode.COOL -> TileTone.Cool
            else -> TileTone.Warm
        },
        pending = state.pendingSeat == seat,
        onClick = onClick,
    )
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

/**
 * 문·트렁크 열림 상태.
 *
 * 예전엔 여기가 버튼 네 개였다 — 누를 수는 있는데 **지금 열려 있는지는 알 수 없었다**.
 * 차는 도어 상태를 계속 읽고 있었으니 화면이 안 쓰고 버린 셈이다.
 * 비 오는 날 창문 열어둔 걸 알려주는 게 여는 버튼보다 중요하다.
 */
@Composable
private fun OpeningTile(state: DashboardUiState, modifier: Modifier, onClick: () -> Unit) {
    val open = state.openings
    StatusTile(
        label = "문 · 적재함",
        value = when {
            !state.hasBodyReading -> "--"
            open.isEmpty() -> "모두 닫힘"
            else -> open.joinToString(" · ")
        },
        modifier = modifier,
        detail = if (open.isEmpty()) null else "눌러서 조작",
        tone = if (open.isNotEmpty()) TileTone.Alert else TileTone.Calm,
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
                    OpeningButton("트렁크", state.isReady) { onCommand(VehicleCommand.OpenTrunk) }
                    OpeningButton("프렁크", state.isReady) { onCommand(VehicleCommand.OpenFrunk) }
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
                        onClick = { mode = candidate },
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
    val seatCooler: Map<SeatPosition, Level>,
    val seatHeater: Map<SeatPosition, Level>,
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

    val connectionDetail: String
        get() = when {
            pendingCommand != null -> "${pendingCommand.label} 전송 중"
            link is LinkState.Connecting -> "신호 ${link.rssi}dBm"
            link is LinkState.Failed -> link.reason
            link is LinkState.Ready && isSimulated -> "가상 차량"
            link is LinkState.Ready -> "BLE 직결"
            link is LinkState.Scanning -> "차량을 찾는 중"
            else -> "대기 중"
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
