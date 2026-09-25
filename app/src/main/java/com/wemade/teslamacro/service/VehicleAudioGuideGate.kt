package com.wemade.teslamacro.service

import com.wemade.teslamacro.data.settings.DeviceMode
import com.wemade.teslable.BondedDevice

// 1. 등록 차량 별칭을 우선하고, 별칭이 없을 때만 유일한 테슬라 페어링을 사용한다.
internal fun matchingVehicleAudioAddress(vehicleName: String, bondedDevices: List<BondedDevice>): String? {
    // 여러 테슬라가 페어링돼 있으면 저장 별칭만으로 VIN에 맞는 차량을 증명할 수 없다.
    if (bondedDevices.count { it.name.contains("tesla", ignoreCase = true) } > 1) return null
    val candidates = if (vehicleName.isNotBlank()) {
        bondedDevices.filter { it.name.equals(vehicleName, ignoreCase = true) }
    } else {
        bondedDevices.filter { it.name.contains("tesla", ignoreCase = true) }
    }
    // 중복 별칭은 다른 차량의 연결을 자기 차량으로 오인할 수 있어 안내를 열지 않는다.
    return candidates.singleOrNull()?.address
}

// 1. 거치 기기는 기존 안내를 유지하고 휴대 기기만 차량 오디오 연결에 GPS를 묶는다.
internal fun shouldMonitorGuidance(mode: DeviceMode, enabled: Boolean, carAudioConnected: Boolean): Boolean =
    enabled && (mode == DeviceMode.MOUNTED || carAudioConnected)
