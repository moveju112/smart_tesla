package com.wemade.teslamacro

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.android.resources.ScreenOrientation
import com.wemade.teslamacro.domain.gateway.LinkState
import com.wemade.teslamacro.domain.model.Level
import com.wemade.teslamacro.domain.model.SeatClimate
import com.wemade.teslamacro.domain.model.SeatMode
import com.wemade.teslamacro.domain.model.SeatPosition
import com.wemade.teslamacro.feature.dashboard.DashboardScreen
import com.wemade.teslamacro.feature.dashboard.DashboardUiState
import com.wemade.teslamacro.ui.nav.Destination
import org.junit.Rule
import org.junit.Test

/**
 * 실차 거치 태블릿(ALLDOCUBE iPlay 60 mini Pro, 8.4" 1920x1200)으로 찍는다.
 *
 * 기본 PIXEL_C는 1.42:1인데 이 기기는 1.6:1로 넓고 납작하다 — dp로는 약 960x600이라
 * 세로가 특히 짧다. 이 차이 때문에 기본 기기에서 멀쩡하던 타일이 실기기에서 속이 텅 비었다.
 * 제어 화면은 "스크롤 없이 한 화면"이 전제라 비율이 바뀌면 바로 티가 난다.
 */
class WideScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_C.copy(
            orientation = ScreenOrientation.LANDSCAPE,
            nightMode = NightMode.NOTNIGHT,
            softButtons = false,
            screenWidth = 1920,
            screenHeight = 1200,
            density = com.android.resources.Density.XHIGH,
        ),
        showSystemUi = false,
    )

    @Test
    fun `W1 제어 화면 - 와이드`() {
        paparazzi.snapshot("W1-dashboard-wide") {
            AppFrame(Destination.Dashboard) {
                DashboardScreen(
                    state = wideState(),
                    onCommand = {},
                    onRetryConnect = {},
                    onDismissError = {},
                )
            }
        }
    }

    // 아직 목표에 못 간 상태 — 이때만 온도가 색을 얻어야 한다.
    // 문이 열려 있으면 그 타일 하나만 통째로 물든다
    @Test
    fun `W2 제어 화면 - 냉방 중 + 문 열림`() {
        paparazzi.snapshot("W2-dashboard-cooling") {
            AppFrame(Destination.Dashboard) {
                DashboardScreen(
                    state = wideState().copy(
                        insideTemp = "31.4",
                        isLocked = false,
                        openings = listOf("운전석 도어"),
                    ),
                    onCommand = {},
                    onRetryConnect = {},
                    onDismissError = {},
                )
            }
        }
    }

    private fun wideState() = DashboardUiState(
        link = LinkState.Ready,
        vehicleName = "Tesla Model Y Why",
        insideTemp = "22.5",
        outsideTemp = "30.0",
        targetTemp = "22.5",
        targetTempValue = 22.5,
        isClimateOn = true,
        isLocked = true,
        seatCooler = emptyMap(),
        seatHeater = emptyMap(),
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
}
