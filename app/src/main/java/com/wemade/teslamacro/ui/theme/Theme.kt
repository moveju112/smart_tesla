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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import com.wemade.teslamacro.data.settings.ThemeMode
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

/** 손으로 누르는 요소와 콘텐츠 면의 모서리를 구분한다. */
object Radius {
    val button = 12.dp
    val card = 20.dp
    val hero = 24.dp
    val pill = 999.dp
    val segment = 12.dp
    val tile = 16.dp
}

/** 구분선과 선택 윤곽을 같은 굵기로 유지한다. */
object Stroke {
    /** 정보가 없는 보조 구분선 */
    val hair = 0.5.dp
    /** 입력·목록 경계 */
    val thin = 1.dp
    /** 선택 항목 윤곽 */
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

/** Material 기본 보라색이 메뉴·선택 표시로 새지 않도록 모든 면 역할을 연결한다. */
private fun colorSchemeFor(palette: Palette, dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = palette.electric, onPrimary = palette.void,
        primaryContainer = palette.electricFaint, onPrimaryContainer = palette.electric,
        inversePrimary = LightPalette.electric,
        secondary = palette.electric, onSecondary = palette.void,
        secondaryContainer = palette.electricFaint, onSecondaryContainer = palette.electric,
        tertiary = palette.ok, onTertiary = palette.void,
        tertiaryContainer = palette.slate, onTertiaryContainer = palette.okText,
        background = palette.void, onBackground = palette.ink,
        surface = palette.carbon, onSurface = palette.ink,
        surfaceVariant = palette.slate, onSurfaceVariant = palette.inkMuted,
        surfaceTint = palette.electric,
        surfaceDim = palette.void, surfaceBright = palette.graphite,
        surfaceContainerLowest = palette.void, surfaceContainerLow = palette.carbon,
        surfaceContainer = palette.carbon, surfaceContainerHigh = palette.graphite,
        surfaceContainerHighest = palette.slate,
        inverseSurface = LightPalette.carbon, inverseOnSurface = LightPalette.ink,
        outline = palette.inkFaint, outlineVariant = palette.hairline,
        error = palette.danger, onError = palette.onDanger,
        errorContainer = palette.danger.copy(alpha = 0.12f).compositeOver(palette.carbon),
        onErrorContainer = palette.danger,
    )
} else {
    lightColorScheme(
        primary = palette.electric, onPrimary = Color.White,
        primaryContainer = palette.electricFaint, onPrimaryContainer = palette.electricPressed,
        inversePrimary = DarkPalette.electric,
        secondary = palette.electric, onSecondary = Color.White,
        secondaryContainer = palette.electricFaint, onSecondaryContainer = palette.electric,
        tertiary = palette.ok, onTertiary = Color.White,
        tertiaryContainer = palette.slate, onTertiaryContainer = palette.okText,
        background = palette.void, onBackground = palette.ink,
        surface = palette.carbon, onSurface = palette.ink,
        surfaceVariant = palette.slate, onSurfaceVariant = palette.inkMuted,
        surfaceTint = palette.electric,
        surfaceDim = palette.slate, surfaceBright = palette.carbon,
        surfaceContainerLowest = palette.carbon, surfaceContainerLow = palette.void,
        surfaceContainer = palette.carbon, surfaceContainerHigh = palette.void,
        surfaceContainerHighest = palette.slate,
        inverseSurface = DarkPalette.carbon, inverseOnSurface = DarkPalette.ink,
        outline = palette.inkFaint, outlineVariant = palette.hairline,
        error = palette.danger, onError = palette.onDanger,
        errorContainer = palette.danger.copy(alpha = 0.08f).compositeOver(palette.carbon),
        onErrorContainer = palette.danger,
    )
}

private val LightScheme = colorSchemeFor(LightPalette, dark = false)
private val DarkScheme = colorSchemeFor(DarkPalette, dark = true)

/**
 * @param dark null이면 저장된 mode를 따르고, 자동일 때만 시각으로 정한다.
 *   스냅샷 테스트처럼 결과가 고정돼야 하는 곳에서만 true/false를 직접 넘긴다.
 */
@Composable
fun TeslaMacroTheme(dark: Boolean? = null, mode: ThemeMode = ThemeMode.AUTO, content: @Composable () -> Unit) {
    val isDark = dark ?: mode.isDark(if (mode == ThemeMode.AUTO) rememberIsNight() else false)
    val palette = if (isDark) DarkPalette else LightPalette
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            colorScheme = if (isDark) DarkScheme else LightScheme,
            typography = TeslaTypography,
            shapes = TeslaShapes,
            content = content,
        )
    }
}
