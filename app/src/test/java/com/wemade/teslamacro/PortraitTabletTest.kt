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
 * 이 폭이 사각지대였다. 600dp는 `Pane.Medium`이라 폰 세로(450dp, `Compact`)와 다르게
 * 갈리고, 태블릿 가로(960dp, `Expanded`)와도 다르다 — 세로 컷이 폰밖에 없으면
 * 정작 이 기기를 돌렸을 때의 화면을 아무도 본 적이 없게 된다.
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
}
