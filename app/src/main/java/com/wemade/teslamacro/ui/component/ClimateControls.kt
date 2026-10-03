package com.wemade.teslamacro.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wemade.teslamacro.domain.model.Level
import com.wemade.teslamacro.ui.theme.Motion
import com.wemade.teslamacro.ui.theme.Radius
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

/** 통풍·열선 단계는 한눈에 읽히는 네 가지 선택 면으로 표시한다. */
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 중립 트랙과 강조 선택 면으로 단계 차이를 구분한다.
                .background(T.Slate, RoundedCornerShape(Radius.button))
                .padding(Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Level.entries.forEach { level ->
                val isSelected = level == selected
                val background by animateColorAsState(
                    targetValue = when {
                        !enabled -> Color.Transparent
                        isSelected && level == Level.OFF -> T.Carbon
                        isSelected -> accent
                        else -> Color.Transparent
                    },
                    animationSpec = Motion.quick(),
                    label = "levelBackground",
                )
                val cellShape = RoundedCornerShape(Radius.segment)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .background(background, cellShape)
                        // 꺼짐 선택은 중립 면과 테두리로 구분한다.
                        .then(
                            if (enabled && isSelected && level == Level.OFF)
                                Modifier.border(1.dp, T.Hairline, cellShape)
                            else Modifier
                        )
                        .clickable(enabled = enabled) { onSelect(level) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = level.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = when {
                            !enabled -> T.InkFaint
                            isSelected && level == Level.OFF -> T.Ink
                            isSelected -> MaterialTheme.colorScheme.surface
                            else -> T.InkMuted
                        },
                        textAlign = TextAlign.Center,
                    )
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
