package com.wemade.teslamacro.feature.macro.edit

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import com.wemade.teslamacro.ui.component.Hairline
import com.wemade.teslamacro.ui.theme.Motion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import com.wemade.teslamacro.ui.component.DraftField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.wemade.teslamacro.ui.component.DraftMark
import androidx.compose.ui.text.style.TextAlign
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
import com.wemade.teslamacro.ui.component.ChoiceGrid as ChipRow
import com.wemade.teslamacro.ui.component.EmptyState
import com.wemade.teslamacro.ui.component.PickerList
import com.wemade.teslamacro.ui.component.PickerRow
import com.wemade.teslamacro.ui.component.PickerSheet
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.ui.component.TCard
import com.wemade.teslamacro.ui.component.ToggleRow
import com.wemade.teslamacro.ui.layout.LocalPane
import com.wemade.teslamacro.ui.theme.Radius
import com.wemade.teslamacro.ui.component.SectionTabs
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

private enum class OpenPicker { NONE, TRIGGER, CONDITION, ACTION, WAIT_UNTIL }

/** 탭 번호를 탐색 이력으로 취급하지 않고 열린 선택창 또는 편집 화면만 닫는다. */
internal fun handleEditorBack(pickerOpen: Boolean, closePicker: () -> Unit, closeEditor: () -> Unit) {
    if (pickerOpen) closePicker() else closeEditor()
}

/** 단계 이름은 탭과 본문 제목에 같이 사용한다. */
private val STEPS = listOf("실행 시점", "실행 조건", "실행 순서", "이름과 옵션")

/**
 * 매크로 편집 — 페이지 위저드.
 *
 * **언제 → 조건 → 동작 → 마무리**는 탭으로 이동하고 신규 생성만 이전/다음으로 안내한다.
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
    saveError: String? = null,
) {
    var picker by remember { mutableStateOf(OpenPicker.NONE) }
    var step by rememberSaveable(draft.id) { mutableStateOf(if (draft.isNew) 0 else 2) }
    // 상세 입력은 한 항목만 펼치되 탭 전환·화면 회전 후에도 같은 대상을 편집한다.
    var triggerIndex by rememberSaveable(draft.id) { mutableStateOf(-1) }
    var conditionIndex by rememberSaveable(draft.id) { mutableStateOf(-1) }
    var actionIndex by rememberSaveable(draft.id) { mutableStateOf(-1) }
    val compact = LocalPane.current.isCompact
    val last = step == STEPS.lastIndex

    // 편집 탭은 탐색 이력이 아니다. 선택창만 먼저 닫고 편집에서는 한 번에 목록으로 돌아간다.
    androidx.activity.compose.BackHandler {
        handleEditorBack(picker != OpenPicker.NONE, { picker = OpenPicker.NONE }, onCancel)
    }

    // 본문은 키보드 위까지 스크롤되고 저장 동작은 화면 하단에서 계속 접근할 수 있다.
    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(if (compact) Space.md else Space.lg),
    ) {

        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .size(Space.xxl)
                    .clip(RoundedCornerShape(Radius.pill))
                    .clickable(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = DraftMark.Close,
                    contentDescription = "닫기",
                    tint = T.InkMuted,
                    modifier = Modifier.size(Space.lg),
                )
            }
            Text(
                text = if (draft.isNew) "매크로 만들기" else draft.name,
                style = MaterialTheme.typography.titleMedium,
                color = T.Ink,
                modifier = Modifier.weight(1f).padding(horizontal = Space.sm),
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(Space.sm))
        SectionTabs(
            options = listOf(0, 1, 2, 3),
            selected = step,
            label = { listOf("언제", "조건", "동작", "마무리")[it] },
            onSelect = { step = it },
        )
        Spacer(Modifier.height(Space.md))

        // 본문 — 현재 페이지만. 페이지가 옆으로 밀려 들어와 "넘어간다"는 감각을 준다
        AnimatedContent(
            targetState = step,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                val forward = targetState > initialState
                val enter = slideInHorizontally(Motion.standard()) { full ->
                    if (forward) full / 3 else -full / 3
                } + fadeIn(Motion.quick())
                val exit = slideOutHorizontally(Motion.standard()) { full ->
                    if (forward) -full / 3 else full / 3
                } + fadeOut(Motion.quick())
                enter togetherWith exit
            },
            label = "wizardStep",
        ) { current ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth()) {
                    Text(STEPS[current], style = MaterialTheme.typography.titleMedium, color = T.Ink)
                    Spacer(Modifier.height(Space.md))

                    when (current) {
                        0 -> StepTriggers(draft, onChange, triggerIndex, { triggerIndex = it }) { picker = OpenPicker.TRIGGER }
                        1 -> StepConditions(draft, onChange, conditionIndex, { conditionIndex = it }) { picker = OpenPicker.CONDITION }
                        2 -> StepActions(
                            draft = draft,
                            onChange = onChange,
                            expandedIndex = actionIndex,
                            onExpandedChange = { actionIndex = it },
                            onPickAction = { picker = OpenPicker.ACTION },
                        )
                        else -> StepFinish(draft, onChange, onDelete)
                    }
                    Spacer(Modifier.height(Space.xxl))
                }
            }
        }

        // 저장은 항상 같은 위치에 두고, 새 매크로만 다음 단계 버튼으로 안내한다.
        val nextEnabled = when (step) {
            0 -> draft.triggers.isNotEmpty()
            2 -> draft.actions.isNotEmpty()
            STEPS.lastIndex -> draft.canSave
            else -> true
        }
        // 다음이 막힌 이유를 버튼 위에 바로 알려준다. 버튼만 비활성이면 이유를 모른다
        val blockHint = when {
            !draft.isNew -> draft.blockReason
            nextEnabled -> null
            step == 0 -> "발동 시점을 하나 이상 골라야 다음으로 갈 수 있어요"
            step == 2 -> "실행할 동작을 하나 이상 쌓아야 다음으로 갈 수 있어요"
            else -> draft.blockReason
        }
        // 저장·오류·유효성 사유를 본문 밖에 두어 스크롤 중에도 복구할 수 있다.
        Hairline()
        // 힌트·CTA도 본문과 같은 680dp 폭으로 맞춰 넓은 화면에서 좌우 정렬이 어긋나지 않게 한다
        blockHint?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = T.WarnText,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .padding(top = Space.sm),
            )
        }
        saveError?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = T.Danger,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .padding(top = Space.sm),
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = 680.dp)
                .fillMaxWidth()
                .padding(top = Space.sm + Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            if (draft.isNew && step > 0) {
                TButton("이전", ButtonTone.Secondary, modifier = Modifier.weight(1f)) { step-- }
            }
            if (!last && draft.isNew) {
                TButton("다음", ButtonTone.Secondary, modifier = Modifier.weight(1f),
                    enabled = nextEnabled, onClick = { step++ })
            }
            TButton("저장", modifier = Modifier.weight(1f), enabled = draft.canSave, onClick = onSave)
        }
    }

    when (picker) {
        OpenPicker.TRIGGER -> TriggerPicker(
            onDismiss = { picker = OpenPicker.NONE },
            onPick = {
                triggerIndex = draft.triggers.size
                onChange(draft.addTrigger(it))
                picker = OpenPicker.NONE
            },
        )

        OpenPicker.CONDITION -> ConditionPicker(
            onDismiss = { picker = OpenPicker.NONE },
            onPick = {
                conditionIndex = draft.conditions.size
                onChange(draft.addCondition(it))
                picker = OpenPicker.NONE
            },
        )

        OpenPicker.ACTION -> ActionPicker(
            onDismiss = { picker = OpenPicker.NONE },
            onPick = { template ->
                actionIndex = draft.actions.size
                onChange(draft.addAction(ActionStep.Run(CommandCatalog.defaultCommand(template))))
                picker = OpenPicker.NONE
            },
            onPickNavigate = {
                actionIndex = draft.actions.size
                onChange(draft.addAction(ActionStep.Navigate(destinationName = "", address = "")))
                picker = OpenPicker.NONE
            },
            onPickStealthCharging = {
                actionIndex = draft.actions.size
                onChange(draft.addAction(ActionStep.SetStealthCharging()))
                picker = OpenPicker.NONE
            },
            onPickWait = {
                actionIndex = draft.actions.size
                onChange(draft.addAction(ActionStep.Wait(60)))
                picker = OpenPicker.NONE
            },
            onPickWaitUntil = { picker = OpenPicker.WAIT_UNTIL },
        )

        OpenPicker.WAIT_UNTIL -> ConditionPicker(
            title = "이 조건이 될 때까지 대기",
            onDismiss = { picker = OpenPicker.NONE },
            onPick = {
                actionIndex = draft.actions.size
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
    expandedIndex: Int,
    onExpandedChange: (Int) -> Unit,
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
                expanded = expandedIndex == index,
                onToggle = { onExpandedChange(toggleEditorIndex(expandedIndex, index)) },
                onRemove = {
                    onExpandedChange(editorIndexAfterRemoval(expandedIndex, index))
                    onChange(draft.removeTrigger(index))
                },
            )
        }
        TButton("실행 시점 추가", ButtonTone.Secondary, icon = DraftMark.Add, onClick = onAdd)
    }
}

/** 2/4 — 조건 (선택 사항) */
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
        TButton("조건 추가", ButtonTone.Secondary, icon = DraftMark.Add, onClick = onAdd)
    }
}

/** 3/4 — 실행 동작 */
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
        TButton("동작 추가", ButtonTone.Secondary, icon = DraftMark.Add, onClick = onPickAction)
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
        DraftField(
            value = draft.name,
            onValueChange = { onChange(draft.copy(name = it)) },
            label = "매크로 이름",
            note = "바로가기에도 이 이름을 써요",
            singleLine = true,
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
