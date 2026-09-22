package com.wemade.teslamacro.data.gateway

import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.domain.gateway.VehicleGateway
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CommandFeedbackTest {
    /** 무음·진동·음량·방해금지별 생략 사유를 확인하고 정상 소리만 허용한다. */
    @Test
    fun `confirmation tone respects device sound policy with diagnostic reasons`() {
        // 로컬 테스트의 Android 오디오 스텁에 없는 상수는 플랫폼 정의값(무음=0, 진동=1, 소리=2)을 쓴다.
        val normal = 2
        val all = android.app.NotificationManager.INTERRUPTION_FILTER_ALL
        assertNull(confirmationToneSkipReason(normal, 5, all))
        assertEquals("무음 모드", confirmationToneSkipReason(0, 5, all))
        assertEquals("진동 모드", confirmationToneSkipReason(1, 5, all))
        assertEquals("알림 음량 0", confirmationToneSkipReason(normal, 0, all))
        listOf(android.app.NotificationManager.INTERRUPTION_FILTER_NONE,
            android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS,
            android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN).forEach {
            assertEquals("방해금지 또는 알림 허용 상태 확인 불가", confirmationToneSkipReason(normal, 5, it))
        }
        assertEquals("소리 모드 확인 불가", confirmationToneSkipReason(-1, 5, all))
    }

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
