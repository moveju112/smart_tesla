package com.wemade.teslamacro.feature.macro.edit

import com.wemade.teslamacro.domain.macro.ActionStep
import com.wemade.teslamacro.domain.macro.Condition
import com.wemade.teslamacro.domain.macro.MacroRule
import com.wemade.teslamacro.domain.macro.Trigger
import java.util.UUID

/**
 * 편집 중인 매크로.
 *
 * 저장된 [MacroRule]과 분리한 이유는 편집 중에는 "트리거 0개" 같은
 * 저장 불가능한 상태를 허용해야 하기 때문이다.
 */
data class MacroDraft(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val triggers: List<Trigger>,
    val conditions: List<Condition>,
    val actions: List<ActionStep>,
    val cooldownSeconds: Int,
    val isNew: Boolean,
) {
    /** 저장 가능한지. 이유가 있으면 문자열, 없으면 null */
    val blockReason: String?
        get() = when {
            name.isBlank() -> "이름을 입력해 주세요"
            // 트리거가 없으면 발동 시점이 없다. 조건만으로는 절대 실행되지 않는다
            triggers.isEmpty() -> "\"언제\"를 하나 이상 추가해 주세요"
            // 지도 안내도 어엿한 동작이다 — 안내만 있는 매크로(탑승 → 길안내)를 막으면 안 된다
            actions.none { it is ActionStep.Run || it is ActionStep.Navigate } ->
                "\"실행할 동작\"을 하나 이상 추가해 주세요"
            // 목적지가 비면 실행 시점에 아무 데도 못 간다
            actions.any { it is ActionStep.Navigate && it.address.isBlank() } ->
                "지도 안내의 주소를 입력해 주세요"
            // 위치가 비어 있는 조건은 절대 충족되지 않아 매크로가 영영 안 돈다
            conditions.any { it is Condition.NearLocation && it.latitude == null } ->
                "\"출발지 근처\" 조건에 현재 위치를 저장해 주세요"
            else -> null
        }

    val canSave: Boolean get() = blockReason == null

    fun toRule() = MacroRule(
        id = id,
        name = name.trim(),
        enabled = enabled,
        triggers = triggers,
        conditions = conditions,
        actions = actions,
        cooldownSeconds = cooldownSeconds,
    )

    // ---- 트리거 ----
    fun addTrigger(trigger: Trigger) = copy(triggers = triggers + trigger)

    fun replaceTrigger(index: Int, trigger: Trigger) =
        copy(triggers = triggers.toMutableList().apply { set(index, trigger) })

    fun removeTrigger(index: Int) = copy(triggers = triggers.filterIndexed { i, _ -> i != index })

    // ---- 조건 ----
    fun addCondition(condition: Condition) = copy(conditions = conditions + condition)

    fun replaceCondition(index: Int, condition: Condition) =
        copy(conditions = conditions.toMutableList().apply { set(index, condition) })

    fun removeCondition(index: Int) =
        copy(conditions = conditions.filterIndexed { i, _ -> i != index })

    // ---- 동작 ----
    fun addAction(step: ActionStep) = copy(actions = actions + step)

    fun replaceAction(index: Int, step: ActionStep) =
        copy(actions = actions.toMutableList().apply { set(index, step) })

    fun removeAction(index: Int) = copy(actions = actions.filterIndexed { i, _ -> i != index })

    /** 실행 순서 바꾸기. 범위를 벗어나면 아무 일도 하지 않는다 */
    fun moveAction(index: Int, offset: Int): MacroDraft {
        val target = index + offset
        if (target !in actions.indices) return this
        return copy(
            actions = actions.toMutableList().apply { add(target, removeAt(index)) }
        )
    }

    companion object {
        fun from(rule: MacroRule) = MacroDraft(
            id = rule.id,
            name = rule.name,
            enabled = rule.enabled,
            triggers = rule.triggers,
            conditions = rule.conditions,
            actions = rule.actions,
            cooldownSeconds = rule.cooldownSeconds,
            isNew = false,
        )

        fun blank() = MacroDraft(
            id = "macro-${UUID.randomUUID()}",
            name = "",
            enabled = true,
            triggers = emptyList(),
            conditions = emptyList(),
            actions = emptyList(),
            cooldownSeconds = 300,
            isNew = true,
        )
    }
}
