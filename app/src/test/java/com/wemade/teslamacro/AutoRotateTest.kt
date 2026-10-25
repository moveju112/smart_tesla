package com.wemade.teslamacro

import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.paparazzi.Paparazzi
import com.wemade.teslamacro.data.backup.BackupFile
import com.wemade.teslamacro.data.backup.toBackup
import com.wemade.teslamacro.data.settings.AppSettings
import com.wemade.teslamacro.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AutoRotateTest {
    @get:Rule val paparazzi = Paparazzi()
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `켜면 모든 방향의 센서를 쓰고 끄면 현재 방향을 고정한다`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR, screenOrientationFor(true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED, screenOrientationFor(false))
    }

    @Test fun `기존 설치는 자동 회전이며 켜기와 끄기를 저장소 재시작 뒤에도 유지한다`() = runTest {
        assertTrue(AppSettings().autoRotateEnabled)
        val context = ContextWrapper(paparazzi.context)
        for (enabled in listOf(false, true)) {
            val file = File(temporaryFolder.root, "rotation-$enabled.preferences_pb")
            val writerJob = Job(backgroundScope.coroutineContext[Job])
            val preferences = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(backgroundScope.coroutineContext + writerJob),
            ) { file }
            val store = SettingsStore(context, preferences)
            assertTrue(store.settings.first().autoRotateEnabled)
            store.setAutoRotateEnabled(enabled)
            assertEquals(enabled, store.settings.first().autoRotateEnabled)
            writerJob.cancelAndJoin()

            val reopened = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
            assertEquals(enabled, SettingsStore(context, reopened).settings.first().autoRotateEnabled)
        }
    }

    @Test fun `백업 왕복과 복원에서 자동 회전 선택을 유지한다`() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(temporaryFolder.root, "backup.preferences_pb")
        }
        val store = SettingsStore(ContextWrapper(paparazzi.context), preferences)
        for (enabled in listOf(false, true)) {
            val backup = BackupFile(settings = AppSettings(autoRotateEnabled = enabled).toBackup())
            val encoded = BackupFile.json.encodeToString(BackupFile.serializer(), backup)
            val decoded = BackupFile.json.decodeFromString(BackupFile.serializer(), encoded)
            assertEquals(enabled, decoded.settings.autoRotateEnabled)
            store.setAutoRotateEnabled(!enabled)
            store.restore(decoded.settings)
            assertEquals(enabled, store.settings.first().autoRotateEnabled)
        }
    }

    @Test fun `자동 회전 항목이 없는 구백업은 현재 선택을 덮어쓰지 않는다`() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(temporaryFolder.root, "legacy.preferences_pb")
        }
        val store = SettingsStore(ContextWrapper(paparazzi.context), preferences)
        val legacy = BackupFile.json.decodeFromString(
            BackupFile.serializer(), """{"version":9,"settings":{}}""",
        )
        assertNull(legacy.settings.autoRotateEnabled)
        for (enabled in listOf(false, true)) {
            store.setAutoRotateEnabled(enabled)
            store.restore(legacy.settings)
            assertEquals(enabled, store.settings.first().autoRotateEnabled)
        }
    }
}
