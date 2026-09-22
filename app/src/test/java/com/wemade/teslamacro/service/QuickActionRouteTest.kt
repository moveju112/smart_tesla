package com.wemade.teslamacro.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickActionRouteTest {
    /** Fleet를 켰어도 이미 연결된 차량은 BLE로만 명령을 보낸다. */
    @Test
    fun `connected BLE takes precedence over Fleet`() {
        assertFalse(shouldUseFleetForQuickAction(fleetEnabled = true, bleConnected = true))
        assertFalse(shouldUseFleetForQuickAction(fleetEnabled = false, bleConnected = true))
    }

    /** 차량 BLE가 없으면 기존 Fleet 설정에 따르며 서버를 임의로 사용하지 않는다. */
    @Test
    fun `disconnected BLE preserves configured route`() {
        assertTrue(shouldUseFleetForQuickAction(fleetEnabled = true, bleConnected = false))
        assertFalse(shouldUseFleetForQuickAction(fleetEnabled = false, bleConnected = false))
    }
}
