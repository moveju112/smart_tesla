package com.wemade.teslamacro.data.settings

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.paparazzi.Paparazzi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 기존 SettingsStore의 원자적 edit으로 재시작·동시 실행 기록을 보존한다. */
class MacroCooldownSettingsTest {
    @get:Rule val paparazzi = Paparazzi()
    @get:Rule val temporaryFolder = TemporaryFolder()

    /** 메모리를 재사용하지 않고 저장 파일을 새 DataStore로 다시 연다. */
    @Test fun `저장소 종료 후 재생성해도 쿨다운 기록이 남는다`() = runTest {
        val file = File(temporaryFolder.root, "cooldown.preferences_pb")
        val firstJob = Job(coroutineContext[Job])
        val firstPreferences = PreferenceDataStoreFactory.create(scope = CoroutineScope(coroutineContext + firstJob)) { file }
        val first = SettingsStore(ContextWrapper(paparazzi.context), firstPreferences)
        assertEquals(emptyMap<String, Long>(), first.macroLastFired())
        first.saveMacroFired("driver", 100_000L, setOf("driver"))
        firstJob.cancelAndJoin()

        val nextPreferences = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val restarted = SettingsStore(ContextWrapper(paparazzi.context), nextPreferences)
        assertEquals(mapOf("driver" to 100_000L), restarted.macroLastFired())
    }

    /** 서로 다른 실행 기록은 합치고 다음 저장 때 삭제된 규칙만 정리한다. */
    @Test fun `동시 기록과 삭제된 규칙 정리가 다른 쿨다운을 지우지 않는다`() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(temporaryFolder.root, "concurrent.preferences_pb")
        }
        val store = SettingsStore(ContextWrapper(paparazzi.context), preferences)
        val ids = setOf("driver", "passenger")
        listOf(
            launch { store.saveMacroFired("driver", 100L, ids) },
            launch { store.saveMacroFired("passenger", 200L, ids) },
        ).joinAll()
        assertEquals(mapOf("driver" to 100L, "passenger" to 200L), store.macroLastFired())
        store.saveMacroFired("driver", 300L, setOf("driver"))
        assertEquals(mapOf("driver" to 300L), store.macroLastFired())
    }
}
