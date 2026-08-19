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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wemade.teslamacro.domain.model.Level
import com.wemade.teslamacro.ui.theme.MetricTextStyle
import com.wemade.teslamacro.ui.theme.Motion
import com.wemade.teslamacro.ui.theme.Radius
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

/**
 * 통풍/열선 4단계 선택기.
 * 슬라이더 대신 세그먼트를 쓴다 — 주행 중 눈을 안 떼고 누를 수 있는 큰 타겟이 필요해서다.
 */
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
                // 옅은 회색 트랙 위에 선택 칸만 도드라지는 토스식 세그먼트
                .background(T.Slate, RoundedCornerShape(Radius.button))
                .padding(Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Level.entries.forEach { level ->
                val isSelected = level == selected
                val background by animateColorAsState(
                    targetValue = when {
                        !enabled -> Color.Transparent
                        isSelected && level == Level.OFF -> Color.White
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
                        .height(44.dp)
                        .background(background, cellShape)
                        // OFF 선택 칸은 흰색 위 흰색이라 테두리 없으면 선택 여부가 안 보인다
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
                            // 주황(열선) 위 흰 글자는 대비 미달 — 어두운 글자로
                            isSelected && accent == T.Heat -> T.Ink
                            isSelected -> Color.White
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
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                // 파란 트랙 위 어두운 thumb는 켜짐이 안 보인다 — 토스식 흰 thumb
                checkedThumbColor = T.Carbon,
                checkedTrackColor = T.InkMuted,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = T.InkFaint,
                uncheckedTrackColor = T.Slate,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}
