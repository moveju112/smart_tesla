package com.wemade.teslamacro.data.poll

import com.wemade.teslamacro.data.settings.DeviceRole
import com.wemade.teslamacro.data.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionGuardTest {

    @Test
    fun `기기 역할 저장값이 없거나 깨졌으면 개인 휴대폰으로 돌아간다`() {
        assertEquals(DeviceRole.PERSONAL_PHONE, DeviceRole.of(null))
        assertEquals(DeviceRole.PERSONAL_PHONE, DeviceRole.of("BROKEN"))
        assertEquals(DeviceRole.PERSONAL_PHONE, AppSettings().deviceRole)
    }

    @Test
    fun `차량 태블릿에서 보호를 끄면 사용 사유가 없어도 연결을 유지한다`() {
        assertTrue(decision(protectPhoneKey = false).keep)
    }

    @Test
    fun `보호 중인 차량 태블릿은 사용 사유가 없으면 연결하지 않는다`() {
        assertFalse(decision().keep)
    }

    @Test
    fun `차량 전원 상승 뒤 첫 상태 확인까지 연결한다`() {
        assertEquals(
            VehicleConnectionReason.TABLET_RIDE,
            decision(vehiclePowerConnected = true, vehiclePowerWakePending = true).reason,
        )
    }

    @Test
    fun `차량 전원이 있어도 빈 차가 확인되면 연결하지 않는다`() {
        assertEquals(
            VehicleConnectionReason.TABLET_EMPTY,
            decision(vehiclePowerConnected = true, vehicleUserPresent = false).reason,
        )
    }

    @Test
    fun `첫 상태 확인 뒤에도 탑승을 확인하지 못하면 연결하지 않는다`() {
        assertEquals(
            VehicleConnectionReason.TABLET_UNCONFIRMED,
            decision(vehiclePowerConnected = true, vehicleUserPresent = null).reason,
        )
    }

    @Test
    fun `차량 전원과 탑승이 확인되면 연결을 유지한다`() {
        assertTrue(decision(vehiclePowerConnected = true, vehicleUserPresent = true).keep)
    }

    @Test
    fun `개인 휴대폰은 충전과 보호 해제와 자동 매크로를 연결 사유로 쓰지 않는다`() {
        val result = decision(
            deviceRole = DeviceRole.PERSONAL_PHONE,
            protectPhoneKey = false,
            vehiclePowerConnected = true,
            vehiclePowerWakePending = true,
            vehicleUserPresent = true,
            macroRunning = true,
            stealthChargeNeedsConnection = true,
        )
        assertEquals(VehicleConnectionReason.PHONE_IDLE, result.reason)
    }

    @Test
    fun `차량 태블릿은 스텔스 충전 1회가 끝날 때까지 보호를 잠시 미룬다`() {
        assertEquals(
            VehicleConnectionReason.STEALTH_CHARGING,
            decision(stealthChargeNeedsConnection = true).reason,
        )
    }

    @Test
    fun `개인 휴대폰도 앱 화면과 직접 명령에는 연결한다`() {
        assertTrue(decision(deviceRole = DeviceRole.PERSONAL_PHONE, appVisible = true).keep)
        assertTrue(decision(deviceRole = DeviceRole.PERSONAL_PHONE, commandActive = true).keep)
    }

    @Test
    fun `사용자 일시정지는 다른 모든 연결 사유보다 우선한다`() {
        val result = decision(
            protectPhoneKey = false,
            vehiclePowerConnected = true,
            vehiclePowerWakePending = true,
            vehicleUserPresent = true,
            appVisible = true,
            commandActive = true,
            macroRunning = true,
            stealthChargeNeedsConnection = true,
            manuallyPaused = true,
        )
        assertEquals(VehicleConnectionReason.USER_PAUSED, result.reason)
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

    /** 테스트마다 바뀌는 조건만 이름으로 넘겨 연결 판정을 읽기 쉽게 만든다. */
    private fun decision(
        deviceRole: DeviceRole = DeviceRole.CAR_TABLET,
        protectPhoneKey: Boolean = true,
        vehiclePowerConnected: Boolean = false,
        vehiclePowerWakePending: Boolean = false,
        vehicleUserPresent: Boolean? = null,
        appVisible: Boolean = false,
        commandActive: Boolean = false,
        macroRunning: Boolean = false,
        stealthChargeNeedsConnection: Boolean = false,
        manuallyPaused: Boolean = false,
    ): VehicleConnectionDecision = decideVehicleConnection(
        deviceRole = deviceRole,
        protectPhoneKey = protectPhoneKey,
        vehiclePowerConnected = vehiclePowerConnected,
        vehiclePowerWakePending = vehiclePowerWakePending,
        vehicleUserPresent = vehicleUserPresent,
        appVisible = appVisible,
        commandActive = commandActive,
        macroRunning = macroRunning,
        stealthChargeNeedsConnection = stealthChargeNeedsConnection,
        manuallyPaused = manuallyPaused,
    )
}
