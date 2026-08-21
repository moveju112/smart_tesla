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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wemade.teslamacro.domain.macro.MacroLogEntry
import com.wemade.teslamacro.domain.macro.MacroProgress
import com.wemade.teslamacro.domain.macro.MacroRule
import com.wemade.teslamacro.domain.macro.describeRule
import com.wemade.teslamacro.domain.macro.formatDuration
import com.wemade.teslamacro.ui.component.ButtonTone
import com.wemade.teslamacro.ui.component.CalloutNumber
import com.wemade.teslamacro.ui.component.DraftMark
import com.wemade.teslamacro.ui.component.DraftToggle
import com.wemade.teslamacro.ui.component.EmptyState
import com.wemade.teslamacro.ui.component.Hairline
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.ui.component.TableHeader
import com.wemade.teslamacro.ui.theme.CalloutNumberStyle
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 매크로 목록 — 시트 2. **절차 명세표**다.
 *
 * 예전엔 카드 하나가 매크로 하나였다. 카드 세 장이면 화면이 꽉 차서
 * 매크로가 몇 개인지, 어느 게 지금 도는지 한눈에 볼 수 없었다.
 * 도면집의 부품 명세표처럼 한 줄이 하나다 — 열이 고정되어 있어 훑기만 하면 비교된다.
 *
 * 실행 기록은 아래 **개정란**으로 붙는다. 도면이 변경 이력을 적는 자리가 거기다.
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

    Column(modifier = modifier.fillMaxSize()) {
        // 표 위 도구 줄. 제목은 두지 않는다 — 어느 시트인지는 좌측 목차가 이미 말한다
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg, vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "절차 ${rules.size}건 · 감시 ${rules.count { it.enabled }}건",
                style = MaterialTheme.typography.labelSmall,
                color = T.InkFaint,
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
            TButton(
                text = "새 절차",
                fillWidth = false,
                small = true,
                icon = DraftMark.Add,
                onClick = onCreate,
            )
        }

        if (rules.isEmpty()) {
            EmptyState(
                title = "아직 절차가 없어요",
                description = "탑승을 감지해 통풍을 켜는 식의 자동화를 만들 수 있어요.",
                actionLabel = "첫 절차 만들기",
                onAction = onCreate,
                modifier = Modifier.padding(horizontal = Space.lg),
            )
        } else {
            TableHeader(
                columns = listOf(
                    "번호" to NUMBER_WEIGHT,
                    "절차 · 조건" to NAME_WEIGHT,
                    "동작" to STEPS_WEIGHT,
                    "마지막 실행" to LAST_RUN_WEIGHT,
                    "상태" to STATE_WEIGHT,
                ),
                modifier = Modifier.padding(horizontal = Space.lg),
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = Space.lg, vertical = 0.dp),
        ) {
            items(rules, key = { it.id }) { rule ->
                MacroRow(
                    number = rules.indexOf(rule) + 1,
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
        }

        // 개정란 — 도면이 변경 이력을 적는 자리. 실행 기록이 정확히 그것이다
        RevisionBlock(log)
    }
}

// 표의 열 비율. 지시선 계산과 같은 이유로 상수로 둔다 — 머리글과 본문이 어긋나면 표가 아니다
private const val NUMBER_WEIGHT = 0.06f
private const val NAME_WEIGHT = 0.44f
private const val STEPS_WEIGHT = 0.10f
private const val LAST_RUN_WEIGHT = 0.20f
private const val STATE_WEIGHT = 0.20f

/**
 * 표의 한 행.
 *
 * 행 전체가 편집 진입점이다. 편집 버튼을 따로 두면 행이 버튼 창고가 된다.
 * 실행 중이면 번호 원이 채워진다 — 색이 아니라 채움으로 표시하는 게 이 세계의 방식이다.
 */
@Composable
private fun MacroRow(
    number: Int,
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
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .clickable(onClick = onEdit)
                .padding(vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(NUMBER_WEIGHT)) {
                CalloutNumber(number = number, highlighted = isRunning)
            }
            Column(Modifier.weight(NAME_WEIGHT).padding(end = Space.sm)) {
                Text(
                    text = rule.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (rule.enabled) T.Ink else T.InkFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = describeRule(rule),
                    style = MaterialTheme.typography.bodySmall,
                    color = T.InkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "${rule.actions.size}",
                style = CalloutNumberStyle,
                color = T.InkMuted,
                modifier = Modifier.weight(STEPS_WEIGHT),
            )
            Text(
                text = lastRunLabel(lastRunMillis),
                style = MaterialTheme.typography.bodySmall,
                color = T.InkFaint,
                maxLines = 1,
                modifier = Modifier.weight(LAST_RUN_WEIGHT),
            )
            Row(
                modifier = Modifier.weight(STATE_WEIGHT),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DraftToggle(
                    checked = rule.enabled,
                    onCheckedChange = onToggle,
                    label = if (isRunning) runningLabel(progress) else if (rule.enabled) "감시" else "끔",
                )
                Spacer(Modifier.weight(1f))
                RowActions(
                    rule = rule,
                    onRunNow = onRunNow,
                    onDuplicate = onDuplicate,
                    onDelete = onDelete,
                )
            }
        }
        // 괘선 — 표의 행 경계. 카드 간 여백으로 나누면 표가 아니라 목록이 된다
        Hairline()
    }
}

/** 행 끝의 실행·더보기. 부가 동작은 ⋯로 접는다 */
@Composable
private fun RowActions(
    rule: MacroRule,
    onRunNow: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(onClick = onRunNow),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = DraftMark.Run,
            contentDescription = "지금 실행",
            tint = T.Ink,
            modifier = Modifier.size(18.dp),
        )
    }

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
            text = "개정란 · 실행 기록",
            style = MaterialTheme.typography.labelSmall,
            color = T.InkFaint,
        )
        Spacer(Modifier.height(Space.xs))
        if (log.isEmpty()) {
            Text(
                text = "아직 실행된 절차가 없어요. 조건이 맞으면 여기에 기록이 쌓여요.",
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
