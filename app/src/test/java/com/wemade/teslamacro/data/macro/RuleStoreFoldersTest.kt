package com.wemade.teslamacro.data.macro

import android.content.ContextWrapper
import app.cash.paparazzi.Paparazzi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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

    /** 동시에 저장한 매크로가 서로 덮이지 않고 재시작 뒤에도 모두 남는다. */
    @Test fun `concurrent rule edits survive restart`() = runBlocking {
        val first = store()
        first.load()
        val template = first.rules.value.first()
        coroutineScope {
            repeat(8) { index ->
                launch(Dispatchers.Default) {
                    first.upsert(template.copy(id = "concurrent-$index", name = "매크로 $index"))
                }
            }
        }
        val restarted = store()
        restarted.load()
        assertEquals((0 until 8).map { "concurrent-$it" }.toSet(),
            restarted.rules.value.map { it.id }.filter { it.startsWith("concurrent-") }.toSet())
    }

    /** 쓰기가 막히면 화면 상태를 앞서 바꾸지 않고 기존 저장본을 복구한다. */
    @Test fun `failed rule write preserves state and previous file`() = runBlocking {
        val first = store()
        first.load()
        val before = first.rules.value
        val blockingDirectory = java.io.File(temporary.root, "macros.json.new")
        assertTrue(blockingDirectory.mkdir())
        assertTrue(runCatching {
            first.upsert(before.first().copy(name = "저장되지 않아야 함"))
        }.isFailure)
        assertEquals(before, first.rules.value)
        val restarted = store()
        restarted.load()
        assertEquals(before, restarted.rules.value)
    }

    /** 손상된 저장본을 기본값으로 덮지 않고, 파일 복구 뒤 같은 스토어에서 재시도한다. */
    @Test fun `corrupt rules remain intact until repaired and reloaded`() = runBlocking {
        val original = store()
        original.load()
        original.upsert(original.rules.value.first().copy(name = "보존할 매크로"))
        val file = java.io.File(temporary.root, "macros.json")
        val saved = file.readText()
        file.writeText("{broken")
        val restarted = store()
        assertTrue(runCatching { restarted.load() }.isFailure)
        assertEquals("{broken", file.readText())
        file.writeText(saved)
        restarted.load()
        assertEquals(original.rules.value, restarted.rules.value)
    }

    /** 이 버전이 모르는 매크로 하나가 앱 시작을 막지 않고, 다시 읽히면 목록에 되돌아온다. */
    @Test fun `unknown macro is quarantined and recovered later`() = runBlocking {
        val first = store()
        first.load()
        val count = first.rules.value.size
        val file = java.io.File(temporary.root, "macros.json")
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(file.readText()).jsonArray
        val template = parsed.first().jsonObject
        val future = kotlinx.serialization.json.JsonObject(template + mapOf(
            "id" to kotlinx.serialization.json.JsonPrimitive("future-1"),
            "actions" to kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.buildJsonObject { put("type", kotlinx.serialization.json.JsonPrimitive("future.Action")) })
            },
        ))
        file.writeText(kotlinx.serialization.json.JsonArray(parsed + future).toString())

        val older = store()
        older.load()
        assertEquals(count, older.rules.value.size)
        assertFalse(older.rules.value.any { it.id == "future-1" })
        val rejected = java.io.File(temporary.root, "macros.rejected.json")
        assertTrue(rejected.exists())

        // 새 버전이 읽을 수 있게 된 상황을 같은 원문의 읽히는 매크로로 재현한다
        val readable = kotlinx.serialization.json.JsonObject(template + mapOf("id" to kotlinx.serialization.json.JsonPrimitive("future-1")))
        rejected.writeText(kotlinx.serialization.json.JsonArray(listOf(readable)).toString())
        // 구버전에서 다른 매크로를 저장해 본 파일에서는 빠진 상태다
        file.writeText(parsed.toString())
        val newer = store()
        newer.load()
        assertTrue(newer.rules.value.any { it.id == "future-1" })
        assertFalse(rejected.exists())
    }
}
