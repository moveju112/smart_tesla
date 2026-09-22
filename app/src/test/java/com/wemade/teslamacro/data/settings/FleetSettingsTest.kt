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

class FleetSettingsTest {
    @get:Rule val paparazzi = Paparazzi()
    @get:Rule val temporaryFolder = TemporaryFolder()

    /** 기존 설치는 BLE가 기본이고 Fleet 선택은 Store 재생성 뒤에도 유지한다. */
    @Test
    fun `fleet choice defaults off and persists without changing voice settings`() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(temporaryFolder.root, "fleet.preferences_pb")
        }
        val store = SettingsStore(ContextWrapper(paparazzi.context), preferences)
        assertFalse(store.settings.first().fleetApiEnabled)
        store.setSmartThingsEnabled(true)
        store.setSmartThingsValiditySeconds(60)
        store.setFleetApiEnabled(true)
        val restored = SettingsStore(ContextWrapper(paparazzi.context), preferences)
        assertTrue(restored.settings.first().fleetApiEnabled)
        assertTrue(restored.settings.first().smartThingsEnabled)
        assertEquals(60, restored.settings.first().smartThingsValiditySeconds)
        restored.setFleetApiEnabled(false)
        assertFalse(store.settings.first().fleetApiEnabled)
    }
}
