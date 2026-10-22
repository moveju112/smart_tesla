package com.wemade.teslamacro.data.nav

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.paparazzi.Paparazzi
import com.wemade.teslamacro.data.settings.SettingsStore
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TeslaNavigationShareTest {
    @get:Rule val paparazzi = Paparazzi()
    @get:Rule val temporary = TemporaryFolder()
    private val naver = "com.nhn.android.nmap"

    /** 종료된 경로의 거리는 지우고 같은 목적지의 새 관측도 최댓값이 아닌 최신값을 쓴다. */
    @Test fun distanceBelongsToTheCurrentDestinationAndRoute() = runTest {
        val context = paparazzi.context
        val store = SettingsStore(context, PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporary.newFolder().resolve("settings.preferences_pb")
        })
        val near = com.wemade.teslamacro.domain.macro.GeoPoint(1.0, 1.0)
        val local = TeslaDestinationCandidate("가까운 후보", near.copy(latitude = 1.009))
        val distant = TeslaDestinationCandidate("먼 후보", near.copy(latitude = 1.69))
        val share = TeslaNavigationShare(context, store, NaverNavigator(context), backgroundScope,
            currentPoint = { near }, lookup = { _, _ -> listOf(local, distant) })
        // 새 경로의 긴 거리 자체는 버리지 않아 실제 먼 목적지도 선택할 수 있어야 한다.
        share.routeDistance(100_000, "중앙공원")
        startRoute(share, "중앙공원")
        runCurrent()
        assertEquals(distant, share.selection.value?.preview)
        share.clear()
        share.routeDistance(100_000, "중앙공원")
        share.routeDistance(1_000, "중앙공원")
        startRoute(share, "중앙공원")
        runCurrent()
        assertEquals(local, share.selection.value?.preview)
        share.clear()
        share.routeDistance(100_000, "다른 목적지")
        startRoute(share, "중앙공원")
        runCurrent()
        assertEquals(local, share.selection.value?.preview)
        share.clear()
        share.routeDistance(100_000, "중앙공원")
        share.routeDistance(null, "중앙공원")
        startRoute(share, "중앙공원")
        runCurrent()
        assertEquals(local, share.selection.value?.preview)
    }

    /** 좌표 목적지는 미리보기에 유지하되 로컬 진단 기록에는 원문을 남기지 않는다. */
    @Test fun coordinateDestinationDoesNotEnterDiagnosticLog() = runTest {
        val context = paparazzi.context
        val store = SettingsStore(context, PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporary.newFolder().resolve("settings.preferences_pb")
        })
        val share = TeslaNavigationShare(context, store, NaverNavigator(context), backgroundScope,
            lookup = { _, _ -> error("좌표는 지오코딩하지 않는다") })
        com.wemade.teslable.DiagLog.clear()
        startRoute(share, "1.123456,2.654321")
        runCurrent()
        assertNotNull(share.selection.value?.preview)
        val log = com.wemade.teslable.DiagLog.lines.value.joinToString("\n")
        assertFalse(log.contains("1.123456"))
        assertFalse(log.contains("2.654321"))
    }

    /** 실제 알림·화면 판독 순서로 공유 예약을 시작하되 테스트 모드가 실차 전송을 막는다. */
    private suspend fun startRoute(share: TeslaNavigationShare, destination: String) {
        share.screen(naver, destination)
        share.notification(naver, "route", "네이버 지도", "내비게이션 - 안내 중")
        share.naverScreen(NaverGuidanceMode.ROUTE)
    }

    /** 최근 검색 캐시가 있어도 안심주행에서는 조회를 시작하지 않는다. */
    @Test fun safeDriveDoesNotResolveCachedDestination() = runTest {
        val context = paparazzi.context
        val store = SettingsStore(context, PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporary.newFolder().resolve("settings.preferences_pb")
        })
        store.setTeslaNavigationTestMode(true)
        var lookups = 0
        val share = TeslaNavigationShare(context, store, NaverNavigator(context), backgroundScope,
            lookup = { _, _ -> lookups++; emptyList() })
        share.screen(naver, "서울역")
        share.notification(naver, "route", "네이버 지도", "내비게이션 - 안내 중")
        runCurrent()
        assertEquals(0, lookups)
        share.naverScreen(NaverGuidanceMode.SAFE_DRIVE)
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(0, lookups)
        assertNull(share.selection.value)
        // 반복 안심주행 프레임은 새 검색 후보를 지우거나 먼저 소비하지 않는다.
        share.screen(naver, "서울역")
        share.naverScreen(NaverGuidanceMode.SAFE_DRIVE)
        share.naverScreen(NaverGuidanceMode.ROUTE)
        runCurrent()
        assertEquals(1, lookups)
        share.naverScreen(NaverGuidanceMode.ROUTE)
        runCurrent()
        assertEquals(1, lookups)
    }

    /** 주소 입력 대기 이후 판독된 안심주행도 기존 입력창을 닫는다. */
    @Test fun lateSafeDriveClosesAddressSelection() = runTest {
        val context = paparazzi.context
        val store = SettingsStore(context, PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporary.newFolder().resolve("settings.preferences_pb")
        })
        store.setTeslaNavigationTestMode(true)
        val share = TeslaNavigationShare(context, store, NaverNavigator(context), backgroundScope)
        share.notification(naver, "route", "네이버 지도", "내비게이션 - 안내 중")
        runCurrent()
        advanceTimeBy(5_001)
        runCurrent()
        assertNotNull(share.selection.value)
        share.naverScreen(NaverGuidanceMode.SAFE_DRIVE)
        assertNull(share.selection.value)
    }
}
