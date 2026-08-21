package com.wemade.teslamacro.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * 도면의 글자.
 *
 * 도면에는 큰 제목이 없다. 제일 큰 글자는 **기입된 치수**이고,
 * 도면 이름은 표제란 안에 작게 적힌다. 그래서 위계가 거꾸로다 —
 * 계측값이 가장 크고, 제목이 가장 작다.
 *
 * 세 가지 역할만 있다:
 * - **계측**([Mono]): 숫자·단위·부품번호. 고정폭이라 자릿수가 바뀌어도 안 흔들린다
 * - **라벨**: 부품 이름. 얇고 자간이 넓다 — 도면 라벨은 읽히되 물러서 있다
 * - **표제**: 표제란 안의 글자. 작고 또렷하다
 *
 * 전용 서체를 심지 않았다. 이 기기에 실을 수 있는 한글 서체는 시스템 서체(Noto Sans CJK)
 * 하나뿐이라 20MB를 더 실어도 화면이 똑같다. 그래서 성격은 서체가 아니라
 * **고정폭 계측 + 넓은 자간의 라벨 + 거꾸로 된 위계**가 만든다.
 */
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
        fontSize = 17.sp, lineHeight = 24.sp, letterSpacing = 0.4.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Mono, fontFeatureSettings = TABULAR, fontWeight = FontWeight.W500,
        fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp,
    ),
    // 절 제목 — 도면의 구역 이름. 넓은 자간으로 눕는다
    titleMedium = TextStyle(
        fontFamily = Sans, fontFeatureSettings = TABULAR, fontWeight = FontWeight.W600,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.7.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Sans, fontFeatureSettings = TABULAR, fontWeight = FontWeight.W600,
        fontSize = 13.sp, lineHeight = 19.sp, letterSpacing = 0.5.sp,
    ),
    // 본문 — 주기(註記). 도면의 설명 글은 작다
    bodyMedium = TextStyle(
        fontFamily = Sans, fontFeatureSettings = TABULAR, fontWeight = FontWeight.W400,
        fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = 0.1.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Sans, fontFeatureSettings = TABULAR, fontWeight = FontWeight.W400,
        fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp,
    ),
    // 버튼 라벨 — 도면의 지시. 자간을 벌려 명판처럼 읽힌다
    labelLarge = TextStyle(
        fontFamily = Sans, fontFeatureSettings = TABULAR, fontWeight = FontWeight.W600,
        fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.6.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Sans, fontFeatureSettings = TABULAR, fontWeight = FontWeight.W600,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp,
    ),
    // 부품 라벨 — 가장 물러선 글자.
    // 자간을 1.6sp(0.145em)까지 벌렸더니 한글 자모 덩어리가 흩어졌다.
    // 라틴 소형 대문자 관례를 한글에 그대로 쓸 수 없다 — 0.055em까지만 벌린다
    labelSmall = TextStyle(
        fontFamily = Sans, fontFeatureSettings = TABULAR, fontWeight = FontWeight.W500,
        fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.6.sp,
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
