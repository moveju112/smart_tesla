package com.wemade.teslamacro.domain.macro

import kotlinx.serialization.Serializable

/** 폴더는 표시용 분류이며 매크로의 실행 조건과 활성 상태를 바꾸지 않는다. */
@Serializable
data class MacroFolder(val id: String, val name: String, val ruleIds: Set<String> = emptySet())

/** 최초 업데이트에서 기본 좌석 프리셋만 분류하고 공통 하차 종료는 밖에 둔다. */
fun defaultMacroFolders(rules: List<MacroRule>): List<MacroFolder> = listOf(
    MacroFolder("seat-cooling", "통풍", rules.filter { it.id.matches(Regex("preset-seat-(driver|passenger)-cool-[123]")) }.map { it.id }.toSet()),
    MacroFolder("seat-heating", "열선", rules.filter { it.id in setOf("preset-seat-driver-heat", "preset-seat-passenger-heat") }.map { it.id }.toSet()),
)

/** 빈 이름·중복 이름을 막고 이름 변경 시 소속 매크로를 보존한다. */
fun saveMacroFolder(folders: List<MacroFolder>, id: String, name: String): List<MacroFolder> {
    val trimmed = name.trim()
    require(trimmed.isNotEmpty() && trimmed.length <= 40) { "폴더 이름은 1~40자로 입력해 주세요." }
    require(folders.none { it.id != id && it.name.equals(trimmed, ignoreCase = true) }) { "같은 이름의 폴더가 있어요." }
    return if (folders.any { it.id == id }) folders.map { if (it.id == id) it.copy(name = trimmed) else it }
    else folders + MacroFolder(id, trimmed)
}

/** 한 매크로는 한 폴더에만 넣고 null 대상은 폴더 밖으로 이동한다. */
fun moveMacroToFolder(folders: List<MacroFolder>, ruleId: String, folderId: String?): List<MacroFolder> {
    require(folderId == null || folders.any { it.id == folderId }) { "폴더를 찾을 수 없어요." }
    return folders.map { folder -> folder.copy(ruleIds = if (folder.id == folderId) folder.ruleIds + ruleId else folder.ruleIds - ruleId) }
}

/** 이름이 같은 다른 기기 폴더는 합치고 백업 밖 매크로의 소속은 유지한다. */
fun restoreMacroFolders(current: List<MacroFolder>, restored: List<MacroFolder>, restoredRuleIds: Set<String>): List<MacroFolder> {
    var folders = current.map { it.copy(ruleIds = it.ruleIds - restoredRuleIds) }
    for (folder in restored) {
        val name = folder.name.trim()
        val targetId = folders.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id ?: folder.id
        require(targetId.isNotBlank()) { "폴더 식별자가 비어 있어요." }
        folders = saveMacroFolder(folders, targetId, name)
        for (ruleId in folder.ruleIds.intersect(restoredRuleIds)) {
            folders = moveMacroToFolder(folders, ruleId, targetId)
        }
    }
    return folders
}

/** 홈에는 미분류만, 폴더 안에는 그 폴더의 항목만 원래 순서대로 표시한다. */
fun macroRulesInFolder(rules: List<MacroRule>, folders: List<MacroFolder>, folderId: String?): List<MacroRule> {
    val selected = folders.firstOrNull { it.id == folderId }
    if (selected != null) return rules.filter { it.id in selected.ruleIds }
    if (folders.isEmpty()) return rules
    val filedIds = HashSet<String>()
    for (folder in folders) filedIds.addAll(folder.ruleIds)
    return rules.filterNot { it.id in filedIds }
}
