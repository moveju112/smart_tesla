package com.wemade.teslamacro.data.macro

import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.domain.macro.ActionStep
import com.wemade.teslamacro.domain.macro.Condition
import com.wemade.teslamacro.domain.macro.MacroRule
import com.wemade.teslamacro.domain.macro.Trigger
import com.wemade.teslamacro.domain.model.Level
import com.wemade.teslamacro.domain.model.SeatPosition
import com.wemade.teslamacro.domain.model.Signal

/** 통풍은 탑승 중 온도를 따라가고 열선·하차 정리는 기존 문 사건을 유지한다. */
internal object SeatComfortPresets {
    private val seats = listOf(SeatPosition.FRONT_LEFT, SeatPosition.FRONT_RIGHT)

    /** 안정적인 id로 한 번만 추가하고 사용자가 고친 값과 삭제는 보존한다. */
    fun defaults(temperatureFollowing: Boolean = true): List<MacroRule> {
        val boarding = seats.flatMap { seat ->
            val driver = seat == SeatPosition.FRONT_LEFT
            val key = if (driver) "driver" else "passenger"
            val label = if (driver) "운전석" else "조수석"
            val door = if (driver) Signal.DOOR_DRIVER_FRONT else Signal.DOOR_PASSENGER_FRONT
            val heaterId = "preset-seat-$key-heat"
            val trigger = listOf(Trigger.SignalBecomes(door, to = true, afterDriving = false))
            val levels = if (temperatureFollowing) Level.entries else listOf(Level.LOW, Level.MEDIUM, Level.HIGH)
            val coolers = levels.map { level ->
                val stage = Level.entries.indexOf(level)
                MacroRule(
                    id = "preset-seat-$key-cool-$stage",
                    name = if (stage == 0) "$label · 통풍 끄기" else "$label · 통풍 ${stage}단",
                    triggers = if (temperatureFollowing) listOf(Trigger.Always) else trigger,
                    conditions = (if (temperatureFollowing) listOf(
                        Condition.SignalIs(Signal.USER_PRESENT, value = true),
                        // 하차 문이 열린 동안 다시 켜지 않아 기존 종료 명령과 경쟁하지 않는다.
                        Condition.SignalIs(Signal.DOOR_DRIVER_FRONT, value = false),
                    ) else emptyList()) + Condition.InRange(Signal.SEAT_COOLING_LEVEL, gte = stage.toDouble(), lte = stage.toDouble()),
                    actions = listOf(ActionStep.Run(VehicleCommand.SetSeatCooler(seat, level))),
                    cooldownSeconds = 0,
                    cancelRunningIds = if (stage == 0) emptySet() else setOf(heaterId),
                )
            }
            coolers + MacroRule(
                id = heaterId,
                name = "$label · 열선 2단 15분",
                triggers = trigger,
                conditions = listOf(
                    Condition.InRange(Signal.OUTSIDE_TEMP, lte = 12.0),
                    Condition.InRange(Signal.INSIDE_TEMP, lte = 18.0),
                ),
                actions = listOf(
                    ActionStep.Run(VehicleCommand.SetSeatHeater(seat, Level.MEDIUM)),
                    ActionStep.Wait(seconds = 900),
                    ActionStep.Run(VehicleCommand.SetSeatHeater(seat, Level.OFF)),
                ),
                cooldownSeconds = 0,
            )
        }
        return boarding + MacroRule(
            id = "preset-seat-exit-off",
            name = "주행 후 하차 · 앞좌석 모두 끄기",
            triggers = listOf(Trigger.SignalBecomes(Signal.DOOR_DRIVER_FRONT, to = true, afterDriving = true)),
            actions = seats.flatMap { seat -> listOf(
                ActionStep.Run(VehicleCommand.SetSeatCooler(seat, Level.OFF)),
                ActionStep.Run(VehicleCommand.SetSeatHeater(seat, Level.OFF)),
            ) },
            cooldownSeconds = 0,
            cancelRunningIds = boarding.map { it.id }.toSet(),
        )
    }

    /** 수정·비활성·삭제를 보존하고 손대지 않은 문 열림 통풍 기본값만 교체한다. */
    fun upgradeTemperatureFollowing(rules: List<MacroRule>): List<MacroRule> {
        val legacy = defaults(temperatureFollowing = false).associateBy { it.id }
        val current = defaults().associateBy { it.id }
        return rules.map { rule ->
            val old = legacy[rule.id]
            if (old != null && rule.copy(enabled = old.enabled) == old) current.getValue(rule.id).copy(enabled = rule.enabled)
            else rule
        }
    }
}
