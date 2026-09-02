package com.wemade.teslamacro.data.macro

import com.wemade.teslamacro.domain.macro.Trigger
import org.junit.Assert.assertEquals
import org.junit.Test

class MacroShortcutPublisherTest {

    @Test
    fun `애프터블로우와 수동 매크로를 제한 슬롯에 먼저 넣는다`() {
        val automatic = MacroPresets.summerBoarding()
        val manual = automatic.copy(
            id = "macro-manual",
            name = "수동 환기",
            triggers = listOf(Trigger.Manual),
        )

        val selected = selectMacroShortcuts(
            rules = listOf(automatic, manual, MacroPresets.afterBlow()),
            limit = 2,
        )

        assertEquals(listOf("preset-after-blow", "macro-manual"), selected.map { it.id })
    }
}
