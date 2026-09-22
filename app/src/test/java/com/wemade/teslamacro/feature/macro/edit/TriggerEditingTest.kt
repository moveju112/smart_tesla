package com.wemade.teslamacro.feature.macro.edit

import com.wemade.teslamacro.data.macro.SeatComfortPresets
import com.wemade.teslamacro.domain.macro.*
import com.wemade.teslamacro.domain.model.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** 사건과 주행 조건을 나눠 보여도 프리셋·직접 추가·OR 판정은 동일해야 한다. */
class TriggerEditingTest {
    /** 모든 상태 변화의 제목에서만 주행 제한을 분리하고 꺼짐 방향도 보존한다. */
    @Test
    fun `event and driving requirement have independent summaries`() {
        Signal.entries.filter { it.kind == SignalKind.BOOLEAN }.forEach { signal ->
            listOf(true, false).forEach { target ->
                val event = Trigger.SignalBecomes(signal, target)
                listOf<Boolean?>(null, false, true).forEach { requirement ->
                    val trigger = event.copy(afterDriving = requirement)
                    assertEquals(describe(event), triggerEventSummary(trigger))
                    assertEquals(requirement?.let { "주행 조건 · ${drivingRequirementLabel(it)}" }, triggerDrivingSummary(trigger))
                }
            }
        }
        assertEquals("제한 없음", drivingRequirementLabel(null))
        assertEquals("주행 전 · P단", drivingRequirementLabel(false))
        assertEquals("주행 후 · P단", drivingRequirementLabel(true))
    }

    /** 시간·호출 트리거에 없는 주행 제한을 만들어 표시하지 않는다. */
    @Test
    fun `other triggers keep their complete summaries`() {
        listOf(Trigger.AtTime(480, setOf(1, 3)), Trigger.Every(30), Trigger.Manual, Trigger.Always).forEach {
            assertEquals(describe(it), triggerEventSummary(it))
            assertNull(triggerDrivingSummary(it))
        }
    }

    /** 기존 프리셋을 편집해도 사건·조건·동작·취소 대상과 저장 형식을 보존한다. */
    @Test
    fun `each requirement can be changed cleared saved and reopened`() {
        SeatComfortPresets.defaults().forEach { original ->
            val draft = MacroDraft.from(original)
            val event = original.triggers.single() as Trigger.SignalBecomes
            listOf<Boolean?>(null, false, true).forEach { requirement ->
                val changed = event.copy(afterDriving = requirement)
                val edited = draft.replaceTrigger(0, changed)
                assertTrue(edited.canSave)
                val saved = Json.decodeFromString<MacroRule>(Json.encodeToString(edited.toRule()))
                assertEquals(original.copy(triggers = listOf(changed)), saved)
                assertEquals(saved, MacroDraft.from(saved).toRule())
                if (requirement == null) assertEquals(listOf(event.signal), changed.signals())
                else assertTrue(changed.signals().containsAll(listOf(Signal.DRIVING, Signal.PARKED, Signal.USER_PRESENT)))
            }
        }
    }

    /** 선택창에서 추가한 사건에 주행 조건을 붙이면 모든 프리셋의 실행 시점을 재현한다. */
    @Test
    fun `manual addition can reproduce every preset trigger`() {
        SeatComfortPresets.defaults().forEach { preset ->
            val expected = preset.triggers.single() as Trigger.SignalBecomes
            val added = Trigger.SignalBecomes(expected.signal, to = true)
            val draft = MacroDraft.blank().addTrigger(added)
                .replaceTrigger(0, added.copy(to = expected.to, afterDriving = expected.afterDriving))
            assertEquals(preset.triggers, draft.toRule().triggers)
            val changed = draft.replaceTrigger(0, expected.copy(to = !expected.to))
            assertEquals(expected.afterDriving, (changed.triggers.single() as Trigger.SignalBecomes).afterDriving)
            assertTrue(changed.conditions.isEmpty())
        }
    }

    /** 새로 추가한 문 이벤트도 프리셋처럼 관측된 주행→P→문 열림에서만 실행한다. */
    @Test
    fun `manually added driving requirement matches preset exit sequence`() {
        val event = Trigger.SignalBecomes(Signal.DOOR_DRIVER_FRONT, true).copy(afterDriving = true)
        val draft = MacroDraft.blank().copy(name = "하차", cooldownSeconds = 0)
            .addTrigger(event).addAction(ActionStep.SetStealthCharging(false))
        assertTrue(draft.canSave)
        val rule = draft.toRule()
        val engine = MacroEngine()
        val parked = reading()
        val opened = reading(open = true)
        val driving = reading(shift = ShiftState.DRIVE)
        assertTrue(engine.evaluate(listOf(rule), parked, opened, emptyMap()).isEmpty())
        engine.evaluate(listOf(rule), opened, driving, emptyMap())
        assertTrue(engine.evaluate(listOf(rule), driving, parked, emptyMap()).isEmpty())
        assertEquals(listOf(rule), engine.evaluate(listOf(rule), parked, opened, emptyMap()))
        // 재연결만으로 주행 이력을 추측하지 않는다.
        assertTrue(engine.evaluate(listOf(rule), null, parked, emptyMap()).isEmpty())
        assertTrue(engine.evaluate(listOf(rule), parked, opened, emptyMap()).isEmpty())
    }

    /** 주행 전 문 조건이 맞지 않아도 다른 OR 사건까지 막아서는 안 된다. */
    @Test
    fun `driving requirement remains local to its trigger`() {
        val rule = MacroRule("mixed", "혼합", triggers = listOf(
            Trigger.SignalBecomes(Signal.DOOR_DRIVER_FRONT, true, afterDriving = false),
            Trigger.SignalBecomes(Signal.DOOR_PASSENGER_FRONT, true),
        ), actions = listOf(ActionStep.SetStealthCharging(false)))
        val driving = reading(shift = ShiftState.DRIVE)
        val parked = reading()
        val engine = MacroEngine()
        engine.evaluate(listOf(rule), null, driving, emptyMap())
        engine.evaluate(listOf(rule), driving, parked, emptyMap())
        assertTrue(engine.evaluate(listOf(rule), parked, reading(open = true), emptyMap()).isEmpty())
        val passenger = parked.copy(snapshot = parked.snapshot.copy(doorOpen = mapOf(
            Door.DRIVER_FRONT to false, Door.PASSENGER_FRONT to true,
        )))
        assertEquals(listOf(rule), engine.evaluate(listOf(rule), parked, passenger, emptyMap()))
    }

    /** 제한 없음으로 되돌리면 원래 문 변화만 평가하며 P단을 추가로 강요하지 않는다. */
    @Test
    fun `clearing requirement restores an ordinary event`() {
        val trigger = Trigger.SignalBecomes(Signal.DOOR_DRIVER_FRONT, true, afterDriving = true)
            .copy(afterDriving = null)
        val rule = MacroRule("plain", "문", triggers = listOf(trigger), actions = emptyList())
        assertEquals(listOf(rule), MacroEngine().evaluate(listOf(rule),
            reading(shift = ShiftState.NEUTRAL), reading(open = true, shift = ShiftState.NEUTRAL), emptyMap()))
    }

    /** 실제 문 변화와 주행 이력을 판정할 최소 표본을 만든다. */
    private fun reading(open: Boolean = false, shift: ShiftState = ShiftState.PARK) = Reading(
        VehicleSnapshot(timestampMillis = 1000L, shiftState = shift, isUserPresent = true,
            doorOpen = mapOf(Door.DRIVER_FRONT to open, Door.PASSENGER_FRONT to false)),
        TimeContext(1000L, 0, 1),
    )
}
