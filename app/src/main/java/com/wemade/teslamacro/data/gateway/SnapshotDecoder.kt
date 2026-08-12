package com.wemade.teslamacro.data.gateway

import com.tesla.generated.carserver.server.CarServer
import com.tesla.generated.carserver.vehicle.Vehicle
import com.tesla.generated.vcsec.Vcsec
import com.wemade.teslamacro.domain.model.Door
import com.wemade.teslamacro.domain.model.Level
import com.wemade.teslamacro.domain.model.SeatPosition
import com.wemade.teslamacro.domain.model.ShiftState
import com.wemade.teslamacro.domain.model.VehicleSnapshot

/**
 * 차량 응답을 앱 스냅샷으로 옮긴다.
 *
 * 차량은 지원하지 않는 항목을 **아예 안 보낸다** (통풍 없는 트림 등).
 * 그래서 `hasX()`로 확인하고 없으면 null을 유지한다.
 * 없는 값을 0이나 false로 채우면 매크로가 엉뚱하게 발동한다.
 */
object SnapshotDecoder {

    /** 인포테인먼트 공조 응답 */
    fun fromClimateResponse(responseBytes: ByteArray, nowMillis: Long): VehicleSnapshot {
        val climate = CarServer.Response.parseFrom(responseBytes).vehicleData.climateState
        return VehicleSnapshot(
            timestampMillis = nowMillis,
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
    }

    /** 인포테인먼트 충전 응답 */
    fun fromChargeResponse(responseBytes: ByteArray, nowMillis: Long): VehicleSnapshot {
        val charge = CarServer.Response.parseFrom(responseBytes).vehicleData.chargeState
        return VehicleSnapshot(
            timestampMillis = nowMillis,
            batteryLevelPercent = charge.takeIf { it.hasBatteryLevel() }?.batteryLevel,
            isCharging = charge.takeIf { it.hasChargerPower() }?.let { it.chargerPower > 0 },
            chargeLimitPercent = charge.takeIf { it.hasChargeLimitSoc() }?.chargeLimitSoc,
            chargingAmps = charge.takeIf { it.hasChargingAmps() }?.chargingAmps,
            rangeKm = charge.takeIf { it.hasBatteryRange() }
                ?.let { it.batteryRange * MILES_TO_KM },
            isChargePortOpen = charge.takeIf { it.hasChargePortDoorOpen() }?.chargePortDoorOpen,
        )
    }

    /**
     * 인포테인먼트 주행 응답.
     * 기어를 못 읽으면 "주차 중" 조건이 영원히 불충족이라 매크로가 안 돈다.
     */
    fun fromDriveResponse(responseBytes: ByteArray, nowMillis: Long): VehicleSnapshot {
        val drive = CarServer.Response.parseFrom(responseBytes).vehicleData.driveState
        val shift = drive.shiftState
        return VehicleSnapshot(
            timestampMillis = nowMillis,
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
