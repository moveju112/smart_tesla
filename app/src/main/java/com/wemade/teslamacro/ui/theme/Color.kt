package com.wemade.teslamacro.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Material 색상표를 역할별로 묶어 낮과 밤의 대비를 함께 관리한다. */
@Immutable
data class Palette(
    // 배경과 콘텐츠 면의 계층
    val void: Color,
    val carbon: Color,
    val graphite: Color,
    val slate: Color,
    val hairline: Color,
    // 본문·보조·설명 텍스트의 대비
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    // 액센트 — 주요 동작과 선택 상태
    val electric: Color,
    val electricPressed: Color,
    val electricFaint: Color,
    // 상태색 — 냉각·난방·주의·오류
    val cool: Color,
    val heat: Color,
    val warn: Color,
    val warnText: Color,
    val warnFaint: Color,
    val danger: Color,
    /** 경보 면 위에 얹는 글자색. 적색 면 위엔 종이색 글씨가 얹힌다 */
    val onDanger: Color,
    val ok: Color,
    val okText: Color,
)

/** 낮에는 Blue Grey 계열의 밝은 면과 Material Blue 800을 기본으로 쓴다. */
val LightPalette = Palette(
    void = Color(0xFFF3F6FA),
    carbon = Color(0xFFFFFFFF),
    graphite = Color(0xFFFFFFFF),
    slate = Color(0xFFE8EEF5),
    hairline = Color(0xFFD1DAE5),
    ink = Color(0xFF1C2B3A),
    inkMuted = Color(0xFF46596B),
    inkFaint = Color(0xFF52677B),
    electric = Color(0xFF1565C0),
    electricPressed = Color(0xFF0D47A1),
    electricFaint = Color(0xFFE3F2FD),
    cool = Color(0xFF0277BD),
    heat = Color(0xFFBF360C),
    warn = Color(0xFF8D5700),
    warnText = Color(0xFF795000),
    warnFaint = Color(0xFFFFF3E0),
    danger = Color(0xFFC62828),
    onDanger = Color(0xFFFFFFFF),
    ok = Color(0xFF2E7D32),
    okText = Color(0xFF256629),
)

/** 밤에는 차콜 면과 Blue 200으로 조작과 콘텐츠를 구분한다. */
val DarkPalette = Palette(
    void = Color(0xFF141C24),
    carbon = Color(0xFF1E2935),
    graphite = Color(0xFF24313F),
    slate = Color(0xFF2C3B4C),
    hairline = Color(0xFF43576B),
    ink = Color(0xFFEFF5FC),
    inkMuted = Color(0xFFC1CFDF),
    inkFaint = Color(0xFFAABCD0),
    electric = Color(0xFF90CAF9),
    electricPressed = Color(0xFFBBDEFB),
    electricFaint = Color(0xFF213B55),
    cool = Color(0xFF81D4FA),
    heat = Color(0xFFFFAB91),
    warn = Color(0xFFFFCC80),
    warnText = Color(0xFFFFCC80),
    warnFaint = Color(0xFF3E3020),
    danger = Color(0xFFFFAB91),
    onDanger = Color(0xFF29130F),
    ok = Color(0xFFA5D6A7),
    okText = Color(0xFFA5D6A7),
)

/** 지금 팔레트. [TeslaMacroTheme]이 낮/밤에 맞춰 갈아 끼운다 */
val LocalPalette = staticCompositionLocalOf { LightPalette }

/**
 * 색 토큰 꺼내는 곳.
 *
 * 예전엔 상수 묶음이었지만 낮/밤을 갈아 끼우려고 읽기 전용 게터로 바꿨다.
 * 호출부(`T.Ink`)는 그대로라 화면 코드는 영향이 없다.
 * 단 @Composable 밖(상태 클래스·enum 등)에서는 못 쓴다 — 거기선 [ColorRole]을 쓴다.
 */
object T {
    val Void: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.void
    val Carbon: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.carbon
    val Graphite: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.graphite
    val Slate: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.slate
    val Hairline: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.hairline
    val Ink: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.ink
    val InkMuted: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.inkMuted
    val InkFaint: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.inkFaint
    val Electric: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.electric
    val ElectricPressed: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.electricPressed
    val ElectricFaint: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.electricFaint
    val Cool: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.cool
    val Heat: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.heat
    val Warn: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.warn
    val WarnText: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.warnText
    val WarnFaint: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.warnFaint
    val Danger: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.danger
    val OnDanger: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.onDanger
    val Ok: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.ok
    val OkText: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.okText
}

/**
 * @Composable 밖에서 색을 고를 때 쓰는 이름표.
 *
 * 상태 클래스가 `Color`를 직접 들고 있으면 팔레트가 바뀌어도 낮 색이 그대로 남는다.
 * 그래서 상태는 "무슨 뜻인지"만 정하고, 실제 색은 그리는 쪽에서 [color]로 푼다.
 */
enum class ColorRole {
    Ink, InkMuted, InkFaint, Electric, Cool, Heat, Warn, WarnText, Danger, Ok, OkText;

    val color: Color
        @Composable @ReadOnlyComposable get() = when (this) {
            Ink -> T.Ink
            InkMuted -> T.InkMuted
            InkFaint -> T.InkFaint
            Electric -> T.Electric
            Cool -> T.Cool
            Heat -> T.Heat
            Warn -> T.Warn
            WarnText -> T.WarnText
            Danger -> T.Danger
            Ok -> T.Ok
            OkText -> T.OkText
        }
}
