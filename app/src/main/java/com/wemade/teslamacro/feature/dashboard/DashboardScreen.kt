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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Luggage
import androidx.compose.material.icons.rounded.VerticalAlignBottom
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
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
import com.wemade.teslamacro.ui.component.SectionHeader
import com.wemade.teslamacro.ui.component.Hairline
import com.wemade.teslamacro.ui.component.SkeletonBlock
import com.wemade.teslamacro.ui.component.StatusPill
import com.wemade.teslamacro.ui.component.softShadow
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.ui.component.TCard
import com.wemade.teslamacro.ui.component.ToggleRow
import com.wemade.teslamacro.ui.layout.LocalPane
import com.wemade.teslamacro.ui.theme.Radius
import com.wemade.teslamacro.ui.theme.MetricTextStyle
import com.wemade.teslamacro.ui.theme.Elevation
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

/**
 * 제어 화면 — 0.7.0 리디자인.
 *
 * "차에 타서 3초 안에 원하는 걸 누른다"가 목표.
 * 세로(폰) 기준: 상태 히어로 → 바로 아래 퀵액션 그리드 → 공조 → 시트.
 * 이전 버전의 "상태" 카드는 없앴다 — 배터리·잠금은 히어로로, 갱신·자동화는 헤더 캡션으로.
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

            // 좁으면 한 단 스크롤, 넓으면 좌우 두 단.
            // 폰에서 두 단을 유지하면 세그먼트 버튼이 손가락보다 좁아진다
            TwoPane(
                compact = compact,
                modifier = Modifier.fillMaxSize().padding(top = Space.sm),
                first = {
                    HeroCard(state)

                    Spacer(Modifier.height(Space.md))
                    QuickActionGrid(state, onCommand, columns = if (compact) 3 else 2)

                    // 넓은 화면에선 충전을 왼단에 — 오른단(공조+시트)과 단 길이 균형을 맞춘다
                    if (!compact) {
                        SectionHeader("충전")
                        ChargeCard(state, onCommand, onStealthCharging)
                    }
                },
                second = {
                    // 단 맨 위 헤더는 위 여백 0 — 왼단 히어로와 시작선을 맞춘다
                    SectionHeader("공조", topPadding = if (compact) Space.lg else 0.dp)
                    ClimateCard(state, onCommand)

                    SectionHeader("시트")
                    TCard {
                        // 운전석/동승석은 따로. 통풍이냐 열선이냐만 토글 하나로 합친다
                        SeatControl(
                            seatLabel = "운전석",
                            climate = state.seatClimate[SeatPosition.FRONT_LEFT] ?: SeatClimate(),
                            enabled = state.isReady,
                            pending = state.pendingSeat == SeatPosition.FRONT_LEFT,
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
                            pending = state.pendingSeat == SeatPosition.FRONT_RIGHT,
                            onChange = { mode, level ->
                                onSeatClimate(SeatPosition.FRONT_RIGHT, mode, level)
                            },
                        )
                    }

                    // 좁은 화면에선 원래 순서대로 맨 아래에
                    if (compact) {
                        SectionHeader("충전")
                        ChargeCard(state, onCommand, onStealthCharging)
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

// ---- 퀵액션 ----

/** 퀵액션 한 칸의 정의 */
private data class QuickAction(
    val icon: ImageVector,
    val label: String,
    /** 켜짐 상태로 강조할지 (잠금·공조처럼 상태가 있는 것만) */
    val active: Boolean = false,
    val command: VehicleCommand,
)

/**
 * 자주 쓰는 명령을 아이콘 그리드로.
 * 세로 화면에서 스크롤 없이 손 닿는 위치에 두는 게 목적이라 히어로 바로 아래에 있다.
 */
@Composable
private fun QuickActionGrid(
    state: DashboardUiState,
    onCommand: (VehicleCommand) -> Unit,
    columns: Int,
) {
    val actions = listOf(
        QuickAction(
            // 라벨은 "누르면 일어날 일"로 쓴다 — 현재 상태는 아이콘·테두리가 말해준다.
            // 읽기 전엔 상태를 단정하지 않는다 (히어로는 "--"인데 여기만 "열림" 주장하는 모순 방지)
            icon = if (state.hasBodyReading && state.isLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
            label = if (state.hasBodyReading && state.isLocked) "잠금 해제" else "잠그기",
            active = state.hasBodyReading && state.isLocked,
            command = if (state.hasBodyReading && state.isLocked) VehicleCommand.Unlock else VehicleCommand.Lock,
        ),
        QuickAction(
            icon = Icons.Rounded.AcUnit,
            label = if (state.hasClimateReading && state.isClimateOn) "공조 끄기" else "공조 켜기",
            active = state.hasClimateReading && state.isClimateOn,
            command = if (state.hasClimateReading && state.isClimateOn) VehicleCommand.ClimateOff
            else VehicleCommand.ClimateOn,
        ),
        QuickAction(
            icon = Icons.Rounded.VerticalAlignBottom,
            label = "창문 환기",
            command = VehicleCommand.VentWindows,
        ),
        QuickAction(
            icon = Icons.Rounded.VerticalAlignTop,
            label = "창문 닫기",
            command = VehicleCommand.CloseWindows,
        ),
        QuickAction(
            icon = Icons.Rounded.Luggage,
            label = "트렁크",
            command = VehicleCommand.OpenTrunk,
        ),
        QuickAction(
            icon = Icons.Rounded.DirectionsCar,
            label = "프렁크",
            command = VehicleCommand.OpenFrunk,
        ),
    )

    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        actions.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                row.forEach { action ->
                    QuickActionCell(
                        action = action,
                        enabled = state.isReady,
                        modifier = Modifier.weight(1f),
                        onClick = { onCommand(action.command) },
                    )
                }
                // 마지막 줄이 모자라면 빈 칸으로 정렬을 지킨다
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 아이콘 + 라벨 한 칸. 상태가 있으면(잠금·공조) 켜짐 색으로 알려준다 */
@Composable
private fun QuickActionCell(
    action: QuickAction,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Radius.card)
    val tint = when {
        !enabled -> T.InkFaint
        action.active -> T.Electric
        else -> T.InkMuted
    }
    Column(
        modifier = modifier
            .softShadow(Elevation.card, Radius.card)
            .clip(shape)
            .background(T.Graphite, shape)
            // 켜짐 상태는 배경이 아니라 얇은 테두리로 — 면을 칠하면 라이트 미니멀이 깨진다
            .border(1.dp, if (action.active && enabled) T.Electric.copy(alpha = 0.35f) else Color.Transparent, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = Space.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(imageVector = action.icon, contentDescription = action.label, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(Space.sm))
        Text(
            text = action.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) T.InkMuted else T.InkFaint,
            // 좁은 칸에서 라벨이 2줄로 꺾이면 같은 줄 셀 높이가 제각각 — 한 줄로 고정
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---- 공조 ----

/** 공조 켜기/끄기 + 목표 온도 슬라이더 */
@Composable
private fun ClimateCard(
    state: DashboardUiState,
    onCommand: (VehicleCommand) -> Unit,
) {
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
                    // 읽기 전엔 "꺼짐"이 아니라 확인 중 — 충전 카드와 같은 규칙
                    text = when {
                        !state.hasClimateReading -> "상태 확인 중"
                        state.isClimateOn -> "작동 중"
                        else -> "꺼짐"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (state.hasClimateReading && state.isClimateOn) T.Ink else T.InkFaint,
                )
                Text(
                    text = "목표 ${"%.1f".format(draftTemp)}℃",
                    style = MaterialTheme.typography.bodySmall,
                    color = T.InkFaint,
                )
            }
            TButton(
                text = if (state.isClimateOn) "끄기" else "켜기",
                tone = if (state.isClimateOn) ButtonTone.Secondary else ButtonTone.Primary,
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
}

// ---- 충전 ----

/**
 * 충전 시작/중지 + 한도(%) + 전류(A) 슬라이더.
 * 값은 차가 보고한 설정값에서 시작하고, 손을 떼는 순간 한 번만 전송한다 (공조 슬라이더와 같은 규칙).
 */
@Composable
private fun ChargeCard(
    state: DashboardUiState,
    onCommand: (VehicleCommand) -> Unit,
    onStealthCharging: (Boolean) -> Unit,
) {
    TCard {
        var draftLimit by remember(state.chargeLimitPercent) {
            mutableStateOf(state.chargeLimitPercent ?: 80)
        }
        var draftAmps by remember(state.chargingAmps) {
            mutableStateOf(state.chargingAmps ?: 32)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (state.isCharging) {
                        true -> "충전 중"
                        false -> "충전 안 함"
                        null -> "상태 확인 중"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (state.isCharging == true) T.Ink else T.InkFaint,
                )
                Text(
                    // 절 이어쓰기(" · ") 대신 줄 분리 — 난독증 사용자 기준 한 항목 = 한 줄
                    text = "한도 ${draftLimit}%\n전류 ${draftAmps}A",
                    style = MaterialTheme.typography.bodySmall,
                    color = T.InkFaint,
                )
            }
            TButton(
                text = if (state.isCharging == true) "중지" else "시작",
                tone = if (state.isCharging == true) ButtonTone.Secondary else ButtonTone.Primary,
                fillWidth = false,
                enabled = state.isReady,
                onClick = { onCommand(VehicleCommand.SetCharging(state.isCharging != true)) },
            )
        }

        Spacer(Modifier.height(Space.sm))
        LabeledSlider(
            label = "한도",
            valueText = "${draftLimit}%",
            value = draftLimit.toFloat(),
            range = 50f..100f,
            // 5% 단위 — 차가 받는 최소 단위보다 촘촘할 이유가 없다
            onChange = { draftLimit = ((it / 5).roundToInt() * 5) },
            onCommit = { onCommand(VehicleCommand.SetChargeLimit(draftLimit)) },
            enabled = state.isReady,
        )
        LabeledSlider(
            label = "전류",
            valueText = "${draftAmps}A",
            value = draftAmps.toFloat(),
            range = 5f..48f,
            onChange = { draftAmps = it.roundToInt() },
            onCommit = { onCommand(VehicleCommand.SetChargingAmps(draftAmps)) },
            enabled = state.isReady && !state.stealthCharging,
        )

        Spacer(Modifier.height(Space.sm))
        Hairline()
        Spacer(Modifier.height(Space.sm))
        // 스텔스 충전 토글. 켜면 컨트롤러가 전류를 계속 흔들어 수동 전류 조절은 잠근다
        ToggleRow(
            title = "스텔스 충전",
            subtitle = "전류를 난수로 흔들어 충전 부하 패턴을 흐려요.\n충전이 느려질 수 있어요.",
            checked = state.stealthCharging,
            onCheckedChange = onStealthCharging,
        )
    }
}

/** 이름 + 값 + 슬라이더 한 줄. 충전 카드 전용의 얇은 배치 */
@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit,
    enabled: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = T.InkFaint,
            // 고정 폭이면 글꼴 확대 시 잘린다 — 최소 폭만 잡고 스스로 늘어나게
            modifier = Modifier.widthIn(min = 32.dp),
        )
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onCommit,
            valueRange = range,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = T.Electric,
                activeTrackColor = T.Electric,
                inactiveTrackColor = T.Slate,
            ),
            modifier = Modifier.weight(1f).padding(horizontal = Space.sm),
        )
        Text(
            text = valueText,
            style = MaterialTheme.typography.labelSmall,
            color = T.InkMuted,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 44.dp),
        )
    }
}

// ---- 시트 ----

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
    pending: Boolean = false,
    onChange: (SeatMode, Level) -> Unit,
) {
    val accent = if (climate.mode == SeatMode.COOL) T.Cool else T.Heat

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(seatLabel, style = MaterialTheme.typography.titleSmall, color = T.Ink)
                // 화면 값은 즉시 바뀌지만 차량 전송은 뒤따라온다 — 그 간극을 스피너로 보여준다
                if (pending) {
                    Spacer(Modifier.width(Space.sm))
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = accent,
                    )
                }
            }
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
// ponytail: ChipRow로 통합하지 않고 자체 구현 유지 — 모드별 채움색(Cool/Heat)이 ChipRow 규칙과 달라 통합 이득이 없다
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
            .padding(Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        SeatMode.entries.forEach { m ->
            val selected = m == mode
            val fill = if (m == SeatMode.COOL) T.Cool else T.Heat
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(if (selected) fill else Color.Transparent)
                    .clickable(enabled = enabled) { onSelect(m) }
                    // 주행 중 눈 안 떼고 누르는 화면 — 최소 터치 타깃 44dp 확보
                    .heightIn(min = 44.dp)
                    .padding(horizontal = Space.md, vertical = Space.xs),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = m.label,
                    style = MaterialTheme.typography.labelSmall,
                    // 주황(Heat) 위 흰 글자는 대비 미달이라 진회색으로 — 파랑(Cool)은 흰 글자 유지
                    color = when {
                        !selected -> T.InkMuted
                        m == SeatMode.HEAT -> T.Ink
                        else -> Color.White
                    },
                )
            }
        }
    }
}

// ---- 히어로 ----

/**
 * 화면 상단 히어로 — 이 차의 "지금"을 한 장으로.
 * 실내 온도를 크게 앞세우고, 외부·목표·배터리·잠금을 아래 한 줄로 묶는다.
 * 이전의 "상태" 카드가 여기로 흡수됐다 (같은 값을 두 군데서 보여주지 않는다).
 */
@Composable
private fun HeroCard(state: DashboardUiState) {
    val shape = RoundedCornerShape(Radius.hero)
    // 실내 온도에 따라 배경 톤을 미세하게 바꾼다 — 그라데이션 금지 규칙에 맞춰 단색 틴트만
    val bg = when {
        !state.isClimateOn -> T.Graphite
        state.insideTempAccent == T.Heat -> T.HeatTint
        else -> T.CoolTint
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .softShadow(Elevation.hero, Radius.hero)
            .clip(shape)
            .background(bg, shape)
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
                    // 읽기 전 기본값(false)이 "꺼짐"으로 보이면 오보 — 잠금 칸과 같은 가드
                    text = when {
                        !state.hasClimateReading -> "확인 중"
                        state.isClimateOn -> "공조 켜짐"
                        else -> "공조 꺼짐"
                    },
                    color = if (state.hasClimateReading && state.isClimateOn) T.Cool else T.InkFaint,
                    // 옅은 파랑 틴트 위 파랑 글자는 대비 미달 — 진한 파랑으로
                    textColor = if (state.hasClimateReading && state.isClimateOn) T.ElectricPressed else T.InkMuted,
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
                HeroStat("외부", if (state.hasClimateReading) "${state.outsideTemp}℃" else "--", Modifier.weight(1f))
                // 첫 읽기 전엔 "--℃" 같은 어색한 표기가 되므로 외부와 같은 가드를 건다
                HeroStat("목표", if (state.hasClimateReading) "${state.targetTemp}℃" else "--", Modifier.weight(1f))
                HeroStat("배터리", state.batteryLabel, Modifier.weight(1f))
                // 읽기 전 기본값(false)이 "열림"으로 보이면 오보다 — 다른 칸과 같은 가드
                HeroStat("잠금", if (state.hasBodyReading) { if (state.isLocked) "잠김" else "열림" } else "--", Modifier.weight(1f))
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

// ---- 헤더 ----

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
        // weight가 없으면 긴 실패 사유가 오른쪽 StatusPill·버튼을 화면 밖으로 밀어낸다
        Column(modifier = Modifier.weight(1f).padding(end = Space.sm)) {
            Text(
                text = state.vehicleName,
                style = MaterialTheme.typography.headlineMedium,
                color = T.Ink,
            )
            // 연결 상세 + 갱신 시각 + 자동화 상태를 캡션으로.
            // 항목마다 줄을 나눈다 — 난독증 사용자 기준 한 항목 = 한 줄
            // 실패 사유엔 개행이 들어올 수 있어 캡션에선 공백으로 눌러 항목당 1줄을 보장한다
            Text(
                text = listOf(
                    state.connectionDetail.replace('\n', ' '),
                    "갱신 ${state.lastUpdatedLabel}",
                    "자동화 ${state.automationLabel}",
                ).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = T.InkFaint,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(
                text = state.connectionLabel,
                color = state.connectionColor,
                textColor = state.connectionTextColor,
            )
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
    /** VCSEC(잠금·탑승)를 실제로 읽었는가 — 전역 hasReading은 다른 카테고리 성공으로도 참이 된다 */
    val hasBodyReading: Boolean = false,
    /** CLIMATE를 실제로 읽었는가 — 읽기 전 "공조 꺼짐" 단정을 막는다 */
    val hasClimateReading: Boolean = false,
    val pendingCommand: VehicleCommand?,
    val errorMessage: String?,
    /** 마지막으로 차량 상태를 읽은 뒤 흐른 초. 못 읽었으면 null */
    val secondsSinceReading: Long? = null,
    val batteryPercent: Int? = null,
    /** 충전 상태. 못 읽었으면 null — 카드가 "확인 중"으로 보인다 */
    val isCharging: Boolean? = null,
    val chargeLimitPercent: Int? = null,
    val chargingAmps: Int? = null,
    val stealthCharging: Boolean = false,
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

    /** 지금 차량으로 전송 중인 시트 명령의 좌석. 그 좌석 컨트롤에만 스피너를 단다 */
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

    val connectionColor: Color
        get() = when (link) {
            is LinkState.Ready -> if (isSimulated) T.Warn else T.Ok
            is LinkState.Failed -> T.Danger
            else -> T.InkMuted
        }

    /** 배지 글자색 — 밝은 상태색(Warn/Ok)은 옅은 틴트 위에서 안 읽혀 진한 색으로 분리한다 */
    val connectionTextColor: Color
        get() = when (link) {
            is LinkState.Ready -> if (isSimulated) T.WarnText else T.OkText
            is LinkState.Failed -> T.Danger
            else -> T.InkMuted
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
