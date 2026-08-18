package com.wemade.teslamacro.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

/**
 * 앱 초기화 화면.
 *
 * 대개 한 프레임 만에 지나가지만, 첫 실행이나 저장 파일이 클 때는 눈에 띈다.
 * "준비 중…" 텍스트만 두면 멈춘 것처럼 보여서 흐르는 표시를 넣었다.
 */
@Composable
fun AppSplash(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AirflowMark()

        Spacer(Modifier.height(Space.lg))
        Text(
            text = "SMART TESLA",
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 15.sp,
                letterSpacing = 4.sp,   // 워드마크에만 자간을 준다
            ),
            color = T.Ink,
        )
        Spacer(Modifier.height(Space.sm))
        Text(
            text = "차량 연결 준비 중",
            style = MaterialTheme.typography.bodySmall,
            color = T.InkFaint,
        )

        Spacer(Modifier.height(Space.xl))
        Box(modifier = Modifier.width(160.dp)) { IndeterminateBar() }
    }
}

/**
 * 통풍 바람을 형상화한 마크. 선 3개가 시간차로 흘러간다.
 * 이미지 자산 없이 Canvas로 그려서 어느 해상도에서도 선명하다.
 */
@Composable
private fun AirflowMark(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "airflow")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    // 그리기 람다 안에서는 색 토큰을 못 읽는다 — 바깥에서 꺼내 둔다
    val markColor = T.Electric
    Canvas(modifier = modifier.size(width = 88.dp, height = 56.dp)) {
        val lineCount = 3
        val gap = size.height / (lineCount + 1)

        repeat(lineCount) { index ->
            // 선마다 위상을 어긋나게 해서 바람이 흐르는 느낌을 만든다
            val offset = (phase + index * 0.22f) % 1f
            val length = size.width * 0.45f
            val startX = -length + (size.width + length) * offset
            val y = gap * (index + 1)

            // 화면 밖으로 나가는 구간은 서서히 사라지게 한다
            val fade = 1f - kotlin.math.abs(offset - 0.5f) * 1.6f
            drawLine(
                color = markColor.copy(alpha = fade.coerceIn(0f, 1f)),
                start = Offset(startX.coerceAtLeast(0f), y),
                end = Offset((startX + length).coerceAtMost(size.width), y),
                // 픽셀 리터럴은 고밀도 화면에서 실처럼 얇아진다 — dp 기준으로 굵기 고정
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}
