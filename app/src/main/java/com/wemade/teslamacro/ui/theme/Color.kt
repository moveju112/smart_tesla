package com.wemade.teslamacro.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 토스/네이버 계열의 밝고 단정한 팔레트.
 *
 * 핵심 규칙:
 * - 배경은 아주 옅은 회색, 카드는 순백. 이 대비만으로 층을 만든다
 * - 그라데이션·글로우를 쓰지 않는다 (그게 "힘준 AI" 티의 원인이었다)
 * - 유채색은 파랑 하나로 통일하고, 의미색은 필요할 때만
 * - 위계는 색이 아니라 **굵기와 여백**으로 잡는다
 *
 * 토큰 이름은 예전(다크)과 같게 유지해 화면 코드를 안 건드린다.
 */
object T {

    // 배경 계층 — 옅은 회색 바탕 위 순백 카드
    val Void = Color(0xFFF2F4F6)        // 앱 배경 (토스 배경 그레이)
    val Carbon = Color(0xFFFFFFFF)      // 바/표면
    val Graphite = Color(0xFFFFFFFF)    // 카드 = 순백
    val Slate = Color(0xFFEDF0F3)       // 눌린 상태 / 트랙 / 보조버튼 면

    // 경계선
    val Hairline = Color(0xFFE5E8EB)

    // 텍스트 3단계 (토스 그레이 스케일)
    val Ink = Color(0xFF191F28)
    val InkMuted = Color(0xFF4E5968)
    val InkFaint = Color(0xFF8B95A1)

    // 액센트 — 토스 블루
    val Electric = Color(0xFF3182F6)
    val ElectricPressed = Color(0xFF1B64DA)
    val ElectricFaint = Color(0xFFEAF3FF)   // 옅은 파랑 배경(선택 배지 등)

    // 의미색
    val Cool = Color(0xFF3182F6)        // 통풍 / 냉방 (파랑 계열로 통일감)
    val Heat = Color(0xFFFF6B00)        // 열선 / 난방
    val Warn = Color(0xFFFFB020)        // 칩·점·틴트 배경 전용 — 흰 배경 텍스트엔 WarnText를 쓴다
    val WarnText = Color(0xFFB45309)    // 경고 '텍스트' 전용 — 밝은 앰버(Warn)는 흰 배경에서 안 읽힌다
    val Danger = Color(0xFFF04452)
    val Ok = Color(0xFF12B886)

    // 히어로 배경 단색 — 그라데이션 금지 규칙에 맞춰 틴트 한 색으로 끝낸다
    val CoolTint = Color(0xFFF4F8FF)    // 냉방 히어로 배경
    val HeatTint = Color(0xFFFFF6F0)    // 난방 히어로 배경
}

// Grad(그라데이션 브러시 모음)는 마지막 참조가 사라져 삭제했다 — 그라데이션 금지 규칙 완결
