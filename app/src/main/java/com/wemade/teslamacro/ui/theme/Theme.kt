package com.wemade.teslamacro.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.util.Calendar
import kotlinx.coroutines.delay

/** 8dp 그리드. 화면에서 raw dp를 쓰지 말고 여기서 꺼내 쓴다 */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
}

/**
 * 도면에 둥근 모서리는 없다. 전부 0dp다.
 *
 * 예전엔 4dp였다. 4dp는 "각지게 하려고 했다"는 표시일 뿐이고,
 * 제도된 판에서는 선이 만나는 곳이 그냥 만나야 한다.
 * 알약(999dp)은 점·구멍처럼 실제로 원인 것에만 남긴다.
 */
object Radius {
    val button = 0.dp
    val card = 0.dp
    val hero = 0.dp
    val pill = 999.dp
    val segment = 0.dp
    val tile = 0.dp
}

/**
 * 선 굵기 3계층 — 도면 규범.
 *
 * 도면이 읽히는 건 색이 아니라 선 굵기의 계층 덕분이다.
 * 굵기를 마음대로 정하면 그 계층이 무너져 전부 같은 무게로 보인다.
 *
 * 실기기 패널이 값싸서 0.5dp가 사라질 수 있다 — 그래서 치수·격자에만 쓰고
 * 정보를 지고 있는 선은 최소 [thin]으로 올린다.
 */
object Stroke {
    /** 치수선 · 격자 · 지시선. 정보를 지지 않는 보조선 */
    val hair = 0.5.dp
    /** 부품 윤곽 · 판 경계 · 표 괘선 */
    val thin = 1.dp
    /** 외곽선 · 주요 부품 · 지금 고른 것 */
    val bold = 2.dp
}


/** 원본의 0.33s cubic-bezier를 그대로 옮긴 공용 모션 */
object Motion {
    private val Standard = CubicBezierEasing(0.5f, 0f, 0f, 0.75f)
    fun <T> standard() = tween<T>(durationMillis = 330, easing = Standard)
    fun <T> quick() = tween<T>(durationMillis = 160, easing = Standard)

    // 반복(숨쉬기·훑기) 애니메이션 공용 스펙 — 화면마다 tween 리터럴을 만들지 않는다
    fun <T> breathe(durationMillis: Int) =
        tween<T>(durationMillis = durationMillis, easing = LinearEasing)
}

private val TeslaShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.button),
    small = RoundedCornerShape(Radius.button),
    medium = RoundedCornerShape(Radius.card),
    large = RoundedCornerShape(Radius.card),
)

// 낮이 시작·끝나는 시각. 계절마다 해 뜨는 때가 달라 정밀 계산은 과하고,
// 실차에서 눈부심을 보고 조절할 수 있게 상수로 빼 둔다
private const val DAY_START_HOUR = 7
private const val DAY_END_HOUR = 19

/**
 * 시계만 보고 밤인지 정한다. 태블릿 시스템 다크가 꺼져 있어도 밤엔 어두워져야 한다.
 * 액티비티가 첫 프레임 배경·상태바를 맞출 때도 같은 답을 써야 해서 밖에 열어 둔다.
 */
fun isNightNow(): Boolean {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return hour < DAY_START_HOUR || hour >= DAY_END_HOUR
}

// 경계(07시·19시)를 넘겼는지 보는 주기. 분 단위로 볼 이유가 없다
private const val NIGHT_RECHECK_MILLIS = 10 * 60 * 1000L

/** 시간이 흐르면 스스로 낮↔밤을 뒤집는다 */
@Composable
private fun rememberIsNight(): Boolean {
    var night by remember { mutableStateOf(isNightNow()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(NIGHT_RECHECK_MILLIS)
            night = isNightNow()
        }
    }
    return night
}

private fun colorSchemeFor(palette: Palette, dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = palette.electric,
        onPrimary = Color.White,
        secondary = palette.inkMuted,
        onSecondary = Color.White,
        background = palette.void,
        onBackground = palette.ink,
        surface = palette.carbon,
        onSurface = palette.ink,
        surfaceVariant = palette.slate,
        onSurfaceVariant = palette.inkMuted,
        outline = palette.hairline,
        error = palette.danger,
        onError = Color.White,
    )
} else {
    lightColorScheme(
        primary = palette.electric,
        onPrimary = Color.White,
        secondary = palette.inkMuted,
        onSecondary = Color.White,
        background = palette.void,
        onBackground = palette.ink,
        surface = palette.carbon,
        onSurface = palette.ink,
        surfaceVariant = palette.slate,
        onSurfaceVariant = palette.inkMuted,
        outline = palette.hairline,
        error = palette.danger,
        onError = Color.White,
    )
}

/**
 * @param dark null이면 시계를 보고 스스로 정한다.
 *   스냅샷 테스트처럼 결과가 고정돼야 하는 곳에서만 true/false를 직접 넘긴다.
 */
@Composable
fun TeslaMacroTheme(dark: Boolean? = null, content: @Composable () -> Unit) {
    val isDark = dark ?: rememberIsNight()
    val palette = if (isDark) DarkPalette else LightPalette
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            colorScheme = colorSchemeFor(palette, isDark),
            typography = TeslaTypography,
            shapes = TeslaShapes,
            content = content,
        )
    }
}
