package com.wemade.teslamacro.feature.macro.edit

import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.domain.macro.ActionStep
import com.wemade.teslamacro.domain.macro.Condition
import com.wemade.teslamacro.domain.macro.MacroRule
import com.wemade.teslamacro.domain.macro.Trigger
import com.wemade.teslamacro.domain.macro.describe
import com.wemade.teslamacro.domain.macro.formatDuration
import com.wemade.teslamacro.domain.model.Level
import com.wemade.teslamacro.domain.model.SeatPosition
import com.wemade.teslamacro.domain.model.Signal
import org.junit.Assert.*
import org.junit.Test

class EditorSummaryTest {
    /** 탭마다 하나의 인덱스만 유지하고 같은 요약을 다시 누르면 모두 접는다. */
    @Test fun `toggling selects only one editor and toggling again closes it`() {
        for (count in 1..12) {
            for (selected in -1 until count) {
                for (toggled in 0 until count) {
                    val result = toggleEditorIndex(selected, toggled)
                    assertEquals(if (selected == toggled) -1 else toggled, result)
                    assertEquals(if (result == -1) 0 else 1, (0 until count).count { it == result })
                    if (result != -1) assertEquals(-1, toggleEditorIndex(result, toggled))
                }
            }
        }
    }

    /** 추가 직전 길이가 새 편집 위치라는 계약을 모든 추가 경로에서 확인한다. */
    @Test fun `new trigger condition and every action append at expansion index`() {
        var draft = MacroDraft.from(MacroRule("append-editor", "추가 테스트",
            triggers = emptyList(), actions = emptyList()))
        val condition = Condition.SignalIs(Signal.CLIMATE_ON, false)
        repeat(3) {
            val triggerIndex = draft.triggers.size
            draft = draft.addTrigger(Trigger.Manual)
            assertEquals(triggerIndex, draft.triggers.lastIndex)
            assertEquals(Trigger.Manual, draft.triggers[triggerIndex])
            val conditionIndex = draft.conditions.size
            draft = draft.addCondition(condition)
            assertEquals(conditionIndex, draft.conditions.lastIndex)
            assertEquals(condition, draft.conditions[conditionIndex])
        }
        val actions = listOf(
            ActionStep.Run(VehicleCommand.SetSeatHeater(SeatPosition.FRONT_LEFT, Level.LOW)),
            ActionStep.Wait(60), ActionStep.WaitUntil(condition),
            ActionStep.Navigate("목적지", "테스트 주소"), ActionStep.SetStealthCharging(),
        )
        for (action in actions) {
            val existing = draft.actions
            val actionIndex = existing.size
            draft = draft.addAction(action)
            assertEquals(actionIndex, draft.actions.lastIndex)
            assertSame(action, draft.actions[actionIndex])
            assertEquals(existing, draft.actions.take(actionIndex))
        }
    }

    /** 어느 항목을 지워도 다른 항목이 갑자기 편집 상태로 바뀌지 않는다. */
    @Test fun `removal preserves selection identity or closes deleted editor`() {
        for (count in 1..12) {
            val items = (0 until count).toList()
            for (selected in -1 until count) {
                for (removed in items.indices) {
                    val remaining = items.filterIndexed { index, _ -> index != removed }
                    val expected = if (selected == -1) -1 else remaining.indexOf(selected)
                    assertEquals(expected, editorIndexAfterRemoval(selected, removed))
                }
            }
        }
    }

    /** 위·아래 이동 및 앞뒤 여러 칸 이동 모두 실제 목록의 위치 변경과 일치한다. */
    @Test fun `reordering retains selected item for every source and target`() {
        for (count in 1..12) {
            val items = (0 until count).toList()
            for (selected in -1 until count) {
                for (from in items.indices) {
                    for (to in items.indices) {
                        val moved = items.toMutableList().apply { add(to, removeAt(from)) }
                        val expected = if (selected == -1) -1 else moved.indexOf(selected)
                        assertEquals(expected, editorIndexAfterMove(selected, from, to))
                    }
                }
            }
        }
    }

    /** 같은 대기 값이 반복되어도 equals로 다른 동작을 선택하지 않는다. */
    @Test fun `duplicate actions keep the edited instance through draft reorder`() {
        val first = ActionStep.Wait(60)
        val second = ActionStep.Wait(60)
        val rule = MacroRule("editor-test", "순서 테스트", triggers = listOf(Trigger.Manual),
            actions = listOf(first, second, ActionStep.SetStealthCharging()))
        val draft = MacroDraft.from(rule)
        val selected = editorIndexAfterMove(1, 1, 0)
        val moved = draft.moveAction(1, -1)
        assertSame(second, moved.actions[selected])
        assertSame(first, moved.actions[1])
        assertEquals(-1, editorIndexAfterRemoval(selected, selected))
        assertEquals(listOf(first, rule.actions.last()), moved.removeAction(selected).actions)
        assertEquals(rule, draft.toRule())
    }

    /** 접힌 좌석 요약에서도 위치·통풍/열선·단계를 기존 명령 라벨 그대로 유지한다. */
    @Test fun `seat summaries preserve every position and level`() {
        for (seat in SeatPosition.entries) {
            for (level in Level.entries) {
                val heater = VehicleCommand.SetSeatHeater(seat, level)
                assertEquals(heater.label, actionSummary(ActionStep.Run(heater)))
                if (seat.supportsCooler) {
                    val cooler = VehicleCommand.SetSeatCooler(seat, level)
                    assertEquals(cooler.label, actionSummary(ActionStep.Run(cooler)))
                }
            }
        }
    }

    /** 기다리는 조건뿐 아니라 상한 시간까지 읽을 수 있어야 실행 흐름이 보인다. */
    @Test fun `wait summaries include duration condition and timeout`() {
        assertEquals("${formatDuration(900)} 대기", actionSummary(ActionStep.Wait(900)))
        val condition = Condition.InRange(Signal.INSIDE_TEMP, lte = 24.0)
        val summary = actionSummary(ActionStep.WaitUntil(condition, 1800))
        assertEquals("${describe(condition)}까지 대기 · 최대 ${formatDuration(1800)}", summary)
    }

    /** 긴 목적지와 미입력 상태를 자르지 않고 충전 켜기·끄기를 구별한다. */
    @Test fun `navigation and charging remain distinguishable when collapsed`() {
        val destination = "주차장 입구가 다른 아주 긴 목적지 이름"
        assertEquals("지도 안내 — $destination", actionSummary(ActionStep.Navigate(destination, "테스트 주소")))
        assertEquals("지도 안내 — 목적지 미입력", actionSummary(ActionStep.Navigate(" ", "")))
        assertEquals("스텔스 충전 1회 켜기", actionSummary(ActionStep.SetStealthCharging(true)))
        assertEquals("스텔스 충전 1회 끄기", actionSummary(ActionStep.SetStealthCharging(false)))
    }

    /** 읽기 전용 요약 생성은 규칙·안전 취소 목록·저장 가능 여부를 건드리지 않는다. */
    @Test fun `summarizing a mixed macro does not change persisted behavior`() {
        val condition = Condition.SignalIs(Signal.CLIMATE_ON, false)
        val rule = MacroRule("mixed-editor", "혼합 동작", triggers = listOf(Trigger.Manual),
            conditions = listOf(condition), actions = listOf(
                ActionStep.Run(VehicleCommand.SetSeatHeater(SeatPosition.FRONT_LEFT, Level.LOW)),
                ActionStep.Wait(60), ActionStep.WaitUntil(condition),
                ActionStep.Navigate("목적지", "테스트 주소"), ActionStep.SetStealthCharging(),
            ), cancelRunningIds = setOf("previous-heating"))
        val draft = MacroDraft.from(rule)
        draft.actions.forEach { assertTrue(actionSummary(it).isNotBlank()) }
        assertTrue(draft.canSave)
        assertEquals(rule, draft.toRule())
        assertFalse(draft.addAction(ActionStep.Navigate("", "")).canSave)
    }
}
