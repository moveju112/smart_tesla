package com.wemade.teslamacro.feature.history

import org.junit.Assert.assertEquals
import org.junit.Test

/** 저장값은 보존하면서 사용자에게 보이는 시간과 반올림 경계만 검증한다. */
class HistoryFormattingTest {
    /** 짧은 주행·정확한 분/시간 경계·비정상 음수 기간을 읽기 쉬운 단위로 표시한다. */
    @Test fun readableDurationBoundaries() {
        assertEquals("0초", historyDuration(-1))
        assertEquals("12초", historyDuration(12_000))
        assertEquals("59초", historyDuration(59_999))
        assertEquals("1분", historyDuration(60_000))
        assertEquals("1분 1초", historyDuration(61_000))
        assertEquals("59분 59초", historyDuration(3_599_000))
        assertEquals("1시간", historyDuration(3_600_000))
        assertEquals("2시간 5분", historyDuration(7_500_000))
    }

    /** 반올림된 음수 0만 정규화하고 실제 회생 전력의 부호와 미수신은 보존한다. */
    @Test fun roundedNegativeZeroWithoutHidingRegeneration() {
        assertEquals("0.0", historyNumber(-0.0))
        assertEquals("0.0", historyNumber(-0.04))
        assertEquals("-0.1", historyNumber(-0.06))
        assertEquals("-1.2", historyNumber(-1.24))
        assertEquals("0.1", historyNumber(0.06))
        assertEquals("--", historyNumber(null))
        assertEquals("--", historyNumber(Double.NaN))
        assertEquals("--", historyNumber(Double.POSITIVE_INFINITY))
    }
}
