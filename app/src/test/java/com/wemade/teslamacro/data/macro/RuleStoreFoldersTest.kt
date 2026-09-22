package com.wemade.teslamacro.data.macro

import android.content.ContextWrapper
import app.cash.paparazzi.Paparazzi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 화면을 렌더링하지 않고 기존 Android 테스트 컨텍스트로 실제 파일 저장을 확인한다. */
class RuleStoreFoldersTest {
    @get:Rule val paparazzi = Paparazzi()
    @get:Rule val temporary = TemporaryFolder()

    /** 폴더의 별도 파일 저장을 실제 재생성한 스토어로 읽는다. */
    private fun store() = RuleStore(object : ContextWrapper(paparazzi.context) {
        // 테스트 폴더만 사용해 실행 환경의 실제 데이터를 건드리지 않는다.
        override fun getFilesDir() = temporary.root
    })

    /** 최초 분류 후 사용자가 옮긴 항목과 빈 폴더는 재시작에도 보존한다. */
    @Test fun `restart preserves renamed empty folders and moved macros`() = runBlocking {
        val first = store()
        first.load()
        val rules = first.rules.value
        assertEquals(listOf(6, 2), first.folders.value.map { it.ruleIds.size })
        first.saveFolder(null, "내 폴더")
        first.saveFolder("seat-cooling", "여름")
        first.moveToFolder("preset-seat-driver-cool-3", null)
        val restarted = store()
        restarted.load()
        assertEquals(first.folders.value, restarted.folders.value)
        assertEquals(rules, restarted.rules.value)
        assertFalse(restarted.folders.value.any { "preset-seat-driver-cool-3" in it.ruleIds })
    }

    /** 원자적 쓰기 도중 남은 백업이 있으면 기본 분류로 덮지 않고 복구한다. */
    @Test fun `interrupted folder write restores backup`() = runBlocking {
        val first = store()
        first.load()
        first.saveFolder("seat-cooling", "내 통풍")
        val file = java.io.File(temporary.root, "macro_folders.json")
        assertTrue(file.renameTo(java.io.File(temporary.root, "macro_folders.json.bak")))
        val restarted = store()
        restarted.load()
        assertEquals(first.folders.value, restarted.folders.value)
    }

    /** 동시 폴더 생성이 서로 덮어쓰지 않고 저장 실패는 기존 상태를 유지한다. */
    @Test fun `concurrent edits and invalid destination preserve saved state`() = runBlocking {
        val first = store()
        first.load()
        coroutineScope { repeat(8) { index -> launch { first.saveFolder(null, "폴더 $index") } } }
        val saved = first.folders.value
        assertEquals(10, saved.size)
        assertTrue(runCatching { first.moveToFolder("preset-seat-driver-heat", "missing") }.isFailure)
        val restarted = store()
        restarted.load()
        assertEquals(saved, restarted.folders.value)
    }
}
