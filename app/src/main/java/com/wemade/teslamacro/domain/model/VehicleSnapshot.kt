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

/** 타이어 네 자리. 도면의 바퀴 위치와 그대로 대응한다 */
enum class TirePosition(val label: String) {
    FRONT_LEFT("앞 좌"),
    FRONT_RIGHT("앞 우"),
    REAR_LEFT("뒤 좌"),
    REAR_RIGHT("뒤 우"),
}

/**
 * 차량 소프트웨어 업데이트 상태.
 * 앱 업데이트와 헷갈리지 않게 이름에 Vehicle을 붙인다.
 */
data class VehicleSoftwareUpdate(
    val status: Status,
    val version: String? = null,
    /** 내려받는 중일 때만 0~100 */
    val downloadPercent: Int? = null,
    /** 설치 예약 시각(epoch millis). 예약이 없으면 null */
    val scheduledAtMillis: Long? = null,
) {
    enum class Status(val label: String) {
        NONE("없음"),
        AVAILABLE("설치 가능"),
        DOWNLOADING("내려받는 중"),
        DOWNLOADING_WIFI_WAIT("와이파이 대기"),
        SCHEDULED("설치 예약됨"),
        INSTALLING("설치 중"),
        UNKNOWN("알 수 없음"),
    }
}

/**
 * 한 시점의 차량 상태. 매크로 판정의 유일한 입력이다.
 * 아직 못 읽은 값은 null이며, 매크로는 null을 "조건 불충족"으로 다룬다.
 */
data class VehicleSnapshot(
    val timestampMillis: Long,
    /**
     * 카테고리별 마지막 실제 읽기 시각.
     * 낙관 표시(명령 직후 화면 선반영)를 "그 카테고리를 읽은 뒤"에만 거두기 위함 —
     * 전체 타임스탬프로 지우면 차체만 읽어도 공조 낙관값이 과거값으로 되돌아간다
     */
    val categoryReadAt: Map<StateCategory, Long> = emptyMap(),
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
    /** 충전 전류 설정값(A). 충전 화면 슬라이더의 현재 위치가 된다 */
    val chargingAmps: Int? = null,
    /**
     * 지금 물린 충전기가 받아주는 최대 전류(A).
     * 벽 콘센트냐 완속기냐에 따라 달라서, 상한을 코드에 박으면 절반이 헛값이 된다
     */
    val maxChargingAmps: Int? = null,
    val rangeKm: Float? = null,
    val isChargePortOpen: Boolean? = null,
    val speedKph: Float? = null,
    /** 차가 아니라 폴러가 잰다 — 탑승 중이면 지금까지, 하차 후엔 직전 세션 길이(분) */
    val rideMinutes: Double? = null,
    /** 타이어 공기압(bar). 못 읽은 자리는 아예 빠진다 — 0으로 채우지 않는다 */
    val tirePressuresBar: Map<TirePosition, Float> = emptyMap(),
    /**
     * 차가 스스로 보고한 자기 위치. 태블릿 GPS와 다른 값이다 —
     * 태블릿은 차 안에 있지만 차에서 내려 들고 나올 수도 있다
     */
    val vehicleLatitude: Double? = null,
    val vehicleLongitude: Double? = null,
    /** 차량 소프트웨어 업데이트 상태. 앱 업데이트와 별개다 */
    val softwareUpdate: VehicleSoftwareUpdate? = null,
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

    /** 타이어 공기압. 자주 안 바뀌므로 상시 폴링 대상이 아니다 */
    TIRES("타이어", needsInfotainment = true),

    /** 차가 보고하는 자기 위치 */
    LOCATION("차량 위치", needsInfotainment = true),

    /** 차량 소프트웨어 업데이트. 몇 시간에 한 번이면 충분하다 */
    SOFTWARE("차량 소프트웨어", needsInfotainment = true),
}

/**
 * [fresh]에서 실제로 읽힌 값만 이 스냅샷 위에 얹는다.
 *
 * null은 "안 읽었다"는 뜻이라 기존 값을 덮지 않는다 — 카테고리별로 따로 도착하는
 * 상태를 합칠 때 이 규칙이 없으면 방금 읽은 값이 서로를 지운다.
 *
 * 병합을 여기 한 곳에 둔다. 게이트웨이와 폴러가 따로 갖고 있으면
 * 새 필드를 추가할 때 한쪽이 빠져 값이 조용히 사라진다.
 */
fun VehicleSnapshot.overlay(fresh: VehicleSnapshot): VehicleSnapshot = copy(
    timestampMillis = fresh.timestampMillis,
    insideTempC = fresh.insideTempC ?: insideTempC,
    outsideTempC = fresh.outsideTempC ?: outsideTempC,
    driverTempSettingC = fresh.driverTempSettingC ?: driverTempSettingC,
    isClimateOn = fresh.isClimateOn ?: isClimateOn,
    isPreconditioning = fresh.isPreconditioning ?: isPreconditioning,
    isUserPresent = fresh.isUserPresent ?: isUserPresent,
    isLocked = fresh.isLocked ?: isLocked,
    shiftState = if (fresh.shiftState != ShiftState.UNKNOWN) fresh.shiftState else shiftState,
    doorOpen = doorOpen + fresh.doorOpen,
    seatHeater = seatHeater + fresh.seatHeater,
    seatCooler = seatCooler + fresh.seatCooler,
    batteryLevelPercent = fresh.batteryLevelPercent ?: batteryLevelPercent,
    isCharging = fresh.isCharging ?: isCharging,
    chargeLimitPercent = fresh.chargeLimitPercent ?: chargeLimitPercent,
    chargingAmps = fresh.chargingAmps ?: chargingAmps,
    maxChargingAmps = fresh.maxChargingAmps ?: maxChargingAmps,
    rangeKm = fresh.rangeKm ?: rangeKm,
    isChargePortOpen = fresh.isChargePortOpen ?: isChargePortOpen,
    speedKph = fresh.speedKph ?: speedKph,
    rideMinutes = fresh.rideMinutes ?: rideMinutes,
    tirePressuresBar = tirePressuresBar + fresh.tirePressuresBar,
    vehicleLatitude = fresh.vehicleLatitude ?: vehicleLatitude,
    vehicleLongitude = fresh.vehicleLongitude ?: vehicleLongitude,
    softwareUpdate = fresh.softwareUpdate ?: softwareUpdate,
)

/**
 * 이 아래면 눈으로 봐야 하는 공기압(bar).
 * Model Y 권장이 2.9bar 안팎이라 여기서 약 15% 빠진 지점을 기준으로 둔다 —
 * TPMS 경고등(-25%)보다 먼저 알려줘야 주유소 가는 길에 채울 수 있다.
 */
const val LOW_TIRE_PRESSURE_BAR = 2.5f

/** 공기압이 기준 아래인 바퀴. 못 읽은 자리는 포함되지 않는다 */
val VehicleSnapshot.lowTires: Set<TirePosition>
    get() = tirePressuresBar.filterValues { it < LOW_TIRE_PRESSURE_BAR }.keys
