package com.wemade.teslamacro.feature.macro.edit

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import com.wemade.teslamacro.ui.component.Hairline
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import com.wemade.teslamacro.ui.component.DraftField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.wemade.teslamacro.ui.component.DraftMark
import androidx.compose.ui.unit.dp
import com.wemade.teslamacro.domain.command.CommandCatalog
import com.wemade.teslamacro.domain.command.CommandGroup
import com.wemade.teslamacro.domain.command.CommandTemplate
import com.wemade.teslamacro.domain.macro.ActionStep
import com.wemade.teslamacro.domain.macro.Condition
import com.wemade.teslamacro.domain.macro.ForecastMetric
import com.wemade.teslamacro.domain.macro.Trigger
import com.wemade.teslamacro.domain.model.Signal
import com.wemade.teslamacro.domain.model.SignalKind
import com.wemade.teslamacro.ui.component.ButtonTone
import com.wemade.teslamacro.ui.component.NumberStepper
import com.wemade.teslamacro.ui.component.PickerList
import com.wemade.teslamacro.ui.component.PickerRow
import com.wemade.teslamacro.ui.component.PickerSheet
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.ui.component.ToggleRow
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

private enum class OpenPicker { NONE, TRIGGER, CONDITION, ACTION, WAIT_UNTIL }

private enum class DetailSection { TRIGGER, CONDITION, ACTION }

/** 발동 시점과 추가 조건은 서로 다른 규칙이므로 요약에서도 별도 구역으로 둔다. */
@Composable
fun MacroEditScreen(
    draft: MacroDraft,
    onChange: (MacroDraft) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    saveError: String? = null,
    onDismissSaveError: () -> Unit = {},
) {
    var picker by remember { mutableStateOf(OpenPicker.NONE) }
    var detail by rememberSaveable(draft.id) { mutableStateOf<DetailSection?>(null) }
    var triggerIndex by rememberSaveable(draft.id) { mutableStateOf(-1) }
    var conditionIndex by rememberSaveable(draft.id) { mutableStateOf(-1) }
    var actionIndex by rememberSaveable(draft.id) { mutableStateOf(-1) }
    val scroll = rememberScrollState()
    LaunchedEffect(detail) { scroll.scrollTo(0) }

    PickerSheet(
        title = when (detail) {
            DetailSection.TRIGGER -> "실행 시점 편집"
            DetailSection.CONDITION -> "조건 편집"
            DetailSection.ACTION -> "동작 편집"
            null -> if (draft.isNew) "매크로 만들기" else draft.name.ifBlank { "매크로 편집" }
        },
        onDismiss = { if (detail != null) detail = null else onCancel() },
        modifier = modifier,
        fillHeight = true,
        // 바깥 한 번 탭으로 만들던 매크로가 통째로 사라지지 않게 한다
        dismissOnOutsideTap = false,
        footer = {
            com.wemade.teslamacro.ui.component.ActionFeedback(saveError, onDismiss = onDismissSaveError,
                actionLabel = "다시 저장", onAction = onSave)
            Column(modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth()) {
                draft.blockReason?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = T.WarnText,
                        modifier = Modifier.fillMaxWidth().padding(bottom = Space.sm))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    TButton(
                        if (detail == null) "취소" else "돌아가기",
                        ButtonTone.Ghost,
                        modifier = Modifier.weight(1f),
                    ) { if (detail == null) onCancel() else detail = null }
                    TButton(
                        if (draft.isNew) "저장" else "변경 저장",
                        modifier = Modifier.weight(2f),
                        enabled = draft.canSave,
                        onClick = onSave,
                    )
                }
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                when (detail) {
                    null -> {
                        DraftField(
                            value = draft.name,
                            onValueChange = { onChange(draft.copy(name = it)) },
                            label = "매크로 이름",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        EditorSectionTitle("실행 시점")
                        StepTriggers(draft, onChange, -1, {
                            triggerIndex = it
                            detail = DetailSection.TRIGGER
                        }) { picker = OpenPicker.TRIGGER }
                        Hairline()
                        EditorSectionTitle("실행 조건")
                        StepConditions(draft, onChange, -1, {
                            conditionIndex = it
                            detail = DetailSection.CONDITION
                        }) { picker = OpenPicker.CONDITION }
                        Hairline()
                        EditorSectionTitle("실행 순서")
                        StepActions(draft, onChange, -1, {
                            actionIndex = it
                            detail = DetailSection.ACTION
                        }) { picker = OpenPicker.ACTION }
                        Hairline()
                        EditorSectionTitle("자동 실행과 재발동")
                        FinishOptions(draft, onChange, onDelete)
                    }
                    DetailSection.TRIGGER -> draft.triggers.getOrNull(triggerIndex)?.let { trigger ->
                        TriggerCard(
                            trigger = trigger,
                            onChange = { onChange(draft.replaceTrigger(triggerIndex, it)) },
                            onRemove = {
                                onChange(draft.removeTrigger(triggerIndex))
                                detail = null
                            },
                            expanded = true,
                            onToggle = { detail = null },
                        )
                    }
                    DetailSection.CONDITION -> draft.conditions.getOrNull(conditionIndex)?.let { condition ->
                        ConditionCard(
                            condition = condition,
                            onChange = { onChange(draft.replaceCondition(conditionIndex, it)) },
                            onRemove = {
                                onChange(draft.removeCondition(conditionIndex))
                                detail = null
                            },
                            expanded = true,
                            onToggle = { detail = null },
                        )
                    }
                    DetailSection.ACTION -> draft.actions.getOrNull(actionIndex)?.let { action ->
                        ActionCard(
                            index = actionIndex,
                            total = draft.actions.size,
                            step = action,
                            template = (action as? ActionStep.Run)
                                ?.let { templateFor(it.command, CommandCatalog.all) },
                            onChange = { onChange(draft.replaceAction(actionIndex, it)) },
                            onMove = { offset ->
                                onChange(draft.moveAction(actionIndex, offset))
                                actionIndex += offset
                            },
                            onRemove = {
                                onChange(draft.removeAction(actionIndex))
                                detail = null
                            },
                            expanded = true,
                            onToggle = { detail = null },
                        )
                    }
                }
                Spacer(Modifier.height(Space.sm))
            }
        }
    }
    when (picker) {
        OpenPicker.TRIGGER -> TriggerPicker(
            onDismiss = { picker = OpenPicker.NONE },
            onPick = {
                triggerIndex = draft.triggers.size
                detail = DetailSection.TRIGGER
                onChange(draft.addTrigger(it))
                picker = OpenPicker.NONE
            },
        )

        OpenPicker.CONDITION -> ConditionPicker(
            onDismiss = { picker = OpenPicker.NONE },
            onPick = {
                conditionIndex = draft.conditions.size
                detail = DetailSection.CONDITION
                onChange(draft.addCondition(it))
                picker = OpenPicker.NONE
            },
        )

        OpenPicker.ACTION -> ActionPicker(
            onDismiss = { picker = OpenPicker.NONE },
            onPick = { template ->
                actionIndex = draft.actions.size
                detail = DetailSection.ACTION
                onChange(draft.addAction(ActionStep.Run(CommandCatalog.defaultCommand(template))))
                picker = OpenPicker.NONE
            },
            onPickNavigate = {
                actionIndex = draft.actions.size
                onChange(draft.addAction(ActionStep.Navigate(destinationName = "", address = "")))
                detail = DetailSection.ACTION
                picker = OpenPicker.NONE
            },
            onPickStealthCharging = {
                actionIndex = draft.actions.size
                onChange(draft.addAction(ActionStep.SetStealthCharging()))
                picker = OpenPicker.NONE
                detail = DetailSection.ACTION
            },
            onPickWait = {
                actionIndex = draft.actions.size
                onChange(draft.addAction(ActionStep.Wait(60)))
                picker = OpenPicker.NONE
                detail = DetailSection.ACTION
            },
            onPickWaitUntil = { picker = OpenPicker.WAIT_UNTIL },
        )

        OpenPicker.WAIT_UNTIL -> ConditionPicker(
            title = "이 조건이 될 때까지 대기",
            onDismiss = { picker = OpenPicker.NONE },
            onPick = {
                actionIndex = draft.actions.size
                onChange(draft.addAction(ActionStep.WaitUntil(it)))
                detail = DetailSection.ACTION
                picker = OpenPicker.NONE
            },
        )

        OpenPicker.NONE -> Unit
    }
}


/** 실행 시점 요약 목록과 사건 선택 진입점. */
@Composable
private fun StepTriggers(
    draft: MacroDraft,
    onChange: (MacroDraft) -> Unit,
    expandedIndex: Int,
    onExpandedChange: (Int) -> Unit,
    onAdd: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        if (draft.triggers.isEmpty()) {
            Text("실행 시점을 추가하세요.", style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
        }
        draft.triggers.forEachIndexed { index, trigger ->
            TriggerCard(
                trigger = trigger,
                onChange = { onChange(draft.replaceTrigger(index, it)) },
                expanded = expandedIndex == index,
                onToggle = { onExpandedChange(toggleEditorIndex(expandedIndex, index)) },
                onRemove = {
                    onExpandedChange(editorIndexAfterRemoval(expandedIndex, index))
                    onChange(draft.removeTrigger(index))
                },
            )
        }
        TButton("실행 시점 추가", ButtonTone.Ghost, icon = DraftMark.Add, fillWidth = false, onClick = onAdd)
    }
}

/** 추가 조건 요약 목록과 상태 선택 진입점. */
@Composable
private fun StepConditions(
    draft: MacroDraft,
    onChange: (MacroDraft) -> Unit,
    expandedIndex: Int,
    onExpandedChange: (Int) -> Unit,
    onAdd: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        if (draft.conditions.isEmpty()) {
            Text(
                text = "조건 없이 실행",
                style = MaterialTheme.typography.bodySmall,
                color = T.InkMuted,
            )
        }
        draft.conditions.forEachIndexed { index, condition ->
            ConditionCard(
                condition = condition,
                onChange = { onChange(draft.replaceCondition(index, it)) },
                expanded = expandedIndex == index,
                onToggle = { onExpandedChange(toggleEditorIndex(expandedIndex, index)) },
                onRemove = {
                    onExpandedChange(editorIndexAfterRemoval(expandedIndex, index))
                    onChange(draft.removeCondition(index))
                },
            )
        }
        TButton("조건 추가", ButtonTone.Ghost, icon = DraftMark.Add, fillWidth = false, onClick = onAdd)
    }
}

/** 동작 순서 요약 목록과 동작 추가 진입점. */
@Composable
private fun StepActions(
    draft: MacroDraft,
    onChange: (MacroDraft) -> Unit,
    expandedIndex: Int,
    onExpandedChange: (Int) -> Unit,
    onPickAction: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        if (draft.actions.isEmpty()) {
            Text("실행할 동작을 추가하세요.", style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
        }
        draft.actions.forEachIndexed { index, step ->
            ActionCard(
                index = index,
                total = draft.actions.size,
                step = step,
                template = (step as? ActionStep.Run)
                    ?.let { templateFor(it.command, CommandCatalog.all) },
                onChange = { onChange(draft.replaceAction(index, it)) },
                expanded = expandedIndex == index,
                onToggle = { onExpandedChange(toggleEditorIndex(expandedIndex, index)) },
                onMove = { offset ->
                    val target = index + offset
                    if (target in draft.actions.indices) {
                        onExpandedChange(editorIndexAfterMove(expandedIndex, index, target))
                        onChange(draft.moveAction(index, offset))
                    }
                },
                onRemove = {
                    onExpandedChange(editorIndexAfterRemoval(expandedIndex, index))
                    onChange(draft.removeAction(index))
                },
            )
        }
        // 대기도 동작 추가에서 선택해 목록 하단에 버튼 세 개가 경쟁하지 않게 한다.
        TButton("동작 추가", ButtonTone.Ghost, icon = DraftMark.Add, fillWidth = false, onClick = onPickAction)
    }
}

/** 자동 실행과 재발동 간격을 요약 폼에서 바로 변경한다. */
@Composable
private fun FinishOptions(
    draft: MacroDraft,
    onChange: (MacroDraft) -> Unit,
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        ToggleRow(
            title = "매크로 켜기",
            checked = draft.enabled,
            onCheckedChange = { onChange(draft.copy(enabled = it)) },
        )
        Text("재발동 억제", style = MaterialTheme.typography.labelLarge, color = T.Ink)
        NumberStepper(
            value = draft.cooldownSeconds.toDouble(),
            min = 0.0, max = 3600.0, step = 60.0, unit = "초",
            onChange = { onChange(draft.copy(cooldownSeconds = it.toInt())) },
        )
        if (!draft.isNew) {
            TButton("매크로 삭제", ButtonTone.Danger, fillWidth = false, onClick = onDelete)
        }
    }
}

/** 실행 시점·조건·순서를 구분하는 편집 구역 제목. */
@Composable
private fun EditorSectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = T.Ink)
}

/** 시점 후보를 같은 목록 행으로 묶어 모든 선택지를 한 번에 스크롤한다. */
private data class TriggerChoice(val label: String, val detail: String, val build: () -> Trigger)

/** 트리거는 "사건"만 고를 수 있다. 상태 신호는 여기 안 나온다 */
@Composable
private fun TriggerPicker(onDismiss: () -> Unit, onPick: (Trigger) -> Unit) {
    val choices = Signal.entries.filter { it.kind == SignalKind.BOOLEAN }.map { signal ->
        TriggerChoice(signal.label, "발생 시점과 주행 조건은 추가한 뒤 설정") {
            Trigger.SignalBecomes(signal, to = true)
        }
    } + listOf(
        TriggerChoice("정해진 시각", "매일 18:00처럼 시간에 맞춰 발동") {
            Trigger.AtTime(minutesOfDay = 18 * 60)
        },
        TriggerChoice("일정 주기", "30분마다처럼 반복해서 확인") {
            Trigger.Every(everyMinutes = 60)
        },
        TriggerChoice("호출될 때만", "등록한 바로가기로 실행") { Trigger.Manual },
        TriggerChoice("조건이 되면 (항상 감시)", "조건을 만족하는 순간 실행") { Trigger.Always },
    )
    PickerSheet(title = "발동 시점 추가", onDismiss = onDismiss) {
        PickerList(items = choices) { choice ->
            PickerRow(choice.label, detail = choice.detail, onClick = { onPick(choice.build()) })
        }
    }
}

/** 목록에 뿌릴 조건 후보 한 줄 */
private data class ConditionChoice(
    val label: String,
    val detail: String,
    val build: () -> Condition,
)

/** 예보 조건을 처음 고를 때의 기본 임계값 — 곧바로 말이 되는 값으로 시작한다 */
private fun defaultForecastThreshold(metric: ForecastMetric): Double = when (metric) {
    ForecastMetric.MIN_TEMP -> 0.0      // 영하면 예열
    ForecastMetric.MAX_TEMP -> 30.0     // 더우면 미리 식힘
    ForecastMetric.RAIN_CHANCE -> 60.0  // 비 오면 창문 닫기
}

/** 조건은 "상태"다. 차량 신호 전부 + 시간대/요일 + 오늘 예보 */
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
    ) + ForecastMetric.entries.map { metric ->
        // 차의 외기온은 지금만 말한다. 예보는 앞을 봐서 "미리" 움직이게 해준다
        ConditionChoice(
            label = metric.label,
            detail = "예보 · ${metric.unit} (인터넷 필요)",
            build = { Condition.ForecastInRange(metric, lte = defaultForecastThreshold(metric)) },
        )
    }

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

/** 차량 명령과 대기·기타 동작을 분류해 한 번에 한 종류만 보여준다. */
@Composable
private fun ActionPicker(
    onDismiss: () -> Unit,
    onPick: (CommandTemplate) -> Unit,
    onPickNavigate: () -> Unit,
    onPickStealthCharging: () -> Unit,
    onPickWait: () -> Unit,
    onPickWaitUntil: () -> Unit,
) {
    var group by remember { mutableStateOf<CommandGroup?>(CommandGroup.CLIMATE) }
    var choosingGroup by remember { mutableStateOf(false) }

    PickerSheet(title = "실행할 동작", onDismiss = onDismiss) {
        Column {
            if (choosingGroup) {
                PickerList(items = CommandGroup.entries + listOf(null)) { option ->
                    PickerRow(
                        label = option?.label ?: "대기 · 기타",
                        onClick = {
                            group = option
                            choosingGroup = false
                        },
                    )
                }
            } else {
                TButton(
                    text = "${group?.label ?: "대기 · 기타"} · 분류 변경",
                    tone = ButtonTone.Ghost,
                    fillWidth = false,
                    small = true,
                    onClick = { choosingGroup = true },
                )
                Spacer(Modifier.height(Space.sm))
                if (group == null) {
                    PickerList(items = listOf(
                        "시간 대기" to onPickWait,
                        "조건 대기" to onPickWaitUntil,
                        "네이버 지도 안내" to onPickNavigate,
                        "스텔스 충전 1회" to onPickStealthCharging,
                    )) { (label, onSelect) ->
                        PickerRow(label = label, onClick = onSelect)
                    }
                } else {
                    PickerList(items = CommandCatalog.byGroup[group].orEmpty()) { template ->
                        PickerRow(label = template.label, onClick = { onPick(template) })
                    }
                }
            }
        }
    }
}
