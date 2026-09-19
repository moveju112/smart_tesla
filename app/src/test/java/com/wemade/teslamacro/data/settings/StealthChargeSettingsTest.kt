package com.wemade.teslamacro.data.settings

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.paparazzi.Paparazzi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 스텔스 충전 최대 전류의 기본값·저장·안전 범위를 검증한다. */
class StealthChargeSettingsTest {
    @get:Rule val paparazzi = Paparazzi()
    @get:Rule val temporaryFolder = TemporaryFolder()

    /** 저장값을 5~48A로 제한하고 새 Store에서도 그대로 복원한다. */
    @Test
    fun `최대 전류를 안전 범위로 저장한다`() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(temporaryFolder.root, "stealth-charge.preferences_pb")
        }
        val store = SettingsStore(ContextWrapper(paparazzi.context), preferences)

        assertEquals(48, store.settings.first().stealthMaxAmps)
        store.setStealthMaxAmps(3)
        assertEquals(5, store.settings.first().stealthMaxAmps)
        store.setStealthMaxAmps(60)
        assertEquals(
            48,
            SettingsStore(ContextWrapper(paparazzi.context), preferences)
                .settings.first().stealthMaxAmps,
        )
    }

    /** 하한은 기본이 자동(null)이고, 상한을 내리면 하한도 함께 내려간다. */
    @Test
    fun `최소 전류는 자동이 기본이고 상한을 넘지 않는다`() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(temporaryFolder.root, "stealth-charge-min.preferences_pb")
        }
        val store = SettingsStore(ContextWrapper(paparazzi.context), preferences)

        assertNull(store.settings.first().stealthMinAmps)
        store.setStealthMinAmps(3)
        assertEquals(5, store.settings.first().stealthMinAmps)
        store.setStealthMinAmps(40)
        assertEquals(40, store.settings.first().stealthMinAmps)
        store.setStealthMaxAmps(16)
        assertEquals(16, store.settings.first().stealthMinAmps)
        store.setStealthMinAmps(null)
        assertNull(
            SettingsStore(ContextWrapper(paparazzi.context), preferences)
                .settings.first().stealthMinAmps,
        )
    }
}
