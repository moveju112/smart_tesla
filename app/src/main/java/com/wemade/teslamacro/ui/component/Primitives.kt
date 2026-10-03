package com.wemade.teslamacro.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wemade.teslamacro.ui.theme.Radius
import com.wemade.teslamacro.ui.theme.Stroke
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

enum class ButtonTone { Primary, Secondary, Ghost, Danger }

/**
 * 설정처럼 버튼이 많은 화면에서 기본 버튼을 소형으로 바꾼다.
 * 호출마다 small을 넘기면 새 버튼에서 빠뜨리기 쉬워 화면 루트에서 한 번 정한다. 최소 터치 높이 48dp는 그대로다.
 */
val LocalCompactButtons = androidx.compose.runtime.staticCompositionLocalOf { false }

/** 저장만 채운 버튼으로 강조하고 보조·취소는 기본 Material 버튼 계층을 따른다. */
@Composable
fun TButton(
    text: String,
    tone: ButtonTone = ButtonTone.Primary,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fillWidth: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    /** 소형도 같은 48dp 터치 영역과 기본 리플을 유지한다. */
    small: Boolean = LocalCompactButtons.current,
    onClick: () -> Unit,
) {
    val buttonModifier = modifier
        .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
        .defaultMinSize(minWidth = Space.xxl, minHeight = Space.xxl)
    val shape = RoundedCornerShape(Radius.pill)
    val padding = PaddingValues(horizontal = if (small) Space.md else Space.lg, vertical = Space.sm)
    val label: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(if (small) 18.dp else 20.dp),
            )
            Spacer(Modifier.width(Space.sm))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
        )
    }
    when (tone) {
        ButtonTone.Primary -> Button(
            onClick = onClick,
            modifier = buttonModifier,
            enabled = enabled,
            shape = shape,
            contentPadding = padding,
            colors = ButtonDefaults.buttonColors(
                containerColor = T.Electric,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = T.Slate,
                disabledContentColor = T.InkFaint,
            ),
            content = label,
        )
        ButtonTone.Secondary -> OutlinedButton(
            onClick = onClick,
            modifier = buttonModifier,
            enabled = enabled,
            shape = shape,
            contentPadding = padding,
            border = BorderStroke(Stroke.thin, T.Hairline),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = T.Ink,
                disabledContentColor = T.InkFaint,
            ),
            content = label,
        )
        ButtonTone.Ghost, ButtonTone.Danger -> TextButton(
            onClick = onClick,
            modifier = buttonModifier,
            enabled = enabled,
            shape = shape,
            contentPadding = padding,
            colors = ButtonDefaults.textButtonColors(
                contentColor = if (tone == ButtonTone.Danger) T.Danger else T.Electric,
                disabledContentColor = T.InkFaint,
            ),
            content = label,
        )
    }
}

/** 관련 설정과 동작을 하나의 읽기 쉬운 면으로 묶는다. */
@Composable
fun TCard(
    modifier: Modifier = Modifier,
    outlined: Boolean = false,
    /** 구역 전체를 탭 대상으로 만든다 (목록 항목 탭 = 상세/편집 패턴) */
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Radius.card)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(T.Carbon)
            .then(if (outlined) Modifier.border(Stroke.thin, T.Electric, shape) else Modifier)
            .then(if (onClick != null) Modifier.defaultMinSize(minHeight = Space.xxl)
                .clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(Space.md),
        content = content,
    )
}

/** 구역 제목은 크기와 여백으로 계층을 구분한다. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    /** 단 맨 위에 오는 헤더는 위 여백을 없애 옆 단과 시작선을 맞춘다 */
    topPadding: androidx.compose.ui.unit.Dp = Space.lg,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = topPadding, bottom = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // 제목은 선택 상태가 아닌 일반 본문색으로 읽힌다.
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = T.Ink,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** 상태를 색과 텍스트로 함께 설명하는 작은 보조 배지. */
@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    showDot: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    /** 밝은 상태색(Warn 등)은 옅은 배경 위에서 안 읽힌다 — 글자만 진한 색으로 분리할 때 쓴다 */
    textColor: Color = color,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = Space.sm + Space.xs, vertical = Space.xs + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs + 1.dp),
    ) {
        if (icon != null) {
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp),
            )
        } else if (showDot) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(Radius.pill))
                    .drawBehind { drawRect(color) }
            )
        }
        CompositionLocalProvider(LocalContentColor provides textColor) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
            )
        }
    }
}

/** 얇은 구분선 */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    // alpha 모디파이어는 background 뒤에선 효과가 없다 — 색 자체에 알파를 넣는다
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(T.Hairline.copy(alpha = 0.8f))
    )
}
