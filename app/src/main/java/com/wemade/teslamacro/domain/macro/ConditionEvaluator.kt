package com.wemade.teslamacro.domain.macro

import com.wemade.teslamacro.domain.model.ShiftState

/**
 * 조건 하나가 지금 참인지 판정한다.
 *
 * [MacroEngine]과 [MacroRunner](대기 해제 판정)가 **같은 규칙**을 써야 하므로
 * 두 곳에 복사하지 않고 여기 한 곳에 둔다.
 */
object ConditionEvaluator {

    /** 값을 못 읽었으면 "불충족"으로 본다 — 모르는 걸 참으로 치면 차가 멋대로 움직인다 */
    fun holds(condition: Condition, reading: Reading): Boolean = when (condition) {

        is Condition.InRange -> {
            val value = condition.signal.numberOf(reading.snapshot)
            value != null &&
                (condition.gte == null || value >= condition.gte) &&
                (condition.lte == null || value <= condition.lte)
        }

        is Condition.SignalIs ->
            condition.signal.booleanOf(reading.snapshot) == condition.value

        is Condition.TimeWindow -> {
            val minutes = reading.time.minutesOfDay
            // 22시~06시처럼 자정을 넘는 구간도 지원한다
            if (condition.fromMinutes <= condition.toMinutes) {
                minutes in condition.fromMinutes..condition.toMinutes
            } else {
                minutes >= condition.fromMinutes || minutes <= condition.toMinutes
            }
        }

        is Condition.OnDays ->
            condition.days.isEmpty() || reading.time.dayOfWeek in condition.days
    }

    /** 기어를 못 읽은 상태를 "주차 아님"으로 오해하지 않도록 하는 공용 헬퍼 */
    fun shiftKnown(shiftState: ShiftState): Boolean = shiftState != ShiftState.UNKNOWN
}
