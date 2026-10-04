package com.wemade.teslamacro.data.settings

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.paparazzi.Paparazzi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 위젯 꾸미기 저장·복원과 잘못된 이전 설정값을 확인한다. */
class DestinationWidgetSettingsTest {
    @get:Rule val paparazzi = Paparazzi()
    @get:Rule val temporary = TemporaryFolder()

    /** 완전 투명·밝은 글자 조합이 다시 읽을 때도 유지되고 앱 테마와 독립적인지 확인한다. */
    @Test fun appearancePersistsIndependentlyOfAppTheme() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(temporary.root, "widget.preferences_pb")
        }
        val context = ContextWrapper(paparazzi.context)
        val store = SettingsStore(context, preferences)
        assertEquals(DestinationWidgetAppearance(), store.settings.first().destinationWidgetAppearance)
        val appearance = DestinationWidgetAppearance(DestinationWidgetTheme.DARK, 100, DestinationWidgetText.LIGHT, false)
        store.setThemeMode(ThemeMode.LIGHT)
        store.setDestinationWidgetAppearance(appearance)
        val restored = SettingsStore(context, preferences).settings.first()
        assertEquals(appearance, restored.destinationWidgetAppearance)
        assertEquals(ThemeMode.LIGHT, restored.themeMode)
    }

    /** 범위 밖 투명도와 알 수 없는 테마는 위젯을 깨뜨리지 않는 기본값으로 정리한다. */
    @Test fun invalidStoredValuesHaveSafeFallbacks() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(temporary.root, "invalid-widget.preferences_pb")
        }
        val store = SettingsStore(ContextWrapper(paparazzi.context), preferences)
        preferences.edit {
            it[stringPreferencesKey("destination_widget_theme")] = "unknown"
            it[stringPreferencesKey("destination_widget_text")] = "unknown"
            it[intPreferencesKey("destination_widget_transparency")] = 150
        }
        assertEquals(DestinationWidgetAppearance(transparency = 100), store.settings.first().destinationWidgetAppearance)
        store.setDestinationWidgetAppearance(DestinationWidgetAppearance(transparency = -1))
        assertEquals(0, store.settings.first().destinationWidgetAppearance.transparency)
    }
}
