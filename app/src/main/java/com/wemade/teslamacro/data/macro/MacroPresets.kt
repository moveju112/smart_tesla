package com.wemade.teslamacro.data.macro

import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.domain.macro.ActionStep
import com.wemade.teslamacro.domain.macro.Condition
import com.wemade.teslamacro.domain.macro.MacroRule
import com.wemade.teslamacro.domain.macro.Trigger
import com.wemade.teslamacro.domain.model.Level
import com.wemade.teslamacro.domain.model.SeatPosition
import com.wemade.teslamacro.domain.model.Signal

/**
 * 처음 실행 시 깔리는 기본 매크로.
 * 그대로 쓰거나, 복제해서 자기 상황에 맞게 고쳐 쓴다.
 */
object MacroPresets {

    fun defaults(): List<MacroRule> = listOf(
        summerBoarding(),
        winterBoarding(),
        leaveCar(),
        eveningPrecondition(),
        parkedOverheatVent(),
        afterBlow(),
    )

    /** 여름 탑승 쿨링 — 문이 열릴 때, 실내가 27℃ 이상이면 */
    fun summerBoarding() = MacroRule(
        id = "preset-summer-boarding",
        name = "여름 탑승 쿨링",
        triggers = listOf(Trigger.SignalBecomes(Signal.DOOR_DRIVER_FRONT, to = true)),
        conditions = listOf(Condition.InRange(Signal.INSIDE_TEMP, gte = 27.0)),
        actions = listOf(
            ActionStep.Run(VehicleCommand.SetSeatCooler(SeatPosition.FRONT_LEFT, Level.MEDIUM)),
            ActionStep.Run(VehicleCommand.ClimateOn),
            ActionStep.Run(VehicleCommand.SetTemperature(24.0)),
            ActionStep.Wait(seconds = 300),
            ActionStep.Run(VehicleCommand.SetTemperature(22.0)),
        ),
        cooldownSeconds = 600,
    )

    /** 겨울 탑승 예열 — 문이 열릴 때, 실내가 5℃ 이하면 */
    fun winterBoarding() = MacroRule(
        id = "preset-winter-boarding",
        name = "겨울 탑승 예열",
        triggers = listOf(Trigger.SignalBecomes(Signal.DOOR_DRIVER_FRONT, to = true)),
        conditions = listOf(Condition.InRange(Signal.INSIDE_TEMP, lte = 5.0)),
        actions = listOf(
            ActionStep.Run(VehicleCommand.SetSeatHeater(SeatPosition.FRONT_LEFT, Level.HIGH)),
            ActionStep.Run(VehicleCommand.SetSteeringWheelHeater(enabled = true)),
            ActionStep.Run(VehicleCommand.ClimateOn),
            ActionStep.Run(VehicleCommand.SetTemperature(24.0)),
            ActionStep.Wait(seconds = 600),
            ActionStep.Run(VehicleCommand.SetSeatHeater(SeatPosition.FRONT_LEFT, Level.MEDIUM)),
        ),
        cooldownSeconds = 600,
    )

    /** 하차 정리 — 운전자가 내릴 때, 주차 상태면 */
    fun leaveCar() = MacroRule(
        id = "preset-leave-car",
        name = "하차 정리",
        enabled = false,   // 잠금까지 자동화하는 건 사용자가 켜서 쓸 일
        triggers = listOf(Trigger.SignalBecomes(Signal.USER_PRESENT, to = false)),
        conditions = listOf(Condition.SignalIs(Signal.PARKED, value = true)),
        actions = listOf(
            ActionStep.Run(VehicleCommand.SetSeatCooler(SeatPosition.FRONT_LEFT, Level.OFF)),
            ActionStep.Run(VehicleCommand.SetSeatHeater(SeatPosition.FRONT_LEFT, Level.OFF)),
            ActionStep.Run(VehicleCommand.ClimateOff),
            ActionStep.Wait(seconds = 30),
            ActionStep.Run(VehicleCommand.Lock),
        ),
        cooldownSeconds = 300,
    )

    /** 퇴근 예열 — 평일 18:00에, 외부가 28℃ 이상이면 */
    fun eveningPrecondition() = MacroRule(
        id = "preset-evening-precondition",
        name = "퇴근 전 예열",
        enabled = false,   // 시간이 사람마다 달라 기본은 꺼둔다
        triggers = listOf(Trigger.AtTime(minutesOfDay = 18 * 60, days = setOf(1, 2, 3, 4, 5))),
        conditions = listOf(Condition.InRange(Signal.OUTSIDE_TEMP, gte = 28.0)),
        actions = listOf(
            ActionStep.Run(VehicleCommand.Wake),
            ActionStep.Wait(seconds = 15),
            ActionStep.Run(VehicleCommand.ClimateOn),
            ActionStep.Run(VehicleCommand.SetTemperature(22.0)),
            ActionStep.Run(VehicleCommand.SetSeatCooler(SeatPosition.FRONT_LEFT, Level.LOW)),
        ),
        cooldownSeconds = 3600,
    )

    /**
     * 애프터블로우 — 하차하면 팬만 돌려 증발기 습기를 말린다.
     *
     * 테슬라는 팬 단독 제어를 안 열어놔서 캐빈 과열보호의 "팬만" 모드로 근사한다.
     * 30분 뒤 원래대로 끈다. 실차에서 팬이 실제로 도는지 미검증이라 기본은 꺼둔다.
     * 30분 이상 탄 뒤에만 — 잠깐 탄 차는 증발기에 습기가 찰 시간도 없었다.
     */
    fun afterBlow() = MacroRule(
        id = "preset-after-blow",
        name = "애프터블로우",
        enabled = false,
        triggers = listOf(Trigger.SignalBecomes(Signal.USER_PRESENT, to = false)),
        conditions = listOf(
            Condition.SignalIs(Signal.PARKED, value = true),
            Condition.InRange(Signal.RIDE_MINUTES, gte = 30.0),
        ),
        actions = listOf(
            ActionStep.Run(VehicleCommand.SetCabinOverheatProtection(enabled = true, fanOnly = true)),
            ActionStep.Wait(seconds = 1800),
            ActionStep.Run(VehicleCommand.SetCabinOverheatProtection(enabled = false)),
        ),
        cooldownSeconds = 3600,
    )

    /** 주차 중 폭염 환기 — 1시간마다, 주차 + 실내 45℃ 이상이면 */
    fun parkedOverheatVent() = MacroRule(
        id = "preset-parked-overheat",
        name = "폭염 주차 환기",
        enabled = false,
        triggers = listOf(Trigger.Every(everyMinutes = 60)),
        conditions = listOf(
            Condition.SignalIs(Signal.PARKED, value = true),
            Condition.InRange(Signal.INSIDE_TEMP, gte = 45.0),
        ),
        actions = listOf(
            ActionStep.Run(VehicleCommand.VentWindows),
            ActionStep.Wait(seconds = 600),
            ActionStep.Run(VehicleCommand.CloseWindows),
        ),
        cooldownSeconds = 1800,
    )
}
