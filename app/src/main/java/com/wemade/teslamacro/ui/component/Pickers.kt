package com.wemade.teslamacro.ui.component

import android.view.WindowManager
import com.wemade.teslamacro.ui.theme.Stroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.wemade.teslamacro.ui.layout.LocalPane
import com.wemade.teslamacro.ui.theme.Radius
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

/** 실제 창 안에서 시스템 바·화면 잘림·키보드 영역을 제외해 본문과 저장 버튼을 함께 배치한다. */
@Composable
fun PickerSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
    fillHeight: Boolean = false,
    content: @Composable () -> Unit,
) {
    val compact = LocalPane.current.isCompact
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as DialogWindowProvider).window
        SideEffect {
            // Compose 1.7의 기본 폭 해제는 실제 창보다 큰 screenHeightDp로 재측정한다.
            // 창 자체를 확장하고 기본 측정 경로를 유지해야 Android 15에서도 하단이 잘리지 않는다.
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }
        BoxWithConstraints(
            modifier = modifier.fillMaxSize().safeDrawingPadding()
                .clickable(indication = null, interactionSource = remembered(), onClick = onDismiss),
            contentAlignment = if (compact) Alignment.BottomCenter else Alignment.Center,
        ) {
            val panelHeight = maxHeight * 0.9f
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .then(if (fillHeight) Modifier.height(panelHeight) else Modifier.heightIn(max = panelHeight))
                    .clip(RoundedCornerShape(topStart = Radius.card, topEnd = Radius.card,
                        bottomStart = if (compact) 0.dp else Radius.card,
                        bottomEnd = if (compact) 0.dp else Radius.card))
                    .background(T.Carbon)
                    .clickable(indication = null, interactionSource = remembered()) { }
                    .semantics { paneTitle = title },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = Space.md, end = Space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = T.Ink,
                        modifier = Modifier.weight(1f).padding(end = Space.sm))
                    androidx.compose.material3.IconButton(onClick = onDismiss) {
                        Icon(DraftMark.Close, contentDescription = "닫기", tint = T.InkMuted,
                            modifier = Modifier.size(Space.lg))
                    }
                }
                Hairline()
                Column(Modifier.weight(1f, fill = fillHeight).fillMaxWidth().padding(Space.md)) {
                    content()
                }
                if (footer != null) {
                    Hairline()
                    Column(Modifier.fillMaxWidth().padding(horizontal = Space.md, vertical = Space.sm)) {
                        footer()
                    }
                }
            }
        }
    }
}

/** 선택 항목은 글자로, 상세로 이동하는 행은 오른쪽 화살표로 구분한다. */
@Composable
fun PickerRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    showChevron: Boolean = false,
    value: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.button))
            .clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = Space.xxl)
            .padding(horizontal = Space.sm, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = T.Ink)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
            }
        }
        if (value != null) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = T.InkMuted,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.weight(0.85f))
        }
        if (showChevron) {
            Icon(DraftMark.ChevronRight, contentDescription = null, tint = T.InkMuted,
                modifier = Modifier.size(Space.lg))
        }
    }
}

/** 목록이 길어질 수 있으므로 스크롤을 기본으로 둔다 */
@Composable
fun <T> PickerList(
    items: List<T>,
    modifier: Modifier = Modifier,
    row: @Composable (T) -> Unit,
) {
    val maxListHeight = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.5f)
        .coerceAtMost(520.dp)
    LazyColumn(
        state = rememberLazyListState(),
        modifier = modifier.heightIn(max = maxListHeight),
    ) {
        itemsIndexed(items) { index, item ->
            row(item)
            if (index < items.lastIndex) Hairline()
        }
    }
}

/**
 * 선택된 하나만 강조하는 칩 줄.
 * 칩 개수가 가변이라 좁은 화면에서 넘치지 않게 줄바꿈(FlowRow)을 기본으로 둔다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChipRow(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        options.forEach { option ->
            ChoiceChip(label(option), option == selected, onClick = { onSelect(option) })
        }
    }
}

/** 선택지는 내용 높이만 사용해 스크롤 시트의 남은 높이까지 늘어나지 않게 한다. */
@Composable
fun <T> ChoiceGrid(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 2,
    outlined: Boolean = false,
) {
    val columnCount = if (LocalDensity.current.fontScale >= 1.3f) columns.coerceAtMost(2) else columns
    Column(modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        options.chunked(columnCount).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm),
                verticalAlignment = Alignment.CenterVertically) {
                row.forEach { option ->
                    ChoiceChip(label(option), option == selected, compact = true, outlined = outlined,
                        modifier = Modifier.weight(1f), onClick = { onSelect(option) })
                }
                repeat(columnCount - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 탐색은 값 선택 버튼과 구별하고 긴 탭 이름도 잘리지 않게 줄바꿈한다. */
@Composable
fun <T> SectionTabs(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        options.forEach { option ->
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight()
                    .clip(RoundedCornerShape(Radius.segment))
                    .selectable(selected = option == selected, role = Role.Tab, onClick = { onSelect(option) })
                    .heightIn(min = Space.xxl).padding(top = Space.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.weight(1f).padding(bottom = Space.sm), contentAlignment = Alignment.Center) {
                    Text(label(option), style = MaterialTheme.typography.labelLarge,
                        color = if (option == selected) T.Electric else T.InkMuted, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.fillMaxWidth().height(Stroke.bold)
                    .background(if (option == selected) T.Electric else Color.Transparent))
            }
        }
    }
}

/** 선택 칩은 Material 표시와 체크를 함께 써 색에만 의존하지 않는다. */
@Composable
private fun ChoiceChip(text: String, selected: Boolean, modifier: Modifier = Modifier, compact: Boolean = false, outlined: Boolean = false, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier.semantics { role = Role.RadioButton },
        shape = RoundedCornerShape(Radius.button),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = T.InkMuted,
            selectedContainerColor = if (outlined) Color.Transparent else T.ElectricFaint,
            selectedLabelColor = T.Electric,
            selectedLeadingIconColor = T.Electric,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = T.Hairline,
            selectedBorderColor = T.Electric,
        ),
        leadingIcon = if (selected) ({
            Icon(DraftMark.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
        }) else null,
        label = {
            Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = if (compact) 0.dp else Space.xs))
        },
    )
}

@Composable
private fun remembered() =
    androidx.compose.runtime.remember {
        androidx.compose.foundation.interaction.MutableInteractionSource()
    }
