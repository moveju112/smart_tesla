package com.wemade.teslamacro.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 낮/밤 두 벌로 갈리는 색 묶음.
 *
 * 토큰 이름은 예전 `object T`와 똑같이 유지한다 — 화면 코드 253곳을 안 건드리기 위해서다.
 * 값만 팔레트별로 갈리고, 꺼내 쓰는 문법(`T.Ink`)은 그대로다.
 */
@Immutable
data class Palette(
    // 배경 계층 — 바탕 위에 카드가 한 겹 떠 있다
    val void: Color,
    val carbon: Color,
    val graphite: Color,
    val slate: Color,
    val hairline: Color,
    // 텍스트 3단계
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    // 액센트 — 누를 수 있는 것
    val electric: Color,
    val electricPressed: Color,
    val electricFaint: Color,
    // 의미색 — 차가 지금 뭘 하는지, 뭐가 잘못됐는지
    val cool: Color,
    val heat: Color,
    val warn: Color,
    val warnText: Color,
    val warnFaint: Color,
    val danger: Color,
    /** 경보 면 위에 얹는 글자색. 밤 팔레트의 빨강은 밝아서 흰 글씨가 안 읽힌다 */
    val onDanger: Color,
    val ok: Color,
    val okText: Color,
    val coolTint: Color,
    val heatTint: Color,
)

/**
 * 낮 — 각지고 조용한 팔레트.
 *
 * 그림자를 안 쓴다. 층은 아주 옅은 명도 차와 1dp 경계선으로만 만든다.
 * 액센트가 파랑이 아니라 검정이다 — 유채색을 액센트에서 빼야
 * 냉방·난방·경보의 색이 유일한 유채색으로 남아 곧바로 눈에 걸린다.
 * 회색은 파랑기 없이 따뜻한 쪽으로 잡아 종이에 가깝게 둔다.
 */
val LightPalette = Palette(
    void = Color(0xFFEFEFED),
    carbon = Color(0xFFFAFAF8),
    graphite = Color(0xFFFAFAF8),
    slate = Color(0xFFE4E4E0),
    hairline = Color(0xFFD8D8D4),
    ink = Color(0xFF141414),
    inkMuted = Color(0xFF55554F),
    inkFaint = Color(0xFF7A7A76),
    // 액센트 = 검정. 누를 수 있다는 표시에 색을 쓰지 않는다
    electric = Color(0xFF141414),
    electricPressed = Color(0xFF3A3A38),
    electricFaint = Color(0xFFE4E4E0),
    // 의미색은 남기되 채도를 낮춘다 — 조용한 바탕에선 이 정도로도 충분히 튄다
    cool = Color(0xFF0E7490),
    heat = Color(0xFFB45309),
    warn = Color(0xFFA16207),
    warnText = Color(0xFF7C4A03),
    warnFaint = Color(0xFFF0EBDE),
    danger = Color(0xFFB3261E),
    onDanger = Color(0xFFFAFAF8),
    ok = Color(0xFF4D7C0F),
    okText = Color(0xFF3F6212),
    coolTint = Color(0xFFEDF2F3),
    heatTint = Color(0xFFF5F0E9),
)

/**
 * 밤 — 같은 성격의 어두운 판.
 *
 * 순검정을 쓰지 않는다. 야간 운전에서 눈이 아프고 값싼 패널에서 잔상이 남는다.
 * 액센트는 흰색에 가깝게 — 낮의 검정 액센트를 그대로 뒤집은 것이다.
 */
val DarkPalette = Palette(
    void = Color(0xFF161614),
    carbon = Color(0xFF1C1C1A),
    graphite = Color(0xFF1E1E1C),
    slate = Color(0xFF2A2A27),
    hairline = Color(0xFF34342F),
    ink = Color(0xFFF2F2EE),
    inkMuted = Color(0xFFA8A8A1),
    inkFaint = Color(0xFF7E7E77),
    electric = Color(0xFFF2F2EE),
    electricPressed = Color(0xFFC9C9C2),
    electricFaint = Color(0xFF2A2A27),
    cool = Color(0xFF4FB6CF),
    heat = Color(0xFFE0964A),
    warn = Color(0xFFD9A441),
    warnText = Color(0xFFD9A441),
    warnFaint = Color(0xFF2E2718),
    danger = Color(0xFFE5544A),
    onDanger = Color(0xFF16100F),
    ok = Color(0xFF86B84A),
    okText = Color(0xFF86B84A),
    coolTint = Color(0xFF17252A),
    heatTint = Color(0xFF2A2019),
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
    val CoolTint: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.coolTint
    val HeatTint: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.heatTint
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
