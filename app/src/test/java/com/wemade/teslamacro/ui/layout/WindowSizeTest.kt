package com.wemade.teslamacro.ui.layout

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class WindowSizeTest {
    /** 큰 태블릿도 세로에서는 휴대폰과 같은 한 열을 사용한다. */
    @Test
    fun `세로는 폭과 관계없이 한 열이다`() {
        listOf(411, 600, 900, 1280).forEach { width ->
            assertEquals(Pane.Compact, Pane.of(width.dp, portrait = true))
            assertEquals(1, Pane.of(width.dp, portrait = true).columns)
        }
    }

    /** 가로의 기존 분기점과 좁은 분할 창의 한 열 동작을 보존한다. */
    @Test
    fun `가로는 기존 폭 분기를 유지한다`() {
        assertEquals(Pane.Compact, Pane.of(599.dp, portrait = false))
        assertEquals(Pane.Medium, Pane.of(600.dp, portrait = false))
        assertEquals(Pane.Medium, Pane.of(899.dp, portrait = false))
        assertEquals(Pane.Expanded, Pane.of(900.dp, portrait = false))
        assertEquals(Pane.Expanded, Pane.of(960.dp, portrait = false))
    }
}
