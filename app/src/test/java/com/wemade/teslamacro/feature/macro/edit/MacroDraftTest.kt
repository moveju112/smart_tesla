package com.wemade.teslamacro.feature.macro.edit

import com.wemade.teslamacro.data.macro.MacroPresets
import com.wemade.teslamacro.domain.command.CommandCatalog
import com.wemade.teslamacro.domain.command.CommandTemplate
import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.domain.macro.ActionStep
import com.wemade.teslamacro.domain.macro.Condition
import com.wemade.teslamacro.domain.macro.Trigger
import com.wemade.teslamacro.domain.model.Level
import com.wemade.teslamacro.domain.model.SeatPosition
import com.wemade.teslamacro.domain.model.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 편집 화면이 다루는 초안 모델 검증.
 * 저장 → 다시 불러오기가 손실 없이 도는지가 핵심이다.
 */
class MacroDraftTest {

    private fun validDraft() = MacroDraft.blank()
        .copy(name = "테스트")
        .addTrigger(Trigger.SignalBecomes(Signal.DOOR_DRIVER_FRONT, to = true))
        .addAction(ActionStep.Run(VehicleCommand.ClimateOn))

    @Test
    fun `모든 프리셋이 불러왔다가 그대로 저장하면 원본과 같다`() {
        MacroPresets.defaults().forEach { original ->
            val restored = MacroDraft.from(original).toRule()
            assertEquals(original.name, restored.name)
            assertEquals(original.triggers, restored.triggers)
            assertEquals(original.conditions, restored.conditions)
            assertEquals(original.actions, restored.actions)
            assertEquals(original.cooldownSeconds, restored.cooldownSeconds)
        }
    }

    // ---- 저장 가드 ----

    @Test
    fun `이름이 없으면 저장할 수 없다`() {
        assertNotNull(validDraft().copy(name = "  ").blockReason)
    }

    @Test
    fun `트리거가 없으면 저장할 수 없다`() {
        // 트리거 없는 매크로는 폴링마다 발동하거나 아예 안 돈다. 애초에 못 만들게 막는다
        val onlyConditions = MacroDraft.blank()
            .copy(name = "조건만")
            .addCondition(Condition.InRange(Signal.INSIDE_TEMP, gte = 27.0))
            .addAction(ActionStep.Run(VehicleCommand.ClimateOn))
        assertNotNull(onlyConditions.blockReason)
    }

    @Test
    fun `대기만 있고 명령이 없으면 저장할 수 없다`() {
        val waitOnly = MacroDraft.blank()
            .copy(name = "대기만")
            .addTrigger(Trigger.AtTime(18 * 60))
            .addAction(ActionStep.Wait(60))
        assertNotNull(waitOnly.blockReason)
    }

    @Test
    fun `지도 안내만 있는 매크로도 저장할 수 있다`() {
        // 탑승 → 길안내가 대표 시나리오다. 차량 명령이 없다고 막으면 안 된다
        val navigateOnly = MacroDraft.blank()
            .copy(name = "출근 안내")
            .addTrigger(Trigger.SignalBecomes(Signal.USER_PRESENT, to = true))
            .addAction(ActionStep.Navigate(destinationName = "회사", address = "성남시 분당구"))
        assertNull(navigateOnly.blockReason)
    }

    @Test
    fun `지도 안내의 주소가 비면 저장할 수 없다`() {
        val blankAddress = MacroDraft.blank()
            .copy(name = "출근 안내")
            .addTrigger(Trigger.SignalBecomes(Signal.USER_PRESENT, to = true))
            .addAction(ActionStep.Navigate(destinationName = "회사", address = " "))
        assertNotNull(blankAddress.blockReason)
    }

    @Test
    fun `위치 미저장 출발지 조건이 있으면 저장할 수 없다`() {
        // 좌표 없는 위치 조건은 절대 충족되지 않아 매크로가 영영 안 돈다
        val unset = validDraft().addCondition(Condition.NearLocation())
        assertNotNull(unset.blockReason)

        val saved = validDraft()
            .addCondition(Condition.NearLocation(latitude = 37.0, longitude = 127.0))
        assertNull(saved.blockReason)
    }

    @Test
    fun `조건이 없어도 저장할 수 있다`() {
        // 조건은 선택이다. 트리거만으로 무조건 실행하는 매크로도 정상이다
        assertNull(validDraft().blockReason)
        assertTrue(validDraft().canSave)
    }

    @Test
    fun `저장 불가 사유가 사람이 읽는 문장으로 나온다`() {
        val reason = MacroDraft.blank().copy(name = "이름만").blockReason
        assertEquals("\"언제\"를 하나 이상 추가해 주세요", reason)
    }

    // ---- 순서 편집 ----

    @Test
    fun `동작 순서를 바꿀 수 있다`() {
        val draft = MacroDraft.blank()
            .addAction(ActionStep.Run(VehicleCommand.ClimateOn))
            .addAction(ActionStep.Wait(30))
            .moveAction(0, 1)
        assertTrue(draft.actions.first() is ActionStep.Wait)
    }

    @Test
    fun `범위를 벗어난 이동은 무시된다`() {
        val draft = MacroDraft.blank().addAction(ActionStep.Run(VehicleCommand.ClimateOn))
        assertEquals(draft.actions, draft.moveAction(0, -1).actions)
        assertEquals(draft.actions, draft.moveAction(0, 1).actions)
    }

    @Test
    fun `트리거와 조건을 따로 지웠다 넣을 수 있다`() {
        val draft = validDraft()
            .addCondition(Condition.SignalIs(Signal.PARKED, true))
            .addTrigger(Trigger.AtTime(18 * 60))

        assertEquals(2, draft.triggers.size)
        assertEquals(1, draft.conditions.size)

        val trimmed = draft.removeTrigger(1).removeCondition(0)
        assertEquals(1, trimmed.triggers.size)
        assertTrue(trimmed.conditions.isEmpty())
    }

    // ---- 카탈로그 ----

    @Test
    fun `카탈로그의 모든 템플릿이 기본 명령을 만들 수 있다`() {
        // 하나라도 터지면 편집 화면에서 그 항목을 고르는 순간 앱이 죽는다
        CommandCatalog.all.forEach { template ->
            val command = CommandCatalog.defaultCommand(template)
            assertTrue("${template.label} 라벨이 비었다", command.label.isNotBlank())
        }
    }

    @Test
    fun `저장된 명령에서 원래 템플릿을 되찾는다`() {
        val command = VehicleCommand.SetSeatCooler(SeatPosition.FRONT_RIGHT, Level.HIGH)
        val template = templateFor(command, CommandCatalog.all)
        assertTrue(template is CommandTemplate.SeatLevel)
        assertEquals("통풍 시트", template?.label)
    }

    @Test
    fun `통풍 템플릿은 앞좌석만 제공한다`() {
        // 뒷좌석을 고르게 두면 인코더에서 터진다
        val template = CommandCatalog.all
            .filterIsInstance<CommandTemplate.SeatLevel>()
            .first { it.label == "통풍 시트" }
        assertEquals(listOf(SeatPosition.FRONT_LEFT, SeatPosition.FRONT_RIGHT), template.seats)
    }
}
