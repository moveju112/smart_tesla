package com.wemade.teslamacro.feature.macro

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wemade.teslamacro.domain.macro.ActionStep
import com.wemade.teslamacro.domain.macro.MacroLogEntry
import com.wemade.teslamacro.domain.macro.MacroProgress
import com.wemade.teslamacro.domain.macro.MacroRule
import com.wemade.teslamacro.domain.macro.describe
import com.wemade.teslamacro.domain.macro.describeRule
import com.wemade.teslamacro.domain.macro.formatDuration
import com.wemade.teslamacro.ui.component.ButtonTone
import com.wemade.teslamacro.ui.component.EmptyState
import com.wemade.teslamacro.ui.component.SectionHeader
import com.wemade.teslamacro.ui.component.StatusPill
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.ui.component.TCard
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 매크로 목록. 카드 하나가 매크로 하나다.
 *
 * 넓은 화면에서는 2단으로 깔린다 — 한 단으로 늘이면 카드 하나가 화면을 다 먹어
 * 매크로 두 개를 나란히 비교할 수 없다.
 */
@Composable
fun MacroListScreen(
    rules: List<MacroRule>,
    runningIds: Set<String>,
    progress: Map<String, MacroProgress>,
    log: List<MacroLogEntry>,
    onToggle: (String, Boolean) -> Unit,
    onRunNow: (MacroRule) -> Unit,
    onStopAll: () -> Unit,
    onEdit: (MacroRule) -> Unit,
    onDuplicate: (MacroRule) -> Unit,
    onDelete: (MacroRule) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 420.dp),
        modifier = modifier.fillMaxSize().padding(horizontal = Space.lg),
        contentPadding = PaddingValues(vertical = Space.lg),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("매크로", style = MaterialTheme.typography.headlineMedium, color = T.Ink)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (runningIds.isNotEmpty()) {
                        TButton(
                            text = "실행 중단",
                            tone = ButtonTone.Danger,
                            fillWidth = false,
                            small = true,
                            onClick = onStopAll,
                        )
                    }
                    TButton(
                        text = "새 매크로",
                        fillWidth = false,
                        small = true,
                        icon = Icons.Rounded.Add,
                        onClick = onCreate,
                    )
                }
            }
        }

        if (rules.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(
                    title = "아직 매크로가 없어요",
                    description = "탑승을 감지해 통풍을 켜는 식의 자동화를 만들 수 있어요.",
                    actionLabel = "첫 매크로 만들기",
                    onAction = onCreate,
                )
            }
        }

        items(rules, key = { it.id }) { rule ->
            MacroCard(
                rule = rule,
                isRunning = rule.id in runningIds,
                progress = progress[rule.id],
                onToggle = { onToggle(rule.id, it) },
                onRunNow = { onRunNow(rule) },
                onEdit = { onEdit(rule) },
                onDuplicate = { onDuplicate(rule) },
                onDelete = { onDelete(rule) },
            )
        }

        item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("실행 기록") }
        if (log.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "아직 실행된 매크로가 없어요. 조건이 맞으면 여기에 기록이 쌓여요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = T.InkFaint,
                )
            }
        } else {
            items(
                items = log.asReversed().take(20),
                span = { GridItemSpan(maxLineSpan) },
            ) { entry -> LogRow(entry) }
        }
    }
}

@Composable
private fun MacroCard(
    rule: MacroRule,
    isRunning: Boolean,
    progress: MacroProgress?,
    onToggle: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    // 카드 자체가 편집 진입점이다. 편집 버튼을 따로 두면 카드가 버튼 창고가 된다
    TCard(outlined = isRunning, onClick = onEdit) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = rule.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (rule.enabled) T.Ink else T.InkFaint,
                    )
                    if (isRunning) {
                        Spacer(Modifier.width(Space.sm))
                        StatusPill(text = runningLabel(progress), color = T.Electric)
                    }
                }
                Text(
                    text = describeRule(rule),
                    style = MaterialTheme.typography.bodySmall,
                    color = T.InkMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Space.xs),
                )
            }
            Switch(
                checked = rule.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = T.Ink,
                    checkedTrackColor = T.Electric,
                    checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = T.InkFaint,
                    uncheckedTrackColor = T.Slate,
                    uncheckedBorderColor = Color.Transparent,
                ),
            )
        }

        Spacer(Modifier.height(Space.md))

        // 동작 순서를 나열하되, 실행 중이 아니면 4줄에서 접는다 — 카드는 요약이지 명세서가 아니다
        val visible = if (isRunning) rule.actions.size else minOf(rule.actions.size, MAX_STEP_LINES)
        rule.actions.take(visible).forEachIndexed { index, step ->
            val isCurrent = isRunning && progress?.stepIndex == index
            Row(modifier = Modifier.padding(vertical = 3.dp)) {
                Text(
                    text = if (isCurrent) "▶" else "${index + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isCurrent) T.Electric else T.InkFaint,
                    modifier = Modifier.width(20.dp),
                )
                Text(
                    text = when (step) {
                        is ActionStep.Run -> step.command.label
                        is ActionStep.Wait -> "${formatDuration(step.seconds)} 대기"
                        is ActionStep.WaitUntil ->
                            "${describe(step.condition)}까지 대기 (최대 ${formatDuration(step.timeoutSeconds)})"
                        is ActionStep.Navigate -> "${step.destinationName} 안내 시작"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        isCurrent -> T.Ink
                        step is ActionStep.Run -> T.InkMuted
                        else -> T.InkFaint
                    },
                )
            }
        }
        if (rule.actions.size > visible) {
            Text(
                text = "외 ${rule.actions.size - visible}개 동작",
                style = MaterialTheme.typography.labelSmall,
                color = T.InkFaint,
                modifier = Modifier.padding(start = 20.dp, top = 3.dp),
            )
        }

        Spacer(Modifier.height(Space.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TButton(
                text = "실행",
                tone = ButtonTone.Secondary,
                fillWidth = false,
                small = true,
                icon = Icons.Rounded.PlayArrow,
                onClick = onRunNow,
            )
            Spacer(Modifier.width(Space.sm))
            TButton("복제", ButtonTone.Ghost, fillWidth = false, small = true, onClick = onDuplicate)
            Spacer(Modifier.width(Space.sm))
            // 삭제는 실수 방지로 두 번 탭 — 다이얼로그까지 띄울 일은 아니다
            var confirmDelete by remember(rule.id) { mutableStateOf(false) }
            LaunchedEffect(confirmDelete) {
                if (confirmDelete) {
                    delay(3_000)
                    confirmDelete = false
                }
            }
            TButton(
                text = if (confirmDelete) "한 번 더 누르면 삭제" else "삭제",
                tone = if (confirmDelete) ButtonTone.Danger else ButtonTone.Ghost,
                fillWidth = false,
                small = true,
            ) {
                if (confirmDelete) onDelete() else confirmDelete = true
            }
            Spacer(Modifier.weight(1f))
            // 카드 탭이 편집으로 간다는 힌트. 버튼 대신 관례적인 꺾쇠 하나
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = "편집",
                tint = T.InkFaint,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 접기 전 보여줄 동작 줄 수 */
private const val MAX_STEP_LINES = 4

@Composable
private fun LogRow(entry: MacroLogEntry) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = timeFormat.format(Date(entry.timestampMillis)),
            style = MaterialTheme.typography.labelSmall,
            color = T.InkFaint,
            modifier = Modifier.padding(end = Space.sm),
        )
        Text(
            text = "${entry.ruleName} — ${entry.message}",
            style = MaterialTheme.typography.bodySmall,
            color = if (entry.isError) T.Danger else T.InkMuted,
        )
    }
}

/**
 * "3/5 · 4분 12초" 처럼 진행 상황을 한 조각으로 만든다.
 * 대기 중이면 남은 시간이 1초마다 줄어드는 걸 보여줘야 멈춘 게 아님을 안다.
 */
@Composable
private fun runningLabel(progress: MacroProgress?): String {
    if (progress == null) return "실행 중"

    val step = "${progress.stepIndex + 1}/${progress.totalSteps}"
    val endsAt = progress.waitEndsAtMillis ?: return step

    // 대기가 끝날 때까지 초 단위로 갱신한다
    var remaining by remember(endsAt) {
        mutableIntStateOf(progress.remainingSeconds(System.currentTimeMillis()) ?: 0)
    }
    LaunchedEffect(endsAt) {
        while (remaining > 0) {
            delay(1_000)
            remaining = progress.remainingSeconds(System.currentTimeMillis()) ?: 0
        }
    }
    return "$step · ${formatDuration(remaining)} 남음"
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
