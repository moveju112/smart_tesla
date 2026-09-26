package com.wemade.teslamacro

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.android.resources.ScreenOrientation
import com.wemade.teslamacro.domain.model.Door
import com.wemade.teslamacro.feature.dashboard.DashboardScreen
import com.wemade.teslamacro.ui.nav.Destination
import org.junit.Rule
import org.junit.Test

/**
 * 실기기를 **세로로 돌린** 상태 (600×960dp).
 *
 * 600dp도 세로에서는 `Pane.Compact`로 휴대폰과 같은 한 열을 사용한다.
 * 가로로 돌리면 기존 `Pane.Expanded` 분할을 유지한다.
 *
 * 차내 거치대는 보통 가로지만, 돌아가는 거치대도 있고 부팅 직후 방향이 튀기도 한다.
 */
class PortraitTabletTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_C.copy(
            orientation = ScreenOrientation.PORTRAIT,
            nightMode = NightMode.NOTNIGHT,
            softButtons = false,
            screenWidth = 1200,
            screenHeight = 1920,
            density = com.android.resources.Density.XHIGH,
        ),
        showSystemUi = false,
    )

    @Test
    fun `V1 제어 화면 - 세로`() {
        paparazzi.snapshot("V1-dashboard-portrait") {
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

    @Test
    fun `V2 제어 화면 - 세로 + 문 열림`() {
        paparazzi.snapshot("V2-dashboard-portrait-alert") {
            AppFrame(Destination.Dashboard) {
                DashboardScreen(
                    state = wideState().copy(
                        isLocked = false,
                        openings = listOf(Door.DRIVER_FRONT),
                    ),
                    onCommand = {},
                    onRetryConnect = {},
                    onDismissError = {},
                )
            }
        }
    }

    /**
     * 세로 설정의 한 열 배치와 중분류 목차 네 칸을 검증한다.
     */
    @Test
    fun `V3 설정 - 세로`() {
        paparazzi.snapshot("V3-settings-portrait") {
            AppFrame(Destination.Settings) {
                com.wemade.teslamacro.feature.settings.SettingsScreen(
                    chargeHistory = sampleChargeHistory(),
                    chargeHistoryNowMillis = SNAPSHOT_NOW_MILLIS,
                    settings = com.wemade.teslamacro.data.settings.AppSettings(
                        vin = "5YJS0000000000000",
                        vehicleName = "내 테슬라",
                        hudOverlay = true,
                        safeDrive = true,
                    ),
                    onUnpair = {},
                    onStartPairing = {},
                    battery = com.wemade.teslamacro.feature.settings.BatteryControls(
                        unrestricted = false,
                        onOpenSettings = {},
                    ),
                    navigation = com.wemade.teslamacro.feature.settings.NavigationControls(
                        onAppChange = {},
                        onHudOverlayChange = {},
                        safeDriveAvailable = true,
                        installed = setOf("NAVER", "KAKAO", "TMAP"),
                    ),
                    initialGroup = com.wemade.teslamacro.feature.settings.SettingsGroup.DRIVING,
                )
            }
        }
    }
}
