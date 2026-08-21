package com.wemade.teslamacro

import com.wemade.teslamacro.domain.gateway.LinkState
import com.wemade.teslamacro.domain.model.Level
import com.wemade.teslamacro.domain.model.SeatClimate
import com.wemade.teslamacro.domain.model.SeatMode
import com.wemade.teslamacro.domain.model.SeatPosition
import com.wemade.teslamacro.feature.dashboard.DashboardUiState

/**
 * 실기기 와이드 화면의 표준 상태 — 연결됨·값 도착·운전석 통풍 중.
 *
 * `WideScreenshotTest`와 `WideFontScaleTest`가 나눠 쓴다. 두 벌로 두면
 * 글자 배율 컷만 값이 달라져 비교가 안 된다.
 */
internal fun wideState() = DashboardUiState(
    link = LinkState.Ready,
    vehicleName = "Tesla Model Y Why",
    insideTemp = "22.5",
    outsideTemp = "30.0",
    targetTemp = "22.5",
    targetTempValue = 22.5,
    isClimateOn = true,
    isLocked = true,
    seatClimate = mapOf(
        SeatPosition.FRONT_LEFT to SeatClimate(SeatMode.COOL, Level.MEDIUM),
    ),
    isSimulated = false,
    hasReading = true,
    hasBodyReading = true,
    hasClimateReading = true,
    pendingCommand = null,
    errorMessage = null,
    secondsSinceReading = 2,
    batteryPercent = 85,
    isCharging = false,
    chargeLimitPercent = 100,
    chargingAmps = 32,
    rangeKm = 359,
    openings = emptyList(),
)
