package com.wemade.teslamacro.feature.macro

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChargingStation
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Window
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.domain.macro.ActionStep
import com.wemade.teslamacro.domain.macro.MacroFolder
import com.wemade.teslamacro.domain.macro.MacroProgress
import com.wemade.teslamacro.domain.macro.MacroRule
import com.wemade.teslamacro.domain.macro.Trigger
import com.wemade.teslamacro.domain.macro.describe
import com.wemade.teslamacro.domain.macro.formatDuration
import com.wemade.teslamacro.ui.component.ButtonTone
import com.wemade.teslamacro.ui.component.DraftField
import com.wemade.teslamacro.ui.component.DraftToggle
import com.wemade.teslamacro.ui.component.EmptyState
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.ui.layout.LocalPane
import com.wemade.teslamacro.ui.theme.Radius
import com.wemade.teslamacro.ui.theme.Stroke
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T
import kotlinx.coroutines.delay

/** 폴더와 미분류 매크로를 중립적인 목록으로 보여주고 실행 제어를 제공한다. */
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
    onCreateInFolder: ((String?) -> Unit)? = null,
    folders: List<MacroFolder> = emptyList(),
    folderError: String? = null,
    onSaveFolder: (String?, String) -> Unit = { _, _ -> },
    onMoveToFolder: (String, String?) -> Unit = { _, _ -> },
) {
    var selectedFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    var folderDialog by remember { mutableStateOf(false) }
    var renamingFolder by remember { mutableStateOf<MacroFolder?>(null) }
    var movingRule by remember { mutableStateOf<MacroRule?>(null) }
    var headerMenuOpen by remember { mutableStateOf(false) }
    val selectedFolder = folders.firstOrNull { it.id == selectedFolderId }
    val folderRules = remember(rules, folders, selectedFolderId) {
        com.wemade.teslamacro.domain.macro.macroRulesInFolder(rules, folders, selectedFolderId)
    }
    val visibleFolders = if (selectedFolder == null) folders else emptyList()
    BackHandler(enabled = selectedFolder != null) { selectedFolderId = null }
    if (folderDialog) {
        FolderNameDialog(renamingFolder, folders, onDismiss = { folderDialog = false }) { name ->
            onSaveFolder(renamingFolder?.id, name)
            folderDialog = false
        }
    }
    movingRule?.let { rule ->
        AlertDialog(
            onDismissRequest = { movingRule = null },
            title = { Text("폴더로 이동") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TButton(text = "폴더 밖", tone = ButtonTone.Ghost, onClick = {
                        onMoveToFolder(rule.id, null)
                        movingRule = null
                    })
                    folders.forEach { folder ->
                        TButton(text = folder.name, tone = ButtonTone.Ghost, onClick = {
                            onMoveToFolder(rule.id, folder.id)
                            movingRule = null
                        })
                    }
                }
            },
            confirmButton = { TButton(text = "취소", fillWidth = false, onClick = { movingRule = null }) },
        )
    }
    // 세로 화면과 큰 글씨에서는 한 열로 읽고, 가로 화면에서만 목록 폭을 활용한다.
    val columns = if (LocalDensity.current.fontScale >= 1.3f) 1 else LocalPane.current.columns
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.md, vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectedFolder != null) {
                IconButton(onClick = { selectedFolderId = null }, modifier = Modifier.size(Space.xxl)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "보관함으로 돌아가기", tint = T.Ink)
                }
            }
            Text(
                text = selectedFolder?.name ?: "보관함",
                style = MaterialTheme.typography.titleLarge,
                color = T.Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { onCreateInFolder?.invoke(selectedFolder?.id) ?: onCreate() },
                modifier = Modifier.size(Space.xxl),
            ) {
                Icon(Icons.Default.Add, contentDescription = "매크로 추가", tint = T.Electric)
            }
            Box {
                IconButton(onClick = { headerMenuOpen = true }, modifier = Modifier.size(Space.xxl)) {
                    Icon(Icons.Default.MoreVert, contentDescription = "목록 더보기", tint = T.InkMuted)
                }
                DropdownMenu(expanded = headerMenuOpen, onDismissRequest = { headerMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (selectedFolder == null) "폴더 만들기" else "이름 변경") },
                        onClick = {
                            headerMenuOpen = false
                            renamingFolder = selectedFolder
                            folderDialog = true
                        },
                    )
                }
            }
        }
        folderError?.let { Text(it, color = T.Danger, modifier = Modifier.padding(horizontal = Space.md)) }

        if (runningIds.isNotEmpty()) {
            TButton(
                text = "실행 중단",
                tone = ButtonTone.Danger,
                small = true,
                modifier = Modifier.padding(horizontal = Space.md, vertical = Space.xs),
                onClick = onStopAll,
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = Space.md, vertical = Space.sm),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            if (visibleFolders.isNotEmpty()) {
                item(span = { GridItemSpan(columns) }) {
                    Text("폴더", style = MaterialTheme.typography.titleSmall, color = T.Ink,
                        modifier = Modifier.padding(top = Space.sm, bottom = Space.xs))
                }
                items(visibleFolders, key = { "folder-${it.id}" }, span = { GridItemSpan(columns) }) { folder ->
                    FolderRow(folder, rules.count { it.id in folder.ruleIds }) {
                        selectedFolderId = folder.id
                    }
                }
            }
            if (folderRules.isNotEmpty()) {
                item(span = { GridItemSpan(columns) }) {
                    Text("매크로", style = MaterialTheme.typography.titleSmall, color = T.Ink,
                        modifier = Modifier.padding(top = Space.lg, bottom = Space.xs))
                }
            }
            items(folderRules, key = { it.id }) { rule ->
                MacroCard(
                    rule = rule,
                    isRunning = rule.id in runningIds,
                    progress = progress[rule.id],
                    onToggle = { onToggle(rule.id, it) },
                    onEdit = { onEdit(rule) },
                    onDuplicate = { onDuplicate(rule) },
                    onDelete = { onDelete(rule) },
                    onMove = { movingRule = rule },
                )
            }
            if (folderRules.isEmpty() && visibleFolders.isEmpty()) {
                item(span = { GridItemSpan(columns) }) {
                    EmptyState(
                        title = if (selectedFolder != null) "폴더가 비어 있어요" else "매크로가 없어요",
                        description = if (selectedFolder != null) "새 매크로를 만들거나 다른 매크로를 이 폴더로 이동하세요." else "매크로를 만들거나 폴더를 추가하세요.",
                        actionLabel = "매크로 만들기",
                        onAction = { onCreateInFolder?.invoke(selectedFolder?.id) ?: onCreate() },
                    )
                }
            }
        }
    }
}

/** 폴더는 매크로와 구별되는 탐색 행으로 보여준다. */
@Composable
private fun FolderRow(folder: MacroFolder, count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(Radius.button))
            .background(T.Slate)
            .border(Stroke.thin, T.Hairline, RoundedCornerShape(Radius.button))
            .clickable(onClickLabel = "${folder.name} 폴더 열기", onClick = onClick)
            .heightIn(min = Space.xxl).padding(horizontal = Space.sm, vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Icon(Icons.Default.Folder, contentDescription = null, tint = T.InkMuted, modifier = Modifier.size(Space.lg))
        Text(folder.name, style = MaterialTheme.typography.titleSmall, color = T.Ink,
            modifier = Modifier.weight(1f))
        Text("${count}개", style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = T.InkMuted)
    }
}

/** 이름 입력에 집중하고 빈 이름·중복 이름은 저장 전에 안내한다. */
@Composable
private fun FolderNameDialog(folder: MacroFolder?, folders: List<MacroFolder>, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(folder?.id) { mutableStateOf(folder?.name.orEmpty()) }
    val duplicate = folders.any { it.id != folder?.id && it.name.equals(name.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (folder == null) "폴더 만들기" else "이름 변경") },
        text = {
            DraftField(value = name, onValueChange = { if (it.length <= 40) name = it },
                label = "폴더 이름", note = if (duplicate) "같은 이름의 폴더가 있어요." else null)
        },
        confirmButton = { TButton(text = "저장", fillWidth = false, enabled = name.isNotBlank() && !duplicate, onClick = { onSave(name.trim()) }) },
        dismissButton = { TButton(text = "취소", tone = ButtonTone.Ghost, fillWidth = false, onClick = onDismiss) },
    )
}

/** 명령 아이콘, 이름, 요약, 실행 상태를 한눈에 읽는 중립 목록 행. */
@Composable
private fun MacroCard(
    rule: MacroRule,
    isRunning: Boolean,
    progress: MacroProgress?,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
) {
    val firstAction = rule.actions.firstOrNull { it !is ActionStep.Wait && it !is ActionStep.WaitUntil }
    val command = (firstAction as? ActionStep.Run)?.command
    val timed = rule.triggers.any { it is Trigger.AtTime || it is Trigger.Every }
    val icon: ImageVector = when {
        command is VehicleCommand.SetChargeLimit || command is VehicleCommand.SetCharging ||
            command is VehicleCommand.SetChargingAmps || firstAction is ActionStep.SetStealthCharging -> Icons.Default.ChargingStation
        command is VehicleCommand.SetSeatHeater || command is VehicleCommand.SetSteeringWheelHeater -> Icons.Default.Thermostat
        command is VehicleCommand.SetSeatCooler -> Icons.Default.AcUnit
        command is VehicleCommand.VentWindows || command is VehicleCommand.CloseWindows -> Icons.Default.Window
        command is VehicleCommand.Lock || command is VehicleCommand.Unlock ||
            command is VehicleCommand.SetSentryMode -> Icons.Default.Lock
        timed -> Icons.Default.Schedule
        command is VehicleCommand.ClimateOn || command is VehicleCommand.ClimateOff ||
            command is VehicleCommand.SetTemperature -> Icons.Default.Thermostat
        rule.triggers.any { it is Trigger.SignalBecomes } -> Icons.Default.DirectionsCar
        else -> Icons.Default.Bolt
    }
    val event = rule.triggers.firstOrNull()?.let(::describe) ?: "발생 조건 없음"
    val condition = rule.conditions.firstOrNull()?.let(::describe)
    val summary = listOfNotNull(event, condition, when (firstAction) {
        is ActionStep.Run -> firstAction.command.label
        is ActionStep.Navigate -> "${firstAction.destinationName} 안내"
        is ActionStep.SetStealthCharging -> "스텔스 충전 ${if (firstAction.enabled) "켜기" else "끄기"}"
        else -> null
    }).joinToString(" · ")
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(T.Carbon, RoundedCornerShape(Radius.button))
            .border(Stroke.thin, T.Hairline, RoundedCornerShape(Radius.button))
            .padding(horizontal = Space.sm, vertical = Space.xs),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.weight(1f)
                    .clickable(onClickLabel = "${rule.name} 편집", onClick = onEdit)
                    .heightIn(min = Space.xxl),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                Icon(icon, contentDescription = null, tint = T.InkMuted, modifier = Modifier.size(Space.lg))
                Text(
                    rule.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = T.Ink,
                    modifier = Modifier.weight(1f),
                )
            }
            RowActions(rule = rule, onDuplicate = onDuplicate, onDelete = onDelete, onMove = onMove)
        }
        Text(summary, style = MaterialTheme.typography.bodySmall, color = T.InkMuted,
            modifier = Modifier.padding(start = Space.lg + Space.sm, end = Space.sm))
        Row(
            Modifier.fillMaxWidth().heightIn(min = Space.xxl).padding(start = Space.lg + Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (isRunning) "실행 중 · ${runningLabel(progress)}" else if (rule.enabled) "사용 중" else "꺼짐",
                style = MaterialTheme.typography.labelMedium,
                color = if (isRunning) T.Electric else T.InkMuted,
                modifier = Modifier.weight(1f),
            )
            DraftToggle(
                checked = rule.enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.semantics { contentDescription = "${rule.name} 자동 실행" },
            )
        }
    }
}

/** 이동·복제·삭제는 더보기 메뉴로 접는다. */
@Composable
private fun RowActions(
    rule: MacroRule,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
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
                imageVector = Icons.Default.MoreVert,
                contentDescription = "${rule.name} 이동·복제·삭제 메뉴",
                tint = T.InkMuted,
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
                text = { Text("폴더로 이동", color = T.Ink) },
                onClick = { menuOpen = false; onMove() },
            )
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
