package com.wemade.teslamacro.domain.macro

/**
 * 어떤 매크로를 지금 실행해야 하는지 판정한다.
 *
 * 판정 규칙은 한 줄이다:
 * **트리거 중 하나가 방금 발생했고, 조건이 전부 참이면 실행한다.**
 *
 * 안드로이드 의존이 전혀 없는 순수 함수라 단위 테스트로 전부 검증한다.
 */
class MacroEngine {

    /**
     * @param previous 직전 판정 시점. 트리거는 "변화"라서 직전 값이 필요하다
     * @param lastFiredAtMillis 매크로 id -> 마지막 발동 시각
     * @return 지금 실행해야 하는 매크로들 (선언 순서 유지)
     */
    fun evaluate(
        rules: List<MacroRule>,
        previous: Reading?,
        current: Reading,
        lastFiredAtMillis: Map<String, Long>,
    ): List<MacroRule> = rules.filter { rule ->
        // 1. 꺼진 매크로는 건너뛴다
        if (!rule.enabled) return@filter false

        // 2. 트리거가 없으면 발동할 수 없다 (조건만으로는 절대 실행되지 않는다)
        if (rule.triggers.isEmpty()) return@filter false

        // 3. 쿨다운 중이면 건너뛴다
        val lastFired = lastFiredAtMillis[rule.id]
        if (lastFired != null &&
            current.time.epochMillis - lastFired < rule.cooldownSeconds * 1000L
        ) {
            return@filter false
        }

        // 4. 트리거 하나라도 발생 && 조건 전부 만족
        rule.triggers.any { fired(it, previous, current) } &&
            rule.conditions.all { holds(it, current) }
    }

    /** 트리거는 "방금 바뀌었나"를 본다. 직전 값이 없으면 판단할 수 없다 */
    private fun fired(trigger: Trigger, previous: Reading?, current: Reading): Boolean =
        when (trigger) {

            is Trigger.SignalBecomes -> {
                val now = trigger.signal.booleanOf(current.snapshot)
                val before = previous?.let { trigger.signal.booleanOf(it.snapshot) }
                now == trigger.to && before != null && before != trigger.to
            }

            // 그 분에 들어서는 순간에만 참. 같은 분에 두 번 폴링해도 한 번만 발동한다
            is Trigger.AtTime -> {
                val dayMatches = trigger.days.isEmpty() || current.time.dayOfWeek in trigger.days
                val reachedNow = current.time.minutesOfDay == trigger.minutesOfDay
                val wasBefore = previous?.time?.minutesOfDay == trigger.minutesOfDay
                dayMatches && reachedNow && !wasBefore
            }

            // 주기: 나눠떨어지는 분에 들어서는 순간. 같은 분 재진입은 걸러진다
            is Trigger.Every -> {
                val minutes = current.time.minutesOfDay
                val onTick = trigger.everyMinutes > 0 && minutes % trigger.everyMinutes == 0
                onTick && previous?.time?.minutesOfDay != minutes
            }

            // 호출 전용. 폴링 판정으로는 절대 발동하지 않는다 — 음성/직접 실행만
            is Trigger.Manual -> false
        }

    /** 조건은 "지금 그런 상태인가요"만 본다. 대기 해제와 같은 규칙을 쓴다 */
    private fun holds(condition: Condition, current: Reading): Boolean =
        ConditionEvaluator.holds(condition, current)
}
