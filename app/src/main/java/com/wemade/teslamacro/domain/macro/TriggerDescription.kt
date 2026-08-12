package com.wemade.teslamacro.domain.macro

/**
 * 매크로를 사람이 읽는 문장으로 바꾼다.
 * 목록 카드에서 편집 화면에 들어가지 않고도 무슨 매크로인지 알게 하려는 목적이다.
 */

/** "운전석 도어 열림" */
fun describe(trigger: Trigger): String = when (trigger) {
    is Trigger.SignalBecomes ->
        if (trigger.to) trigger.signal.label else "${trigger.signal.label} 해제"

    is Trigger.AtTime -> {
        val time = "%02d:%02d".format(trigger.hour, trigger.minute)
        if (trigger.days.isEmpty()) "매일 $time" else "${days(trigger.days)} $time"
    }

    is Trigger.Every -> "${formatDuration(trigger.everyMinutes * 60)}마다"

    is Trigger.Manual -> "호출될 때"
}

/** "실내 온도 27℃ 이상" */
fun describe(condition: Condition): String = when (condition) {
    is Condition.InRange -> {
        val unit = condition.signal.unit.orEmpty()
        when {
            condition.gte != null && condition.lte != null ->
                "${condition.signal.label} ${fmt(condition.gte)}~${fmt(condition.lte)}$unit"
            condition.gte != null -> "${condition.signal.label} ${fmt(condition.gte)}$unit 이상"
            condition.lte != null -> "${condition.signal.label} ${fmt(condition.lte)}$unit 이하"
            else -> condition.signal.label
        }
    }

    is Condition.SignalIs ->
        if (condition.value) "${condition.signal.label} 상태" else "${condition.signal.label} 아닌 상태"

    is Condition.TimeWindow ->
        "%02d:%02d~%02d:%02d 사이".format(
            condition.fromMinutes / 60, condition.fromMinutes % 60,
            condition.toMinutes / 60, condition.toMinutes % 60,
        )

    is Condition.OnDays -> "${days(condition.days)}요일"

    is Condition.NearLocation ->
        if (condition.latitude == null) "출발지 근처 (위치 미저장)"
        else "저장 위치 반경 ${condition.radiusMeters}m 안"
}

/**
 * 매크로 전체를 한 문장으로.
 * "운전석 도어 열림 시, 실내 온도 27℃ 이상이면"
 */
fun describeRule(rule: MacroRule): String {
    val whenPart = rule.triggers.joinToString(" 또는 ") { describe(it) }
        .ifEmpty { "트리거 없음" }
    val ifPart = rule.conditions.joinToString(", ") { describe(it) }
    return if (ifPart.isEmpty()) "$whenPart 시" else "$whenPart 시, ${ifPart}이면"
}

private fun days(days: Set<Int>): String =
    days.sorted().joinToString("·") { DAY_NAMES[it - 1] }

private val DAY_NAMES = listOf("월", "화", "수", "목", "금", "토", "일")

private fun fmt(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)
