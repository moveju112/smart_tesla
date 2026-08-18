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
 * 낮 — 토스/네이버 계열의 밝고 단정한 팔레트.
 *
 * 그라데이션·글로우를 쓰지 않는다. 위계는 색이 아니라 굵기와 여백으로 잡는다.
 */
val LightPalette = Palette(
    void = Color(0xFFF2F4F6),
    carbon = Color(0xFFFFFFFF),
    graphite = Color(0xFFFFFFFF),
    slate = Color(0xFFEDF0F3),
    hairline = Color(0xFFE5E8EB),
    ink = Color(0xFF191F28),
    inkMuted = Color(0xFF4E5968),
    inkFaint = Color(0xFF8B95A1),
    electric = Color(0xFF3182F6),
    electricPressed = Color(0xFF1B64DA),
    electricFaint = Color(0xFFEAF3FF),
    // 냉방은 액센트 파랑과 다른 색이어야 한다 — 같으면 "누를 수 있음"과 "차가 식히는 중"이 안 갈린다
    cool = Color(0xFF0E9FD4),
    heat = Color(0xFFFF6B00),
    warn = Color(0xFFFFB020),
    warnText = Color(0xFFB45309),
    warnFaint = Color(0xFFFFF6E4),
    danger = Color(0xFFF04452),
    onDanger = Color(0xFFFFFFFF),
    ok = Color(0xFF12B886),
    okText = Color(0xFF067A57),
    coolTint = Color(0xFFF1F9FD),
    heatTint = Color(0xFFFFF6F0),
)

/**
 * 밤 — 순검정을 쓰지 않는다.
 *
 * 검정 배경에 흰 글씨는 야간 운전에서 눈이 아프고, 값싼 패널에서 잔상이 남는다.
 * 파랑기 없는 따뜻한 그래파이트로 깔고, 카드를 바탕보다 한 단계 밝게 띄운다.
 */
val DarkPalette = Palette(
    void = Color(0xFF121316),
    carbon = Color(0xFF1A1C20),
    graphite = Color(0xFF1E2126),
    slate = Color(0xFF262A30),
    hairline = Color(0xFF2E323A),
    ink = Color(0xFFF0F1EE),
    inkMuted = Color(0xFFA3A9B3),
    inkFaint = Color(0xFF7B828C),
    electric = Color(0xFF4C93F8),
    electricPressed = Color(0xFF7AB0FA),
    electricFaint = Color(0xFF17273D),
    cool = Color(0xFF35B4E3),
    heat = Color(0xFFFF8534),
    warn = Color(0xFFFFC04D),
    // 어두운 바탕에선 밝은 앰버가 그대로 읽힌다 — 낮처럼 어둡게 죽일 필요가 없다
    warnText = Color(0xFFFFC04D),
    warnFaint = Color(0xFF33280F),
    danger = Color(0xFFFF6B72),
    onDanger = Color(0xFF1A1012),
    ok = Color(0xFF2DD4A0),
    okText = Color(0xFF2DD4A0),
    coolTint = Color(0xFF14222B),
    heatTint = Color(0xFF2A1D14),
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
