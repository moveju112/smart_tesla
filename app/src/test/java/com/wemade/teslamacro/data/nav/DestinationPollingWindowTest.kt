package com.wemade.teslamacro.data.nav

import com.wemade.teslamacro.domain.model.ShiftState
import org.junit.Assert.*
import org.junit.Test

/** 출발 전 가속과 원래 조회 시각·백오프 보존을 차량 없이 검사한다. */
class DestinationPollingWindowTest {
    // 새 착석만 집중 수신을 시작하고 미확인·만료·시계 역행에는 일반 주기를 쓴다.
    @Test fun fastPollingRequiresFreshConfirmedPresence() {
        val window = DestinationPollingWindow()
        assertFalse(window.isFast(0))
        window.observePresence(false, 0)
        assertFalse(window.isFast(0))
        window.observePresence(true, 1_000)
        assertTrue(window.isFast(1_000))
        assertTrue(window.isFast(31_000))
        assertFalse(window.isFast(31_001))
        assertFalse(window.isFast(999))
    }

    // 첫 D/R 이후 P나 UNKNOWN으로 돌아와도 하차 전에는 집중 수신을 다시 켜지 않는다.
    @Test fun drivingEndsFastPollingUntilTheNextBoarding() {
        for (shift in listOf(ShiftState.DRIVE, ShiftState.REVERSE)) {
            val window = DestinationPollingWindow()
            window.observePresence(true, 0)
            assertTrue(window.isFast(0))
            window.observeDrive(shift)
            window.observeDrive(ShiftState.PARK)
            window.observeDrive(ShiftState.UNKNOWN)
            window.observePresence(true, 60_000)
            assertFalse(window.isFast(60_000))
            window.observePresence(false, 61_000)
            window.observePresence(true, 62_000)
            assertTrue(window.isFast(62_000))
            window.reset()
            assertFalse(window.isFast(62_000))
        }
    }

    // 주행 관측이 착석 이벤트보다 먼저 도착해도 이미 움직이는 차를 출발 전으로 오인하지 않는다.
    @Test fun driveBeforePresenceCannotStartFastPolling() {
        val window = DestinationPollingWindow()
        window.observeDrive(ShiftState.DRIVE)
        window.observePresence(true, 1_000)
        assertFalse(window.isFast(1_000))
    }

    // 1초/5초 목표 시각 중간에 이벤트가 와도 새 5초 대기를 덧붙이지 않는다.
    @Test fun wakeEventsDoNotPostponeTheExistingDeadline() {
        assertEquals(1_000L, DESTINATION_FAST_POLL_MILLIS)
        assertEquals(5_000L, DESTINATION_NORMAL_POLL_MILLIS)
        assertEquals(600L, destinationPollWait(400, 1_000, 0, false))
        assertEquals(3_000L, destinationPollWait(2_000, 5_000, 0, false))
        assertEquals(1L, destinationPollWait(5_000, 5_000, 0, false))
        assertEquals(1L, destinationPollWait(8_000, 5_000, 0, false))
    }

    // 집중 수신도 오류 기한을 단축하지 않으며 안심운전 대기만 별도 1초 점검을 허용한다.
    @Test fun failuresKeepTheirBackoffWhileFallbackCanWakeSooner() {
        assertEquals(9_600L, destinationPollWait(400, 1_000, 10_000, false))
        assertEquals(1_000L, destinationPollWait(400, 1_000, 10_000, true))
        assertEquals(200L, destinationPollWait(9_800, 1_000, 10_000, true))
        assertEquals(1_000L, destinationPollWait(60_000, 0, 0, true, pollingEnabled = false))
    }
}
