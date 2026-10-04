package com.wemade.teslamacro

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.wemade.teslamacro.data.history.HistoryOverview
import com.wemade.teslamacro.data.settings.AppSettings
import com.wemade.teslamacro.feature.features.AppFeature
import com.wemade.teslamacro.feature.features.FeaturesScreen
import com.wemade.teslamacro.feature.history.HistoryScreen
import com.wemade.teslamacro.feature.history.HistoryUiState
import com.wemade.teslamacro.feature.settings.NavigationControls
import com.wemade.teslamacro.feature.settings.SmartThingsControls
import com.wemade.teslamacro.ui.nav.Destination
import org.junit.Rule
import org.junit.Test

/** 실제 기능 프레임에서 공통 제목·설정·탭과 빈 목록의 큰 글씨 배치를 확인한다. */
class HistoryScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6.copy(
        screenWidth = 720, screenHeight = 1560, density = Density.XHIGH,
        fontScale = 1.3f, softButtons = false), showSystemUi = false)

    /** 온라인 지도 요청 없이 새 기록 화면의 대표 배치만 검토한다. */
    @Test fun phoneLargeText() {
        paparazzi.snapshot {
            AppFrame(Destination.Features, dark = false) {
                FeaturesScreen(
                    settings = AppSettings(vin = "5YJS0000000000000", isEnrolled = true, historyEnabled = true),
                    selected = AppFeature.HISTORY,
                    navigation = NavigationControls(onAppChange = {}, onHudOverlayChange = {}),
                    smartThings = SmartThingsControls(false, {}, { _, _ -> }, {}),
                    onSelect = {}, onSettings = {}, onStealthChange = {},
                    historyContent = {
                        HistoryScreen(HistoryUiState(enabled = true, ready = true,
                            overview = HistoryOverview(storageBytes = 65_536)), {}, {}, {})
                    },
                )
            }
        }
    }
    /** 제보된 짧은 주행도 표본·설명 문단 없이 큰 글씨에서 핵심 값만 읽혀야 한다. */
    @Test fun phoneSummaryLargeText() {
        val session = com.wemade.teslamacro.data.history.HistorySession(
            id = "summary-review", kind = com.wemade.teslamacro.data.history.HistoryKind.DRIVE,
            start = 1_759_622_400_000, end = 1_759_622_412_000, samples = 3,
            firstOdometer = 1000, lastOdometer = 1006, firstBattery = 76, lastBattery = 76,
            estimatedDriveKwh = -0.001, powerCoveredMillis = 6_000)
        paparazzi.snapshot {
            AppFrame(Destination.Features, dark = false) {
                HistoryScreen(HistoryUiState(enabled = true, ready = true,
                    detail = com.wemade.teslamacro.feature.history.HistoryDetail(session)), {}, {}, {})
            }
        }
    }

}
