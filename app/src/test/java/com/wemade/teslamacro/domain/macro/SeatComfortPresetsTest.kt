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

    /** 좌석별 문이 열릴 때 해당 좌석의 높은 단계 한 개만 켠다. */
    @Test
    fun `boarding is per seat and restart is not a door event`() {
        val engine = MacroEngine()
        assertTrue(engine.evaluate(rules, null, reading(driver = true), emptyMap()).isEmpty())
        val driver = engine.evaluate(rules, reading(), reading(driver = true), emptyMap())
        assertEquals(listOf("preset-seat-driver-cool-3"), driver.map { it.id })
        val passenger = engine.evaluate(rules, reading(), reading(passenger = true, inside = 25.0), emptyMap())
        assertEquals(listOf("preset-seat-passenger-cool-2"), passenger.map { it.id })
        assertEquals(2, engine.evaluate(rules, reading(), reading(driver = true, passenger = true), emptyMap()).size)
        assertTrue(engine.evaluate(rules, reading(), reading(driver = true, inside = null), emptyMap()).isEmpty())
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
        assertEquals(8, fired.single().cancelRunningIds.size)
        val emptyCar = reading(present = false)
        engine.evaluate(rules, exit, emptyCar, emptyMap())
        assertEquals(listOf("preset-seat-driver-cool-3"),
            engine.evaluate(rules, emptyCar, reading(driver = true), emptyMap()).map { it.id })
    }

    /** 새 필드는 저장·편집 왕복을 견디며 P 판정과 두 좌석의 상태를 폴링한다. */
    @Test
    fun `presets survive serialization and editing`() {
        assertEquals(9, rules.size)
        assertTrue(rules.all { it.enabled })
        assertEquals(rules.size, rules.map { it.id }.toSet().size)
        assertFalse(MacroPresets.defaults().any { it.id == "preset-summer-boarding" || it.id == "preset-winter-boarding" })
        rules.forEach { rule ->
            assertEquals(rule, Json.decodeFromString<MacroRule>(Json.encodeToString(rule)))
            assertEquals(rule, MacroDraft.from(rule).toRule())
            assertTrue(rule.requiredCategories.containsAll(setOf(StateCategory.DRIVE, StateCategory.BODY_CONTROLLER)))
        }
    }
}
