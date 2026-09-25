package com.wemade.teslamacro.service

import com.wemade.teslamacro.data.settings.DeviceMode
import com.wemade.teslable.BondedDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleAudioGuideGateTest {
    // 연결 여부에 따라 휴대 기기 위치 구독만 바뀌고 거치 기기는 기존 설정을 따른다.
    @Test fun portableRequiresCarAudioButMountedDoesNot() {
        assertFalse(shouldMonitorGuidance(DeviceMode.PORTABLE, true, false))
        assertTrue(shouldMonitorGuidance(DeviceMode.PORTABLE, true, true))
        assertTrue(shouldMonitorGuidance(DeviceMode.MOUNTED, true, false))
        assertFalse(shouldMonitorGuidance(DeviceMode.PORTABLE, false, true))
        assertFalse(shouldMonitorGuidance(DeviceMode.MOUNTED, false, true))
    }

    // 수동 세션도 같은 허용 상태로 GPS·음성을 열지만 기능을 끄면 둘 다 닫는다.
    @Test fun manualSessionUsesTheSamePortableGate() {
        val audioConnected = false
        val manualActive = true
        val active = audioConnected || manualActive
        assertTrue(shouldMonitorGuidance(DeviceMode.PORTABLE, true, active))
        assertFalse(shouldMonitorGuidance(DeviceMode.PORTABLE, false, active))
        assertFalse(shouldMonitorGuidance(DeviceMode.PORTABLE, true, false))
    }

    // 위치 콜백이 절전 이후 재개되면 벽시계 대신 절전 시간을 포함한 경과 시각으로 닫는다.
    @Test fun manualGuideExpiresAtSixHoursOfElapsedTime() {
        val startedAt = 5_000L
        assertFalse(manualGuideExpired(null, startedAt + MANUAL_GUIDE_TIMEOUT_MILLIS))
        assertFalse(manualGuideExpired(startedAt, startedAt + MANUAL_GUIDE_TIMEOUT_MILLIS - 1))
        assertTrue(manualGuideExpired(startedAt, startedAt + MANUAL_GUIDE_TIMEOUT_MILLIS))
        assertFalse(manualGuideExpired(startedAt, startedAt - 1))
    }

    // 권한 변경 이벤트에도 거치 모드에만 활동 인식을 재등록한다.
    @Test fun permissionRefreshNeverSubscribesActivityInPortableMode() {
        assertFalse(shouldSubscribeDrivingActivity(DeviceMode.PORTABLE, true, true))
        assertTrue(shouldSubscribeDrivingActivity(DeviceMode.MOUNTED, true, true))
        assertFalse(shouldSubscribeDrivingActivity(DeviceMode.MOUNTED, false, true))
        assertFalse(shouldSubscribeDrivingActivity(DeviceMode.MOUNTED, true, false))
    }

    // 등록 차량 별칭이 있으면 다른 자동차나 다른 테슬라의 음악 연결은 무시한다.
    @Test fun registeredVehicleNameIsExactAndUnambiguous() {
        val bonded = listOf(
            BondedDevice("헤드폰", "A1", emptyList()),
            BondedDevice("Tesla Model Y Why", "B2", emptyList()),
        )
        assertEquals("B2", matchingVehicleAudioAddress("tesla model y why", bonded))
        assertNull(matchingVehicleAudioAddress("등록되지 않은 차량", bonded))
        assertNull(matchingVehicleAudioAddress("Tesla Model Y Why", bonded + bonded[1].copy(address = "D4")))
        // VIN-오디오 주소 매핑이 없어 여러 테슬라가 있으면 다른 차의 연결을 승인하지 않는다.
        assertNull(matchingVehicleAudioAddress("Tesla Model Y Why",
            bonded + BondedDevice("Tesla Model 3 Other", "C3", emptyList())))
    }

    // 연결된 오디오에서 직접 선택한 주소는 별칭·다중 차량 추측보다 우선한다.
    @Test fun explicitAddressWorksWithSeveralTeslasAndRejectsUnpairedSelection() {
        val bonded = listOf(BondedDevice("Tesla One", "B2", emptyList()),
            BondedDevice("Tesla Two", "C3", emptyList()))
        assertNull(matchingVehicleAudioAddress("Tesla One", bonded))
        assertEquals("C3", matchingVehicleAudioAddress("Tesla One", bonded, "c3"))
        assertNull(matchingVehicleAudioAddress("Tesla One", bonded, "D4"))
        assertEquals(VehicleAudioStatus.MULTIPLE_VEHICLES,
            vehicleAudioStatus("Tesla One", bonded, "", setOf("B2"), true, true, true))
        assertEquals(VehicleAudioStatus.CONNECTED,
            vehicleAudioStatus("Tesla One", bonded, "C3", setOf("C3"), true, true, true))
        assertEquals(VehicleAudioStatus.SELECTED_UNPAIRED,
            vehicleAudioStatus("Tesla One", bonded, "D4", setOf("D4"), true, true, true))
    }

    // 사용자에게 보이는 연결 실패 사유를 권한·프로필·별칭·연결 순서대로 구분한다.
    @Test fun connectionReasonsDoNotOpenGuidanceWithoutMatchingProfile() {
        val bonded = listOf(BondedDevice("Tesla One", "B2", emptyList()))
        assertEquals(VehicleAudioStatus.PERMISSION_REQUIRED,
            vehicleAudioStatus("Tesla One", bonded, "", setOf("B2"), false, true, true))
        assertEquals(VehicleAudioStatus.BLUETOOTH_OFF,
            vehicleAudioStatus("Tesla One", bonded, "", setOf("B2"), true, false, true))
        assertEquals(VehicleAudioStatus.PROFILE_WAITING,
            vehicleAudioStatus("Tesla One", bonded, "", emptySet(), true, true, false))
        assertEquals(VehicleAudioStatus.NO_MATCH,
            vehicleAudioStatus("Other", bonded, "", setOf("B2"), true, true, true))
        assertEquals(VehicleAudioStatus.DISCONNECTED,
            vehicleAudioStatus("Tesla One", bonded, "", emptySet(), true, true, true))
    }

    // 옛 설치처럼 저장된 별칭이 없으면 테슬라 페어링이 유일할 때만 인정한다.
    @Test fun missingNameUsesOnlyUniqueTeslaPairing() {
        val vehicle = BondedDevice("Tesla Model Y", "B2", emptyList())
        val headset = BondedDevice("헤드폰", "A1", emptyList())
        assertEquals("B2", matchingVehicleAudioAddress("", listOf(headset, vehicle)))
        assertNull(matchingVehicleAudioAddress("", listOf(headset)))
        assertNull(matchingVehicleAudioAddress("", listOf(vehicle, vehicle.copy(address = "C3"))))
    }
}
