package com.wemade.teslamacro.feature.macro

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wemade.teslamacro.domain.macro.MacroLogEntry
import com.wemade.teslamacro.domain.macro.MacroProgress
import com.wemade.teslamacro.domain.macro.MacroRule
import com.wemade.teslamacro.domain.macro.describeRule
import com.wemade.teslamacro.domain.macro.formatDuration
import com.wemade.teslamacro.ui.component.ButtonTone
import com.wemade.teslamacro.ui.component.DraftMark
import com.wemade.teslamacro.ui.component.DraftToggle
import com.wemade.teslamacro.ui.component.EmptyState
import com.wemade.teslamacro.ui.component.Hairline
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.ui.component.TCard
import com.wemade.teslamacro.ui.theme.CalloutNumberStyle
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 이름과 발동 조건을 먼저 읽고, 필요한 매크로만 편집하거나 실행한다. */
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
    // 이름별 마지막 실행 시각. 목록에서 진짜 궁금한 건 "언제 마지막으로 뛰었나"다
    val lastRunByName = remember(log) {
        log.groupBy { it.ruleName }.mapValues { (_, entries) -> entries.maxOf { it.timestampMillis } }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 생성은 하단에 고정하고 상단에는 현재 자동화 상태만 남긴다.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg, vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "매크로 ${rules.size}개 · 사용 중 ${rules.count { it.enabled }}개",
                style = MaterialTheme.typography.bodyMedium,
                color = T.InkMuted,
                modifier = Modifier.weight(1f),
            )
            if (runningIds.isNotEmpty()) {
                TButton(
                    text = "실행 중단",
                    tone = ButtonTone.Danger,
                    fillWidth = false,
                    small = true,
                    onClick = onStopAll,
                )
                Spacer(Modifier.width(Space.sm))
            }
        }

        if (rules.isEmpty()) {
            EmptyState(
                title = "반복하는 차량 동작을 자동으로",
                description = "탑승을 감지해 통풍을 켜는 식의 자동화를 만들 수 있어요.",
                actionLabel = "매크로 만들기",
                onAction = onCreate,
                modifier = Modifier.padding(horizontal = Space.lg),
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = Space.md, vertical = Space.sm),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            items(rules, key = { it.id }) { rule ->
                MacroRow(
                    rule = rule,
                    isRunning = rule.id in runningIds,
                    lastRunMillis = lastRunByName[rule.name],
                    progress = progress[rule.id],
                    onToggle = { onToggle(rule.id, it) },
                    onRunNow = { onRunNow(rule) },
                    onEdit = { onEdit(rule) },
                    onDuplicate = { onDuplicate(rule) },
                    onDelete = { onDelete(rule) },
                )
            }
            item { RevisionBlock(log) }
        }
        TButton(
            text = "새 매크로 만들기",
            icon = DraftMark.Add,
            modifier = Modifier.padding(horizontal = Space.md, vertical = Space.sm),
            onClick = onCreate,
        )
    }
}

/** 휴대폰에서 이름·조건·동작을 읽고 하단에서 바로 실행하거나 사용 여부를 바꾼다. */
@Composable
private fun MacroRow(
    rule: MacroRule,
    isRunning: Boolean,
    lastRunMillis: Long?,
    progress: MacroProgress?,
    onToggle: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    TCard(onClick = onEdit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Space.xxl)
                .padding(vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = Space.sm)) {
                Text(
                    text = rule.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (rule.enabled) T.Ink else T.InkFaint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(Space.sm))
                Text(
                    text = "언제 · ${describeRule(rule)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = T.InkMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(Space.xs))
                Text(
                    text = "동작 · ${rule.summary}",
                    style = MaterialTheme.typography.bodySmall,
                    color = T.InkMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            RowActions(rule = rule, onDuplicate = onDuplicate, onDelete = onDelete)
        }
        Text(
            text = if (isRunning) "실행 중 · ${runningLabel(progress)}"
                else "최근 실행 ${lastRunLabel(lastRunMillis)} · 눌러서 수정",
            style = MaterialTheme.typography.labelSmall,
            color = if (isRunning) T.Electric else T.InkFaint,
            modifier = Modifier.padding(bottom = Space.sm),
        )
        Hairline()
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            DraftToggle(
                checked = rule.enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.semantics { contentDescription = "${rule.name} 자동 실행" },
            )
            Spacer(Modifier.weight(1f))
            TButton(
                text = "지금 실행",
                icon = DraftMark.Run,
                tone = ButtonTone.Secondary,
                small = true,
                fillWidth = false,
                onClick = onRunNow,
            )
        }
    }
}

/** 행 끝의 실행·더보기. 부가 동작은 ⋯로 접는다 */
@Composable
private fun RowActions(
    rule: MacroRule,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    // 삭제는 실수 방지로 두 번 탭 — 다이얼로그까지 띄울 일은 아니다
    var menuOpen by remember(rule.id) { mutableStateOf(false) }
    var confirmDelete by remember(rule.id) { mutableStateOf(false) }
    LaunchedEffect(confirmDelete) {
        if (confirmDelete) {
            delay(3_000)
            confirmDelete = false
        }
    }
    Box {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable { menuOpen = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = DraftMark.More,
                contentDescription = "복제·삭제 메뉴",
                tint = T.InkMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = {
                menuOpen = false
                confirmDelete = false
            },
        ) {
            DropdownMenuItem(
                text = { Text("복제", color = T.Ink) },
                onClick = {
                    menuOpen = false
                    onDuplicate()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = if (confirmDelete) "삭제 확인" else "삭제",
                        color = T.Danger,
                    )
                },
                onClick = {
                    // 첫 탭은 라벨만 바꾸고 메뉴를 유지 — 확인 탭에서만 실제 삭제
                    if (confirmDelete) {
                        menuOpen = false
                        confirmDelete = false
                        onDelete()
                    } else {
                        confirmDelete = true
                    }
                },
            )
        }
    }
}

/**
 * 개정란 — 도면 하단의 변경 이력.
 *
 * 최근 것이 위로 온다. 도면의 개정란도 최신 개정을 맨 위에 쌓는다.
 * 화면을 많이 먹지 않게 세 줄만 보인다 — 더 필요하면 진단 로그가 전부 들고 있다.
 */
@Composable
private fun RevisionBlock(log: List<MacroLogEntry>) {
    Hairline()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(T.Carbon)
            .padding(horizontal = Space.lg, vertical = Space.sm),
    ) {
        Text(
            text = "최근 실행 기록",
            style = MaterialTheme.typography.labelSmall,
            color = T.InkFaint,
        )
        Spacer(Modifier.height(Space.xs))
        if (log.isEmpty()) {
            Text(
                text = "매크로가 실행되면 결과가 여기에 표시돼요.",
                style = MaterialTheme.typography.bodySmall,
                color = T.InkFaint,
            )
        } else {
            log.asReversed().take(3).forEach { entry ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                    Text(
                        text = timeFormat.format(Date(entry.timestampMillis)),
                        style = CalloutNumberStyle,
                        color = T.InkFaint,
                        modifier = Modifier.padding(end = Space.sm),
                    )
                    Text(
                        text = entry.ruleName,
                        style = MaterialTheme.typography.bodySmall,
                        color = T.InkFaint,
                        maxLines = 1,
                        modifier = Modifier.width(120.dp),
                    )
                    Text(
                        text = entry.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (entry.isError) T.Danger else T.InkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** "마지막 실행 …" 문구. 오래된 건 날짜로, 최근 건 상대 시간으로 */
private fun lastRunLabel(millis: Long?): String {
    if (millis == null) return "없음"
    val elapsed = System.currentTimeMillis() - millis
    val minutes = elapsed / 60_000
    return when {
        minutes < 1 -> "방금"
        minutes < 60 -> "${minutes}분 전"
        minutes < 24 * 60 -> "${minutes / 60}시간 전"
        minutes < 48 * 60 -> "어제"
        // 아주 오래된 값은 날짜 수가 의미 없다 — 자릿수만 늘어나 읽기 방해된다
        minutes < 30 * 24 * 60 -> "${minutes / (24 * 60)}일 전"
        else -> "한참 전"
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
    return "$step · ${formatDuration(remaining)}"
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
