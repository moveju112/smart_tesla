package com.wemade.teslamacro.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 토스/네이버 계열 타이포.
 *
 * 위계를 색이 아니라 **굵기**로 잡는다.
 * - 제목은 굵게(W700), 강조는 W600, 본문은 W400
 * - 큰 숫자도 얇게 뽑지 않고 굵게 — 얇은 대형 숫자가 "기계가 만든" 인상을 준다
 */
private val Sans = FontFamily.Default

val TeslaTypography = Typography(
    // 화면 제목 — 굵고 큼직하게
    headlineLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.W700, fontSize = 26.sp, lineHeight = 34.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.W700, fontSize = 22.sp, lineHeight = 30.sp,
        letterSpacing = (-0.3).sp,
    ),
    // 카드 제목 / 강조
    titleMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.W600, fontSize = 17.sp, lineHeight = 24.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.W600, fontSize = 15.sp, lineHeight = 21.sp,
        letterSpacing = (-0.1).sp,
    ),
    // 본문
    bodyMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.W400, fontSize = 15.sp, lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.W400, fontSize = 13.sp, lineHeight = 19.sp,
    ),
    // 버튼 라벨 — 토스 버튼은 또렷하게 굵다
    labelLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.W600, fontSize = 16.sp, lineHeight = 20.sp,
        letterSpacing = (-0.2).sp,
    ),
    // 소형 버튼 라벨 — 미정의 시 Material 기본(12sp·양수 자간)으로 폴백돼 스케일이 어긋난다
    labelMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.W600, fontSize = 14.sp, lineHeight = 18.sp,
        letterSpacing = (-0.1).sp,
    ),
    // 타일 라벨 — 값이 주인공이라 라벨은 작고 자간을 넓혀 '이름표'처럼 물러선다
    labelSmall = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.W600, fontSize = 12.sp, lineHeight = 16.sp,
        letterSpacing = 0.6.sp,
    ),
)

/** 온도·배터리처럼 크게 읽히는 값. 토스 숫자처럼 굵게 뽑는다 */
val MetricTextStyle = TextStyle(
    fontFamily = Sans,
    fontWeight = FontWeight.W700,
    fontSize = 48.sp,
    lineHeight = 54.sp,
    letterSpacing = (-1.2).sp,
)

/**
 * 타일 값 — 라벨(12sp)과 3배 가까이 벌린다.
 *
 * 이 화면의 성격은 서체가 아니라 이 낙차가 만든다. 계기판처럼 값만 먼저 눈에 꽂히고
 * 라벨은 필요할 때만 읽히게 하려는 것.
 */
val TileValueStyle = TextStyle(
    fontFamily = Sans,
    fontWeight = FontWeight.W700,
    fontSize = 34.sp,
    lineHeight = 40.sp,
    letterSpacing = (-0.8).sp,
)

/** 히어로 타일(실내 온도) 전용. 가장 멀리서도 읽혀야 하는 단 하나의 값 */
val TileValueStyleLarge = TextStyle(
    fontFamily = Sans,
    fontWeight = FontWeight.W700,
    fontSize = 48.sp,
    lineHeight = 54.sp,
    letterSpacing = (-2).sp,
)
