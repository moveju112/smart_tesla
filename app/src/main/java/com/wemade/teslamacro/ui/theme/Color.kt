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
 *
 * 기존 토큰 이름을 유지하면서 배경·콘텐츠 면·강조색을 낮과 밤에 맞춘다.
 */
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

/** 휴대폰 낮 화면: 밝은 뉴트럴 바탕과 블루 포인트로 정보 계층을 구분한다. */
val LightPalette = Palette(
    void = Color(0xFFF5F6F8),
    carbon = Color(0xFFFFFFFF),
    graphite = Color(0xFFFFFFFF),
    slate = Color(0xFFECEEF2),
    hairline = Color(0xFFD8DCE3),
    ink = Color(0xFF20242B),
    inkMuted = Color(0xFF555E6B),
    // 4.5:1을 넘겨야 한다. 예전 #8F8D84는 2.91:1로, 부품 라벨·표 머리글·치수 이름이
    // 전부 이 색이었다 — 직사광 아래 11sp로 읽어야 하는 글자들이다
    inkFaint = Color(0xFF646D7A),
    // 주요 동작은 블루로 구별한다
    electric = Color(0xFF3569B7),
    electricPressed = Color(0xFF285393),
    electricFaint = Color(0xFFE8EFFA),
    // 제도 청 — 기준선과 냉각
    cool = Color(0xFF1F5C8C),
    // 제도 적 — 정정과 주의. 난방·경보가 같은 계열의 농담으로 갈린다
    heat = Color(0xFFB3411F),
    warn = Color(0xFFA1601A),
    warnText = Color(0xFF7E4712),
    warnFaint = Color(0xFFEDE4D2),
    danger = Color(0xFFC8321E),
    onDanger = Color(0xFFF2F0E9),
    // 정상 상태는 포인트와 같은 계열로 표시한다
    ok = Color(0xFF3569B7),
    okText = Color(0xFF3569B7),
)

/** 밤에는 무채색의 어두운 면과 밝은 블루 포인트로 대비를 유지한다. */
val DarkPalette = Palette(
    void = Color(0xFF15171B),
    carbon = Color(0xFF202329),
    graphite = Color(0xFF202329),
    slate = Color(0xFF2C3038),
    hairline = Color(0xFF424852),
    ink = Color(0xFFEFF1F5),
    inkMuted = Color(0xFFBDC3CD),
    // 밤도 4.13:1로 미달이었다
    inkFaint = Color(0xFFAAB2BF),
    electric = Color(0xFF91B4E8),
    electricPressed = Color(0xFFB1CCF2),
    electricFaint = Color(0xFF293A53),
    cool = Color(0xFF6FB6E0),
    heat = Color(0xFFE08A5A),
    warn = Color(0xFFD9A441),
    warnText = Color(0xFFD9A441),
    warnFaint = Color(0xFF2B2718),
    danger = Color(0xFFE8624E),
    onDanger = Color(0xFF101619),
    ok = Color(0xFF91B4E8),
    okText = Color(0xFF91B4E8),
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
