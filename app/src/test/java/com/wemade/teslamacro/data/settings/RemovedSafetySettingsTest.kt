package com.wemade.teslamacro.data.settings

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import app.cash.paparazzi.Paparazzi
import com.wemade.teslamacro.data.backup.BackupSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RemovedSafetySettingsTest {
    @get:Rule val paparazzi = Paparazzi()
    @get:Rule val temporary = TemporaryFolder()

    /** 이전 저장값·구버전 백업·남은 호환 설정 API로 제거된 단속 기능이 복구되지 않는다. */
    @Test fun legacySettingsCannotReactivateCameraAlerts() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(temporary.root, "removed-safety.preferences_pb")
        }
        preferences.edit { it[booleanPreferencesKey("safe_drive")] = true }
        val store = SettingsStore(ContextWrapper(paparazzi.context), preferences)
        assertFalse(store.settings.first().safeDrive)
        store.restore(BackupSettings(safeDrive = true))
        assertFalse(store.settings.first().safeDrive)
        store.setSafeDrive(true)
        assertFalse(store.settings.first().safeDrive)
    }
}
