package com.wemade.teslamacro.domain.model

enum class SignalKind { NUMBER, BOOLEAN }

/**
 * 매크로 조건이 참조할 수 있는 차량 신호 목록.
 *
 * **확장 지점 1/3**: 새 조건을 늘리려면 여기에 항목 하나만 추가한다.
 * 매크로 편집 UI는 이 enum을 그대로 나열하므로 화면 수정이 필요 없다.
 */
enum class Signal(
    val label: String,
    val kind: SignalKind,
    val unit: String? = null,
) {
    INSIDE_TEMP("실내 온도", SignalKind.NUMBER, "℃"),
    OUTSIDE_TEMP("외부 온도", SignalKind.NUMBER, "℃"),
    BATTERY_LEVEL("배터리", SignalKind.NUMBER, "%"),
    RANGE("주행 가능 거리", SignalKind.NUMBER, "km"),
    SPEED("속도", SignalKind.NUMBER, "km/h"),

    USER_PRESENT("운전자 탑승", SignalKind.BOOLEAN),
    CLIMATE_ON("공조 작동", SignalKind.BOOLEAN),
    LOCKED("잠금", SignalKind.BOOLEAN),
    CHARGING("충전 중", SignalKind.BOOLEAN),
    DOOR_DRIVER_FRONT("운전석 도어 열림", SignalKind.BOOLEAN),
    DOOR_PASSENGER_FRONT("동승석 도어 열림", SignalKind.BOOLEAN),
    PARKED("주차(P)", SignalKind.BOOLEAN),
    DRIVING("주행 중(D)", SignalKind.BOOLEAN),
    CHARGE_PORT_OPEN("충전구 열림", SignalKind.BOOLEAN),
    PRECONDITIONING("예열 중", SignalKind.BOOLEAN);

    /** 숫자 신호 값. 다른 종류이거나 아직 못 읽었으면 null */
    fun numberOf(snapshot: VehicleSnapshot): Double? = when (this) {
        INSIDE_TEMP -> snapshot.insideTempC
        OUTSIDE_TEMP -> snapshot.outsideTempC
        BATTERY_LEVEL -> snapshot.batteryLevelPercent?.toDouble()
        RANGE -> snapshot.rangeKm?.toDouble()
        SPEED -> snapshot.speedKph?.toDouble()
        else -> null
    }

    /** 불리언 신호 값. 다른 종류이거나 아직 못 읽었으면 null */
    fun booleanOf(snapshot: VehicleSnapshot): Boolean? = when (this) {
        USER_PRESENT -> snapshot.isUserPresent
        CLIMATE_ON -> snapshot.isClimateOn
        LOCKED -> snapshot.isLocked
        CHARGING -> snapshot.isCharging
        CHARGE_PORT_OPEN -> snapshot.isChargePortOpen
        PRECONDITIONING -> snapshot.isPreconditioning
        DRIVING -> snapshot.shiftState
            .takeIf { it != ShiftState.UNKNOWN }
            ?.let { it == ShiftState.DRIVE }
        DOOR_DRIVER_FRONT -> snapshot.doorOpen[Door.DRIVER_FRONT]
        DOOR_PASSENGER_FRONT -> snapshot.doorOpen[Door.PASSENGER_FRONT]
        // 기어를 아직 못 읽었으면 "주차 아님"이 아니라 "모름"이다
        PARKED -> snapshot.shiftState
            .takeIf { it != ShiftState.UNKNOWN }
            ?.let { it == ShiftState.PARK }
        else -> null
    }

    /** 이 신호를 읽으려면 어느 카테고리를 폴링해야 하는가 — 폴링 계획 수립에 쓴다 */
    val sourceCategory: StateCategory
        get() = when (this) {
            INSIDE_TEMP, OUTSIDE_TEMP, CLIMATE_ON, PRECONDITIONING -> StateCategory.CLIMATE
            BATTERY_LEVEL, CHARGING, RANGE, CHARGE_PORT_OPEN -> StateCategory.CHARGE
            PARKED, DRIVING, SPEED -> StateCategory.DRIVE
            USER_PRESENT, LOCKED, DOOR_DRIVER_FRONT, DOOR_PASSENGER_FRONT ->
                StateCategory.BODY_CONTROLLER
        }
}
