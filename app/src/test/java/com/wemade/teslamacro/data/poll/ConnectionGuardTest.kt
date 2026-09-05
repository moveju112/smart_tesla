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
}
