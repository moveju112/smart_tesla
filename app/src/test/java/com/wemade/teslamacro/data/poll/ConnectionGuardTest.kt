package com.wemade.teslamacro.data.poll

import com.wemade.teslamacro.data.settings.DeviceMode
import com.wemade.teslamacro.data.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionGuardTest {

    @Test
    fun `사용 모드 저장값이 없거나 깨졌으면 휴대 모드로 돌아간다`() {
        assertEquals(DeviceMode.PORTABLE, DeviceMode.of(null))
        assertEquals(DeviceMode.PORTABLE, DeviceMode.of("BROKEN"))
        assertEquals(DeviceMode.PORTABLE, AppSettings().deviceMode)
    }

    @Test
    fun `기존 기기 역할 저장값은 같은 사용 모드로 이어받는다`() {
        assertEquals(DeviceMode.MOUNTED, DeviceMode.of("CAR_TABLET"))
        assertEquals(DeviceMode.PORTABLE, DeviceMode.of("PERSONAL_PHONE"))
    }

    @Test
    fun `거치 모드에서 보호를 끄면 사용 사유가 없어도 연결을 유지한다`() {
        assertTrue(decision(protectPhoneKey = false).keep)
    }

    @Test
    fun `보호 중인 거치 모드는 사용 사유가 없으면 연결하지 않는다`() {
        assertFalse(decision().keep)
    }

    @Test
    fun `차량 전원 상승 뒤 첫 상태 확인까지 연결한다`() {
        assertEquals(
            VehicleConnectionReason.MOUNTED_USE,
            decision(vehiclePowerConnected = true, vehiclePowerWakePending = true).reason,
        )
    }

    @Test
    fun `차량 전원이 있어도 빈 차가 확인되면 연결하지 않는다`() {
        assertEquals(
            VehicleConnectionReason.MOUNTED_EMPTY,
            decision(vehiclePowerConnected = true, vehicleUserPresent = false).reason,
        )
    }

    @Test
    fun `첫 상태 확인 뒤에도 탑승을 확인하지 못하면 연결하지 않는다`() {
        assertEquals(
            VehicleConnectionReason.MOUNTED_UNCONFIRMED,
            decision(vehiclePowerConnected = true, vehicleUserPresent = null).reason,
        )
    }

    @Test
    fun `차량 전원과 탑승이 확인되면 연결을 유지한다`() {
        assertTrue(decision(vehiclePowerConnected = true, vehicleUserPresent = true).keep)
    }

    @Test
    fun `휴대 모드는 충전과 보호 해제와 자동 매크로를 연결 사유로 쓰지 않는다`() {
        val result = decision(
            deviceMode = DeviceMode.PORTABLE,
            protectPhoneKey = false,
            vehiclePowerConnected = true,
            vehiclePowerWakePending = true,
            vehicleUserPresent = true,
            macroRunning = true,
            stealthChargeNeedsConnection = true,
        )
        assertEquals(VehicleConnectionReason.PORTABLE_IDLE, result.reason)
    }

    @Test
    fun `휴대 모드는 자동 안심운전이 켜지면 전원 상승 뒤 탑승 확인을 한 번 허용한다`() {
        val result = decision(
            deviceMode = DeviceMode.PORTABLE,
            autoStartNavigatorSafeDrive = true,
            vehiclePowerConnected = true,
            vehiclePowerWakePending = true,
        )
        assertEquals(VehicleConnectionReason.PORTABLE_SAFE_DRIVE_CHECK, result.reason)
    }

    @Test
    fun `휴대 모드 안심운전은 전원과 미확인 표식이 모두 있어야 연결한다`() {
        assertEquals(
            VehicleConnectionReason.PORTABLE_IDLE,
            decision(
                deviceMode = DeviceMode.PORTABLE,
                autoStartNavigatorSafeDrive = true,
                vehiclePowerConnected = true,
            ).reason,
        )
        assertEquals(
            VehicleConnectionReason.PORTABLE_IDLE,
            decision(
                deviceMode = DeviceMode.PORTABLE,
                autoStartNavigatorSafeDrive = true,
                vehiclePowerWakePending = true,
            ).reason,
        )
    }

    @Test
    fun `거치 모드는 스텔스 충전 1회가 끝날 때까지 보호를 잠시 미룬다`() {
        assertEquals(
            VehicleConnectionReason.STEALTH_CHARGING,
            decision(stealthChargeNeedsConnection = true).reason,
        )
    }

    @Test
    fun `휴대 모드도 앱 화면과 직접 명령에는 연결한다`() {
        assertTrue(decision(deviceMode = DeviceMode.PORTABLE, appVisible = true).keep)
        assertTrue(decision(deviceMode = DeviceMode.PORTABLE, commandActive = true).keep)
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
        deviceMode: DeviceMode = DeviceMode.MOUNTED,
        protectPhoneKey: Boolean = true,
        autoStartNavigatorSafeDrive: Boolean = false,
        vehiclePowerConnected: Boolean = false,
        vehiclePowerWakePending: Boolean = false,
        vehicleUserPresent: Boolean? = null,
        appVisible: Boolean = false,
        commandActive: Boolean = false,
        macroRunning: Boolean = false,
        stealthChargeNeedsConnection: Boolean = false,
        manuallyPaused: Boolean = false,
    ): VehicleConnectionDecision = decideVehicleConnection(
        deviceMode = deviceMode,
        protectPhoneKey = protectPhoneKey,
        autoStartNavigatorSafeDrive = autoStartNavigatorSafeDrive,
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
