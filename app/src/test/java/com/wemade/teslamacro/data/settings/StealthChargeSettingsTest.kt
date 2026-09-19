package com.wemade.teslamacro.data.settings

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.paparazzi.Paparazzi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}
