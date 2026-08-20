package com.wemade.teslamacro.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wemade.teslamacro.ui.theme.Motion
import com.wemade.teslamacro.ui.theme.Radius
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

enum class ButtonTone { Primary, Secondary, Ghost, Danger }

/**
 * 공용 버튼.
 * - Primary: 파랑 단색 채움, 그림자 없음 (토스식 평면 버튼)
 * - Secondary/Ghost: 카드 위에서 한 겹 밝은 면 + 얇은 테두리
 * 누르면 살짝 작아지며(0.97) 즉각적인 촉감을 준다.
 */
@Composable
fun TButton(
    text: String,
    tone: ButtonTone = ButtonTone.Primary,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fillWidth: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    /** 카드 안 보조 액션용 소형(44dp). 주 동작 버튼은 기본(52dp)을 유지한다 */
    small: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = Motion.quick(),
        label = "buttonPress",
    )

    // 채움은 Primary만. Danger는 빨간 덩어리 대신 조용한 면에 빨간 글자로 둔다 —
    // 화면에서 빨간 면은 "지금 봐야 할 상태" 전용이라 버튼이 그 자리를 뺏으면 안 된다
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
        else -> T.Carbon
    }

    val borderColor = when {
        !enabled -> T.Hairline.copy(alpha = 0.5f)
        tone == ButtonTone.Danger -> T.Danger.copy(alpha = 0.35f)
        tone == ButtonTone.Secondary || tone == ButtonTone.Ghost -> T.Hairline
        else -> Color.Transparent
    }

    val shape = RoundedCornerShape(Radius.button)

    Box(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .scale(press)
            // 토스 버튼은 평평하다. 글로우/그림자를 쓰지 않는다
            .clip(shape)
            .background(fillColor)
            .border(1.dp, borderColor, shape)
            // small도 44dp — 흔들리는 차 안에서 36dp는 자주 빗나간다
            .defaultMinSize(minHeight = if (small) 44.dp else 52.dp)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(
                horizontal = if (small) Space.sm + Space.xs else Space.md,
                vertical = if (small) 0.dp else Space.sm,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs + 2.dp),
        ) {
            // 이모지 대신 벡터 아이콘. 폰트 따라 모양이 달라지는 이모지는 쓰지 않는다
            if (icon != null) {
                androidx.compose.material3.Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(if (small) 14.dp else 18.dp),
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
}

/**
 * 카드 표면. 흰 카드 + 얕은 그림자.
 * 옅은 회색 배경과의 대비만으로 층을 만든다 (그라데이션·하이라이트 없음).
 */
@Composable
fun TCard(
    modifier: Modifier = Modifier,
    outlined: Boolean = false,
    /** 카드 전체를 탭 대상으로 만든다 (목록 카드 탭 = 상세/편집 패턴) */
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Radius.card)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(T.Graphite, shape)
            // 그림자 대신 1dp 경계선으로 층을 만든다. outlined는 강조용으로 색만 진하게
            .border(1.dp, if (outlined) T.InkFaint else T.Hairline, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(Space.lg),
        content = content,
    )
}

/** 섹션 제목. 작은 대문자식 자간으로 상용 앱의 절제된 헤더 느낌 */
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
        // 토스 섹션 제목은 흐린 소문자가 아니라 또렷한 굵은 진회색이다
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = T.Ink,
        )
        trailing?.invoke()
    }
}

/**
 * 상태 배지. 옅은 색 면 + 색 점(또는 아이콘) + 라벨. 연결·매크로 상태처럼 한 단어 정보에 쓴다.
 * 테두리는 두지 않는다 — 토스 배지는 면 하나로 끝난다. 이모지 대신 [icon]을 쓴다.
 */
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
