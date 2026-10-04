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

/** 실제 기능 프레임에서 주행 요약과 최근 주행의 큰 글씨 배치를 확인한다. */
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
                            overview = insightFixture()), {}, {}, {})
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

    /** 큰 글씨에서도 주행 수치·비교·경로가 한 카드 안에서 겹치지 않는지 확인한다. */
    @Test fun phoneTripLargeText() {
        val overview = insightFixture()
        paparazzi.snapshot {
            AppFrame(Destination.Features, dark = false) {
                com.wemade.teslamacro.feature.history.HistoryTripCard(overview.sessions.first(), overview.insights) {}
            }
        }
    }

    /** 실제 원본과 같은 자료형의 예시로 요약과 전비 추세를 함께 검토한다. */
    private fun insightFixture(): HistoryOverview {
        val today = java.time.LocalDate.of(2026, 10, 5)
        val zone = java.time.ZoneId.of("Asia/Seoul")
        val start = today.atTime(9, 12).atZone(zone).toInstant().toEpochMilli()
        val trip = com.wemade.teslamacro.data.history.HistorySession(
            "recent", com.wemade.teslamacro.data.history.HistoryKind.DRIVE, start, start + 42 * 60_000,
            firstOdometer = 1000, lastOdometer = 3013, firstBattery = 76, lastBattery = 69)
        val sessions = (0 until 30).map { index -> trip.copy(id = "trip-$index", start = start - index * 86_400_000L,
            end = start - index * 86_400_000L + 42 * 60_000) }
        val energy = sessions.mapIndexed { index, session -> session.id to
            com.wemade.teslamacro.data.history.HistoryEnergy(32.4, 4.8 + index * 0.015, 42 * 60_000) }.toMap()
        val points = (0..12).map { index -> com.wemade.teslamacro.data.history.HistorySample(
            start + index * 5_000, latitude = 37.5 + index * 0.0001,
            longitude = 127.0 + index * 0.00015 + kotlin.math.sin(index.toDouble()) * 0.0002) }
        val insights = com.wemade.teslamacro.data.history.historyInsights(sessions, energy, 30, today, zone)
            .copy(previousEnergy = com.wemade.teslamacro.data.history.HistoryEnergy(300.0, 50.0, 3_600_000),
                previewSessionId = sessions.first().id, previewSamples = points)
        return HistoryOverview(sessions = sessions, insights = insights)
    }

}
