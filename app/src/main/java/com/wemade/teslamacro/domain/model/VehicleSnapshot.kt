package com.wemade.teslamacro.domain.model

/** 좌석 위치. 통풍은 앞좌석 2개만 지원한다 (프로토콜 제약) */
enum class SeatPosition(val label: String, val supportsCooler: Boolean) {
    FRONT_LEFT("운전석", true),
    FRONT_RIGHT("동승석", true),
    REAR_LEFT("뒷좌석 좌", false),
    REAR_CENTER("뒷좌석 중앙", false),
    REAR_RIGHT("뒷좌석 우", false),
}

/** 열선/통풍 공용 4단계 */
enum class Level(val label: String) {
    OFF("끔"), LOW("1"), MEDIUM("2"), HIGH("3");

    companion object {
        fun fromStep(step: Int): Level = entries.getOrElse(step) { OFF }
    }
}

/** 좌석 온도 모드. 통풍과 열선은 동시에 켤 수 없어 하나로 묶는다 */
enum class SeatMode(val label: String) { COOL("통풍"), HEAT("열선") }

/**
 * 좌석 한 자리의 현재 설정. 모드 + 단계.
 * 차량 상태 읽기가 불안정해 이 값은 클라이언트에 저장해 UI의 기준으로 삼는다.
 */
data class SeatClimate(val mode: SeatMode = SeatMode.COOL, val level: Level = Level.OFF)

enum class ShiftState { UNKNOWN, PARK, REVERSE, NEUTRAL, DRIVE }

/**
 * 한 시점의 차량 상태. 매크로 판정의 유일한 입력이다.
 * 아직 못 읽은 값은 null이며, 매크로는 null을 "조건 불충족"으로 다룬다.
 */
data class VehicleSnapshot(
    val timestampMillis: Long,
    val insideTempC: Double? = null,
    val outsideTempC: Double? = null,
    val driverTempSettingC: Double? = null,
    val isClimateOn: Boolean? = null,
    val isPreconditioning: Boolean? = null,
    val isUserPresent: Boolean? = null,
    val isLocked: Boolean? = null,
    val shiftState: ShiftState = ShiftState.UNKNOWN,
    val doorOpen: Map<Door, Boolean> = emptyMap(),
    val seatHeater: Map<SeatPosition, Level> = emptyMap(),
    val seatCooler: Map<SeatPosition, Level> = emptyMap(),
    val batteryLevelPercent: Int? = null,
    val isCharging: Boolean? = null,
    val chargeLimitPercent: Int? = null,
    val rangeKm: Float? = null,
    val isChargePortOpen: Boolean? = null,
    val speedKph: Float? = null,
) {
    companion object {
        val Empty = VehicleSnapshot(timestampMillis = 0L)
    }
}

enum class Door(val label: String) {
    DRIVER_FRONT("운전석 도어"),
    DRIVER_REAR("운전석 뒷도어"),
    PASSENGER_FRONT("동승석 도어"),
    PASSENGER_REAR("동승석 뒷도어"),
    TRUNK("트렁크"),
    FRUNK("프렁크"),
}

/** 차량에서 읽어오는 상태 묶음. BLE는 한 번에 하나씩 요청해야 한다 */
enum class StateCategory(val label: String, val needsInfotainment: Boolean) {
    /** VCSEC. 차량이 자고 있어도 응답한다 — 상시 감시는 이것만 쓴다 */
    BODY_CONTROLLER("차체 상태", needsInfotainment = false),
    CLIMATE("공조", needsInfotainment = true),
    CLOSURES("도어/잠금", needsInfotainment = true),
    DRIVE("주행", needsInfotainment = true),
    CHARGE("충전", needsInfotainment = true),
}
