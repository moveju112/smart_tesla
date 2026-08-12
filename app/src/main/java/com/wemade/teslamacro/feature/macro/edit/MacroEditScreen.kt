package com.wemade.teslamacro.feature.macro.edit

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wemade.teslamacro.domain.command.CommandCatalog
import com.wemade.teslamacro.domain.command.CommandGroup
import com.wemade.teslamacro.domain.command.CommandTemplate
import com.wemade.teslamacro.domain.macro.ActionStep
import com.wemade.teslamacro.domain.macro.Condition
import com.wemade.teslamacro.domain.macro.Trigger
import com.wemade.teslamacro.domain.model.Signal
import com.wemade.teslamacro.domain.model.SignalKind
import com.wemade.teslamacro.ui.component.ButtonTone
import com.wemade.teslamacro.ui.component.ChipRow
import com.wemade.teslamacro.ui.component.EmptyState
import com.wemade.teslamacro.ui.component.PickerList
import com.wemade.teslamacro.ui.component.PickerRow
import com.wemade.teslamacro.ui.component.PickerSheet
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.ui.component.TCard
import com.wemade.teslamacro.ui.component.ToggleRow
import com.wemade.teslamacro.ui.layout.LocalPane
import com.wemade.teslamacro.ui.theme.Radius
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

private enum class OpenPicker { NONE, TRIGGER, CONDITION, ACTION, WAIT_UNTIL }

/** 위저드 한 페이지의 제목 묶음 */
private data class WizardStep(val title: String, val subtitle: String)

private val STEPS = listOf(
    WizardStep("언제 실행할까요?", "이 사건이 일어나는 순간 발동해요 (하나라도)"),
    WizardStep("어떤 조건이면요?", "모두 만족해야 실행해요 · 없으면 무조건 실행"),
    WizardStep("무엇을 실행할까요?", "위에서 아래로 순서대로 실행해요"),
    WizardStep("마무리", "이름을 정하면 음성 명령으로도 부를 수 있어요"),
)

/**
 * 매크로 편집 — 페이지 위저드.
 *
 * **언제 → 조건 → 실행 → 마무리**를 한 페이지에 하나씩, 이전/다음으로 넘긴다.
 * 한 번에 다 보여주는 방식은 단 사이 구분이 안 돼 폐기했다.
 * 각 페이지는 질문 하나에만 답하면 되니 설명 없이도 만들 수 있다.
 *
 * 트리거와 조건을 분리한 건 UI 취향이 아니라 안전장치다 —
 * 트리거 없이 조건만 있는 매크로는 폴링마다 계속 발동한다.
 */
@Composable
fun MacroEditScreen(
    draft: MacroDraft,
    onChange: (MacroDraft) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var picker by remember { mutableStateOf(OpenPicker.NONE) }
    var step by rememberSaveable { mutableStateOf(0) }
    val compact = LocalPane.current.isCompact
    val last = step == STEPS.lastIndex

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(if (compact) Space.md else Space.lg),
    ) {

        // 상단: 취소 + 진행 표시
        Row(verticalAlignment = Alignment.CenterVertically) {
            TButton("취소", ButtonTone.Ghost, fillWidth = false, onClick = onCancel)
            Spacer(Modifier.weight(1f))
            Text(
                text = "${step + 1} / ${STEPS.size}",
                style = MaterialTheme.typography.labelLarge,
                color = T.InkMuted,
            )
        }
        Spacer(Modifier.height(Space.sm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            repeat(STEPS.size) { index ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .background(
                            if (index <= step) T.Electric else T.Slate,
                            RoundedCornerShape(Radius.pill),
                        ),
                )
            }
        }
        Spacer(Modifier.height(Space.lg))

        // 본문 — 현재 페이지만
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(modifier = Modifier.widthIn(max = 680.dp)) {
                Text(STEPS[step].title, style = MaterialTheme.typography.headlineMedium, color = T.Ink)
                Text(
                    text = STEPS[step].subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = T.InkFaint,
                    modifier = Modifier.padding(top = Space.xs),
                )
                Spacer(Modifier.height(Space.lg))

                when (step) {
                    0 -> StepTriggers(draft, onChange) { picker = OpenPicker.TRIGGER }
                    1 -> StepConditions(draft, onChange) { picker = OpenPicker.CONDITION }
                    2 -> StepActions(
                        draft = draft,
                        onChange = onChange,
                        onPickAction = { picker = OpenPicker.ACTION },
                        onPickWaitUntil = { picker = OpenPicker.WAIT_UNTIL },
                    )
                    else -> StepFinish(draft, onChange, onDelete)
                }
                Spacer(Modifier.height(Space.xxl))
            }
        }

        // 하단: 이전 / 다음(마지막 페이지는 저장)
        val nextEnabled = when (step) {
            0 -> draft.triggers.isNotEmpty()
            2 -> draft.actions.isNotEmpty()
            STEPS.lastIndex -> draft.canSave
            else -> true
        }
        // 다음이 막힌 이유를 버튼 위에 바로 알려준다. 버튼만 비활성이면 이유를 모른다
        val blockHint = when {
            nextEnabled -> null
            step == 0 -> "발동 시점을 하나 이상 골라야 다음으로 갈 수 있어요"
            step == 2 -> "실행할 동작을 하나 이상 쌓아야 다음으로 갈 수 있어요"
            else -> draft.blockReason
        }
        blockHint?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = T.Warn,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = Space.xs),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            if (step > 0) {
                TButton("이전", ButtonTone.Secondary, modifier = Modifier.weight(1f)) { step-- }
            }
            TButton(
                text = if (last) "저장" else "다음",
                modifier = Modifier.weight(2f),
                enabled = nextEnabled,
                onClick = { if (last) onSave() else step++ },
            )
        }
    }

    when (picker) {
        OpenPicker.TRIGGER -> TriggerPicker(
            onDismiss = { picker = OpenPicker.NONE },
            onPick = {
                onChange(draft.addTrigger(it))
                picker = OpenPicker.NONE
            },
        )

        OpenPicker.CONDITION -> ConditionPicker(
            onDismiss = { picker = OpenPicker.NONE },
            onPick = {
                onChange(draft.addCondition(it))
                picker = OpenPicker.NONE
            },
        )

        OpenPicker.ACTION -> ActionPicker(
            onDismiss = { picker = OpenPicker.NONE },
            onPick = { template ->
                onChange(draft.addAction(ActionStep.Run(CommandCatalog.defaultCommand(template))))
                picker = OpenPicker.NONE
            },
            onPickNavigate = {
                onChange(draft.addAction(ActionStep.Navigate(destinationName = "", address = "")))
                picker = OpenPicker.NONE
            },
        )

        OpenPicker.WAIT_UNTIL -> ConditionPicker(
            title = "이 조건이 될 때까지 대기",
            onDismiss = { picker = OpenPicker.NONE },
            onPick = {
                onChange(draft.addAction(ActionStep.WaitUntil(it)))
                picker = OpenPicker.NONE
            },
        )

        OpenPicker.NONE -> Unit
    }
}

/** 1/4 — 발동 시점 */
@Composable
private fun StepTriggers(
    draft: MacroDraft,
    onChange: (MacroDraft) -> Unit,
    onAdd: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        if (draft.triggers.isEmpty()) {
            EmptyState(
                title = "발동 시점이 없어요",
                description = "문이 열릴 때, 정해진 시각 같은 사건을 골라 주세요.",
            )
        }
        draft.triggers.forEachIndexed { index, trigger ->
            TriggerCard(
                trigger = trigger,
                onChange = { onChange(draft.replaceTrigger(index, it)) },
                onRemove = { onChange(draft.removeTrigger(index)) },
            )
        }
        TButton("언제 추가", ButtonTone.Secondary, onClick = onAdd)
    }
}

/** 2/4 — 조건 (선택 사항) */
@Composable
private fun StepConditions(
    draft: MacroDraft,
    onChange: (MacroDraft) -> Unit,
    onAdd: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        if (draft.conditions.isEmpty()) {
            Text(
                text = "조건 없음 — 발동 시점이 오면 항상 실행해요. 이대로 넘어가도 돼요.",
                style = MaterialTheme.typography.bodySmall,
                color = T.InkFaint,
            )
        }
        draft.conditions.forEachIndexed { index, condition ->
            ConditionCard(
                condition = condition,
                onChange = { onChange(draft.replaceCondition(index, it)) },
                onRemove = { onChange(draft.removeCondition(index)) },
            )
        }
        TButton("조건 추가", ButtonTone.Secondary, onClick = onAdd)
    }
}

/** 3/4 — 실행 동작 */
@Composable
private fun StepActions(
    draft: MacroDraft,
    onChange: (MacroDraft) -> Unit,
    onPickAction: () -> Unit,
    onPickWaitUntil: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        if (draft.actions.isEmpty()) {
            EmptyState(
                title = "실행할 동작이 없어요",
                description = "통풍, 공조, 잠금 같은 명령을 순서대로 쌓아 주세요.",
            )
        }
        draft.actions.forEachIndexed { index, step ->
            ActionCard(
                index = index,
                total = draft.actions.size,
                step = step,
                template = (step as? ActionStep.Run)
                    ?.let { templateFor(it.command, CommandCatalog.all) },
                onChange = { onChange(draft.replaceAction(index, it)) },
                onMove = { onChange(draft.moveAction(index, it)) },
                onRemove = { onChange(draft.removeAction(index)) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            TButton("동작 추가", ButtonTone.Secondary, modifier = Modifier.weight(1f), onClick = onPickAction)
            TButton("시간 대기", ButtonTone.Secondary, modifier = Modifier.weight(1f)) {
                onChange(draft.addAction(ActionStep.Wait(60)))
            }
            TButton("조건 대기", ButtonTone.Secondary, modifier = Modifier.weight(1f), onClick = onPickWaitUntil)
        }
    }
}

/** 4/4 — 이름·옵션·삭제. 저장 직전에 한눈에 훑는 페이지다 */
@Composable
private fun StepFinish(
    draft: MacroDraft,
    onChange: (MacroDraft) -> Unit,
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        OutlinedTextField(
            value = draft.name,
            onValueChange = { onChange(draft.copy(name = it)) },
            label = { Text("매크로 이름") },
            supportingText = { Text("이 이름이 곧 음성 명령이에요", color = T.InkFaint) },
            singleLine = true,
            colors = editorFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        TCard {
            ToggleRow(
                title = "매크로 켜기",
                checked = draft.enabled,
                onCheckedChange = { onChange(draft.copy(enabled = it)) },
            )
            Spacer(Modifier.height(Space.md))
            Text("재발동 억제", style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
            Text(
                "한 번 실행하면 이 시간 동안 다시 발동하지 않아요.",
                style = MaterialTheme.typography.bodySmall,
                color = T.InkFaint,
            )
            Spacer(Modifier.height(Space.sm))
            ChipRow(
                options = listOf(60, 300, 600, 1800, 3600),
                selected = draft.cooldownSeconds,
                label = { if (it >= 60) "${it / 60}분" else "${it}초" },
                onSelect = { onChange(draft.copy(cooldownSeconds = it)) },
            )
        }
        // 파괴적 동작은 마지막 페이지 맨 아래에만 둔다
        if (!draft.isNew) {
            TButton("매크로 삭제", ButtonTone.Danger, onClick = onDelete)
        }
    }
}

@Composable
private fun editorFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = T.Electric,
    unfocusedBorderColor = T.Hairline,
    focusedTextColor = T.Ink,
    unfocusedTextColor = T.Ink,
    cursorColor = T.Electric,
    focusedLabelColor = T.Electric,
    unfocusedLabelColor = T.InkFaint,
)

/** 트리거는 "사건"만 고를 수 있다. 상태 신호는 여기 안 나온다 */
@Composable
private fun TriggerPicker(onDismiss: () -> Unit, onPick: (Trigger) -> Unit) {
    val eventSignals = Signal.entries.filter { it.kind == SignalKind.BOOLEAN }

    PickerSheet(title = "언제 — 발동 시점", onDismiss = onDismiss) {
        PickerList(items = eventSignals + listOf(null)) { signal ->
            if (signal == null) {
                Column {
                    PickerRow(
                        label = "정해진 시각",
                        detail = "매일 18:00처럼 시간에 맞춰 발동",
                        onClick = { onPick(Trigger.AtTime(minutesOfDay = 18 * 60)) },
                    )
                    PickerRow(
                        label = "일정 주기",
                        detail = "30분마다처럼 반복해서 확인",
                        onClick = { onPick(Trigger.Every(everyMinutes = 60)) },
                    )
                    PickerRow(
                        label = "호출될 때만",
                        detail = "자동 발동 없음. 음성으로 이름을 부르거나 직접 실행",
                        onClick = { onPick(Trigger.Manual) },
                    )
                    PickerRow(
                        label = "조건이 되면 (항상 감시)",
                        detail = "예: 실내 26~28℃가 \"되는 순간\" 실행. 조건 페이지와 함께 사용",
                        onClick = { onPick(Trigger.Always) },
                    )
                }
            } else {
                PickerRow(
                    label = signal.label,
                    detail = "이 상태가 되는 순간",
                    onClick = { onPick(Trigger.SignalBecomes(signal, to = true)) },
                )
            }
        }
    }
}

/** 목록에 뿌릴 조건 후보 한 줄 */
private data class ConditionChoice(
    val label: String,
    val detail: String,
    val build: () -> Condition,
)

/** 조건은 "상태"다. 차량 신호 전부 + 시간대/요일 */
@Composable
private fun ConditionPicker(
    onDismiss: () -> Unit,
    onPick: (Condition) -> Unit,
    title: String = "조건 — 이럴 때만",
) {
    val choices = Signal.entries.map { signal ->
        ConditionChoice(
            label = signal.label,
            detail = signal.unit?.let { "숫자 · $it" } ?: "상태",
            build = { defaultConditionFor(signal) },
        )
    } + listOf(
        // 시간 관련 조건은 차량 신호가 아니라 목록 아래에 모아둔다
        ConditionChoice("시간대", "예: 22:00~06:00 사이일 때만") {
            Condition.TimeWindow(22 * 60, 6 * 60)
        },
        ConditionChoice("요일", "예: 평일에만") {
            Condition.OnDays(setOf(1, 2, 3, 4, 5))
        },
        ConditionChoice("출발지 근처", "저장한 위치 반경 안에서만 (예: 집 주차장에서 탔을 때)") {
            Condition.NearLocation()
        },
    )

    PickerSheet(title = title, onDismiss = onDismiss) {
        PickerList(items = choices) { choice ->
            PickerRow(
                label = choice.label,
                detail = choice.detail,
                onClick = { onPick(choice.build()) },
            )
        }
    }
}

@Composable
private fun ActionPicker(
    onDismiss: () -> Unit,
    onPick: (CommandTemplate) -> Unit,
    onPickNavigate: () -> Unit,
) {
    var group by remember { mutableStateOf(CommandGroup.CLIMATE) }

    PickerSheet(title = "실행할 동작", onDismiss = onDismiss) {
        Column {
            // 차량 명령이 아닌 태블릿 동작. 그룹 밖 최상단에 둔다
            PickerRow(
                label = "네이버 지도 안내",
                detail = "저장한 주소로 길안내를 자동 시작",
                onClick = onPickNavigate,
            )
            Spacer(Modifier.height(Space.md))
            ChipRow(
                options = CommandGroup.entries,
                selected = group,
                label = { it.label },
                onSelect = { group = it },
            )
            Spacer(Modifier.height(Space.md))
            PickerList(items = CommandCatalog.byGroup[group].orEmpty()) { template ->
                PickerRow(label = template.label, onClick = { onPick(template) })
            }
        }
    }
}

