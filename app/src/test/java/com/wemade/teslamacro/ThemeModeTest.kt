package com.wemade.teslamacro

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.paparazzi.Paparazzi
import com.wemade.teslamacro.data.settings.SettingsStore
import com.wemade.teslamacro.data.settings.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 수동 테마의 시간 독립성과 저장값 복원을 검증한다. */
class ThemeModeTest {
    @get:Rule val paparazzi = Paparazzi()
    @get:Rule val temporaryFolder = TemporaryFolder()

    /** 라이트·다크 고정은 현재 시간에 영향받지 않는다. */
    @Test fun `수동 테마와 자동 테마를 구별한다`() {
        for (night in listOf(false, true)) {
            assertFalse(ThemeMode.LIGHT.isDark(night))
            assertTrue(ThemeMode.DARK.isDark(night))
            assertEquals(night, ThemeMode.AUTO.isDark(night))
        }
    }

    /** 저장·재조회와 알 수 없는 미래 값의 복구를 실제 DataStore로 확인한다. */
    @Test fun `테마 저장값을 복원하고 기존 설치는 자동으로 시작한다`() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(temporaryFolder.root, "theme.preferences_pb")
        }
        val store = SettingsStore(ContextWrapper(paparazzi.context), preferences)
        assertEquals(ThemeMode.AUTO, store.settings.first().themeMode)
        for (mode in ThemeMode.entries) {
            store.setThemeMode(mode)
            assertEquals(mode, SettingsStore(ContextWrapper(paparazzi.context), preferences).settings.first().themeMode)
        }
        preferences.edit { it[stringPreferencesKey("theme_mode")] = "FUTURE_MODE" }
        assertEquals(ThemeMode.AUTO, store.settings.first().themeMode)
    }
}
