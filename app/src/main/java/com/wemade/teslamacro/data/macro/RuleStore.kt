package com.wemade.teslamacro.data.macro

import android.content.Context
import android.util.AtomicFile
import com.wemade.teslamacro.domain.macro.MacroFolder
import com.wemade.teslamacro.domain.macro.defaultMacroFolders
import com.wemade.teslamacro.domain.macro.saveMacroFolder
import com.wemade.teslamacro.domain.macro.moveMacroToFolder
import com.wemade.teslamacro.domain.macro.restoreMacroFolders
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.wemade.teslamacro.domain.macro.MacroRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import java.io.File

/**
 * 매크로를 앱 파일 하나에 JSON으로 저장한다.
 *
 * 룰 개수가 수십 개를 넘지 않는 데이터라 DB를 두지 않았다.
 * 검색·정렬 요구가 생기면 그때 Room으로 옮긴다.
 */
class RuleStore(context: Context) {

    private val file = AtomicFile(File(context.filesDir, "macros.json"))
    private val ruleLock = Mutex()
    private val folderFile = AtomicFile(File(context.filesDir, "macro_folders.json"))
    private val folderLock = Mutex()
    private val _folders = MutableStateFlow<List<MacroFolder>>(emptyList())
    val folders: StateFlow<List<MacroFolder>> = _folders.asStateFlow()

    /** 한 번이라도 깔아준 프리셋 id 목록. 지운 프리셋이 재시작마다 부활하는 걸 막는다 */
    private val seenPresetsFile = File(context.filesDir, "macro_presets_seen.json")
    /** 이 버전이 읽지 못한 매크로 원문. 새 버전을 다시 깔면 되살린다 */
    private val rejectedFile = AtomicFile(File(context.filesDir, "macros.rejected.json"))
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true   // 앱 업데이트로 필드가 늘어도 옛 파일을 계속 읽는다
        encodeDefaults = true
    }

    private val _rules = MutableStateFlow<List<MacroRule>>(emptyList())
    val rules: StateFlow<List<MacroRule>> = _rules.asStateFlow()

    /** 앱 시작 시 1회. 파일이 없으면 기본 매크로를 깔아준다 */
    suspend fun load() = withContext(Dispatchers.IO) {
        val stored = if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
            decodeRules(file.readFully().decodeToString())
        } else null
        // 예전에 못 읽어 격리한 매크로를 이번 버전이 읽을 수 있으면 목록에 되돌린다
        val recovery = stored?.let { recoverRejected(it.map { rule -> rule.id }.toSet()) }
        val recovered = recovery?.first.orEmpty()
        val loaded = stored?.let { it + recovered }

        // 업데이트로 새 프리셋이 생겨도 기존 사용자에게 깔린다.
        // 같은 id는 사용자가 고친 버전을 존중하고, 한 번도 소개 안 한 것만 이어붙인다 —
        // 소개했는데 없는 건 사용자가 지운 것이니 되살리지 않는다
        val seen = runCatching {
            if (seenPresetsFile.exists()) {
                json.decodeFromString(presetIdSerializer, seenPresetsFile.readText())
            } else emptySet()
        }.getOrDefault(emptySet())

        // 손대지 않은 옛 여름·겨울 프리셋만 교체해 서로 다른 단계가 동시에 켜지지 않게 한다.
        val obsolete = listOf(MacroPresets.summerBoarding(), MacroPresets.winterBoarding())
        val updated = loaded?.filterNot { rule ->
            rule.id == REMOVED_AFTER_BLOW_PRESET_ID || obsolete.any { rule == it || rule == it.copy(enabled = false) }
        }?.let { remaining ->
            val existing = SeatComfortPresets.upgradeTemperatureFollowing(remaining)
            val knownIds = existing.map { it.id }.toSet() + seen
            val missing = MacroPresets.defaults().filter { it.id !in knownIds }.map { preset ->
                // 해당 좌석의 통풍을 모두 껐거나 지웠으면 새 끄기 규칙도 자동으로 켜지 않는다.
                if (preset.id.endsWith("-cool-0")) preset.copy(enabled = existing.any {
                    it.id.startsWith(preset.id.removeSuffix("0")) && it.enabled
                }) else preset
            }
            if (missing.isEmpty() && existing == loaded && recovered.isEmpty()) existing
            else (existing + missing).also { persist(it) }
        } ?: MacroPresets.defaults().also { persist(it) }
        // 본 파일 저장이 끝난 뒤에만 격리 원본을 정리해 저장 실패 시 다음 시작에서 다시 복구한다.
        recovery?.let {
            runCatching { writeRejected(it.second) }
                .onFailure { com.wemade.teslable.DiagLog.add("복구한 매크로의 격리 원본 정리는 다음 시작에서 다시 시도해요") }
        }
        _rules.value = updated

        folderLock.withLock {
            // 이미 저장된 폴더가 있으면 사용자의 이동·이름 변경을 다시 분류하지 않는다.
            _folders.value = if (folderFile.baseFile.exists() || File(folderFile.baseFile.path + ".bak").exists()) {
                val folders = json.decodeFromString<List<MacroFolder>>(folderFile.readFully().decodeToString())
                // 처음 소개하는 끄기 프리셋만 통풍 폴더에 넣고 기존 사용자 이동은 보존한다.
                val newCoolingIds = updated.filter { it.id !in seen && it.id !in loaded.orEmpty().map { rule -> rule.id } &&
                    it.id.matches(Regex("preset-seat-(driver|passenger)-cool-0")) }.map { it.id }.toSet()
                folders.map { if (it.id == "seat-cooling") it.copy(ruleIds = it.ruleIds + newCoolingIds) else it }
                    .also { if (it != folders) persistFolders(it) }
            } else defaultMacroFolders(_rules.value).also { persistFolders(it) }
        }

        // 지금 시점의 프리셋 전부를 "소개함"으로 기록한다
        runCatching {
            seenPresetsFile.writeText(
                json.encodeToString(presetIdSerializer, seen + MacroPresets.defaults().map { it.id }.toSet())
            )
        }
    }

    // 매크로 파일 읽기 (JSON 배열 -> 읽을 수 있는 매크로 + 격리 보관)
    // 새 버전에서 만든 매크로 하나(모르는 트리거·명령)가 앱 시작 전체를 막지 않게 항목별로 읽는다.
    // 파일 자체가 깨졌으면 기존처럼 실패시켜 사용자 매크로를 빈 목록으로 덮어쓰지 않는다
    private fun decodeRules(text: String): List<MacroRule> {
        val rejected = mutableListOf<kotlinx.serialization.json.JsonElement>()
        val rules = json.parseToJsonElement(text).jsonArray.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(MacroRule.serializer(), element) }
                .onFailure { rejected += element }
                .getOrNull()
        }
        if (rejected.isNotEmpty()) {
            val kept = readRejected()
            writeRejected(kept + rejected.filterNot { it in kept })
            com.wemade.teslable.DiagLog.add("매크로 ${rejected.size}개를 이 버전에서 읽지 못해 따로 보관했어요")
        }
        return rules
    }

    // 격리 매크로 복구 (격리 원문 -> 지금 읽히는 매크로)
    private fun recoverRejected(existingIds: Set<String>): Pair<List<MacroRule>, List<kotlinx.serialization.json.JsonElement>> {
        val kept = readRejected()
        if (kept.isEmpty()) return emptyList<MacroRule>() to emptyList()
        val recovered = mutableListOf<MacroRule>()
        val still = kept.filter { element ->
            val rule = runCatching { json.decodeFromJsonElement(MacroRule.serializer(), element) }.getOrNull()
                ?: return@filter true
            if (rule.id !in existingIds && recovered.none { it.id == rule.id }) recovered += rule
            false
        }
        return recovered to still
    }

    /** 격리 파일이 깨졌을 때 빈 목록으로 덮지 않고 원문을 보존한다. */
    private fun readRejected(): List<kotlinx.serialization.json.JsonElement> =
        if (rejectedFile.baseFile.exists() || File(rejectedFile.baseFile.path + ".bak").exists()) {
            json.parseToJsonElement(rejectedFile.readFully().decodeToString()).jsonArray.toList()
        } else emptyList()

    /** 새 격리 원본이 안전하게 저장돼야 읽을 수 있는 매크로의 편집을 허용한다. */
    private fun writeRejected(elements: List<kotlinx.serialization.json.JsonElement>) {
        if (elements.isEmpty()) {
            rejectedFile.delete()
            return
        }
        val output = rejectedFile.startWrite()
        try {
            output.write(json.encodeToString(kotlinx.serialization.json.JsonArray.serializer(),
                kotlinx.serialization.json.JsonArray(elements)).encodeToByteArray())
            rejectedFile.finishWrite(output)
        } catch (error: Exception) {
            rejectedFile.failWrite(output)
            throw error
        }
    }

    /** 폴더 생성과 이름 변경은 같은 저장 경로를 쓰고 쓰기 실패 시 화면 값을 보존한다. */
    suspend fun saveFolder(id: String?, name: String) = mutateFolders {
        saveMacroFolder(it, id ?: java.util.UUID.randomUUID().toString(), name)
    }

    /** 이동은 실행 중인 매크로에 영향을 주지 않는다. */
    suspend fun moveToFolder(ruleId: String, folderId: String?) = mutateFolders {
        require(_rules.value.any { rule -> rule.id == ruleId }) { "매크로를 찾을 수 없어요." }
        moveMacroToFolder(it, ruleId, folderId)
    }

    /** 백업 매크로의 분류만 복원하고 이 기기의 다른 매크로와 폴더는 보존한다. */
    suspend fun restoreFolders(folders: List<MacroFolder>, restoredRuleIds: Set<String>) = mutateFolders {
        restoreMacroFolders(it, folders, restoredRuleIds.intersect(_rules.value.map { rule -> rule.id }.toSet()))
    }

    /** 연속 이동·이름 변경을 직렬화하고 저장 완료 뒤에만 화면에 반영한다. */
    private suspend fun mutateFolders(transform: (List<MacroFolder>) -> List<MacroFolder>) = withContext(Dispatchers.IO) {
        folderLock.withLock {
            val updated = transform(_folders.value)
            persistFolders(updated)
            _folders.value = updated
        }
    }

    /** 프로세스 종료 중에도 직전 폴더 파일이 남도록 원자적으로 교체한다. */
    private fun persistFolders(folders: List<MacroFolder>) {
        val output = folderFile.startWrite()
        try {
            output.write(json.encodeToString(ListSerializer(MacroFolder.serializer()), folders).encodeToByteArray())
            folderFile.finishWrite(output)
        } catch (error: Exception) {
            folderFile.failWrite(output)
            throw error
        }
    }

    suspend fun upsert(rule: MacroRule) = mutate { current ->
        val index = current.indexOfFirst { it.id == rule.id }
        if (index >= 0) current.toMutableList().apply { set(index, rule) } else current + rule
    }

    suspend fun delete(id: String) = mutate { current -> current.filterNot { it.id == id } }

    /**
     * 백업에서 되돌린다. 같은 id는 파일 쪽이 이긴다 —
     * 복원은 "합치기"가 아니라 "그때로 돌아가기"여야 사람이 결과를 예측할 수 있다.
     * 파일에 없는 기존 매크로는 남긴다(다른 기기에서 만든 것을 지우지 않는다).
     */
    suspend fun restore(rules: List<MacroRule>) = mutate { current ->
        val incoming = rules.associateBy { it.id }
        current.filterNot { it.id in incoming } + incoming.values
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = mutate { current ->
        current.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    private suspend fun mutate(transform: (List<MacroRule>) -> List<MacroRule>) = withContext(Dispatchers.IO) {
        ruleLock.withLock {
            val updated = transform(_rules.value)
            persist(updated)
            _rules.value = updated
        }
    }

    /** 저장 실패 시 이전 파일을 복원하고 화면 상태는 바꾸지 않는다. */
    private fun persist(rules: List<MacroRule>) {
        val output = file.startWrite()
        try {
            output.write(json.encodeToString(ruleListSerializer, rules).encodeToByteArray())
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    private companion object {
        const val REMOVED_AFTER_BLOW_PRESET_ID = "preset-after-blow"
        val ruleListSerializer = ListSerializer(MacroRule.serializer())
        val presetIdSerializer = SetSerializer(String.serializer())
    }
}
