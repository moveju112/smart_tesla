package com.wemade.teslamacro.feature.macro.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AirlineSeatReclineNormal
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.wemade.teslamacro.ui.component.DraftField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.wemade.teslamacro.ui.component.DraftMark
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import com.wemade.teslamacro.domain.command.CommandTemplate
import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.domain.macro.ActionStep
import com.wemade.teslamacro.domain.macro.describe
import com.wemade.teslamacro.domain.macro.formatDuration
import com.wemade.teslamacro.domain.model.Level
import com.wemade.teslamacro.domain.model.SeatPosition
import com.wemade.teslamacro.ui.component.ButtonTone
import com.wemade.teslamacro.ui.component.ChoiceGrid as ChipRow
import com.wemade.teslamacro.ui.component.NumberStepper
import com.wemade.teslamacro.ui.component.openOverlayPermissionSettings
import com.wemade.teslamacro.ui.component.rememberOnResume
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.ui.theme.Radius
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

/** 동작 한 걸음을 평평한 요약 행 또는 상세 입력 폼으로 보여준다. */
@Composable
fun ActionCard(
    index: Int,
    total: Int,
    step: ActionStep,
    template: CommandTemplate?,
    onChange: (ActionStep) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = true,
    onToggle: () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth()) {
        EditorItemHeader("${index + 1}. ${actionSummary(step)}", expanded, onToggle)
        if (!expanded) return@Column

        val editor = parameterEditor(step, template)
        if (editor != null) {
            Spacer(Modifier.height(Space.md))
            editor(onChange)
        }
        // 순서·삭제 조작은 별도 행으로 내려 휴대폰에서 제목을 밀어내지 않는다.
        Spacer(Modifier.height(Space.sm))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("순서", style = MaterialTheme.typography.labelMedium,
                color = T.InkMuted, modifier = Modifier.weight(1f))
            CardIconButton(DraftMark.ArrowUp, "${index + 1}번째 동작 위로 이동", enabled = index > 0) { onMove(-1) }
            CardIconButton(DraftMark.ArrowDown, "${index + 1}번째 동작 아래로 이동", enabled = index < total - 1) { onMove(1) }
            CardIconButton(DraftMark.Strike, "${index + 1}번째 동작 삭제", tint = T.InkMuted, onClick = onRemove)
        }
    }
}

/** 접힌 상태에서도 실행 값과 최대 대기 시간을 빠뜨리지 않고 보여준다. */
internal fun actionSummary(step: ActionStep): String = when (step) {
    is ActionStep.Run -> step.command.label
    is ActionStep.Wait -> "${formatDuration(step.seconds)} 대기"
    is ActionStep.WaitUntil -> "${describe(step.condition)}까지 대기 · 최대 ${formatDuration(step.timeoutSeconds)}"
    is ActionStep.Navigate -> "지도 안내 — ${step.destinationName.ifBlank { "목적지 미입력" }}"
    is ActionStep.SetStealthCharging -> "스텔스 충전 1회 ${if (step.enabled) "켜기" else "끄기"}"
}

/**
 * 카드 헤더용 아이콘 버튼.
 * 아이콘은 24dp지만 터치 타깃은 48dp를 보장한다.
 * ConditionEditor의 카드 헤더도 같은 패턴을 쓴다.
 */
@Composable
internal fun CardIconButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    tint: Color = T.InkMuted,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(Space.xxl)
            .clip(RoundedCornerShape(Radius.pill))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else T.InkFaint,
            modifier = Modifier.size(Space.lg),
        )
    }
}

/**
 * 이 걸음에 조절할 파라미터가 있으면 컨트롤을, 없으면 null을 준다.
 * 템플릿 종류마다 필요한 컨트롤이 달라 여기서 분기한다.
 */
@Composable
private fun parameterEditor(
    step: ActionStep,
    template: CommandTemplate?,
): (@Composable ((ActionStep) -> Unit) -> Unit)? = when {

    step is ActionStep.Wait -> { onChange ->
        Column {
            Text("대기 시간", style = MaterialTheme.typography.labelMedium, color = T.InkMuted)
            Spacer(Modifier.height(Space.sm))
            NumberStepper(
                value = step.seconds.toDouble(),
                min = 1.0, max = 3600.0, step = 1.0, unit = "초",
                onChange = { onChange(ActionStep.Wait(it.toInt())) },
            )
        }
    }

    step is ActionStep.WaitUntil -> { onChange ->
        Column {
            Text(
                text = "시간이 지나면 다음 동작으로 넘어가요.",
                style = MaterialTheme.typography.bodySmall,
                color = T.InkMuted,
            )
            Spacer(Modifier.height(Space.sm))
            Text("최대 대기", style = MaterialTheme.typography.labelMedium, color = T.InkMuted)
            NumberStepper(
                value = step.timeoutSeconds.toDouble(),
                min = 60.0, max = 1800.0, step = 60.0, unit = "초",
                onChange = { onChange(step.copy(timeoutSeconds = it.toInt())) },
            )
        }
    }

    step is ActionStep.SetStealthCharging -> { onChange ->
        ChipRow(
            options = listOf(true, false),
            selected = step.enabled,
            label = { if (it) "켜기" else "끄기" },
            onSelect = { onChange(ActionStep.SetStealthCharging(it)) },
        )
    }

    step is ActionStep.Run && template is CommandTemplate.SeatLevel -> { onChange ->
        val command = step.command
        val seat = seatOf(command)
        val level = levelOf(command)
        Column {
            // 대상 위치와 실행 값을 같은 버튼 묶음으로 오해하지 않게 구역을 나눈다.
            Text("좌석", style = MaterialTheme.typography.labelMedium, color = T.InkMuted)
            Spacer(Modifier.height(Space.sm))
            SeatChoiceGrid(template.seats, seat) { selected ->
                onChange(ActionStep.Run(template.build(selected, level)))
            }
            Spacer(Modifier.height(Space.lg))
            Text("작동 단계", style = MaterialTheme.typography.labelMedium, color = T.InkMuted)
            Spacer(Modifier.height(Space.sm))
            ChipRow(
                options = Level.entries,
                columns = 4,
                selected = level,
                label = { if (it == Level.OFF) "끄기" else "${it.label}단" },
                onSelect = { onChange(ActionStep.Run(template.build(seat, it))) },
            )
        }
    }

    step is ActionStep.Run && template is CommandTemplate.Number -> { onChange ->
        NumberStepper(
            value = numberOf(step.command) ?: template.min,
            min = template.min,
            max = template.max,
            step = template.step,
            unit = template.unit,
            onChange = { onChange(ActionStep.Run(template.build(it))) },
        )
    }

    step is ActionStep.Run && template is CommandTemplate.Toggle -> { onChange ->
        ChipRow(
            options = listOf(true, false),
            selected = step.command == template.build(true),
            label = { if (it) "켜기" else "끄기" },
            onSelect = { onChange(ActionStep.Run(template.build(it))) },
        )
    }

    step is ActionStep.Run && template is CommandTemplate.Choice -> { onChange ->
        ChipRow(
            options = template.options,
            selected = template.options.firstOrNull { it.second == step.command },
            label = { it.first },
            onSelect = { onChange(ActionStep.Run(it.second)) },
        )
    }

    step is ActionStep.Navigate -> { onChange ->
        val context = LocalContext.current
        Column {
            DraftField(
                value = step.destinationName,
                onValueChange = { onChange(step.copy(destinationName = it)) },
                label = "목적지 이름 (예: 회사)",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Space.sm))
            DraftField(
                value = step.address,
                onValueChange = { onChange(step.copy(address = it)) },
                label = "주소 (예: 성남시 분당구 판교역로 152)",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // ADB를 쓸 수 없는 경우에도 백그라운드 실행을 유지할 권한을 안내한다.
            // 설정에서 허용하고 돌아오면 경고가 바로 사라지도록 복귀 때마다 다시 읽는다
            val hasOverlay = rememberOnResume { Settings.canDrawOverlays(context) }
            if (!hasOverlay) {
                Spacer(Modifier.height(Space.sm))
                Text(
                    text = "ADB를 사용할 수 없을 때는 \"다른 앱 위에 표시\" 권한이 필요해요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = T.WarnText,
                )
                Spacer(Modifier.height(Space.sm))
                TButton("권한 허용하러 가기", ButtonTone.Secondary, fillWidth = false, small = true) {
                    openOverlayPermissionSettings(context)
                }
            }
        }
    }

    else -> null
}


/** 좌석별 한 행을 선택하며 좌석 아이콘과 현재 선택 표시를 유지한다. */
@Composable
private fun SeatChoiceGrid(
    seats: List<SeatPosition>,
    selected: SeatPosition,
    onSelect: (SeatPosition) -> Unit,
) {
    Column {
        seats.forEach { seat ->
            val active = seat == selected
            Row(
                modifier = Modifier.fillMaxWidth()
                    .selectable(selected = active, role = Role.RadioButton) { onSelect(seat) }
                    .defaultMinSize(minHeight = Space.xxl)
                    .padding(horizontal = Space.sm, vertical = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                Icon(Icons.Rounded.AirlineSeatReclineNormal, contentDescription = null,
                    tint = if (active) T.Electric else T.InkMuted, modifier = Modifier.size(Space.lg))
                Text(seat.label, style = MaterialTheme.typography.bodyLarge,
                    color = T.Ink, modifier = Modifier.weight(1f))
                if (active) Icon(Icons.Rounded.Check, contentDescription = null,
                    tint = T.Electric, modifier = Modifier.size(Space.lg))
            }
        }
    }
}
// 명령에서 현재 값을 되읽는다. 편집기가 상태를 따로 들고 있지 않게 하려는 목적
private fun seatOf(command: VehicleCommand): SeatPosition = when (command) {
    is VehicleCommand.SetSeatCooler -> command.seat
    is VehicleCommand.SetSeatHeater -> command.seat
    else -> SeatPosition.FRONT_LEFT
}

private fun levelOf(command: VehicleCommand): Level = when (command) {
    is VehicleCommand.SetSeatCooler -> command.level
    is VehicleCommand.SetSeatHeater -> command.level
    else -> Level.MEDIUM
}

private fun numberOf(command: VehicleCommand): Double? = when (command) {
    is VehicleCommand.SetTemperature -> command.celsius
    is VehicleCommand.SetChargeLimit -> command.percent.toDouble()
    else -> null
}

/** 저장된 명령이 어느 템플릿에서 왔는지 되찾는다 (편집 컨트롤을 고르기 위해) */
fun templateFor(
    command: VehicleCommand,
    catalog: List<CommandTemplate>,
): CommandTemplate? = catalog.firstOrNull { template ->
    when (template) {
        is CommandTemplate.Simple -> template.command == command
        is CommandTemplate.Toggle ->
            template.build(true) == command || template.build(false) == command
        is CommandTemplate.SeatLevel ->
            template.seats.any { seat ->
                Level.entries.any { level -> template.build(seat, level) == command }
            }
        is CommandTemplate.Number -> numberOf(command)?.let { template.build(it) == command } == true
        is CommandTemplate.Choice -> template.options.any { it.second == command }
    }
}
