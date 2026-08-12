package com.wemade.teslamacro.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/** 8dp 그리드. 화면에서 raw dp를 쓰지 말고 여기서 꺼내 쓴다 */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
}

/** 토스 계열 둥글기 — 카드는 넉넉히, 버튼은 또렷하게 */
object Radius {
    val button = 14.dp
    val card = 18.dp
    val hero = 20.dp
    val pill = 999.dp
}

/**
 * 그림자 세기. 밝은 배경이라 아주 얕게만 준다.
 * 흰 카드는 옅은 회색 배경과의 대비 + 미세 그림자로만 뜬다.
 */
object Elevation {
    val card = 2.dp
    val button = 0.dp
    val hero = 3.dp
}

/** 원본의 0.33s cubic-bezier를 그대로 옮긴 공용 모션 */
object Motion {
    private val Standard = CubicBezierEasing(0.5f, 0f, 0f, 0.75f)
    fun <T> standard() = tween<T>(durationMillis = 330, easing = Standard)
    fun <T> quick() = tween<T>(durationMillis = 160, easing = Standard)
}

private val TeslaShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.button),
    small = RoundedCornerShape(Radius.button),
    medium = RoundedCornerShape(Radius.card),
    large = RoundedCornerShape(Radius.card),
)

private val TeslaColorScheme = lightColorScheme(
    primary = T.Electric,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    secondary = T.InkMuted,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    background = T.Void,
    onBackground = T.Ink,
    surface = T.Carbon,
    onSurface = T.Ink,
    surfaceVariant = T.Slate,
    onSurfaceVariant = T.InkMuted,
    outline = T.Hairline,
    error = T.Danger,
    onError = androidx.compose.ui.graphics.Color.White,
)

@Composable
fun TeslaMacroTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TeslaColorScheme,
        typography = TeslaTypography,
        shapes = TeslaShapes,
        content = content,
    )
}
