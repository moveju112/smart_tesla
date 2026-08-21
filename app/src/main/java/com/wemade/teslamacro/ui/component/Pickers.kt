package com.wemade.teslamacro.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
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
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // 뒤 배경을 덮어 바깥 탭으로 닫는다
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(indication = null, interactionSource = remembered()) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        // 짧은 목록이 화면 85%를 강제로 채우면 아래가 텅 빈다 — 내용만큼만 차지하게 상한만 건다
        val panelMaxHeight = maxHeight * 0.85f
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .heightIn(max = panelMaxHeight)
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
                // 아이콘은 24dp지만 패딩으로 터치 타깃을 48dp까지 키운다 — 주행 중 닫기 실패 방지
                Icon(
                    imageVector = DraftMark.Close,
                    contentDescription = "닫기",
                    tint = T.InkFaint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.pill))
                        .clickable { onDismiss() }
                        .padding(Space.sm + Space.xs)
                        .size(24.dp),
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
    val state = rememberLazyListState()
    Box(modifier = modifier) {
        LazyColumn(state = state, modifier = Modifier.heightIn(max = 520.dp)) {
            // 항목 사이 구분선 — 경계가 없으면 단독 항목이 허공에 뜬 장식처럼 보인다
            itemsIndexed(items) { index, item ->
                row(item)
                if (index < items.lastIndex) Hairline()
            }
        }
        // 아래에 더 있는데 잘려 보이지 않으면 스크롤할 생각을 못 한다 — 하단을 흐려서 알린다
        if (state.canScrollForward) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, T.Carbon))
                    ),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Icon(
                    imageVector = DraftMark.Expand,
                    contentDescription = "아래로 스크롤",
                    tint = T.InkFaint,
                )
            }
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
            val isSelected = option == selected
            val background by animateColorAsState(
                targetValue = if (isSelected) T.Electric else T.Slate,
                animationSpec = Motion.quick(),
                label = "chipBackground",
            )
            Box(
                modifier = Modifier
                    // clip을 먼저 — 리플이 둥근 모서리 밖으로 번지지 않게
                    .clip(RoundedCornerShape(Radius.button))
                    .background(background)
                    // selectable — TalkBack이 선택 상태를 읽을 수 있게
                    .selectable(selected = isSelected, role = Role.Button) { onSelect(option) }
                    .padding(horizontal = Space.md, vertical = Space.sm + Space.xs),
            ) {
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.labelLarge,
                    // 파랑 위 어두운 글자는 안 읽힌다 — TButton Primary와 같은 흰 글자 규칙
                    color = if (isSelected) Color.White else T.InkMuted,
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
