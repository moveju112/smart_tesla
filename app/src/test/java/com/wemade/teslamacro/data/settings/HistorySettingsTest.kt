package com.wemade.teslamacro.data.settings

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.paparazzi.Paparazzi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 장기 위치 기록 동의가 다른 차량으로 넘어가지 않게 한다. */
class HistorySettingsTest {
    @get:Rule val paparazzi = Paparazzi()
    @get:Rule val temporaryFolder = TemporaryFolder()

    /** 기존 기기는 꺼짐이며 같은 차량은 유지하고 차량 변경은 다시 설정한다. */
    @Test fun vehicleChangeClearsRecordingChoice() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(temporaryFolder.root, "history.preferences_pb")
        }
        val store = SettingsStore(ContextWrapper(paparazzi.context), preferences)
        assertFalse(store.settings.first().historyEnabled)
        store.setVin("5YJS0000000000000")
        store.setHistoryEnabled(true)
        store.setVin("5YJS0000000000000")
        assertTrue(store.settings.first().historyEnabled)
        store.setVin("")
        assertFalse(store.settings.first().historyEnabled)
    }
}
