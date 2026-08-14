package com.wemade.teslamacro.domain.macro

import com.wemade.teslamacro.domain.model.Signal

/**
 * 어떤 매크로를 지금 실행해야 하는지 판정한다.
 *
 * 판정 규칙은 한 줄이다:
 * **트리거 중 하나가 방금 발생했고, 조건이 전부 참이면 실행한다.**
 *
 * 안드로이드 의존이 전혀 없어 단위 테스트로 전부 검증한다.
 * "항상 감시" 재발동 억제용 룰별 래치 하나만 상태로 가진다 — 같은 인스턴스로 연속 호출해야 한다.
 */
class MacroEngine {

    /**
     * "항상 감시" 룰별 래치: 직전 판정에서 조건이 전부 참이었나.
     * 없던 룰(새로 만들었거나 다시 켠 것)은 "직전 = 미충족"으로 시작한다 —
     * 켜는 순간 조건이 이미 참이면 1회 발동시키기 위해서다. 실사용 불만에서 나온 규칙:
     * "22~24℃면 통풍"을 22.5℃에 만들었더니 범위를 벗어난 적이 없어 영영 안 터졌다
     */
    private val alwaysHeld = mutableMapOf<String, Boolean>()

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
    ): List<MacroRule> {
        // 꺼졌거나 삭제된 룰의 래치는 잊는다 — 다시 켜면 "이미 참"도 1회 발동한다
        alwaysHeld.keys.retainAll(rules.filter { it.enabled }.map { it.id }.toSet())

        return rules.filter { rule ->
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

            // 4. 트리거 하나라도 발생 && 조건 전부 만족.
            //    any 대신 map+any — 래치 갱신 때문에 모든 트리거를 반드시 평가한다
            rule.triggers.map { fired(rule, it, previous, current) }.any { it } &&
                rule.conditions.all { holds(it, current) }
        }
    }

    /** 트리거는 "방금 바뀌었나"를 본다. 직전 값이 없으면 판단할 수 없다 */
    private fun fired(
        rule: MacroRule,
        trigger: Trigger,
        previous: Reading?,
        current: Reading,
    ): Boolean =
        when (trigger) {

            is Trigger.SignalBecomes -> {
                val now = trigger.signal.booleanOf(current.snapshot)
                val before = previous?.let { trigger.signal.booleanOf(it.snapshot) }
                when {
                    now != trigger.to -> false
                    before != null -> before != trigger.to
                    // 재시작 직후(직전 값 없음)의 "탑승" 트리거는 이미 타 있어도 1회 발동 —
                    // 태블릿이 밤새 재부팅되면 첫 판정이 곧 탑승 순간인데 엣지만 고집하면 영영 놓친다.
                    // 탑승 외 신호는 제외: 재시작 오발동(예: 주차 중 앱 시작 → 잠금 명령)이 더 해롭다
                    else -> trigger.signal == Signal.USER_PRESENT && trigger.to
                }
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

            // 조건이 "안 갖춰졌다가 → 갖춰진" 문턱에서 발동한다. 문턱이 사건이다.
            // 직전은 룰별 래치가 기억한다 — 래치가 없으면(새 룰·재활성·앱 시작) 미충족으로 친다:
            // 이미 조건 안이어도 그 첫 판정에서 1회 발동한다.
            // 탑승 순간에도 래치를 리셋한다 — 빈 차에선 INFOTAINMENT 값이 하차 시점으로 동결되므로
            // "타보니 이미 22~24℃"가 래치엔 계속 참으로 남아 영영 안 터지는 걸 막는다
            is Trigger.Always -> {
                if (rule.conditions.isEmpty()) false
                else {
                    val nowHeld = rule.conditions.all { holds(it, current) }
                    val boarded = current.snapshot.isUserPresent == true &&
                        previous?.snapshot?.isUserPresent != true
                    val beforeHeld = if (boarded) false else alwaysHeld[rule.id] ?: false
                    alwaysHeld[rule.id] = nowHeld
                    !beforeHeld && nowHeld
                }
            }
        }

    /** 조건은 "지금 그런 상태인가요"만 본다. 대기 해제와 같은 규칙을 쓴다 */
    private fun holds(condition: Condition, current: Reading): Boolean =
        ConditionEvaluator.holds(condition, current)
}
