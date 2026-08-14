package com.wemade.teslamacro.domain.command

import com.wemade.teslamacro.domain.model.Level
import com.wemade.teslamacro.domain.model.SeatPosition
import com.wemade.teslamacro.domain.model.StateCategory
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 명령 후 확인 읽기 카테고리 분류를 못박는다.
 * 공조·충전 명령이 차체(BODY_CONTROLLER)로 빠지면 명령 결과가
 * 화면에 최대 120초 늦게 나타난다 — 분류가 어긋나면 여기서 걸린다.
 */
class ConfirmCategoryTest {

    @Test
    fun `공조 계열 명령은 CLIMATE를 확인한다`() {
        assertEquals(StateCategory.CLIMATE, VehicleCommand.ClimateOn.confirmCategory())
        assertEquals(StateCategory.CLIMATE, VehicleCommand.SetTemperature(22.0).confirmCategory())
        assertEquals(
            StateCategory.CLIMATE,
            VehicleCommand.SetSeatCooler(SeatPosition.FRONT_LEFT, Level.HIGH).confirmCategory(),
        )
        assertEquals(
            StateCategory.CLIMATE,
            VehicleCommand.SetSeatHeater(SeatPosition.FRONT_RIGHT, Level.LOW).confirmCategory(),
        )
    }

    @Test
    fun `충전 계열 명령은 CHARGE를 확인한다`() {
        assertEquals(StateCategory.CHARGE, VehicleCommand.SetCharging(true).confirmCategory())
        assertEquals(StateCategory.CHARGE, VehicleCommand.SetChargeLimit(80).confirmCategory())
        assertEquals(StateCategory.CHARGE, VehicleCommand.SetChargingAmps(16).confirmCategory())
        assertEquals(StateCategory.CHARGE, VehicleCommand.SetChargePort(true).confirmCategory())
    }

    @Test
    fun `잠금과 개폐는 매 사이클 읽는 차체 상태로 확인한다`() {
        assertEquals(StateCategory.BODY_CONTROLLER, VehicleCommand.Lock.confirmCategory())
        assertEquals(StateCategory.BODY_CONTROLLER, VehicleCommand.Unlock.confirmCategory())
        assertEquals(StateCategory.BODY_CONTROLLER, VehicleCommand.OpenTrunk.confirmCategory())
        assertEquals(StateCategory.BODY_CONTROLLER, VehicleCommand.VentWindows.confirmCategory())
    }
}
