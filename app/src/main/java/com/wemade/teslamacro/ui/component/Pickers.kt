package com.wemade.teslamacro.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.wemade.teslamacro.ui.layout.LocalPane
import com.wemade.teslamacro.ui.theme.Motion
import com.wemade.teslamacro.ui.theme.Radius
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

/** 현재 화면 위에 짧은 선택 목록을 띄우고 뒤로가기와 바깥 탭은 이 창만 닫는다. */
@Composable
fun PickerSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // 설정에서도 시스템 뒤로가기가 앱 대신 현재 선택창 하나만 닫도록 공용 패널이 맡는다.
    BackHandler(onBack = onDismiss)
    val compact = LocalPane.current.isCompact
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.48f))
            .clickable(indication = null, interactionSource = remembered()) { onDismiss() },
        contentAlignment = if (compact) Alignment.BottomCenter else Alignment.Center,
    ) {
        val panelMaxHeight = maxHeight * 0.85f
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .heightIn(max = panelMaxHeight)
                .padding(horizontal = if (compact) Space.sm else Space.lg)
                .clip(RoundedCornerShape(Radius.card))
                .background(T.Carbon)
                .clickable(indication = null, interactionSource = remembered()) { }
                .semantics { paneTitle = title }
                .padding(Space.md),
        ) {
            if (compact) {
                Box(Modifier.fillMaxWidth().padding(bottom = Space.sm), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(width = 32.dp, height = Space.xs)
                            .clip(RoundedCornerShape(Radius.pill))
                            .background(T.Hairline)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = T.Ink,
                    modifier = Modifier.weight(1f).padding(end = Space.sm))
                Box(
                    modifier = Modifier
                        .size(Space.xxl)
                        .clip(RoundedCornerShape(Radius.pill))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = DraftMark.Close,
                        contentDescription = "닫기",
                        tint = T.InkMuted,
                        modifier = Modifier.size(Space.lg),
                    )
                }
            }
            content()
        }
    }
}

/** 제목 한 줄 + 부제 형태의 선택 항목 */
@Composable
fun PickerRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.button))
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = Space.sm, vertical = Space.sm + Space.xs),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = T.Ink)
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
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
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        options.forEach { option ->
            ChoiceChip(label(option), option == selected, onClick = { onSelect(option) })
        }
    }
}

/** 편집 선택지는 같은 폭으로 정렬하고 마지막 줄도 앞줄의 열 규격을 유지한다. */
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
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        options.chunked(columnCount).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                row.forEach { option ->
                    ChoiceChip(label(option), option == selected, compact = true, outlined = outlined,
                        modifier = Modifier.weight(1f).fillMaxHeight(), onClick = { onSelect(option) })
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

/** 자유 배치 칩과 정렬된 편집 선택지가 동일한 색·높이·접근성 규격을 공유한다. */
@Composable
private fun ChoiceChip(text: String, selected: Boolean, modifier: Modifier = Modifier, compact: Boolean = false, outlined: Boolean = false, onClick: () -> Unit) {
    val background by animateColorAsState(
        targetValue = if (outlined) Color.Transparent else if (selected) T.Electric else T.Slate,
        animationSpec = Motion.quick(), label = "chipBackground",
    )
    Box(
        modifier = modifier.clip(RoundedCornerShape(Radius.button)).background(background)
            .then(if (outlined) Modifier.border(Stroke.thin, if (selected) T.Electric else T.Hairline, RoundedCornerShape(Radius.button)) else Modifier)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .defaultMinSize(minHeight = Space.xxl)
            .padding(horizontal = if (compact) Space.sm else Space.md, vertical = if (compact) Space.sm else Space.sm + Space.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge,
            color = if (selected && outlined) T.Electric else if (selected) T.Void else T.InkMuted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun remembered() =
    androidx.compose.runtime.remember {
        androidx.compose.foundation.interaction.MutableInteractionSource()
    }
