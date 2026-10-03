package com.wemade.teslamacro.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.wemade.teslamacro.domain.model.Level
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

/** 한 단계만 고르는 제어는 기본 분할 버튼으로 선택 표시·리플·접근성을 공유한다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LevelSelector(
    label: String,
    selected: Level,
    accent: Color,
    onSelect: (Level) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier = modifier) {
        if (label.isNotBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) T.InkMuted else T.InkFaint,
                modifier = Modifier.padding(bottom = Space.sm),
            )
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Level.entries.forEachIndexed { index, level ->
                SegmentedButton(
                    selected = level == selected,
                    onClick = { onSelect(level) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index, Level.entries.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = accent.copy(alpha = 0.12f),
                        activeContentColor = T.Ink,
                        activeBorderColor = accent,
                        inactiveContainerColor = T.Carbon,
                        inactiveContentColor = T.InkMuted,
                        inactiveBorderColor = T.Hairline,
                    ),
                ) {
                    Text(level.label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** 제목 + 설명 + 스위치 한 줄. 설정/매크로 목록에서 반복해서 쓴다 */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = T.Ink,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = T.InkFaint,
                    modifier = Modifier.padding(top = Space.xs),
                )
            }
        }
        Spacer(modifier = Modifier.width(Space.md))
        // 스위치와 상태어를 함께 사용해 색을 보지 않아도 현재 값을 알 수 있다.
        DraftToggle(
            checked = checked,
            onCheckedChange = onCheckedChange,
            label = if (checked) "켬" else "끔",
        )
    }
}
