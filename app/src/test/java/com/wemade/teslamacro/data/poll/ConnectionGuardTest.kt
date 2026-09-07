package com.wemade.teslamacro.data.poll

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionGuardTest {

    @Test
    fun `보호를 끄면 사용 사유가 없어도 연결을 유지한다`() {
        assertTrue(shouldKeepVehicleConnection(false, false, false, false, false))
    }

    @Test
    fun `보호 중인 빈 차에서는 연결하지 않는다`() {
        assertFalse(shouldKeepVehicleConnection(true, false, false, false, false))
    }

    @Test
    fun `보호 중에도 실제 사용 사유가 하나면 연결한다`() {
        assertTrue(shouldKeepVehicleConnection(true, true, false, false, false))
        assertTrue(shouldKeepVehicleConnection(true, false, true, false, false))
        assertTrue(shouldKeepVehicleConnection(true, false, false, true, false))
        assertTrue(shouldKeepVehicleConnection(true, false, false, false, true))
    }

    @Test
    fun `10분 미만 전원 출렁임은 새 탑승으로 보지 않는다`() {
        val disconnectedAt = 10_000L
        assertFalse(
            startsNewVehicleSession(
                disconnectedAt,
                disconnectedAt + RIDE_SESSION_GRACE_MILLIS - 1L,
            )
        )
    }

    @Test
    fun `10분 이상 전원 해제 뒤 복귀는 새 탑승으로 본다`() {
        val disconnectedAt = 10_000L
        assertTrue(
            startsNewVehicleSession(
                disconnectedAt,
                disconnectedAt + RIDE_SESSION_GRACE_MILLIS,
            )
        )
    }

    @Test
    fun `전원 해제 기록이 없으면 새 탑승으로 보지 않는다`() {
        assertFalse(startsNewVehicleSession(0L, 40_000L))
    }
}
