package com.wemade.teslamacro.domain.command

import com.wemade.teslamacro.domain.model.Level
import com.wemade.teslamacro.domain.model.SeatPosition
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 재시도해도 되는 명령 분류를 못박는다.
 *
 * 잠든 차를 깨우고 다시 보낼 때 이 분류가 틀리면 트렁크가 두 번 열린다 —
 * 응답이 없다고 명령이 안 갔다는 보장이 없기 때문이다.
 */
class IdempotencyTest {

    @Test
    fun `누를 때마다 상태가 바뀌는 명령은 재시도하지 않는다`() {
        assertFalse(VehicleCommand.OpenTrunk.isIdempotent())
        assertFalse(VehicleCommand.CloseTrunk.isIdempotent())
        assertFalse(VehicleCommand.OpenFrunk.isIdempotent())
        assertFalse(VehicleCommand.Honk.isIdempotent())
        assertFalse(VehicleCommand.FlashLights.isIdempotent())
        assertFalse(VehicleCommand.ToggleMedia.isIdempotent())
        assertFalse(VehicleCommand.NextTrack.isIdempotent())
        assertFalse(VehicleCommand.PreviousTrack.isIdempotent())
        assertFalse(VehicleCommand.NextFavorite.isIdempotent())
    }

    @Test
    fun `절대값을 설정하는 명령은 재시도해도 안전하다`() {
        assertTrue(VehicleCommand.ClimateOn.isIdempotent())
        assertTrue(VehicleCommand.ClimateOff.isIdempotent())
        assertTrue(VehicleCommand.SetTemperature(22.0).isIdempotent())
        assertTrue(VehicleCommand.SetSeatHeater(SeatPosition.FRONT_LEFT, Level.LOW).isIdempotent())
        assertTrue(VehicleCommand.Lock.isIdempotent())
        assertTrue(VehicleCommand.Unlock.isIdempotent())
        assertTrue(VehicleCommand.VentWindows.isIdempotent())
        assertTrue(VehicleCommand.CloseWindows.isIdempotent())
        assertTrue(VehicleCommand.SetChargeLimit(80).isIdempotent())
    }

    /** P단 잠금과 재시도 금지는 뜻이 다르지만, 개폐 명령은 양쪽 모두에 걸려야 한다 */
    @Test
    fun `P단을 요구하는 개폐 명령은 재시도도 금지다`() {
        listOf(
            VehicleCommand.OpenFrunk,
            VehicleCommand.OpenTrunk,
            VehicleCommand.CloseTrunk,
        ).forEach {
            assertTrue(it.label, it.requiresPark())
            assertFalse(it.label, it.isIdempotent())
        }
    }
}
