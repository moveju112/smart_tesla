package com.wemade.teslamacro

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.NightMode
import com.android.resources.ScreenOrientation
import com.wemade.teslamacro.data.settings.AppSettings
import com.wemade.teslamacro.feature.settings.SettingsGroup
import com.wemade.teslamacro.feature.settings.SettingsScreen
import com.wemade.teslamacro.ui.layout.LocalPane
import com.wemade.teslamacro.ui.layout.Pane
import com.wemade.teslamacro.ui.nav.Destination
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 세로 거치 태블릿의 한 열 충전 그래프를 낮·밤 및 큰 글자에서 검증한다. */
@RunWith(Parameterized::class)
class TabletPortraitScreenshotTest(private val dark: Boolean, private val fontScale: Float) {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_C.copy(
            orientation = ScreenOrientation.PORTRAIT,
            nightMode = NightMode.NOTNIGHT,
            softButtons = false,
            screenWidth = 1200,
            screenHeight = 1920,
            density = Density.XHIGH,
            fontScale = fontScale,
        ),
        showSystemUi = false,
    )

    /** 실제 앱의 루트 판정과 기존 충전 기록 샘플을 함께 사용한다. */
    @Test
    fun `세로 태블릿의 충전 설정은 한 열이다`() {
        paparazzi.snapshot("tablet-portrait-charge-$dark-$fontScale") {
            AppFrame(Destination.Settings, dark = dark) {
                assertEquals(Pane.Compact, LocalPane.current)
                androidx.compose.runtime.CompositionLocalProvider(com.wemade.teslamacro.feature.settings.LocalExpandSettingsDetails provides true) {
                    SettingsScreen(
                        settings = AppSettings(stealthCharging = true, stealthMaxAmps = 13),
                        chargeHistory = sampleChargeHistory(),
                        chargeHistoryNowMillis = SNAPSHOT_NOW_MILLIS,
                        stealthSecondsUntilNextChange = 134,
                        onUnpair = {},
                        onStartPairing = {},
                        initialGroup = SettingsGroup.AUTOMATION,
                    )
                }
            }
        }
    }

    companion object {
        /** 일반 글자와 시스템 1.3배를 두 팔레트에 교차 적용한다. */
        @JvmStatic
        @Parameterized.Parameters(name = "dark={0},fontScale={1}")
        fun configurations(): List<Array<Any>> = listOf(
            arrayOf(false, 1.0f), arrayOf(true, 1.0f),
            arrayOf(false, 1.3f), arrayOf(true, 1.3f),
        )
    }
}
