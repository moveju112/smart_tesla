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
 * 세계가 도면으로 바뀌면서 각 이름이 가리키는 대상도 바뀌었다:
 * 배경은 종이, 액센트는 잉크, 의미색은 도면 정정 관행의 적·청 2색뿐이다.
 */
@Immutable
data class Palette(
    // 종이 계층 — 판 위에 표제란과 채워진 면이 얹힌다
    val void: Color,
    val carbon: Color,
    val graphite: Color,
    val slate: Color,
    val hairline: Color,
    // 잉크 3단계
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    // 액센트 — 누를 수 있는 것. 도면에서 그건 잉크다
    val electric: Color,
    val electricPressed: Color,
    val electricFaint: Color,
    // 의미색 — 도면 정정 2색. 청은 기준·냉각, 적은 정정·주의
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

/**
 * 낮 — 제도지에 단색 인쇄.
 *
 * 종이는 순백이 아니다. 미색이 살짝 있어야 직사광에서 눈이 덜 아프고,
 * 그 위의 잉크가 검정이 아니라 인쇄된 것처럼 보인다.
 *
 * 유채색은 두 개뿐이다 — 도면 정정 관행의 적(주의·정정)과 청(기준·냉각).
 * 나머지 화면 전부가 단색 잉크다. 그래서 색이 하나 뜨면 그게 곧 소식이다.
 */
val LightPalette = Palette(
    void = Color(0xFFF2F0E9),        // 제도지
    carbon = Color(0xFFEAE7DE),      // 표제란 · 시트 여백
    graphite = Color(0xFFF2F0E9),    // 판은 종이와 같은 색이다. 카드가 떠 있지 않다
    slate = Color(0xFFE1DDD1),       // 채워진 면 · 눌린 상태
    hairline = Color(0xFFB9B5A8),    // 치수선 · 격자
    ink = Color(0xFF1A1A17),
    inkMuted = Color(0xFF5D5C55),
    // 4.5:1을 넘겨야 한다. 예전 #8F8D84는 2.91:1로, 부품 라벨·표 머리글·치수 이름이
    // 전부 이 색이었다 — 직사광 아래 11sp로 읽어야 하는 글자들이다
    inkFaint = Color(0xFF6E6C64),
    // 누를 수 있다는 표시에 유채색을 쓰지 않는다. 도면의 강조는 잉크가 진해지는 것이다
    electric = Color(0xFF1A1A17),
    electricPressed = Color(0xFF44433D),
    electricFaint = Color(0xFFE1DDD1),
    // 제도 청 — 기준선과 냉각
    cool = Color(0xFF1F5C8C),
    // 제도 적 — 정정과 주의. 난방·경보가 같은 계열의 농담으로 갈린다
    heat = Color(0xFFB3411F),
    warn = Color(0xFFA1601A),
    warnText = Color(0xFF7E4712),
    warnFaint = Color(0xFFEDE4D2),
    danger = Color(0xFFC8321E),
    onDanger = Color(0xFFF2F0E9),
    // 정상엔 색이 없다. 도면에서 "이상 없음"은 표시가 없다는 뜻이다
    ok = Color(0xFF5D5C55),
    okText = Color(0xFF5D5C55),
)

/**
 * 밤 — 청사진(blueprint) 네거티브.
 *
 * 같은 도면을 다른 방식으로 인쇄한 것이다. 흄내기가 아니라 실제로 있던 인쇄법이라
 * 세계가 갈라지지 않는다. 어두운 청 바탕에 흰 선이 뜬다.
 *
 * 순검정을 쓰지 않는다 — 값싼 패널에서 잔상이 남고 야간 운전에 눈이 아프다.
 */
val DarkPalette = Palette(
    void = Color(0xFF101619),        // 청사진 바탕
    carbon = Color(0xFF161E22),      // 표제란
    graphite = Color(0xFF101619),
    slate = Color(0xFF1E282D),
    hairline = Color(0xFF32414A),
    ink = Color(0xFFE7ECEE),
    inkMuted = Color(0xFF9DACB3),
    // 밤도 4.13:1로 미달이었다
    inkFaint = Color(0xFF8B9AA2),
    electric = Color(0xFFE7ECEE),
    electricPressed = Color(0xFFB8C3C8),
    electricFaint = Color(0xFF1E282D),
    cool = Color(0xFF6FB6E0),
    heat = Color(0xFFE08A5A),
    warn = Color(0xFFD9A441),
    warnText = Color(0xFFD9A441),
    warnFaint = Color(0xFF2B2718),
    danger = Color(0xFFE8624E),
    onDanger = Color(0xFF101619),
    ok = Color(0xFF9DACB3),
    okText = Color(0xFF9DACB3),
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
