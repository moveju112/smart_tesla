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
