package com.wemade.teslamacro.domain.macro

import com.wemade.teslamacro.data.macro.MacroPresets
import com.wemade.teslamacro.data.macro.SeatComfortPresets
import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.domain.model.*
import com.wemade.teslamacro.feature.macro.edit.MacroDraft
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SeatComfortPresetsTest {
    private val rules = SeatComfortPresets.defaults()

    /** 실제 문 변화와 기어·온도를 가진 연속 표본을 만든다. */
    private fun reading(driver: Boolean = false, passenger: Boolean = false,
        inside: Double? = 29.0, outside: Double? = 20.0,
        shift: ShiftState = ShiftState.PARK, present: Boolean = true, time: Long = 1000L,
    ) = Reading(VehicleSnapshot(timestampMillis = time, insideTempC = inside, outsideTempC = outside,
        shiftState = shift, isUserPresent = present,
        doorOpen = mapOf(Door.DRIVER_FRONT to driver, Door.PASSENGER_FRONT to passenger)),
        TimeContext(time, 0, 1))

    /** 소수 경계에서도 단계가 겹치지 않으며 어느 한 온도의 높은 단계가 우선한다. */
    @Test
    fun `temperature boundaries and unknown samples`() {
        listOf(21.99 to 0, 22.0 to 1, 24.99 to 1, 25.0 to 2, 28.99 to 2, 29.0 to 3).forEach { (temp, level) ->
            assertEquals(level, recommendedSeatCoolingLevel(temp, 20.0))
        }
        listOf(22.99 to 0, 23.0 to 1, 25.99 to 1, 26.0 to 2, 29.99 to 2, 30.0 to 3).forEach { (temp, level) ->
            assertEquals(level, recommendedSeatCoolingLevel(20.0, temp))
        }
        assertEquals(3, recommendedSeatCoolingLevel(22.0, 30.0))
        assertNull(recommendedSeatCoolingLevel(null, 30.0))
        assertNull(recommendedSeatCoolingLevel(30.0, null))
        assertNull(recommendedSeatCoolingLevel(Double.NaN, 30.0))
    }

    /** 문·기어 변화 없이 단계 문턱마다 양 좌석을 한 번 조절하고 미확인은 실행하지 않는다. */
    @Test
    fun `temperature following changes both seats without door or park events`() {
        val engine = MacroEngine()
        val hot = reading(shift = ShiftState.DRIVE)
        assertEquals(setOf("preset-seat-driver-cool-3", "preset-seat-passenger-cool-3"),
            engine.evaluate(rules, null, hot, emptyMap()).map { it.id }.toSet())
        assertTrue(engine.evaluate(rules, hot, hot, emptyMap()).isEmpty())
        val medium = reading(inside = 25.0, shift = ShiftState.DRIVE)
        assertEquals(setOf("preset-seat-driver-cool-2", "preset-seat-passenger-cool-2"),
            engine.evaluate(rules, hot, medium, emptyMap()).map { it.id }.toSet())
        assertTrue(engine.evaluate(rules, medium, medium.copy(snapshot = medium.snapshot.copy(insideTempC = 26.0)), emptyMap()).isEmpty())
        val low = reading(inside = 22.0, shift = ShiftState.REVERSE)
        assertEquals(2, engine.evaluate(rules, medium, low, emptyMap()).size)
        val cold = reading(inside = 20.0)
        val off = engine.evaluate(rules, low, cold, emptyMap())
        assertEquals(2, off.size)
        assertTrue(off.all { ((it.actions.single() as ActionStep.Run).command as VehicleCommand.SetSeatCooler).level == Level.OFF })
        assertTrue(engine.evaluate(rules, cold, cold, emptyMap()).isEmpty())
        assertEquals(2, engine.evaluate(rules, cold, hot, emptyMap()).size)
        listOf(reading(present = false), reading(driver = true), reading(inside = null), reading(outside = null)).forEach {
            assertTrue(MacroEngine().evaluate(rules, null, it, emptyMap()).isEmpty())
        }
    }

    /** 변경한 사용자 규칙과 꺼둔 상태·삭제는 보존하며 기존 기본값만 갱신한다. */
    @Test
    fun `upgrade preserves edited disabled and deleted presets`() {
        val legacy = SeatComfortPresets.defaults(temperatureFollowing = false)
        val disabled = legacy.first().copy(enabled = false)
        val edited = legacy[1].copy(name = "내 통풍", cooldownSeconds = 45)
        val input = listOf(disabled, edited, legacy[2])
        val upgraded = SeatComfortPresets.upgradeTemperatureFollowing(input)
        assertEquals(3, upgraded.size)
        assertFalse(upgraded[0].enabled)
        assertEquals(listOf(Trigger.Always), upgraded[0].triggers)
        assertEquals(edited, upgraded[1])
        assertEquals(listOf(Trigger.Always), upgraded[2].triggers)
        assertEquals(upgraded, SeatComfortPresets.upgradeTemperatureFollowing(upgraded))
    }

    /** 추울 때만 양 좌석 열선이 켜지고 15분 뒤 같은 좌석을 끈다. */
    @Test
    fun `heating uses both thresholds and fifteen minutes`() {
        val fired = MacroEngine().evaluate(rules, reading(),
            reading(driver = true, passenger = true, inside = 18.0, outside = 12.0), emptyMap())
        assertEquals(2, fired.size)
        fired.forEach { rule ->
            assertEquals(ActionStep.Wait(900), rule.actions[1])
            assertEquals(Level.MEDIUM, ((rule.actions.first() as ActionStep.Run).command as VehicleCommand.SetSeatHeater).level)
            assertEquals(Level.OFF, ((rule.actions.last() as ActionStep.Run).command as VehicleCommand.SetSeatHeater).level)
        }
        assertTrue(MacroEngine().evaluate(rules, reading(),
            reading(driver = true, inside = 18.01, outside = 12.0), emptyMap()).isEmpty())
        assertTrue(MacroEngine().evaluate(rules, reading(),
            reading(driver = true, inside = 18.0, outside = 12.01), emptyMap()).isEmpty())
    }

    /** 주행→P→운전석 문 순서에서 끄기만 실행하고 탑승 켜기와 경쟁하지 않는다. */
    @Test
    fun `parking after drive turns both seats off then resets after exit`() {
        val engine = MacroEngine()
        val drive = reading(shift = ShiftState.DRIVE)
        val park = reading()
        engine.evaluate(rules, null, drive, emptyMap())
        assertTrue(engine.evaluate(rules, drive, park, emptyMap()).isEmpty())
        val exit = reading(driver = true, passenger = true)
        val fired = engine.evaluate(rules, park, exit, emptyMap())
        assertEquals(listOf("preset-seat-exit-off"), fired.map { it.id })
        assertEquals(4, fired.single().actions.size)
        assertEquals(10, fired.single().cancelRunningIds.size)
        val emptyCar = reading(present = false)
        engine.evaluate(rules, exit, emptyCar, emptyMap())
        assertEquals(2, engine.evaluate(rules, emptyCar, reading(), emptyMap()).size)
    }

    /** 하차 끄기 뒤 착석 값이 남아 있어도 문을 닫거나 동승자가 내릴 때 통풍을 다시 켜지 않는다. */
    @Test
    fun `exit off is not undone while seat sensor still reports present`() {
        val engine = MacroEngine()
        val drive = reading(shift = ShiftState.DRIVE)
        val park = reading()
        engine.evaluate(rules, null, drive, emptyMap())
        engine.evaluate(rules, drive, park, emptyMap())
        val exit = reading(driver = true)
        assertEquals(listOf("preset-seat-exit-off"), engine.evaluate(rules, park, exit, emptyMap()).map { it.id })
        val closed = reading()
        assertTrue(engine.evaluate(rules, exit, closed, emptyMap()).isEmpty())
        val passengerOut = reading(passenger = true)
        assertTrue(engine.evaluate(rules, closed, passengerOut, emptyMap()).isEmpty())
        val emptyCar = reading(present = false)
        assertTrue(engine.evaluate(rules, passengerOut, emptyCar, emptyMap()).isEmpty())
        assertEquals(2, engine.evaluate(rules, emptyCar, reading(), emptyMap()).size)
    }

    /** 새 필드는 저장·편집 왕복을 견디며 P 판정과 두 좌석의 상태를 폴링한다. */
    @Test
    fun `presets survive serialization and editing`() {
        assertEquals(11, rules.size)
        assertTrue(rules.all { it.enabled })
        assertEquals(rules.size, rules.map { it.id }.toSet().size)
        assertFalse(MacroPresets.defaults().any { it.id == "preset-summer-boarding" || it.id == "preset-winter-boarding" })
        rules.forEach { rule ->
            assertEquals(rule, Json.decodeFromString<MacroRule>(Json.encodeToString(rule)))
            assertEquals(rule, MacroDraft.from(rule).toRule())
            assertTrue(StateCategory.BODY_CONTROLLER in rule.requiredCategories)
            if (rule.triggers == listOf(Trigger.Always)) assertTrue(StateCategory.CLIMATE in rule.requiredCategories)
            else assertTrue(StateCategory.DRIVE in rule.requiredCategories)
        }
    }
}
