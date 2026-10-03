package com.wemade.teslamacro.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wemade.teslamacro.ui.theme.Motion
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

/** 주요 동작은 파란 면으로, 보조 동작은 중립 면으로 구분하는 공용 버튼. */
@Composable
fun TButton(
    text: String,
    tone: ButtonTone = ButtonTone.Primary,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fillWidth: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    /** 카드 안 보조 액션용 소형(48dp). 주 동작 버튼은 기본(52dp)이고, [LocalCompactButtons] 화면에선 소형이 기본이다 */
    small: Boolean = LocalCompactButtons.current,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = Motion.quick(),
        label = "buttonPress",
    )

    // 조작 피드백은 현재 팔레트의 면 색에서만 가져온다.
    val fillColor: Color = when {
        !enabled -> Color.Transparent
        tone == ButtonTone.Primary -> if (pressed) T.ElectricPressed else T.Electric
        tone == ButtonTone.Danger -> if (pressed) T.Hairline else T.Slate
        tone == ButtonTone.Secondary -> if (pressed) T.Hairline else T.Slate
        tone == ButtonTone.Ghost && pressed -> T.Slate
        else -> Color.Transparent
    }

    val content = when {
        !enabled -> T.InkFaint
        tone == ButtonTone.Ghost -> T.InkMuted
        tone == ButtonTone.Secondary -> T.Ink
        tone == ButtonTone.Danger -> T.Danger
        else -> MaterialTheme.colorScheme.onPrimary
    }

    val borderColor = when {
        !enabled -> T.Hairline.copy(alpha = 0.5f)
        tone == ButtonTone.Danger -> T.Danger.copy(alpha = 0.35f)
        tone == ButtonTone.Secondary || tone == ButtonTone.Ghost -> T.Hairline
        else -> Color.Transparent
    }

    val shape = RoundedCornerShape(Radius.button)
    // 설정의 줄 끝 버튼(폭을 채우지 않는 버튼)은 48dp 면이면 옆 글자 줄보다 유난히 커 보인다.
    // 보이는 면은 32dp 알약으로 줄이고 누르는 영역만 48dp로 넓혀 최소 터치 높이를 지킨다.
    val inline = LocalCompactButtons.current && !fillWidth
    val face = Modifier
        .scale(press)
        // 면과 터치 영역을 같은 둥근 형태로 유지한다.
        .clip(shape)
        .background(fillColor)
        .border(1.dp, borderColor, shape)
    val clickable = Modifier.clickable(
        enabled = enabled,
        role = Role.Button,
        interactionSource = interaction,
        indication = null,
        onClick = onClick,
    )
    val label: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs + 2.dp),
        ) {
            // 아이콘은 라벨을 보조하며 접근성 이름은 버튼 문구가 제공한다.
            if (icon != null) {
                androidx.compose.material3.Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(if (small) 18.dp else 20.dp),
                )
            }
            Text(
                text = text,
                style = if (small) MaterialTheme.typography.labelMedium
                else MaterialTheme.typography.labelLarge,
                color = content,
                textAlign = TextAlign.Center,
            )
        }
    }

    if (inline) {
        Box(modifier = modifier.defaultMinSize(minWidth = Space.xxl, minHeight = Space.xxl).then(clickable), contentAlignment = Alignment.Center) {
            Box(
                modifier = face.defaultMinSize(minHeight = 32.dp).padding(horizontal = Space.sm + Space.xs),
                contentAlignment = Alignment.Center,
            ) { label() }
        }
        return
    }
    Box(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .then(face)
            // 소형 동작도 최소 48dp 터치 영역을 유지한다.
            .defaultMinSize(minWidth = Space.xxl, minHeight = if (small) Space.xxl else 52.dp)
            .then(clickable)
            .padding(
                horizontal = if (small) Space.sm + Space.xs else Space.md,
                vertical = if (small) 0.dp else Space.sm,
            ),
        contentAlignment = Alignment.Center,
    ) { label() }
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
