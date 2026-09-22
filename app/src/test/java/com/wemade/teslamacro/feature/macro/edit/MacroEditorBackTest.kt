package com.wemade.teslamacro.feature.macro.edit

import org.junit.Assert.assertEquals
import org.junit.Test

class MacroEditorBackTest {
    /** 기존 매크로의 동작 탭에서도 이전 탭을 거치지 않고 한 번에 편집을 닫는다. */
    @Test fun `back exits editor directly without traversing steps`() {
        var pickerClosed = 0
        var editorClosed = 0
        handleEditorBack(false, { pickerClosed++ }, { editorClosed++ })
        assertEquals(0, pickerClosed)
        assertEquals(1, editorClosed)
    }

    /** 선택창은 먼저 닫고 다음 뒤로가기에서만 목록으로 돌아간다. */
    @Test fun `open picker consumes only one back before editor exit`() {
        var pickerOpen = true
        var editorClosed = 0
        handleEditorBack(pickerOpen, { pickerOpen = false }, { editorClosed++ })
        assertEquals(false, pickerOpen)
        assertEquals(0, editorClosed)
        handleEditorBack(pickerOpen, { pickerOpen = false }, { editorClosed++ })
        assertEquals(1, editorClosed)
    }
}
