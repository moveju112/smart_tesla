package com.wemade.teslamacro.service

import com.wemade.teslamacro.data.settings.DeviceMode
import com.wemade.teslable.BondedDevice

// 1. 등록 차량 별칭을 우선하고, 별칭이 없을 때만 유일한 테슬라 페어링을 사용한다.
internal fun matchingVehicleAudioAddress(
    vehicleName: String,
    bondedDevices: List<BondedDevice>,
    selectedAddress: String = "",
): String? {
    // 사용자가 연결된 오디오에서 고른 주소는 이름·다중 차량 추측보다 우선한다.
    if (selectedAddress.isNotBlank()) return bondedDevices.singleOrNull {
        it.address.equals(selectedAddress, ignoreCase = true)
    }?.address
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

// 1. 위치 감시와 소리 허용에 같은 식별 결과를 쓰도록 실패 사유를 분류한다.
internal fun vehicleAudioStatus(
    vehicleName: String,
    bondedDevices: List<BondedDevice>,
    selectedAddress: String,
    connectedAddresses: Set<String>,
    permitted: Boolean,
    bluetoothEnabled: Boolean,
    profileReady: Boolean,
): VehicleAudioStatus {
    if (!permitted) return VehicleAudioStatus.PERMISSION_REQUIRED
    if (!bluetoothEnabled) return VehicleAudioStatus.BLUETOOTH_OFF
    if (!profileReady) return VehicleAudioStatus.PROFILE_WAITING
    val address = matchingVehicleAudioAddress(vehicleName, bondedDevices, selectedAddress)
    if (address == null) return when {
        selectedAddress.isNotBlank() -> VehicleAudioStatus.SELECTED_UNPAIRED
        bondedDevices.count { it.name.contains("tesla", ignoreCase = true) } > 1 -> VehicleAudioStatus.MULTIPLE_VEHICLES
        else -> VehicleAudioStatus.NO_MATCH
    }
    return if (connectedAddresses.any { it.equals(address, ignoreCase = true) })
        VehicleAudioStatus.CONNECTED else VehicleAudioStatus.DISCONNECTED
}

/** MAC·차량 이름을 로그에 남기지 않고 설정 화면에 연결 근거를 보여준다. */
enum class VehicleAudioStatus(val label: String) {
    CHECKING("차량 오디오 확인 중"),
    PERMISSION_REQUIRED("Bluetooth 연결 권한 필요 · 내부 안내 대기"),
    BLUETOOTH_OFF("Bluetooth 꺼짐 · 내부 안내 대기"),
    PROFILE_WAITING("음악용 Bluetooth 연결 상태 확인 중"),
    SELECTED_UNPAIRED("선택한 차량 오디오가 페어링 목록에서 사라졌어요"),
    MULTIPLE_VEHICLES("테슬라 여러 대가 페어링됨 · 오디오 기기 선택 필요"),
    NO_MATCH("등록 차량 오디오를 찾지 못했어요 · 연결 후 기기를 선택하세요"),
    DISCONNECTED("차량 오디오 연결 대기 · GPS 안내 중지"),
    CONNECTED("차량 오디오 연결됨 · 내부 GPS 안내 사용"),
    READ_FAILED("Bluetooth 상태 확인 실패 · 내부 안내 대기"),
}

internal const val MANUAL_GUIDE_TIMEOUT_MILLIS = 6 * 60 * 60_000L

// 1. 절전 중에도 흐르는 elapsedRealtime으로 수동 안내의 경과 시간을 판정한다.
internal fun manualGuideExpired(startedAt: Long?, now: Long): Boolean =
    startedAt != null && now >= startedAt && now - startedAt >= MANUAL_GUIDE_TIMEOUT_MILLIS

// 1. 권한 변경과 설정 변경이 동일한 구독 조건을 사용해 휴대 모드의 재등록을 막는다.
internal fun shouldSubscribeDrivingActivity(mode: DeviceMode, guideEnabled: Boolean, soundEnabled: Boolean): Boolean =
    mode == DeviceMode.MOUNTED && guideEnabled && soundEnabled

// 1. 거치 기기는 기존 안내를 유지하고 휴대 기기만 차량 오디오 또는 수동 세션에 묶는다.
internal fun shouldMonitorGuidance(mode: DeviceMode, enabled: Boolean, portableGuidanceActive: Boolean): Boolean =
    enabled && (mode == DeviceMode.MOUNTED || portableGuidanceActive)
