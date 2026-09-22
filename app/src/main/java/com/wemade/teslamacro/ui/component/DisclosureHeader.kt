package com.wemade.teslamacro.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

/** 편집 요약과 설정 상세가 같은 터치 영역·줄바꿈·펼침 상태를 공유한다. */
@Composable
fun DisclosureHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = Space.xxl)
            .semantics { stateDescription = if (expanded) "펼침" else "접힘" }
            .clickable(role = Role.Button, onClickLabel = if (expanded) "접기" else "펼치기", onClick = onToggle)
            .padding(vertical = Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = T.Ink)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
            }
        }
        Icon(DraftMark.Expand, contentDescription = null, tint = T.InkMuted,
            modifier = Modifier.size(Space.lg).rotate(if (expanded) 180f else 0f))
    }
}
