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
