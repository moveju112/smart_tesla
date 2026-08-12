package com.wemade.teslamacro.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.wemade.teslamacro.ui.theme.Motion
import com.wemade.teslamacro.ui.theme.Radius
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

/**
 * 화면을 덮는 선택 패널.
 *
 * Material 바텀시트 대신 직접 만든 이유: 그림자·모서리·색을 전부 눌러야 해서
 * 커스터마이즈 양이 새로 만드는 것보다 많았다.
 */
@Composable
fun PickerSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            // 뒤 배경을 덮어 바깥 탭으로 닫는다
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(indication = null, interactionSource = remembered()) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxHeight(0.85f)
                .padding(Space.lg)
                .background(T.Carbon, RoundedCornerShape(Radius.card))
                // 패널 안 탭이 닫기로 새어나가지 않게 막는다
                .clickable(indication = null, interactionSource = remembered()) { }
                .padding(Space.lg),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = Space.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = T.Ink)
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "닫기",
                    tint = T.InkFaint,
                    modifier = Modifier.size(24.dp).clickable { onDismiss() },
                )
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
            .clickable(onClick = onClick)
            .padding(vertical = Space.sm + Space.xs),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = T.Ink)
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = T.InkFaint)
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
    LazyColumn(modifier = modifier.heightIn(max = 520.dp)) {
        items(items) { item -> row(item) }
    }
}

/** 선택된 하나만 강조하는 가로 칩 줄 */
@Composable
fun <T> ChipRow(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val background by animateColorAsState(
                targetValue = if (isSelected) T.Electric else T.Slate,
                animationSpec = Motion.quick(),
                label = "chipBackground",
            )
            Box(
                modifier = Modifier
                    .background(background, RoundedCornerShape(Radius.button))
                    .clickable { onSelect(option) }
                    .padding(horizontal = Space.md, vertical = Space.sm + Space.xs),
            ) {
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) T.Ink else T.InkMuted,
                )
            }
        }
    }
}

@Composable
private fun remembered() =
    androidx.compose.runtime.remember {
        androidx.compose.foundation.interaction.MutableInteractionSource()
    }
