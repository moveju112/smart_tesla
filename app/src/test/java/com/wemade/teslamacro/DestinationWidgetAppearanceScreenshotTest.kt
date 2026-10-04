package com.wemade.teslamacro

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.material3.Surface
import com.android.resources.Density
import com.wemade.teslamacro.data.settings.DestinationWidgetAppearance
import com.wemade.teslamacro.data.settings.DestinationWidgetTheme
import com.wemade.teslamacro.data.settings.SettingsStore
import com.wemade.teslamacro.feature.destination.DestinationWidgetAppearanceScreen
import com.wemade.teslamacro.ui.theme.TeslaMacroTheme
import org.junit.Rule
import org.junit.Test

/** 휴대폰 큰 글씨에서 실제 RemoteViews 미리보기와 꾸미기 항목을 함께 확인한다. */
class DestinationWidgetAppearanceScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(
            screenWidth = 720, screenHeight = 1440, density = Density.XHIGH,
            fontScale = 1.3f, softButtons = false,
        ), showSystemUi = false,
    )

    /** 반투명 배경 위의 글자는 투명해지지 않고, 설정은 한 열로 스크롤돼야 한다. */
    @Test fun largeFontAppearance() {
        val store = SettingsStore(paparazzi.context)
        paparazzi.snapshot {
            TeslaMacroTheme(dark = false) {
                Surface {
                    DestinationWidgetAppearanceScreen(
                        DestinationWidgetAppearance(DestinationWidgetTheme.DARK, transparency = 50), store, {},
                    )
                }
            }
        }
    }
}
