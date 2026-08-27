package com.wemade.teslamacro.data.gateway

import com.tesla.generated.carserver.server.CarServer
import com.tesla.generated.carserver.vehicle.Vehicle
import com.tesla.generated.vcsec.Vcsec
import com.wemade.teslamacro.domain.model.Door
import com.wemade.teslamacro.domain.model.Level
import com.wemade.teslamacro.domain.model.SeatPosition
import com.wemade.teslamacro.domain.model.ShiftState
import com.wemade.teslamacro.domain.model.TirePosition
import com.wemade.teslamacro.domain.model.VehicleSoftwareUpdate
import com.wemade.teslamacro.domain.model.VehicleSnapshot

/**
 * 차량 응답을 앱 스냅샷으로 옮긴다.
 *
 * 차량은 지원하지 않는 항목을 **아예 안 보낸다** (통풍 없는 트림 등).
 * 그래서 `hasX()`로 확인하고 없으면 null을 유지한다.
 * 없는 값을 0이나 false로 채우면 매크로가 엉뚱하게 발동한다.
 */
object SnapshotDecoder {

    /**
     * 인포테인먼트 상태 응답을 통째로 읽는다.
     *
     * 한 응답에 여러 상태가 함께 올 수 있어(묶음 조회) 카테고리를 가리지 않고
     * 들어 있는 것만 채운다 — 안 온 상태는 기본 인스턴스라 `hasX()`가 전부 false고
     * 해당 필드는 null로 남는다. "못 읽은 값은 없는 값"이 그대로 지켜진다.
     */
    fun fromVehicleData(responseBytes: ByteArray, nowMillis: Long): VehicleSnapshot {
        val data = CarServer.Response.parseFrom(responseBytes).vehicleData
        return VehicleSnapshot(timestampMillis = nowMillis)
            .withClimate(data.climateState)
            .withCharge(data.chargeState)
            .withDrive(data.driveState)
            .withTires(data.tirePressureState)
            .withLocation(data.locationState)
            .withSoftwareUpdate(data.softwareUpdateState)
    }

    private fun VehicleSnapshot.withClimate(climate: Vehicle.ClimateState): VehicleSnapshot = copy(
        insideTempC = climate.takeIf { it.hasInsideTempCelsius() }?.insideTempCelsius?.toDouble(),
        outsideTempC = climate.takeIf { it.hasOutsideTempCelsius() }
            ?.outsideTempCelsius?.toDouble(),
        driverTempSettingC = climate.takeIf { it.hasDriverTempSetting() }
            ?.driverTempSetting?.toDouble(),
        isClimateOn = climate.takeIf { it.hasIsClimateOn() }?.isClimateOn,
        isPreconditioning = climate.takeIf { it.hasIsPreconditioning() }?.isPreconditioning,
        seatHeater = buildMap {
            if (climate.hasSeatHeaterLeft()) {
                put(SeatPosition.FRONT_LEFT, Level.fromStep(climate.seatHeaterLeft))
            }
            if (climate.hasSeatHeaterRight()) {
                put(SeatPosition.FRONT_RIGHT, Level.fromStep(climate.seatHeaterRight))
            }
        },
        seatCooler = buildMap {
            if (climate.hasSeatFanFrontLeft()) {
                put(SeatPosition.FRONT_LEFT, Level.fromStep(climate.seatFanFrontLeft))
            }
            if (climate.hasSeatFanFrontRight()) {
                put(SeatPosition.FRONT_RIGHT, Level.fromStep(climate.seatFanFrontRight))
            }
        },
    )

    private fun VehicleSnapshot.withCharge(charge: Vehicle.ChargeState): VehicleSnapshot = copy(
        batteryLevelPercent = charge.takeIf { it.hasBatteryLevel() }?.batteryLevel,
        isCharging = charge.takeIf { it.hasChargerPower() }?.let { it.chargerPower > 0 },
        chargeLimitPercent = charge.takeIf { it.hasChargeLimitSoc() }?.chargeLimitSoc,
        chargingAmps = charge.takeIf { it.hasChargingAmps() }?.chargingAmps,
        maxChargingAmps = charge.takeIf { it.hasChargeCurrentRequestMax() }?.chargeCurrentRequestMax,
        rangeKm = charge.takeIf { it.hasBatteryRange() }?.let { it.batteryRange * MILES_TO_KM },
        isChargePortOpen = charge.takeIf { it.hasChargePortDoorOpen() }?.chargePortDoorOpen,
    )

    /** 기어를 못 읽으면 "주차 중" 조건이 영원히 불충족이라 매크로가 안 돈다 */
    private fun VehicleSnapshot.withDrive(drive: Vehicle.DriveState): VehicleSnapshot {
        val shift = drive.shiftState
        return copy(
            shiftState = when {
                shift.hasP() -> ShiftState.PARK
                shift.hasR() -> ShiftState.REVERSE
                shift.hasN() -> ShiftState.NEUTRAL
                shift.hasD() -> ShiftState.DRIVE
                else -> ShiftState.UNKNOWN
            },
            speedKph = drive.takeIf { it.hasSpeedFloat() }?.let { it.speedFloat * MILES_TO_KM },
        )
    }

    /** 타이어는 자리마다 따로 온다. 못 읽은 자리는 지도에서 빠진다 */
    private fun VehicleSnapshot.withTires(tires: Vehicle.TirePressureState): VehicleSnapshot = copy(
        tirePressuresBar = buildMap {
            if (tires.hasTpmsPressureFl()) put(TirePosition.FRONT_LEFT, tires.tpmsPressureFl)
            if (tires.hasTpmsPressureFr()) put(TirePosition.FRONT_RIGHT, tires.tpmsPressureFr)
            if (tires.hasTpmsPressureRl()) put(TirePosition.REAR_LEFT, tires.tpmsPressureRl)
            if (tires.hasTpmsPressureRr()) put(TirePosition.REAR_RIGHT, tires.tpmsPressureRr)
        },
    )

    private fun VehicleSnapshot.withLocation(
        location: Vehicle.LocationState,
    ): VehicleSnapshot = copy(
        vehicleLatitude = location.takeIf { it.hasLatitude() }?.latitude?.toDouble(),
        vehicleLongitude = location.takeIf { it.hasLongitude() }?.longitude?.toDouble(),
    )

    /**
     * 차량 소프트웨어 상태. 상태를 못 읽으면 null로 두고 화면에서 `--`로 나간다 —
     * "없음"으로 단정하면 설치 예약이 걸린 걸 못 보고 지나친다.
     */
    private fun VehicleSnapshot.withSoftwareUpdate(
        update: Vehicle.SoftwareUpdateState,
    ): VehicleSnapshot {
        val status = update.status
        val known = when {
            status.hasInstalling() -> VehicleSoftwareUpdate.Status.INSTALLING
            status.hasScheduled() -> VehicleSoftwareUpdate.Status.SCHEDULED
            status.hasAvailable() -> VehicleSoftwareUpdate.Status.AVAILABLE
            status.hasDownloading() -> VehicleSoftwareUpdate.Status.DOWNLOADING
            status.hasDownloadingWifiWait() -> VehicleSoftwareUpdate.Status.DOWNLOADING_WIFI_WAIT
            status.hasUnknown() -> VehicleSoftwareUpdate.Status.UNKNOWN
            else -> return this   // 상태 자체가 안 왔다 — 이 카테고리를 안 읽은 것이다
        }
        return copy(
            softwareUpdate = VehicleSoftwareUpdate(
                status = known,
                version = update.version.takeIf { it.isNotBlank() },
                downloadPercent = update.takeIf { it.hasDownloadPerc() }?.downloadPerc,
                scheduledAtMillis = update.takeIf { it.hasScheduledTimeMs() }
                    ?.scheduledTimeMs?.takeIf { it > 0 },
            ),
        )
    }

    private const val MILES_TO_KM = 1.60934f

    /**
     * VCSEC 차체 상태 응답.
     * 차가 자고 있어도 오는 유일한 상태라 상시 감시는 이것만 쓴다.
     */
    fun fromVcsecStatus(message: Vcsec.FromVCSECMessage, nowMillis: Long): VehicleSnapshot {
        val status = message.vehicleStatus
        val closures = status.closureStatuses

        return VehicleSnapshot(
            timestampMillis = nowMillis,
            isUserPresent = when (status.userPresence) {
                Vcsec.UserPresence_E.VEHICLE_USER_PRESENCE_PRESENT -> true
                Vcsec.UserPresence_E.VEHICLE_USER_PRESENCE_NOT_PRESENT -> false
                else -> null   // UNKNOWN은 "없음"이 아니라 "모름"이다
            },
            isLocked = when (status.vehicleLockState) {
                Vcsec.VehicleLockState_E.VEHICLELOCKSTATE_LOCKED,
                Vcsec.VehicleLockState_E.VEHICLELOCKSTATE_INTERNAL_LOCKED -> true
                Vcsec.VehicleLockState_E.VEHICLELOCKSTATE_UNLOCKED,
                Vcsec.VehicleLockState_E.VEHICLELOCKSTATE_SELECTIVE_UNLOCKED -> false
                else -> null
            },
            doorOpen = buildMap {
                putOpen(Door.DRIVER_FRONT, closures.frontDriverDoor)
                putOpen(Door.PASSENGER_FRONT, closures.frontPassengerDoor)
                putOpen(Door.DRIVER_REAR, closures.rearDriverDoor)
                putOpen(Door.PASSENGER_REAR, closures.rearPassengerDoor)
                putOpen(Door.TRUNK, closures.rearTrunk)
                putOpen(Door.FRUNK, closures.frontTrunk)
            },
        )
    }

    /** CLOSED가 아니면 열린 것으로 본다. UNKNOWN은 판단하지 않는다 */
    private fun MutableMap<Door, Boolean>.putOpen(door: Door, state: Vcsec.ClosureState_E) {
        when (state) {
            Vcsec.ClosureState_E.CLOSURESTATE_UNKNOWN,
            Vcsec.ClosureState_E.UNRECOGNIZED -> Unit
            Vcsec.ClosureState_E.CLOSURESTATE_CLOSED -> put(door, false)
            else -> put(door, true)
        }
    }
}
