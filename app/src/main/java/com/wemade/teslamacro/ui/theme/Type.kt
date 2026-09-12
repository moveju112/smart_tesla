package com.wemade.teslamacro.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** 휴대폰에서 제목과 본문을 구별하고 한글 자간을 자연스럽게 유지한다. */
private const val TABULAR = "tnum"

/** 한글이 섞이는 곳. 라벨과 본문 */
private val Sans = FontFamily.Default

/**
 * 계측값 전용 고정폭.
 *
 * 멋내기가 아니라 계측이라서 쓴다 — 22.5→22.6에서 자릿수 폭이 흔들리면
 * 흘깃 보는 화면에서 숫자 전체가 좌우로 움직인다.
 */
private val Mono = FontFamily.Monospace

val TeslaTypography = Typography(
    // 도면 이름 — 표제란 글자다. 크지 않다
    headlineLarge = TextStyle(
        fontFamily = Sans, fontFeatureSettings = TABULAR, fontWeight = FontWeight.W600,
        fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Sans, fontFeatureSettings = TABULAR, fontWeight = FontWeight.W600,
        fontSize = 22.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp,
    ),
    // 절 제목 — 도면의 구역 이름. 넓은 자간으로 눕는다
    titleMedium = TextStyle(
        fontFamily = Sans, fontFeatureSettings = TABULAR, fontWeight = FontWeight.W600,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Sans, fontFeatureSettings = TABULAR, fontWeight = FontWeight.W600,
        fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = 0.sp,
    ),
    // 본문 — 주기(註記). 도면의 설명 글은 작다
    bodyMedium = TextStyle(
        fontFamily = Sans, fontFeatureSettings = TABULAR, fontWeight = FontWeight.W400,
        fontSize = 15.sp, lineHeight = 23.sp, letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Sans, fontFeatureSettings = TABULAR, fontWeight = FontWeight.W400,
        fontSize = 13.sp, lineHeight = 20.sp, letterSpacing = 0.sp,
    ),
    // 버튼 라벨 — 도면의 지시. 자간을 벌려 명판처럼 읽힌다
    labelLarge = TextStyle(
        fontFamily = Sans, fontFeatureSettings = TABULAR, fontWeight = FontWeight.W600,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Sans, fontFeatureSettings = TABULAR, fontWeight = FontWeight.W600,
        fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp,
    ),
    // 부품 라벨 — 가장 물러선 글자.
    // 자간을 1.6sp(0.145em)까지 벌렸더니 한글 자모 덩어리가 흩어졌다.
    // 라틴 소형 대문자 관례를 한글에 그대로 쓸 수 없다 — 0.055em까지만 벌린다
    labelSmall = TextStyle(
        fontFamily = Sans, fontFeatureSettings = TABULAR, fontWeight = FontWeight.W500,
        fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.sp,
    ),
)

/** 부품번호 — 지시선 끝에 매달리는 두 자리 숫자. 도면과 표를 잇는 유일한 끈 */
val CalloutNumberStyle = TextStyle(
    fontFamily = Mono, fontFeatureSettings = TABULAR,
    fontWeight = FontWeight.W500,
    fontSize = 11.sp,
    lineHeight = 14.sp,
    letterSpacing = 0.6.sp,
)

/** 표에 기입된 계측값. 한 행의 주인공 */
val MetricTextStyle = TextStyle(
    fontFamily = Mono, fontFeatureSettings = TABULAR,
    fontWeight = FontWeight.W500,
    fontSize = 26.sp,
    lineHeight = 32.sp,
    letterSpacing = (-0.4).sp,
)

/** 지시선에 매달린 값. 선도 옆 여백에 앉는다 */
val TileValueStyle = TextStyle(
    fontFamily = Mono, fontFeatureSettings = TABULAR,
    fontWeight = FontWeight.W500,
    fontSize = 20.sp,
    lineHeight = 26.sp,
    letterSpacing = (-0.2).sp,
)

/** 좁은 화면에서 선도를 접었을 때의 값 크기 */
val TileValueStyleLarge = TextStyle(
    fontFamily = Mono, fontFeatureSettings = TABULAR,
    fontWeight = FontWeight.W500,
    fontSize = 26.sp,
    lineHeight = 32.sp,
    letterSpacing = (-0.4).sp,
)

/**
 * 기입된 치수 — 화면에서 유일하게 압도적으로 큰 것.
 *
 * 도면에서 제일 큰 글자는 제목이 아니라 치수다. 실내 온도가 그 치수다.
 * 실제 크기는 화면 폭에 따라 화면 쪽에서 정한다.
 */
val HeroValueStyle = TextStyle(
    fontFamily = Mono,
    fontFeatureSettings = TABULAR,
    fontWeight = FontWeight.W500,
    fontSize = 96.sp,
    lineHeight = 100.sp,
    // em으로 잡는다. sp로 두면 화면이 크기를 84sp로 줄여도 절대값이 남아
    // 비율이 -0.048em까지 벌어져 자간 하한(-0.04em)을 넘었다
    letterSpacing = (-0.035).em,
)
