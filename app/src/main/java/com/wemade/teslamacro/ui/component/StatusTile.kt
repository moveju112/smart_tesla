package com.wemade.teslamacro.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wemade.teslamacro.ui.theme.Motion
import com.wemade.teslamacro.ui.theme.Radius
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T
import com.wemade.teslamacro.ui.theme.TileValueStyle
import com.wemade.teslamacro.ui.theme.TileValueStyleLarge

/**
 * 타일의 성격.
 *
 * 이 앱의 색 규칙이 여기 다 들어있다 — 평소엔 [Calm]이라 화면 전체가 무채색이고,
 * 차가 실제로 뭔가 하고 있을 때만 [Cool]/[Warm], 사람이 봐야 할 때만 [Alert]다.
 * 색이 곧 "이걸 봐라"라는 신호라서 아껴 쓴다.
 */
enum class TileTone { Calm, Cool, Warm, Alert }

/**
 * 상태 타일 — 이 앱 홈 화면의 기본 단위.
 *
 * 값이 크고 라벨이 작다. 보통 앱과 반대인데, 운전 중에 곁눈으로 읽으려면
 * "무엇인지"보다 "얼마인지"가 먼저 들어와야 하기 때문이다.
 * 위치가 고정이라 라벨은 한 번 외우면 다시 안 읽게 된다.
 *
 * @param big 히어로 타일(실내 온도)만 true. 크기로 중요도를 고정한다
 * @param content 값 아래에 붙는 조작 (스텝퍼·단계 선택 등)
 */
@Composable
fun StatusTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    tone: TileTone = TileTone.Calm,
    big: Boolean = false,
    pending: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val alert = tone == TileTone.Alert
    // 경보만 면을 통째로 채운다. 나머지는 글자만 물든다 —
    // 면이 물드는 타일이 둘 이상이면 어느 쪽을 봐야 할지 알 수 없다
    val background by animateColorAsState(
        targetValue = if (alert) T.Danger else T.Graphite,
        animationSpec = Motion.standard(),
        label = "tileBackground",
    )
    val onAlert = T.OnDanger
    val valueColor = when {
        alert -> onAlert
        tone == TileTone.Cool -> T.Cool
        tone == TileTone.Warm -> T.Heat
        else -> T.Ink
    }
    val labelColor = if (alert) onAlert.copy(alpha = 0.75f) else T.InkFaint
    val detailColor = if (alert) onAlert.copy(alpha = 0.9f) else T.InkMuted

    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.tile))
            .background(background)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null) {
                        onClick()
                    }
                } else {
                    Modifier
                }
            )
            // 주행 중 조작이라 타일은 손가락보다 넉넉해야 한다
            .heightIn(min = if (big) 200.dp else 108.dp)
            .padding(Space.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
                modifier = Modifier.weight(1f),
            )
            // 명령이 오가는 중이라는 표시. 점 하나로 끝낸다
            if (pending) {
                Box(
                    modifier = Modifier
                        .height(6.dp)
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(if (alert) onAlert else T.Electric)
                        .fillMaxWidth(0.06f),
                )
            }
        }

        // 라벨만 천장에 못 박고, 값 덩어리는 남는 공간의 한가운데에 띄운다.
        // 위아래로 같은 여백을 두면 타일이 커져도 빈 구석이 생기지 않는다
        Spacer(Modifier.weight(1f))

        Text(
            text = value,
            style = if (big) TileValueStyleLarge else TileValueStyle,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (detail != null) {
            Spacer(Modifier.height(Space.xs))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = detailColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (content != null) {
            Spacer(Modifier.height(Space.md))
            content()
        }

        Spacer(Modifier.weight(1f))

        // 바닥에 붙는 보조 수치들. 큰 타일이 허전해지지 않게 받쳐준다
        if (footer != null) footer()
    }
}

/**
 * 앱에서 유일하게 반복 움직이는 것.
 *
 * 공조가 실제로 돌아가는 동안에만 천천히 숨쉰다.
 * 다른 곳에 반복 애니메이션을 넣지 않는 이유 — 움직임 자체가
 * "차가 지금 일하는 중"이라는 뜻이 되어야 해서다.
 */
@Composable
fun BreathingBar(color: Color, modifier: Modifier = Modifier) {
    // 기기에서 애니메이션을 껐으면 움직이지 않는다. 다만 막대는 남긴다 —
    // 이 막대의 존재 자체가 "공조가 돌고 있다"는 정보라 지우면 안 된다
    if (reducedMotion()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(Radius.pill))
                .background(color),
        )
        return
    }

    val transition = rememberInfiniteTransition(label = "breathing")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = Motion.breathe(2_000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathingAlpha",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(Radius.pill))
            .background(color.copy(alpha = alpha)),
    )
}
