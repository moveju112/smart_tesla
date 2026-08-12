package com.wemade.teslamacro.data.gateway

import com.tesla.generated.carserver.common.Common
import com.tesla.generated.carserver.server.CarServer
import com.tesla.generated.carserver.server.CarServer.HvacSeatCoolerActions
import com.tesla.generated.carserver.server.CarServer.HvacSeatHeaterActions
import com.tesla.generated.carserver.vehicle.Vehicle
import com.tesla.generated.vcsec.Vcsec
import com.wemade.teslamacro.domain.command.ClimateKeeperMode
import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.domain.model.Level
import com.wemade.teslamacro.domain.model.SeatPosition

/** 명령이 어느 도메인으로 가는지 + 실제 페이로드 */
sealed interface EncodedCommand {
    /** 인포테인먼트: 공조·시트·충전·미디어 */
    data class Infotainment(val action: CarServer.Action) : EncodedCommand

    /** VCSEC: 잠금·리모트 시동. 차가 자고 있어도 받는다 */
    data class Vehicle(val message: Vcsec.UnsignedMessage) : EncodedCommand
}

/**
 * 도메인 명령을 Tesla protobuf로 바꾼다.
 *
 * **확장 지점 2/3의 나머지 절반**: `VehicleCommand`에 타입을 추가하면
 * 여기 `when`에 분기 하나만 더하면 된다. 다른 파일은 건드릴 일이 없다.
 */
object CommandEncoder {

    fun encode(command: VehicleCommand): EncodedCommand = when (command) {

        // ---- 공조 ----
        is VehicleCommand.ClimateOn -> action {
            hvacAutoAction = CarServer.HvacAutoAction.newBuilder().setPowerOn(true).build()
        }

        is VehicleCommand.ClimateOff -> action {
            hvacAutoAction = CarServer.HvacAutoAction.newBuilder().setPowerOn(false).build()
        }

        is VehicleCommand.SetTemperature -> action {
            // 좌우 온도를 같이 맞춘다. 분리 제어가 필요해지면 zone을 인자로 뺀다
            hvacTemperatureAdjustmentAction =
                CarServer.HvacTemperatureAdjustmentAction.newBuilder()
                    .setAbsoluteCelsius(command.celsius.toFloat())
                    .addHvacTemperatureZone(tempZone(frontLeft = true))
                    .addHvacTemperatureZone(tempZone(frontLeft = false))
                    .build()
        }

        is VehicleCommand.SetSeatCooler -> action {
            hvacSeatCoolerActions = HvacSeatCoolerActions.newBuilder()
                .addHvacSeatCoolerAction(
                    HvacSeatCoolerActions.HvacSeatCoolerAction.newBuilder()
                        .setSeatCoolerLevel(coolerLevel(command.level))
                        .setSeatPosition(coolerPosition(command.seat))
                )
                .build()
        }

        is VehicleCommand.SetSeatHeater -> action {
            hvacSeatHeaterActions = HvacSeatHeaterActions.newBuilder()
                .addHvacSeatHeaterAction(heaterAction(command.seat, command.level))
                .build()
        }

        is VehicleCommand.SetAutoSeatClimate -> action {
            autoSeatClimateAction = CarServer.AutoSeatClimateAction.newBuilder()
                .addCarseat(
                    CarServer.AutoSeatClimateAction.CarSeat.newBuilder()
                        .setOn(command.enabled)
                        .setSeatPosition(autoSeatPosition(command.seat))
                )
                .build()
        }

        is VehicleCommand.SetSteeringWheelHeater -> action {
            hvacSteeringWheelHeaterAction =
                CarServer.HvacSteeringWheelHeaterAction.newBuilder()
                    .setPowerOn(command.enabled)
                    .build()
        }

        // ---- 개폐 ----
        is VehicleCommand.VentWindows -> action {
            vehicleControlWindowAction = CarServer.VehicleControlWindowAction.newBuilder()
                .setVent(void())
                .build()
        }

        is VehicleCommand.CloseWindows -> action {
            vehicleControlWindowAction = CarServer.VehicleControlWindowAction.newBuilder()
                .setClose(void())
                .build()
        }

        is VehicleCommand.SetClimateKeeper -> action {
            hvacClimateKeeperAction = CarServer.HvacClimateKeeperAction.newBuilder()
                .setClimateKeeperAction(climateKeeperMode(command.mode))
                .build()
        }

        is VehicleCommand.SetCabinOverheatProtection -> action {
            setCabinOverheatProtectionAction =
                CarServer.SetCabinOverheatProtectionAction.newBuilder()
                    .setOn(command.enabled)
                    .setFanOnly(command.fanOnly)
                    .build()
        }

        // ---- 적재 공간 (VCSEC 도어 제어) ----
        is VehicleCommand.OpenTrunk -> closure { rearTrunk = Vcsec.ClosureMoveType_E.CLOSURE_MOVE_TYPE_OPEN }
        is VehicleCommand.CloseTrunk -> closure { rearTrunk = Vcsec.ClosureMoveType_E.CLOSURE_MOVE_TYPE_CLOSE }
        is VehicleCommand.OpenFrunk -> closure { frontTrunk = Vcsec.ClosureMoveType_E.CLOSURE_MOVE_TYPE_OPEN }

        is VehicleCommand.SetChargePort -> action {
            if (command.open) {
                chargePortDoorOpen = CarServer.ChargePortDoorOpen.newBuilder().build()
            } else {
                chargePortDoorClose = CarServer.ChargePortDoorClose.newBuilder().build()
            }
        }

        // ---- 보안 ----
        is VehicleCommand.SetSentryMode -> action {
            vehicleControlSetSentryModeAction =
                CarServer.VehicleControlSetSentryModeAction.newBuilder()
                    .setOn(command.enabled)
                    .build()
        }

        is VehicleCommand.FlashLights -> action {
            vehicleControlFlashLightsAction =
                CarServer.VehicleControlFlashLightsAction.newBuilder().build()
        }

        is VehicleCommand.Honk -> action {
            vehicleControlHonkHornAction =
                CarServer.VehicleControlHonkHornAction.newBuilder().build()
        }

        // ---- 충전 ----
        is VehicleCommand.SetChargeLimit -> action {
            chargingSetLimitAction = CarServer.ChargingSetLimitAction.newBuilder()
                .setPercent(command.percent)
                .build()
        }

        is VehicleCommand.SetCharging -> action {
            chargingStartStopAction = CarServer.ChargingStartStopAction.newBuilder()
                .apply { if (command.start) setStartStandard(void()) else setStop(void()) }
                .build()
        }

        // ---- 미디어 ----
        is VehicleCommand.ToggleMedia -> action {
            mediaPlayAction = CarServer.MediaPlayAction.newBuilder().build()
        }

        is VehicleCommand.NextTrack -> action {
            mediaNextTrack = CarServer.MediaNextTrack.newBuilder().build()
        }

        is VehicleCommand.PreviousTrack -> action {
            mediaPreviousTrack = CarServer.MediaPreviousTrack.newBuilder().build()
        }

        is VehicleCommand.NextFavorite -> action {
            mediaNextFavorite = CarServer.MediaNextFavorite.newBuilder().build()
        }

        is VehicleCommand.SetVolume -> action {
            mediaUpdateVolume = CarServer.MediaUpdateVolume.newBuilder()
                .setVolumeAbsoluteFloat(command.level.toFloat())
                .build()
        }

        // ---- 접근 제어 ----
        is VehicleCommand.SetValetMode -> action {
            vehicleControlSetValetModeAction =
                CarServer.VehicleControlSetValetModeAction.newBuilder()
                    .setOn(command.enabled)
                    .setPassword(command.pin)
                    .build()
        }

        is VehicleCommand.SetGuestMode -> action {
            guestModeAction = Vehicle.VehicleState.GuestMode.newBuilder()
                .setGuestModeActive(command.enabled)
                .build()
        }

        // ---- 전원 관리 ----
        is VehicleCommand.SetLowPowerMode -> action {
            setLowPowerModeAction = CarServer.SetLowPowerModeAction.newBuilder()
                .setLowPowerMode(command.enabled)
                .build()
        }

        is VehicleCommand.SetKeepAccessoryPower -> action {
            setKeepAccessoryPowerModeAction =
                CarServer.SetKeepAccessoryPowerModeAction.newBuilder()
                    .setKeepAccessoryPowerMode(command.enabled)
                    .build()
        }

        is VehicleCommand.SetChargingAmps -> action {
            setChargingAmpsAction = CarServer.SetChargingAmpsAction.newBuilder()
                .setChargingAmps(command.amps)
                .build()
        }

        // ---- VCSEC (차량이 자고 있어도 받는 명령) ----
        is VehicleCommand.Lock -> rke(Vcsec.RKEAction_E.RKE_ACTION_LOCK)
        is VehicleCommand.Unlock -> rke(Vcsec.RKEAction_E.RKE_ACTION_UNLOCK)
        is VehicleCommand.Wake -> rke(Vcsec.RKEAction_E.RKE_ACTION_WAKE_VEHICLE)
    }

    /** 상태 읽기 요청도 인포테인먼트 액션이다 */
    fun encodeClimateStateRequest(): CarServer.Action = wrap(
        CarServer.VehicleAction.newBuilder()
            .setGetVehicleData(
                CarServer.GetVehicleData.newBuilder()
                    .setGetClimateState(CarServer.GetClimateState.newBuilder())
            )
            .build()
    )

    /** 기어·속도·주행거리. PARKED 조건이 이 값을 쓴다 */
    fun encodeDriveStateRequest(): CarServer.Action = wrap(
        CarServer.VehicleAction.newBuilder()
            .setGetVehicleData(
                CarServer.GetVehicleData.newBuilder()
                    .setGetDriveState(CarServer.GetDriveState.newBuilder())
            )
            .build()
    )

    fun encodeChargeStateRequest(): CarServer.Action = wrap(
        CarServer.VehicleAction.newBuilder()
            .setGetVehicleData(
                CarServer.GetVehicleData.newBuilder()
                    .setGetChargeState(CarServer.GetChargeState.newBuilder())
            )
            .build()
    )

    /** VCSEC 차체 상태 요청 — 차가 자고 있어도 응답한다 */
    fun encodeBodyControllerStateRequest(): Vcsec.UnsignedMessage =
        Vcsec.UnsignedMessage.newBuilder()
            .setInformationRequest(
                Vcsec.InformationRequest.newBuilder()
                    .setInformationRequestType(
                        Vcsec.InformationRequestType.INFORMATION_REQUEST_TYPE_GET_STATUS
                    )
            )
            .build()

    // ---- 조립 도우미 ----

    private fun action(build: CarServer.VehicleAction.Builder.() -> Unit): EncodedCommand =
        EncodedCommand.Infotainment(
            wrap(CarServer.VehicleAction.newBuilder().apply(build).build())
        )

    private fun wrap(vehicleAction: CarServer.VehicleAction): CarServer.Action =
        CarServer.Action.newBuilder().setVehicleAction(vehicleAction).build()

    private fun rke(action: Vcsec.RKEAction_E): EncodedCommand =
        EncodedCommand.Vehicle(Vcsec.UnsignedMessage.newBuilder().setRKEAction(action).build())

    private fun closure(build: Vcsec.ClosureMoveRequest.Builder.() -> Unit): EncodedCommand =
        EncodedCommand.Vehicle(
            Vcsec.UnsignedMessage.newBuilder()
                .setClosureMoveRequest(Vcsec.ClosureMoveRequest.newBuilder().apply(build))
                .build()
        )

    private fun climateKeeperMode(mode: ClimateKeeperMode) = when (mode) {
        ClimateKeeperMode.OFF ->
            CarServer.HvacClimateKeeperAction.ClimateKeeperAction_E.ClimateKeeperAction_Off
        ClimateKeeperMode.ON ->
            CarServer.HvacClimateKeeperAction.ClimateKeeperAction_E.ClimateKeeperAction_On
        ClimateKeeperMode.DOG ->
            CarServer.HvacClimateKeeperAction.ClimateKeeperAction_E.ClimateKeeperAction_Dog
        ClimateKeeperMode.CAMP ->
            CarServer.HvacClimateKeeperAction.ClimateKeeperAction_E.ClimateKeeperAction_Camp
    }

    private fun void(): Common.Void = Common.Void.newBuilder().build()

    private fun tempZone(frontLeft: Boolean) =
        CarServer.HvacTemperatureAdjustmentAction.HvacTemperatureZone.newBuilder()
            .apply {
                if (frontLeft) setTEMPZONEFRONTLEFT(void()) else setTEMPZONEFRONTRIGHT(void())
            }
            .build()

    private fun coolerLevel(level: Level) = when (level) {
        Level.OFF -> HvacSeatCoolerActions.HvacSeatCoolerLevel_E.HvacSeatCoolerLevel_Off
        Level.LOW -> HvacSeatCoolerActions.HvacSeatCoolerLevel_E.HvacSeatCoolerLevel_Low
        Level.MEDIUM -> HvacSeatCoolerActions.HvacSeatCoolerLevel_E.HvacSeatCoolerLevel_Med
        Level.HIGH -> HvacSeatCoolerActions.HvacSeatCoolerLevel_E.HvacSeatCoolerLevel_High
    }

    private fun coolerPosition(seat: SeatPosition) = when (seat) {
        SeatPosition.FRONT_LEFT ->
            HvacSeatCoolerActions.HvacSeatCoolerPosition_E.HvacSeatCoolerPosition_FrontLeft
        SeatPosition.FRONT_RIGHT ->
            HvacSeatCoolerActions.HvacSeatCoolerPosition_E.HvacSeatCoolerPosition_FrontRight
        // 통풍은 앞좌석만 지원한다. 뒷좌석 요청은 도메인 단계에서 막혀야 한다
        else -> error("${seat.label}은 통풍을 지원하지 않아요")
    }

    private fun heaterAction(seat: SeatPosition, level: Level) =
        HvacSeatHeaterActions.HvacSeatHeaterAction.newBuilder()
            .apply {
                when (level) {
                    Level.OFF -> setSEATHEATEROFF(void())
                    Level.LOW -> setSEATHEATERLOW(void())
                    Level.MEDIUM -> setSEATHEATERMED(void())
                    Level.HIGH -> setSEATHEATERHIGH(void())
                }
                when (seat) {
                    SeatPosition.FRONT_LEFT -> setCARSEATFRONTLEFT(void())
                    SeatPosition.FRONT_RIGHT -> setCARSEATFRONTRIGHT(void())
                    SeatPosition.REAR_LEFT -> setCARSEATREARLEFT(void())
                    SeatPosition.REAR_CENTER -> setCARSEATREARCENTER(void())
                    SeatPosition.REAR_RIGHT -> setCARSEATREARRIGHT(void())
                }
            }
            .build()

    private fun autoSeatPosition(seat: SeatPosition) = when (seat) {
        SeatPosition.FRONT_LEFT ->
            CarServer.AutoSeatClimateAction.AutoSeatPosition_E.AutoSeatPosition_FrontLeft
        SeatPosition.FRONT_RIGHT ->
            CarServer.AutoSeatClimateAction.AutoSeatPosition_E.AutoSeatPosition_FrontRight
        else -> error("${seat.label}은 자동 시트 온도를 지원하지 않아요")
    }
}
