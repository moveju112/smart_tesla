package com.wemade.teslamacro.service

import org.junit.Assert.assertEquals
import org.junit.Test

/** 예약을 누른 뒤 잠금할 시간은 짧으면 놓치고 길면 시험 의도를 잊으므로 범위를 고정한다. */
class SafeDriveTestDelayTest {

    @Test
    fun `예약 시간은 5초에서 30초 사이로 제한한다`() {
        assertEquals(5_000L, normalizedSafeDriveTestDelay(0L))
        assertEquals(10_000L, normalizedSafeDriveTestDelay(10_000L))
        assertEquals(30_000L, normalizedSafeDriveTestDelay(60_000L))
    }
}
