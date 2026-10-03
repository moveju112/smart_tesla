package com.wemade.teslamacro

import com.wemade.teslamacro.ui.component.parseNumberInput
import com.wemade.teslamacro.ui.component.parseTimeInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ValueInputTest {
    /** 직접 입력으로 NaN·무한대·범위 밖 값이 차량 명령에 들어가지 않게 한다. */
    @Test fun numericInputRejectsInvalidValues() {
        for (text in listOf("", "-", "NaN", "Infinity", "14.9", "28.1")) {
            assertNull(parseNumberInput(text, 15.0, 28.0, 0.5))
        }
        assertEquals(15.0, parseNumberInput("15", 15.0, 28.0, 0.5)!!, 0.0)
        assertEquals(28.0, parseNumberInput("28", 15.0, 28.0, 0.5)!!, 0.0)
        assertEquals(21.5, parseNumberInput("21,5", 15.0, 28.0, 0.5)!!, 0.0)
        assertEquals(-2.0, parseNumberInput("-2", -20.0, 60.0, 1.0)!!, 0.0)
    }

    /** 시각 입력은 자정·하루 끝을 보존하고 잘못된 시간은 저장하지 않는다. */
    @Test fun timeInputChecksDayBoundaries() {
        assertEquals(0, parseTimeInput("00:00"))
        assertEquals(1439, parseTimeInput("23:59"))
        assertEquals(460, parseTimeInput("7:40"))
        for (text in listOf("24:00", "23:60", "-1:20", "7:4", "", "07:30:00", "+1:20")) {
            assertNull(parseTimeInput(text))
        }
    }
}
