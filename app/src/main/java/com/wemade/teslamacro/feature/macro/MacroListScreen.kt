package com.wemade.teslamacro.feature.macro

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clip
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
import com.wemade.teslamacro.ui.theme.Radius
import com.wemade.teslamacro.ui.layout.LocalPane
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
    // 이름별 마지막 실행 시각. 목록에서 진짜 궁금한 건 "언제 마지막으로 뛰었나"다
    val lastRunByName = remember(log) {
        log.groupBy { it.ruleName }.mapValues { (_, entries) -> entries.maxOf { it.timestampMillis } }
    }

    val compact = LocalPane.current.isCompact

    // 넓으면 목록과 기록을 좌우로 나눈다. 한 단으로 쌓으면 기록이 목록 아래에 묻혀
    // 매크로를 보려면 매번 기록을 지나쳐 스크롤해야 했다
    Row(
        modifier = modifier.fillMaxSize().padding(horizontal = Space.lg),
        horizontalArrangement = Arrangement.spacedBy(Space.lg),
    ) {
        LazyVerticalGrid(
            // 실기기 목록 칸이 약 540dp라 360dp면 한 열로 떨어진다. 두 열이 들어가게 낮춘다
            columns = GridCells.Adaptive(minSize = 250.dp),
            modifier = Modifier.weight(if (compact) 1f else 2f),
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
                    lastRunMillis = lastRunByName[rule.name],
                    progress = progress[rule.id],
                    onToggle = { onToggle(rule.id, it) },
                    onRunNow = { onRunNow(rule) },
                    onEdit = { onEdit(rule) },
                    onDuplicate = { onDuplicate(rule) },
                    onDelete = { onDelete(rule) },
                )
            }

            // 좁은 화면에서는 옆에 둘 자리가 없어 아래로 잇는다
            if (compact) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("실행 기록") }
                if (log.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { EmptyLogNotice() }
                } else {
                    items(
                        items = log.asReversed().take(20),
                        span = { GridItemSpan(maxLineSpan) },
                    ) { entry -> LogRow(entry) }
                }
            }
        }

        if (!compact) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = Space.lg),
            ) {
                item { SectionHeader("실행 기록", topPadding = 0.dp) }
                if (log.isEmpty()) {
                    item { EmptyLogNotice() }
                } else {
                    val recent = log.asReversed().take(40)
                    items(recent.size) { index -> LogRow(recent[index]) }
                }
            }
        }
    }
}

/** 기록이 비었을 때 — 왜 비었는지까지 말해준다 */
@Composable
private fun EmptyLogNotice() {
    Text(
        text = "아직 실행된 매크로가 없어요.\n조건이 맞으면 여기에 기록이 쌓여요.",
        style = MaterialTheme.typography.bodySmall,
        color = T.InkFaint,
    )
}

@Composable
private fun MacroCard(
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
    // 카드 자체가 편집 진입점이다. 편집 버튼을 따로 두면 카드가 버튼 창고가 된다
    TCard(outlined = isRunning, onClick = onEdit) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = rule.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (rule.enabled) T.Ink else T.InkFaint,
                        // 이름이 길어도 실행중 StatusPill이 밀려나지 않게 이름 쪽만 줄인다
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isRunning) {
                        Spacer(Modifier.width(Space.sm))
                        // 옅은 파랑 배경 위 파랑 글자는 대비 미달 — 글자만 진한 파랑으로
                        StatusPill(
                            text = runningLabel(progress),
                            color = T.Electric,
                            textColor = T.ElectricPressed,
                        )
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
                    checkedThumbColor = Color.White,
                    checkedTrackColor = T.Electric,
                    checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = T.InkFaint,
                    uncheckedTrackColor = T.Slate,
                    uncheckedBorderColor = Color.Transparent,
                ),
            )
        }

        Spacer(Modifier.height(Space.md))

        // 단계 목록은 편집 화면 내용이라 카드에서 뺐다. 목록에서 궁금한 건
        // "무엇을 하는가"가 아니라 "제대로 돌고 있는가"다
        Text(
            text = "동작 ${rule.actions.size}개 · ${lastRunLabel(lastRunMillis)}",
            style = MaterialTheme.typography.labelSmall,
            color = T.InkFaint,
            modifier = Modifier.padding(top = Space.sm),
        )

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
            Spacer(Modifier.weight(1f))
            // 복제·삭제는 부가 동작이라 ⋯ 메뉴로 접는다 — 액션 줄은 "실행" 하나로 단순하게
            var menuOpen by remember(rule.id) { mutableStateOf(false) }
            // 삭제는 실수 방지로 두 번 탭 — 다이얼로그까지 띄울 일은 아니다
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
                        .size(44.dp)
                        .clip(RoundedCornerShape(Radius.pill))
                        .clickable { menuOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreHoriz,
                        contentDescription = "복제·삭제 메뉴",
                        tint = T.InkMuted,
                        modifier = Modifier.size(22.dp),
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
            Spacer(Modifier.width(Space.xs))
            // 카드 탭이 편집으로 간다는 힌트. 버튼 대신 관례적인 꺾쇠 하나
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                // 장식용 — 실제 편집 진입은 카드 클릭이라 TalkBack엔 읽히지 않게 한다
                contentDescription = null,
                tint = T.InkFaint,
                modifier = Modifier.size(stepNumberWidth),
            )
        }
    }
}

/** "마지막 실행 …" 문구. 오래된 건 날짜로, 최근 건 상대 시간으로 */
private fun lastRunLabel(millis: Long?): String {
    if (millis == null) return "실행된 적 없음"
    val elapsed = System.currentTimeMillis() - millis
    val minutes = elapsed / 60_000
    return when {
        minutes < 1 -> "방금 실행"
        minutes < 60 -> "${minutes}분 전 실행"
        minutes < 24 * 60 -> "${minutes / 60}시간 전 실행"
        minutes < 48 * 60 -> "어제 실행"
        // 아주 오래된 값은 날짜 수가 의미 없다 — 자릿수만 늘어나 읽기 방해된다
        minutes < 30 * 24 * 60 -> "${minutes / (24 * 60)}일 전 실행"
        else -> "한참 전 실행"
    }
}

/** 스텝 번호·현재 표시 칼럼 고정 폭 — 토큰 밖 값이라 이름 붙여 한곳에서 관리 */
private val stepNumberWidth = 20.dp

@Composable
private fun LogRow(entry: MacroLogEntry) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = Space.xs)) {
        Text(
            text = timeFormat.format(Date(entry.timestampMillis)),
            style = MaterialTheme.typography.labelSmall,
            color = T.InkFaint,
            modifier = Modifier.padding(end = Space.sm),
        )
        // "이름 — 메시지" 이어쓰기 대신 두 줄 — 긴 문장이 한 줄에 흐르지 않게
        Column {
            Text(
                text = entry.ruleName,
                style = MaterialTheme.typography.labelSmall,
                color = T.InkFaint,
            )
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall,
                color = if (entry.isError) T.Danger else T.InkMuted,
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
    return "$step · ${formatDuration(remaining)} 남음"
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
