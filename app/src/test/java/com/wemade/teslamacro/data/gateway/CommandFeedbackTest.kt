package com.wemade.teslamacro.data.gateway

import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.domain.gateway.VehicleGateway
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CommandFeedbackTest {
    /** 개폐·잠금은 알리고 자동 공조·전류 조절에는 반복음을 내지 않는다. */
    @Test
    fun `only access commands request confirmation tone`() {
        listOf(VehicleCommand.OpenFrunk, VehicleCommand.OpenTrunk, VehicleCommand.CloseTrunk,
            VehicleCommand.Lock, VehicleCommand.Unlock, VehicleCommand.VentWindows,
            VehicleCommand.CloseWindows, VehicleCommand.SetChargePort(true), VehicleCommand.SetChargePort(false))
            .forEach { assertTrue(it.hasConfirmationTone()) }
        listOf(VehicleCommand.ClimateOn, VehicleCommand.ClimateOff, VehicleCommand.SetTemperature(22.0),
            VehicleCommand.SetChargingAmps(10)).forEach { assertFalse(it.hasConfirmationTone()) }
    }

    /** 공유 게이트웨이를 통과하는 성공은 출처에 관계없이 한 번만 알리고 실패는 알리지 않는다. */
    @Test
    fun `gateway reports success once and never reports failure`() = runTest {
        var sends = 0
        var confirmed = 0
        var success = true
        val target = object : VehicleGateway by SimulatedVehicleGateway() {
            /** Android·BLE 없이 전송 결과만 통제한다. */
            override suspend fun send(command: VehicleCommand): Result<Unit> {
                sends++
                return if (success) Result.success(Unit) else Result.failure(IllegalStateException("rejected"))
            }
        }
        val gateway = SwitchingVehicleGateway(target, backgroundScope) { confirmed++ }
        assertTrue(gateway.send(VehicleCommand.OpenFrunk).isSuccess)
        assertEquals(1, confirmed)
        success = false
        assertTrue(gateway.send(VehicleCommand.OpenFrunk).isFailure)
        assertEquals(1, confirmed)
        assertEquals(2, sends)
    }

    /** 오디오 오류가 차량 성공을 실패로 바꾸거나 재전송을 유발하지 않는다. */
    @Test
    fun `feedback failure preserves vehicle result`() = runTest {
        var sends = 0
        val target = object : VehicleGateway by SimulatedVehicleGateway() {
            /** 차량은 이미 명령을 수락한 상태다. */
            override suspend fun send(command: VehicleCommand): Result<Unit> { sends++; return Result.success(Unit) }
        }
        val gateway = SwitchingVehicleGateway(target, backgroundScope) { error("audio unavailable") }
        assertTrue(gateway.send(VehicleCommand.OpenFrunk).isSuccess)
        assertEquals(1, sends)
    }
}
