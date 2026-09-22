package com.wemade.teslamacro.data.macro

import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.domain.macro.ActionStep
import com.wemade.teslamacro.domain.macro.Condition
import com.wemade.teslamacro.domain.macro.MacroRule
import com.wemade.teslamacro.domain.macro.Trigger
import com.wemade.teslamacro.domain.model.Level
import com.wemade.teslamacro.domain.model.SeatPosition
import com.wemade.teslamacro.domain.model.Signal

/** 좌석별 문 열림에만 반응하며 하차 사건에서는 켜기와 끄기가 경쟁하지 않는다. */
internal object SeatComfortPresets {
    private val seats = listOf(SeatPosition.FRONT_LEFT, SeatPosition.FRONT_RIGHT)

    /** 안정적인 id로 한 번만 추가하고 사용자가 고친 값과 삭제는 보존한다. */
    fun defaults(): List<MacroRule> {
        val boarding = seats.flatMap { seat ->
            val driver = seat == SeatPosition.FRONT_LEFT
            val key = if (driver) "driver" else "passenger"
            val label = if (driver) "운전석" else "조수석"
            val door = if (driver) Signal.DOOR_DRIVER_FRONT else Signal.DOOR_PASSENGER_FRONT
            val heaterId = "preset-seat-$key-heat"
            val trigger = listOf(Trigger.SignalBecomes(door, to = true, afterDriving = false))
            val coolers = listOf(Level.LOW, Level.MEDIUM, Level.HIGH).mapIndexed { index, level ->
                val stage = index + 1
                MacroRule(
                    id = "preset-seat-$key-cool-$stage",
                    name = "$label · 통풍 ${stage}단",
                    triggers = trigger,
                    conditions = listOf(Condition.InRange(Signal.SEAT_COOLING_LEVEL, gte = stage.toDouble(), lte = stage.toDouble())),
                    actions = listOf(ActionStep.Run(VehicleCommand.SetSeatCooler(seat, level))),
                    cooldownSeconds = 0,
                    cancelRunningIds = setOf(heaterId),
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
}
