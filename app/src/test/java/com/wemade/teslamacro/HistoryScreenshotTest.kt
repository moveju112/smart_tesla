package com.wemade.teslamacro

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.wemade.teslamacro.data.history.HistoryOverview
import com.wemade.teslamacro.data.history.HistorySample
import com.wemade.teslamacro.data.settings.DeviceMode
import com.wemade.teslamacro.feature.history.HistoryScreen
import com.wemade.teslamacro.feature.history.HistoryUiState
import org.junit.Rule
import org.junit.Test

/** 새 목록의 긴 수집 안내·용량 입력을 휴대폰 큰 글씨 한 화면으로 확인한다. */
class HistoryScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6.copy(
        screenWidth = 720, screenHeight = 1560, density = Density.XHIGH,
        fontScale = 1.3f, softButtons = false), showSystemUi = false)

    /** 온라인 지도 요청 없이 새 기록 화면의 대표 배치만 검토한다. */
    @Test fun phoneLargeText() {
        paparazzi.snapshot {
            FullScreenFrame(dark = false) {
                HistoryScreen(HistoryUiState(enabled = true, ready = true, mode = DeviceMode.MOUNTED,
                    overview = HistoryOverview(latest = HistorySample(1_700_000_000_000, batteryPercent = 73),
                        sampleCount = 720, storageBytes = 65_536)), {}, {}, {}, {})
            }
        }
    }
}
