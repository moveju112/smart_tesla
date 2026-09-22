package com.wemade.teslamacro.feature.macro.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.wemade.teslamacro.ui.component.DraftMark
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

/** 요약 행 전체를 눌러 편집하고, 다른 항목이 접혀도 현재 제목을 화면에 유지한다. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun EditorItemHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {
    val requester = remember { BringIntoViewRequester() }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(expanded, placed) {
        if (expanded && placed) requester.bringIntoView()
    }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = Space.xxl)
            .bringIntoViewRequester(requester)
            .onPlaced { placed = true }
            .semantics { stateDescription = if (expanded) "편집 중" else "접힘" }
            .clickable(role = Role.Button, onClickLabel = if (expanded) "접기" else "편집", onClick = onToggle),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = T.Ink, modifier = Modifier.weight(1f))
        Icon(DraftMark.Expand, contentDescription = null, tint = T.InkMuted,
            modifier = Modifier.size(Space.lg).rotate(if (expanded) 180f else 0f))
    }
}

/** 같은 요약을 누르면 닫고 다른 요약을 누르면 편집 대상 하나만 교체한다. */
internal fun toggleEditorIndex(selected: Int, toggled: Int): Int = if (selected == toggled) -1 else toggled

/** 지운 항목 아래의 선택 위치를 보정하고 삭제된 편집기는 닫는다. */
internal fun editorIndexAfterRemoval(selected: Int, removed: Int): Int = when {
    selected == removed -> -1
    selected > removed -> selected - 1
    else -> selected
}

/** 같은 내용의 동작도 실행 순서상 위치로 구별해 이동 후 편집 대상을 유지한다. */
internal fun editorIndexAfterMove(selected: Int, from: Int, to: Int): Int = when {
    selected == from -> to
    from < to && selected in (from + 1)..to -> selected - 1
    to < from && selected in to until from -> selected + 1
    else -> selected
}
