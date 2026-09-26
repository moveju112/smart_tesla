package com.wemade.teslamacro.data.poll

import com.wemade.teslamacro.data.settings.AppSettings
import com.wemade.teslamacro.data.settings.DeviceMode
import com.wemade.teslamacro.domain.macro.MacroRule
import com.wemade.teslamacro.domain.macro.Trigger
import com.wemade.teslamacro.domain.model.Signal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoorMacroWakeCheckTest {
    private val settings = AppSettings(deviceMode = DeviceMode.MOUNTED)
    private val rule = MacroRule("door", "문 열림", triggers = listOf(Trigger.SignalBecomes(Signal.DOOR_DRIVER_FRONT, true)), actions = emptyList())

    // 안심운전 설정과 무관하게 켜진 문 열림 매크로를 감시한다.
    @Test fun enabledDoorMacroUsesWakeBudget() {
        assertTrue(needsDoorMacroWakeCheck(settings.copy(autoStartNavigatorSafeDrive = false), listOf(rule)))
        assertTrue(needsDoorMacroWakeCheck(settings, listOf(rule.copy(triggers = listOf(Trigger.SignalBecomes(Signal.DOOR_PASSENGER_FRONT, true))))))
    }

    // 휴대 모드와 비활성·닫힘 매크로에는 연결 예산을 추가하지 않는다.
    @Test fun unrelatedModesAndRulesDoNotKeepConnection() {
        assertFalse(needsDoorMacroWakeCheck(settings.copy(deviceMode = DeviceMode.PORTABLE), listOf(rule)))
        assertFalse(needsDoorMacroWakeCheck(settings, listOf(rule.copy(enabled = false))))
        assertFalse(needsDoorMacroWakeCheck(settings, emptyList()))
        assertFalse(needsDoorMacroWakeCheck(settings, listOf(rule.copy(triggers = listOf(Trigger.SignalBecomes(Signal.DOOR_DRIVER_FRONT, false))))))
        assertFalse(needsDoorMacroWakeCheck(settings, listOf(rule.copy(triggers = listOf(Trigger.SignalBecomes(Signal.LOCKED, false))))))
    }
}
