package com.wemade.teslamacro.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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

/**
 * 진행 중 표시. 스피너 대신 2dp 선을 쓴다.
 * 원형 스피너는 Material 색을 물고 들어와 다크 팔레트를 깨뜨린다.
 */
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

/**
 * 아직 값을 못 읽은 자리. 대시(--)만 띄우면 "고장인가"로 읽힌다.
 * 은은하게 숨 쉬게 해서 "읽는 중"임을 보여준다.
 */
@Composable
fun SkeletonBlock(
    width: Int,
    height: Int,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = Motion.breathe(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )
    // alpha 모디파이어는 background 뒤에선 효과가 없다 — 색 알파로 숨쉬게 한다
    Box(
        modifier = modifier
            .width(width.dp)
            .height(height.dp)
            .clip(RoundedCornerShape(Radius.button))
            .background(T.Slate.copy(alpha = alpha)),
    )
}

/**
 * 목록이 비었을 때. 빈 화면을 그냥 두면 로딩 실패인지 원래 없는 건지 알 수 없다.
 */
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

/**
 * 화면 안에 뜨는 알림 줄. 토스트를 안 쓰는 이유는
 * 차량 화면에서 스쳐 지나가면 왜 실패했는지 놓치기 때문이다.
 */
@Composable
fun InlineBanner(
    message: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    tone: BannerTone = BannerTone.Error,
) {
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        val color = when (tone) {
            BannerTone.Error -> T.Danger
            BannerTone.Warning -> T.Warn
            BannerTone.Info -> T.Electric
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(color.copy(alpha = 0.12f), RoundedCornerShape(Radius.button))
                .padding(horizontal = Space.md, vertical = Space.sm + Space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = message.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = color,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Space.xs))
            // 시각 크기는 유지하고 터치 타깃만 44dp로 — 차 안 오탭 방지 기준
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.pill))
                    .clickable(onClick = onDismiss)
                    .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "닫기",
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                )
            }
        }
    }
}

enum class BannerTone { Error, Warning, Info }

