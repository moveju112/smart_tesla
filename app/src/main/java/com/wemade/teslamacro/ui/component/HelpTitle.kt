package com.wemade.teslamacro.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

/** 설명이 있는 제목만 정보 표시와 독립 터치 영역을 제공한다. */
@Composable
fun HelpTitle(title: String, description: String, modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium) {
    var showing by rememberSaveable { mutableStateOf(false) }
    Row(modifier.heightIn(min = Space.xxl)
        .clickable(role = Role.Button, onClickLabel = "$title 설명 보기") { showing = true },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text(title, style = style, color = T.Ink, modifier = Modifier.weight(1f, fill = false))
        Icon(DraftMark.Info, contentDescription = null, tint = T.InkMuted,
            modifier = Modifier.size(Space.lg))
    }
    if (showing) HelpSheet(title, description, onDismiss = { showing = false })
}

/** 도움말은 공통 모달에서 읽고 닫으며 설정이나 실행 상태를 바꾸지 않는다. */
@Composable
fun HelpSheet(title: String, description: String, onDismiss: () -> Unit) {
    PickerSheet(title, onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(description, style = MaterialTheme.typography.bodyMedium, color = T.Ink)
        }
    }
}
