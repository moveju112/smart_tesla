package com.wemade.teslamacro.feature.macro

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import com.wemade.teslamacro.domain.macro.MacroProgress
import com.wemade.teslamacro.domain.macro.MacroRule
import com.wemade.teslamacro.domain.macro.formatDuration
import com.wemade.teslamacro.ui.component.ButtonTone
import com.wemade.teslamacro.ui.component.DraftMark
import com.wemade.teslamacro.ui.component.DraftToggle
import com.wemade.teslamacro.ui.component.EmptyState
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.ui.component.TCard
import com.wemade.teslamacro.ui.layout.LocalPane
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T
import kotlinx.coroutines.delay

/** 이름 중심의 카드에서 매크로를 편집하고 자동 실행 여부를 선택한다. */
@Composable
fun MacroListScreen(
    rules: List<MacroRule>,
    runningIds: Set<String>,
    progress: Map<String, MacroProgress>,
    onToggle: (String, Boolean) -> Unit,
    onStopAll: () -> Unit,
    onEdit: (MacroRule) -> Unit,
    onDuplicate: (MacroRule) -> Unit,
    onDelete: (MacroRule) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 큰 글씨에서는 카드 폭을 확보하고, 기본 휴대폰은 두 열로 공간을 활용한다.
    val columns = if (LocalDensity.current.fontScale >= 1.3f) {
        LocalPane.current.columns
    } else {
        LocalPane.current.columns.coerceAtLeast(2)
    }
    Column(modifier = modifier.fillMaxSize()) {
        // 추가 버튼을 요약 옆에 두어 하단 고정 버튼이 목록을 가리지 않게 한다.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.md, vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "매크로 ${rules.size}개 · 사용 중 ${rules.count { it.enabled }}개",
                style = MaterialTheme.typography.bodySmall,
                color = T.InkMuted,
                modifier = Modifier.weight(1f),
            )
            TButton(
                text = "추가",
                icon = DraftMark.Add,
                fillWidth = false,
                small = true,
                onClick = onCreate,
            )
        }

        if (runningIds.isNotEmpty()) {
            TButton(
                text = "실행 중단",
                tone = ButtonTone.Danger,
                small = true,
                modifier = Modifier.padding(horizontal = Space.md, vertical = Space.xs),
                onClick = onStopAll,
            )
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

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = Space.md, vertical = Space.sm),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            items(rules, key = { it.id }) { rule ->
                MacroCard(
                    rule = rule,
                    isRunning = rule.id in runningIds,
                    progress = progress[rule.id],
                    onToggle = { onToggle(rule.id, it) },
                    onEdit = { onEdit(rule) },
                    onDuplicate = { onDuplicate(rule) },
                    onDelete = { onDelete(rule) },
                )
            }
        }
    }
}

/** 조건·동작은 편집 화면에 두고 카드에는 이름과 자동 실행 토글만 남긴다. */
@Composable
private fun MacroCard(
    rule: MacroRule,
    isRunning: Boolean,
    progress: MacroProgress?,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    TCard(onClick = onEdit, outlined = isRunning) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Space.xxl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = rule.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = T.Ink,
                    minLines = 2,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            RowActions(rule = rule, onDuplicate = onDuplicate, onDelete = onDelete)
        }
        if (isRunning) {
            Text(
                text = "실행 중 · ${runningLabel(progress)}",
                style = MaterialTheme.typography.labelSmall,
                color = T.Electric,
                modifier = Modifier.padding(bottom = Space.sm),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DraftToggle(
                checked = rule.enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.semantics { contentDescription = "${rule.name} 자동 실행" },
            )
        }
    }
}

/** 복제·삭제는 더보기 메뉴로 접는다. */
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
        IconButton(
            onClick = { menuOpen = true },
            modifier = Modifier.size(Space.xxl),
        ) {
            Icon(
                imageVector = DraftMark.More,
                contentDescription = "${rule.name} 복제·삭제 메뉴",
                tint = T.InkMuted,
                modifier = Modifier.size(Space.lg),
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
