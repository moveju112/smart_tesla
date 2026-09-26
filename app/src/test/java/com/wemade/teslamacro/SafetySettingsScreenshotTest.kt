package com.wemade.teslamacro

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.wemade.teslamacro.data.settings.AppSettings
import com.wemade.teslamacro.feature.settings.NavigationControls
import com.wemade.teslamacro.feature.settings.SafeDrivePanel
import com.wemade.teslamacro.ui.theme.Space
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 휴대폰 낮·밤과 거치 화면 큰 글자에서 경보 설정·주의 문구를 보존한다. */
@RunWith(Parameterized::class)
class SafetySettingsScreenshotTest(private val dark: Boolean, private val wide: Boolean) {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = if (wide) DeviceConfig.PIXEL_C.copy(
            screenWidth = 1920, screenHeight = 1200, density = Density.XHIGH,
            orientation = ScreenOrientation.LANDSCAPE, fontScale = 1.3f, softButtons = false,
        ) else DeviceConfig.PIXEL_6.copy(softButtons = false),
        showSystemUi = false,
    )

    /** +5 설정 예시와 소리·음량 선택을 실제 공용 패널로 캡처한다. */
    @Test fun thresholdSettings() {
        paparazzi.snapshot {
            FullScreenFrame(dark = dark) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(Space.md)) {
                    androidx.compose.runtime.CompositionLocalProvider(com.wemade.teslamacro.feature.settings.LocalExpandSettingsDetails provides true) {
                        SafeDrivePanel(
                            AppSettings(safeDrive = true, safeDriveToleranceKph = 5),
                            NavigationControls(onAppChange = {}, onHudOverlayChange = {},
                                safeDriveAvailable = true, locationPermitted = true,
                                automaticSoundStatus = "차량 오디오 Bluetooth 연결 대기 · 자동 소리 보류",
                                safeDriveVoiceStatus = "음성 재생 요청됨 · 들리지 않으면 미디어 음량을 확인하세요."),
                        )
                    }
                }
            }
        }
    }

    companion object {
        /** 두 팔레트·두 화면 크기를 고정해 시간과 기기 설정의 영향을 없앤다. */
        @JvmStatic @Parameterized.Parameters(name = "dark={0},wide={1}")
        fun configurations(): List<Array<Any>> = listOf(
            arrayOf(false, false), arrayOf(true, false), arrayOf(false, true), arrayOf(true, true),
        )
    }
}
