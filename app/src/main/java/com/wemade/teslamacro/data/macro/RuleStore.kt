package com.wemade.teslamacro.data.macro

import android.content.Context
import com.wemade.teslamacro.domain.macro.MacroRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 매크로를 앱 파일 하나에 JSON으로 저장한다.
 *
 * 룰 개수가 수십 개를 넘지 않는 데이터라 DB를 두지 않았다.
 * 검색·정렬 요구가 생기면 그때 Room으로 옮긴다.
 */
class RuleStore(context: Context) {

    private val file = File(context.filesDir, "macros.json")

    /** 한 번이라도 깔아준 프리셋 id 목록. 지운 프리셋이 재시작마다 부활하는 걸 막는다 */
    private val seenPresetsFile = File(context.filesDir, "macro_presets_seen.json")
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true   // 앱 업데이트로 필드가 늘어도 옛 파일을 계속 읽는다
        encodeDefaults = true
    }

    private val _rules = MutableStateFlow<List<MacroRule>>(emptyList())
    val rules: StateFlow<List<MacroRule>> = _rules.asStateFlow()

    /** 앱 시작 시 1회. 파일이 없으면 기본 매크로를 깔아준다 */
    suspend fun load() = withContext(Dispatchers.IO) {
        val loaded = runCatching {
            if (!file.exists()) null else json.decodeFromString<List<MacroRule>>(file.readText())
        }.getOrNull()

        // 업데이트로 새 프리셋이 생겨도 기존 사용자에게 깔린다.
        // 같은 id는 사용자가 고친 버전을 존중하고, 한 번도 소개 안 한 것만 이어붙인다 —
        // 소개했는데 없는 건 사용자가 지운 것이니 되살리지 않는다
        val seen = runCatching {
            if (seenPresetsFile.exists()) {
                json.decodeFromString(presetIdSerializer, seenPresetsFile.readText())
            } else emptySet()
        }.getOrDefault(emptySet())

        _rules.value = loaded?.let { existing ->
            val knownIds = existing.map { it.id }.toSet() + seen
            val missing = MacroPresets.defaults().filter { it.id !in knownIds }
            if (missing.isEmpty()) existing
            else (existing + missing).also { persist(it) }
        } ?: MacroPresets.defaults().also { persist(it) }

        // 지금 시점의 프리셋 전부를 "소개함"으로 기록한다
        runCatching {
            seenPresetsFile.writeText(
                json.encodeToString(presetIdSerializer, MacroPresets.defaults().map { it.id }.toSet())
            )
        }
    }

    suspend fun upsert(rule: MacroRule) = mutate { current ->
        val index = current.indexOfFirst { it.id == rule.id }
        if (index >= 0) current.toMutableList().apply { set(index, rule) } else current + rule
    }

    suspend fun delete(id: String) = mutate { current -> current.filterNot { it.id == id } }

    suspend fun setEnabled(id: String, enabled: Boolean) = mutate { current ->
        current.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    private suspend fun mutate(transform: (List<MacroRule>) -> List<MacroRule>) {
        val updated = transform(_rules.value)
        _rules.update { updated }
        withContext(Dispatchers.IO) { persist(updated) }
    }

    private fun persist(rules: List<MacroRule>) {
        runCatching { file.writeText(json.encodeToString(ruleListSerializer, rules)) }
    }

    private companion object {
        val ruleListSerializer = ListSerializer(MacroRule.serializer())
        val presetIdSerializer = SetSerializer(String.serializer())
    }
}
