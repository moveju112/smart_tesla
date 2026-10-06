package com.wemade.teslamacro.ui.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wemade.teslamacro.ui.theme.Motion
import com.wemade.teslamacro.ui.theme.Radius
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

/** 진행 중일 때 강조색 막대를 표시하고 동작 제거 설정에서는 정지 상태로 남긴다. */
@Composable
fun IndeterminateBar(
    modifier: Modifier = Modifier,
    color: Color = T.Electric,
    active: Boolean = true,
) {
    if (!active) {
        // 자리를 유지해야 켜질 때 레이아웃이 튀지 않는다
        Box(modifier = modifier.fillMaxWidth().height(2.dp))
        return
    }

    // 애니메이션을 껐으면 훑는 대신 가득 찬 선으로 "진행 중"을 표시한다
    if (reducedMotion()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(color),
        )
        return
    }

    val transition = rememberInfiniteTransition(label = "indeterminate")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = Motion.breathe(1200),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp)
            .clip(RoundedCornerShape(Radius.pill))
            .background(color.copy(alpha = 0.15f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(SEGMENT_FRACTION)
                .height(2.dp)
                // 왼쪽 밖에서 오른쪽 밖으로 한 번 훑고 반복한다
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val travel = constraints.maxWidth + placeable.width
                    layout(constraints.maxWidth, placeable.height) {
                        placeable.placeRelative(
                            x = (travel * progress).toInt() - placeable.width,
                            y = 0,
                        )
                    }
                }
                .background(color),
        )
    }
}

private const val SEGMENT_FRACTION = 0.35f

/** 아직 수신되지 않은 값의 자리를 유지한다. */
@Composable
fun SkeletonBlock(
    width: Int,
    height: Int,
    modifier: Modifier = Modifier,
) {
    val alpha = if (reducedMotion()) {
        0.55f
    } else {
        val transition = rememberInfiniteTransition(label = "skeleton")
        val animatedAlpha by transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 0.55f,
            animationSpec = infiniteRepeatable(
                animation = Motion.breathe(900),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "breath",
        )
        animatedAlpha
    }
    // 값이 없는 구역과 구별되는 중립 면을 유지한다.
    Box(
        modifier = modifier
            .width(width.dp)
            .height(height.dp)
            .clip(RoundedCornerShape(Radius.button))
            .background(T.Slate.copy(alpha = alpha)),
    )
}

/** 비어 있는 목록의 이유와 가능한 동작을 함께 표시한다. */
@Composable
fun EmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = Space.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = T.InkMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Space.sm))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = T.InkFaint,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Space.lg))
            TButton(actionLabel, fillWidth = false, onClick = onAction)
        }
    }
}
