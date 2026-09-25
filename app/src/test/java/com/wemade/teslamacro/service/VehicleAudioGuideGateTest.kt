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

    // 옛 설치처럼 저장된 별칭이 없으면 테슬라 페어링이 유일할 때만 인정한다.
    @Test fun missingNameUsesOnlyUniqueTeslaPairing() {
        val vehicle = BondedDevice("Tesla Model Y", "B2", emptyList())
        val headset = BondedDevice("헤드폰", "A1", emptyList())
        assertEquals("B2", matchingVehicleAudioAddress("", listOf(headset, vehicle)))
        assertNull(matchingVehicleAudioAddress("", listOf(headset)))
        assertNull(matchingVehicleAudioAddress("", listOf(vehicle, vehicle.copy(address = "C3"))))
    }
}
