package com.wemade.teslamacro.domain.macro

import com.wemade.teslamacro.data.macro.MacroPresets
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class MacroFolderTest {
    /** 기본 통풍 6개·열선 2개만 분류하고 하차 종료는 밖에 둔다. */
    @Test fun `default seats are grouped without changing execution`() {
        val rules = MacroPresets.defaults()
        val folders = defaultMacroFolders(rules)
        assertEquals(listOf("통풍", "열선"), folders.map { it.name })
        assertEquals(listOf(6, 2), folders.map { it.ruleIds.size })
        assertFalse(folders.any { "preset-seat-exit-off" in it.ruleIds })
        assertEquals(folders, Json.decodeFromString<List<MacroFolder>>(Json.encodeToString(folders)))
    }

    /** 빈 폴더 생성·이름 변경·다른 폴더 이동·폴더 밖 이동을 검증한다. */
    @Test fun `create rename and move retain one membership`() {
        var folders = saveMacroFolder(emptyList(), "a", " 첫 폴더 ")
        folders = saveMacroFolder(folders, "b", "두 번째")
        assertEquals("첫 폴더", folders.first().name)
        folders = moveMacroToFolder(folders, "macro", "a")
        folders = saveMacroFolder(folders, "a", "새 이름")
        assertEquals(setOf("macro"), folders.first().ruleIds)
        folders = moveMacroToFolder(folders, "macro", "b")
        assertTrue(folders.first().ruleIds.isEmpty())
        assertEquals(setOf("macro"), folders.last().ruleIds)
        folders = moveMacroToFolder(folders, "macro", null)
        assertTrue(folders.all { it.ruleIds.isEmpty() })
    }

    /** 중복·공백·초과 이름과 사라진 목적지는 기존 분류를 훼손하지 않는다. */
    @Test fun `invalid folder operations are rejected`() {
        val folders = listOf(MacroFolder("a", "통풍", setOf("macro")))
        listOf(" ", "통풍", "a".repeat(41)).forEach { name ->
            assertTrue(runCatching { saveMacroFolder(folders, "b", name) }.isFailure)
        }
        assertTrue(runCatching { moveMacroToFolder(folders, "macro", "missing") }.isFailure)
        assertEquals(setOf("macro"), folders.single().ruleIds)
    }
}
