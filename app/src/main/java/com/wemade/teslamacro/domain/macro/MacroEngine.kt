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
     * @param onBlocked 트리거는 발동했는데 조건이 막은 경우 알림 — "왜 안 터졌지" 진단용
     * @return 지금 실행해야 하는 매크로들 (선언 순서 유지)
     */
    /**
     * @param knownPresenceBeforeRestart 재시작 전에 마지막으로 본 탑승 상태.
     *   앱이 죽었다 살면 [previous]가 null이라 엣지를 못 보는데, 이 값이 있으면
     *   "이미 타 있었다"를 알아 헛발동을 막는다. 오래된 값은 호출부가 null로 걸러 넣는다.
     */
    fun evaluate(
        rules: List<MacroRule>,
        previous: Reading?,
        current: Reading,
        lastFiredAtMillis: Map<String, Long>,
        knownPresenceBeforeRestart: Boolean? = null,
        onBlocked: (MacroRule, List<Condition>) -> Unit = { _, _ -> },
    ): List<MacroRule> {
        // 꺼졌거나 삭제된 룰의 래치는 잊는다 — 다시 켜면 "이미 참"도 1회 발동한다
        alwaysHeld.keys.retainAll(rules.filter { it.enabled }.map { it.id }.toSet())

        return rules.filter { rule ->
            // 1. 꺼진 매크로는 건너뛴다
            if (!rule.enabled) return@filter false

            // 2. 트리거가 없으면 발동할 수 없다 (조건만으로는 절대 실행되지 않는다)
            if (rule.triggers.isEmpty()) return@filter false

            // 3. 트리거 평가를 쿨다운보다 먼저 — Always 래치는 쿨다운 중에도 갱신돼야 한다.
            //    쿨다운이 먼저 자르면 래치가 발동 시점의 참으로 굳어, 쿨다운이 끝나도
            //    조건 이탈-재진입을 한 번 더 해야만 발동하는 침묵 구간이 생긴다.
            //    any 대신 map+any — 래치 갱신 때문에 모든 트리거를 반드시 평가한다
            val triggered = rule.triggers.map { fired(rule, it, previous, current, knownPresenceBeforeRestart) }.any { it }
            if (!triggered) return@filter false

            // 4. 쿨다운 중이면 발동하지 않는다
            val lastFired = lastFiredAtMillis[rule.id]
            if (lastFired != null &&
                current.time.epochMillis - lastFired < rule.cooldownSeconds * 1000L
            ) {
                return@filter false
            }

            // 어떤 조건이 막았는지 알려준다 — 진단 로그 없이는 "왜 안 터졌는지" 알 길이 없다
            val unmet = rule.conditions.filter { !holds(it, current) }
            if (unmet.isNotEmpty()) {
                onBlocked(rule, unmet)
                return@filter false
            }
            true
        }
    }

    /** 트리거는 "방금 바뀌었나"를 본다. 직전 값이 없으면 판단할 수 없다 */
    private fun fired(
        rule: MacroRule,
        trigger: Trigger,
        previous: Reading?,
        current: Reading,
        knownPresenceBeforeRestart: Boolean? = null,
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
                    // 탑승 외 신호는 제외: 재시작 오발동(예: 주차 중 앱 시작 → 잠금 명령)이 더 해롭다.
                    //
                    // 단 재시작 직전에 이미 타 있었다는 기록이 있으면 방금 탄 게 아니다 —
                    // 주행 중 업데이트로 앱이 되살아나면 탑승 매크로가 통째로 다시 터졌다(0.8.22 실차).
                    // 예전엔 기어(D)로 걸렀는데 DRIVE 카테고리를 평소에 안 읽어 판정이 항상
                    // UNKNOWN이었고, 그래서 가드가 통과해 버렸다(0.8.36 검토에서 발견).
                    else -> trigger.signal == Signal.USER_PRESENT && trigger.to &&
                        knownPresenceBeforeRestart != true
                }
            }

            // (직전, 현재] 창 판정 — 분 일치 비교는 폴링이 성기면(깊은 유휴 2분+) 그 분을
            // 통째로 건너뛰어 시각 매크로가 조용히 유실된다. 같은 분 재폴링은 창이 비어 걸러진다.
            // Doze로 몇 시간 밀린 뒤의 뒷북 발동은 의미가 없어 15분까지만 소급한다
            is Trigger.AtTime -> {
                val dayMatches = trigger.days.isEmpty() || current.time.dayOfWeek in trigger.days
                dayMatches && crossedInWindow(previous, current) { minute ->
                    minute == trigger.minutesOfDay
                }
            }

            // 주기: (직전, 현재] 창 안에 배수 분이 있으면 발동 — AtTime과 같은 그물눈 보정
            is Trigger.Every -> {
                trigger.everyMinutes > 0 && crossedInWindow(previous, current) { minute ->
                    minute % trigger.everyMinutes == 0
                }
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

    /**
     * (직전, 현재] 사이에 [matches]가 참인 분(minute)이 있었는지.
     * 자정 넘김을 처리하고, 소급은 [LATE_FIRE_LIMIT_MINUTES]까지만 본다.
     * 직전 표본이 없으면(첫 판정) 현재 분만 본다 — 재시작 시 과거 발동을 몰아서 터뜨리지 않는다
     */
    private fun crossedInWindow(
        previous: Reading?,
        current: Reading,
        matches: (Int) -> Boolean,
    ): Boolean {
        val cur = current.time.minutesOfDay
        val prev = previous?.time?.minutesOfDay ?: return matches(cur)
        val gap = ((cur - prev) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        val window = minOf(gap, LATE_FIRE_LIMIT_MINUTES)
        return (0 until window).any { back ->
            matches(((cur - back) + MINUTES_PER_DAY) % MINUTES_PER_DAY)
        }
    }

    private companion object {
        const val MINUTES_PER_DAY = 24 * 60

        /** 폴링 공백(깊은 유휴·Doze) 소급 발동 상한 — 이보다 늦은 시각 트리거는 뒷북이라 버린다 */
        const val LATE_FIRE_LIMIT_MINUTES = 15
    }
}
