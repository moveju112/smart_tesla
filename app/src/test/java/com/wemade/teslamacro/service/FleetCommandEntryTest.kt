package com.wemade.teslamacro.service

import com.wemade.teslamacro.data.fleet.fleetCommandBody
import com.wemade.teslamacro.domain.command.VehicleCommand
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/** 새 UI와 외부 호출은 동일한 허용 목록을 통과하며 입력값을 별도 무인 우회 통로로 받지 않는다. */
class FleetCommandEntryTest {
    /** 충전 시작·중지와 UI의 온도·한도 범위 전체가 서버 인코더에 연결된다. */
    @Test fun everyNewUiChoiceMapsToASupportedFleetCommand() {
        assertEquals(VehicleCommand.SetCharging(true), QuickActionActivity.ACTIONS["charge_start"])
        assertEquals(VehicleCommand.SetCharging(false), QuickActionActivity.ACTIONS["charge_stop"])
        assertEquals("charge_start", fleetCommandBody(QuickActionActivity.ACTIONS.getValue("charge_start"), 60)["type"]!!.jsonPrimitive.content)
        assertEquals("charge_stop", fleetCommandBody(QuickActionActivity.ACTIONS.getValue("charge_stop"), 60)["type"]!!.jsonPrimitive.content)
        for (tenths in 150..280 step 5) {
            val command = QuickActionActivity.ACTIONS.getValue("set_temperature_$tenths")
            assertEquals(VehicleCommand.SetTemperature(tenths / 10.0), command)
            assertEquals("set_temps", fleetCommandBody(command, 60)["type"]!!.jsonPrimitive.content)
        }
        for (percent in 50..100) {
            val command = QuickActionActivity.ACTIONS.getValue("set_charge_limit_$percent")
            assertEquals(VehicleCommand.SetChargeLimit(percent), command)
            assertEquals("set_charge_limit", fleetCommandBody(command, 60)["type"]!!.jsonPrimitive.content)
        }
        for (invalid in listOf("set_temperature_149", "set_temperature_285", "set_temperature_NaN",
            "set_charge_limit_49", "set_charge_limit_101")) assertNull(QuickActionActivity.ACTIONS[invalid])
    }
}
