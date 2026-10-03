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

/** 상태의 의미에 따라 중립·냉각·난방·경고 강조를 선택한다. */
enum class TileTone { Calm, Cool, Warm, Alert }

/** 실제 공조가 동작 중인 경우 막대의 부드러운 반복으로 상태를 보조한다. */
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
