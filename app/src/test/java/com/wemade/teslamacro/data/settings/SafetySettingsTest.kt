package com.wemade.teslamacro.data.settings

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.paparazzi.Paparazzi
import com.wemade.teslamacro.data.backup.BackupFile
import com.wemade.teslamacro.data.backup.BackupSettings
import com.wemade.teslamacro.data.backup.toBackup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SafetySettingsTest {
    @get:Rule val paparazzi = Paparazzi()
    @get:Rule val temporaryFolder = TemporaryFolder()

    /** 기본값·저장·범위 제한·복원을 같은 Store 계약으로 확인한다. */
    @Test fun tolerancePersistsAndRestores() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(temporaryFolder.root, "safety.preferences_pb")
        }
        val store = SettingsStore(ContextWrapper(paparazzi.context), preferences)
        assertEquals(5, store.settings.first().safeDriveToleranceKph)
        store.setSafeDrive(true)
        store.setSafeDriveToleranceKph(7)
        val restored = SettingsStore(ContextWrapper(paparazzi.context), preferences)
        assertEquals(7, restored.settings.first().safeDriveToleranceKph)
        assertTrue(restored.settings.first().safeDrive)
        assertEquals(7, restored.settings.first().toBackup().safeDriveToleranceKph)
        store.setSafeDriveToleranceKph(-1)
        assertEquals(0, store.settings.first().safeDriveToleranceKph)
        store.setSafeDriveToleranceKph(31)
        assertEquals(30, store.settings.first().safeDriveToleranceKph)
        store.restore(BackupSettings(safeDriveToleranceKph = 9))
        assertEquals(9, store.settings.first().safeDriveToleranceKph)
        store.restore(BackupSettings(safeDriveToleranceKph = 99))
        assertEquals(30, store.settings.first().safeDriveToleranceKph)
    }

    /** 구형 백업은 기본값으로 열고 새 설정은 JSON 왕복 후에도 유지한다. */
    @Test fun backupCompatibility() {
        val old = BackupFile.json.decodeFromString<BackupFile>("""{"version":3,"settings":{}}""")
        assertEquals(5, old.settings.safeDriveToleranceKph)
        val backup = BackupFile(settings = BackupSettings(safeDriveToleranceKph = 8))
        val text = BackupFile.json.encodeToString(BackupFile.serializer(), backup)
        assertEquals(backup, BackupFile.json.decodeFromString<BackupFile>(text))
    }
}
