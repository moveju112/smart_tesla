package com.wemade.teslamacro.domain.macro

import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.domain.model.Signal
import com.wemade.teslamacro.domain.model.StateCategory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * **트리거 — "언제"**. 사건이 일어나는 순간에만 참이 된다.
 *
 * 트리거와 조건을 나눈 이유는 안전 때문이다.
 * "실내 27℃ 이상"만으로 매크로를 만들면 폴링마다 계속 발동한다.
 * 사건(트리거) 없이는 발동하지 않게 강제해서 이 사고를 구조적으로 막는다.
 *
 * **확장 지점 3/3-a**: 새 사건은 여기에 하위 타입 1개 + [MacroEngine] 분기 1개.
 */
@Serializable
sealed interface Trigger {

    /** 상태 신호가 바뀌는 순간 (문이 열릴 때, 운전자가 탈 때) */
    @Serializable @SerialName("signal_becomes")
    data class SignalBecomes(val signal: Signal, val to: Boolean) : Trigger

    /**
     * 정해진 시각이 되는 순간.
     *
     * @param days 발동 요일. 월=1 … 일=7. 비우면 매일
     */
    @Serializable @SerialName("at_time")
    data class AtTime(
        val minutesOfDay: Int,
        val days: Set<Int> = emptySet(),
    ) : Trigger {
        val hour: Int get() = minutesOfDay / 60
        val minute: Int get() = minutesOfDay % 60
    }

    /**
     * 일정 주기마다 (30분마다, 매시 정각).
     * 자정 기준으로 나눠떨어지는 시각에 발동하므로 매일 같은 시각에 걸린다.
     */
    @Serializable @SerialName("every")
    data class Every(val everyMinutes: Int) : Trigger

    /**
     * 호출될 때만. 자동으로는 절대 발동하지 않는다.
     * 음성(매크로 이름 부르기)이나 목록의 "지금 실행"으로만 도는 매크로용이다.
     */
    @Serializable @SerialName("manual")
    data object Manual : Trigger
}

/**
 * **조건 — "~라면"**. 트리거가 발생한 그 순간에 함께 참이어야 하는 상태.
 *
 * 조건만으로는 절대 발동하지 않는다. 트리거를 거르는 필터 역할이다.
 *
 * **확장 지점 3/3-b**: 새 조건은 여기에 하위 타입 1개 + [MacroEngine] 분기 1개.
 */
@Serializable
sealed interface Condition {

    /** 숫자 신호가 범위 안. gte/lte 중 하나만 줘도 된다 */
    @Serializable @SerialName("in_range")
    data class InRange(
        val signal: Signal,
        val gte: Double? = null,
        val lte: Double? = null,
    ) : Condition

    /** 상태 신호가 특정 값 */
    @Serializable @SerialName("signal_is")
    data class SignalIs(val signal: Signal, val value: Boolean) : Condition

    /** 시간대 안에 있을 때 (밤에만, 출근 시간대에만) */
    @Serializable @SerialName("time_window")
    data class TimeWindow(val fromMinutes: Int, val toMinutes: Int) : Condition

    /** 특정 요일일 때 */
    @Serializable @SerialName("on_days")
    data class OnDays(val days: Set<Int>) : Condition

    /**
     * 태블릿 위치가 저장 지점 반경 안일 때.
     *
     * "집 주차장에서 탔을 때만 회사 안내"처럼 출발지를 거르는 용도다.
     * 좌표가 null이면 아직 저장 전 — 절대 충족되지 않는다 (fail-closed).
     */
    @Serializable @SerialName("near_location")
    data class NearLocation(
        val latitude: Double? = null,
        val longitude: Double? = null,
        val radiusMeters: Int = 400,
    ) : Condition
}

/** 매크로가 순서대로 실행하는 한 걸음 */
@Serializable
sealed interface ActionStep {

    @Serializable @SerialName("run")
    data class Run(val command: VehicleCommand) : ActionStep

    /** 다음 걸음까지 대기. "5분 뒤 auto로" 같은 시나리오의 핵심 */
    @Serializable @SerialName("wait")
    data class Wait(val seconds: Int) : ActionStep

    /**
     * 네이버 지도 길안내 시작.
     *
     * 차량 명령이 아니라 태블릿 동작이다 — 게이트웨이가 아니라 앱 계층의 내비게이터가 처리한다.
     * 주소 → 좌표 변환은 실행 시점에 한다 (저장 시점에 하면 이사·오타 수정이 안 먹는다).
     */
    @Serializable @SerialName("navigate")
    data class Navigate(val destinationName: String, val address: String) : ActionStep

    /**
     * 조건이 맞을 때까지 대기.
     *
     * "실내가 24℃로 내려가면 통풍을 낮춘다"처럼 **시간이 아니라 결과를 기다릴 때** 쓴다.
     * 고정 대기는 여름과 겨울에 다른 결과를 내지만 이건 항상 같은 결과를 낸다.
     *
     * @param timeoutSeconds 이 시간까지 조건이 안 맞으면 포기하고 다음으로 넘어간다.
     *   무한 대기는 매크로가 영원히 안 끝나게 만들어 반드시 상한을 둔다.
     */
    @Serializable @SerialName("wait_until")
    data class WaitUntil(
        val condition: Condition,
        val timeoutSeconds: Int = 600,
    ) : ActionStep
}

/**
 * 사용자가 만드는 매크로 한 개.
 *
 * 읽는 법: **[triggers] 중 하나가 발생했을 때, [conditions]가 모두 맞으면, [actions]를 순서대로 실행한다.**
 */
@Serializable
data class MacroRule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    /** 하나라도 발생하면 (OR). 비어 있으면 절대 발동하지 않는다 */
    val triggers: List<Trigger>,
    /** 전부 만족해야 (AND). 비어 있으면 조건 없이 통과 */
    val conditions: List<Condition> = emptyList(),
    val actions: List<ActionStep>,
    /** 재발동 억제 시간. 문을 여닫을 때마다 중복 실행되는 걸 막는다 */
    val cooldownSeconds: Int = 300,
) {
    /** 목록 화면에 보여줄 한 줄 요약 */
    val summary: String
        get() = actions
            .mapNotNull { step ->
                when (step) {
                    is ActionStep.Run -> step.command.label
                    is ActionStep.Navigate -> "${step.destinationName} 안내"
                    else -> null
                }
            }
            .joinToString(" · ")
            .ifEmpty { "동작 없음" }

    /** 이 매크로를 판정하려면 폴링해야 하는 카테고리들 */
    val requiredCategories: Set<StateCategory>
        get() = (triggers.flatMap { it.signals() } + conditions.flatMap { it.signals() })
            .map { it.sourceCategory }
            .toSet()

    /** 실제로 실행할 명령 수 (대기 제외) */
    val commandCount: Int get() = actions.count { it is ActionStep.Run }
}

/** 트리거가 참조하는 차량 신호 (폴링 계획 수립용) */
fun Trigger.signals(): List<Signal> = when (this) {
    is Trigger.SignalBecomes -> listOf(signal)
    // 시간 기반/호출 트리거는 차량을 읽을 필요가 없다
    is Trigger.AtTime, is Trigger.Every, is Trigger.Manual -> emptyList()
}

/** 조건이 참조하는 차량 신호 */
fun Condition.signals(): List<Signal> = when (this) {
    is Condition.InRange -> listOf(signal)
    is Condition.SignalIs -> listOf(signal)
    // 시간·위치 조건은 차량이 아니라 태블릿에서 온다
    is Condition.TimeWindow, is Condition.OnDays, is Condition.NearLocation -> emptyList()
}
