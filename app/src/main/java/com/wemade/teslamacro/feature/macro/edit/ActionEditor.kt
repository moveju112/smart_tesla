package com.wemade.teslamacro.feature.macro.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wemade.teslamacro.domain.command.CommandTemplate
import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.domain.macro.ActionStep
import com.wemade.teslamacro.domain.macro.describe
import com.wemade.teslamacro.domain.macro.formatDuration
import com.wemade.teslamacro.domain.model.Level
import com.wemade.teslamacro.domain.model.SeatPosition
import com.wemade.teslamacro.ui.component.ButtonTone
import com.wemade.teslamacro.ui.component.ChipRow
import com.wemade.teslamacro.ui.component.NumberStepper
import com.wemade.teslamacro.ui.component.rememberOnResume
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.ui.component.TCard
import com.wemade.teslamacro.ui.theme.Radius
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

/**
 * 동작 한 걸음을 편집한다.
 *
 * 순서가 곧 실행 순서라 위/아래 이동을 카드 안에 둔다.
 * 드래그 정렬은 흔들리는 차 안에서 실패하기 쉬워 버튼으로 갔다.
 */
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
) {
    TCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${index + 1}",
                style = MaterialTheme.typography.titleMedium,
                color = T.InkFaint,
                modifier = Modifier.width(28.dp),
            )
            Text(
                text = when (step) {
                    is ActionStep.Run -> step.command.label
                    is ActionStep.Wait -> "${formatDuration(step.seconds)} 대기"
                    is ActionStep.WaitUntil -> "${describe(step.condition)}까지 대기"
                    is ActionStep.Navigate ->
                        "지도 안내 — ${step.destinationName.ifBlank { "목적지 미입력" }}"
                },
                style = MaterialTheme.typography.titleMedium,
                color = if (step is ActionStep.Run) T.Ink else T.InkMuted,
                modifier = Modifier.weight(1f),
            )
            // 글자 글리프(▲▼) 대신 벡터 아이콘 — 접근성 설명과 44dp 터치 타깃을 함께 확보한다
            CardIconButton(Icons.Rounded.KeyboardArrowUp, "위로", enabled = index > 0) { onMove(-1) }
            CardIconButton(Icons.Rounded.KeyboardArrowDown, "아래로", enabled = index < total - 1) { onMove(1) }
            // 삭제는 파괴적 동작 — Danger 색으로 드러낸다
            CardIconButton(Icons.Rounded.Delete, "삭제", tint = T.Danger, onClick = onRemove)
        }

        val editor = parameterEditor(step, template)
        if (editor != null) {
            Spacer(Modifier.height(Space.md))
            editor(onChange)
        }
    }
}

/**
 * 카드 헤더용 아이콘 버튼.
 * 아이콘은 20dp지만 터치 타깃은 44dp를 보장한다 — 흔들리는 차 안에서 오탭을 줄인다.
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
            .size(44.dp)
            .clip(RoundedCornerShape(Radius.pill))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else T.InkFaint,
            modifier = Modifier.size(20.dp),
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
            // 자주 쓰는 값은 눌러서 고르고, 그 사이 값은 스테퍼로 미세 조정한다
            ChipRow(
                options = listOf(3, 10, 30, 60, 300, 600),
                selected = step.seconds,
                label = { formatDuration(it) },
                onSelect = { onChange(ActionStep.Wait(it)) },
            )
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
                text = "조건이 맞을 때까지 기다려요.\n시간이 지나면 포기하고 다음으로 넘어가요.",
                style = MaterialTheme.typography.bodySmall,
                color = T.InkFaint,
            )
            Spacer(Modifier.height(Space.sm))
            Text("최대 대기", style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
            ChipRow(
                options = listOf(60, 300, 600, 1800),
                selected = step.timeoutSeconds,
                label = { formatDuration(it) },
                onSelect = { onChange(step.copy(timeoutSeconds = it)) },
            )
        }
    }

    step is ActionStep.Run && template is CommandTemplate.SeatLevel -> { onChange ->
        val command = step.command
        val seat = seatOf(command)
        val level = levelOf(command)
        Column {
            ChipRow(
                options = template.seats,
                selected = seat,
                label = { it.label },
                onSelect = { onChange(ActionStep.Run(template.build(it, level))) },
            )
            Spacer(Modifier.height(Space.sm))
            ChipRow(
                options = Level.entries,
                selected = level,
                label = { it.label },
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
            OutlinedTextField(
                value = step.destinationName,
                onValueChange = { onChange(step.copy(destinationName = it)) },
                label = { Text("목적지 이름 (예: 회사)") },
                singleLine = true,
                colors = editorFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Space.sm))
            OutlinedTextField(
                value = step.address,
                onValueChange = { onChange(step.copy(address = it)) },
                label = { Text("주소 (예: 성남시 분당구 판교역로 152)") },
                singleLine = true,
                colors = editorFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            // 백그라운드에서 지도를 띄우려면 이 권한이 필수다. 여기서 바로 받는다.
            // 설정에서 허용하고 돌아오면 경고가 바로 사라지도록 복귀 때마다 다시 읽는다
            val hasOverlay = rememberOnResume { Settings.canDrawOverlays(context) }
            if (!hasOverlay) {
                Spacer(Modifier.height(Space.sm))
                Text(
                    text = "자동으로 지도를 띄우려면 \"다른 앱 위에 표시\" 권한이 필요해요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = T.WarnText,
                )
                Spacer(Modifier.height(Space.sm))
                TButton("권한 허용하러 가기", ButtonTone.Secondary, fillWidth = false, small = true) {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
    }

    else -> null
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
