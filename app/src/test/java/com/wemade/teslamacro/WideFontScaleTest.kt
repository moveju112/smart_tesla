package com.wemade.teslamacro

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.android.resources.ScreenOrientation
import com.wemade.teslamacro.feature.dashboard.DashboardScreen
import com.wemade.teslamacro.ui.nav.Destination
import org.junit.Rule
import org.junit.Test

/**
 * 실기기 크기 + **시스템 글자 크기 1.3배**.
 *
 * 값이 없는 화면에서는 기입 치수가 아예 안 그려져 "배율을 따라 커졌는가"도
 * "칸을 넘치는가"도 증명할 수 없다. 그래서 여기는 항상 값이 들어간 상태로 찍는다.
 *
 * 기입 치수 크기는 상수가 아니라 칸 폭에서 계산된다(`inscribedSize`).
 * 그 계산이 맞는지는 **글자 수가 다른 두 값**을 나란히 세워야 드러난다 —
 * 네 글자만 보면 다섯 글자에서 넘치는 걸 못 잡는다.
 */
class WideFontScaleTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_C.copy(
            orientation = ScreenOrientation.LANDSCAPE,
            nightMode = NightMode.NOTNIGHT,
            softButtons = false,
            screenWidth = 1920,
            screenHeight = 1200,
            density = com.android.resources.Density.XHIGH,
            fontScale = 1.3f,
        ),
        showSystemUi = false,
    )

    /** 네 글자 기입값 */
    @Test
    fun `F1 제어 화면 - 글자 확대`() {
        paparazzi.snapshot("F1-dashboard-fontscale") {
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

    /** 다섯 글자 음수 기입값 — 폭 계산이 여기서 걸린다 */
    @Test
    fun `F2 제어 화면 - 다섯 글자 값 + 글자 확대`() {
        paparazzi.snapshot("F2-dashboard-five-glyph") {
            AppFrame(Destination.Dashboard) {
                DashboardScreen(
                    state = wideState().copy(insideTemp = "-10.5", outsideTemp = "-12.0"),
                    onCommand = {},
                    onRetryConnect = {},
                    onDismissError = {},
                )
            }
        }
    }
}
