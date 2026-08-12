package com.wemade.teslamacro.domain.command

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P단 잠금 분류를 못박는다.
 * 보닛/트렁크는 주행 중 열리면 치명적이라 잠그고,
 * 창문은 사용자 결정으로 제외했다 — 누가 실수로 넣거나 빼면 여기서 걸린다.
 */
class ParkGuardTest {

    @Test
    fun `보닛과 트렁크만 P단을 요구한다`() {
        assertTrue(VehicleCommand.OpenFrunk.requiresPark())
        assertTrue(VehicleCommand.OpenTrunk.requiresPark())
        assertTrue(VehicleCommand.CloseTrunk.requiresPark())
    }

    @Test
    fun `창문과 잠금은 주행 중에도 허용된다`() {
        assertFalse(VehicleCommand.VentWindows.requiresPark())
        assertFalse(VehicleCommand.CloseWindows.requiresPark())
        assertFalse(VehicleCommand.Lock.requiresPark())
        assertFalse(VehicleCommand.Unlock.requiresPark())
    }
}
