package com.wemade.teslamacro.data.gateway

import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.domain.gateway.EnrollmentState
import com.wemade.teslamacro.domain.gateway.LinkState
import com.wemade.teslamacro.domain.gateway.VehicleGateway
import com.wemade.teslamacro.domain.model.Door
import com.wemade.teslamacro.domain.model.Level
import com.wemade.teslamacro.domain.model.SeatPosition
import com.wemade.teslamacro.domain.model.ShiftState
import com.wemade.teslamacro.domain.model.StateCategory
import com.wemade.teslamacro.domain.model.VehicleSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 차 없이 UI와 매크로를 돌려보기 위한 가짜 차량.
 *
 * 프로토콜이 완성되기 전에도 화면·매크로 흐름을 끝까지 검증하려고 둔다.
 * 실차 연결이 붙은 뒤에도 회귀 확인용으로 남긴다.
 */
class SimulatedVehicleGateway : VehicleGateway {

    private val _linkState = MutableStateFlow<LinkState>(LinkState.Idle)
    override val linkState: StateFlow<LinkState> = _linkState.asStateFlow()

    private val _enrollmentState = MutableStateFlow<EnrollmentState>(EnrollmentState.NotEnrolled)
    override val enrollmentState: StateFlow<EnrollmentState> = _enrollmentState.asStateFlow()

    private val state = MutableStateFlow(
        VehicleSnapshot(
            timestampMillis = 0L,
            insideTempC = 31.5,
            outsideTempC = 29.0,
            driverTempSettingC = 22.0,
            isClimateOn = false,
            isUserPresent = false,
            isLocked = true,
            shiftState = ShiftState.PARK,
            doorOpen = Door.entries.associateWith { false },
            seatHeater = SeatPosition.entries.associateWith { Level.OFF },
            seatCooler = SeatPosition.entries.associateWith { Level.OFF },
            batteryLevelPercent = 72,
            isCharging = false,
            chargeLimitPercent = 80,
            chargingAmps = 16,
            rangeKm = 340f,
            isChargePortOpen = false,
            speedKph = 0f,
        )
    )

    /** 조작판이 현재 값을 보여주기 위해 읽는다 */
    val current: StateFlow<VehicleSnapshot> = state.asStateFlow()

    fun setInsideTemp(celsius: Double) = state.update { it.copy(insideTempC = celsius) }

    fun setOutsideTemp(celsius: Double) = state.update { it.copy(outsideTempC = celsius) }

    /** 데모 버튼에서 탑승 상황을 흉내 낼 때 쓴다 */
    fun simulateBoarding() = state.update {
        it.copy(
            doorOpen = it.doorOpen + (Door.DRIVER_FRONT to true),
            isUserPresent = true,
            isLocked = false,
        )
    }

    fun simulateLeaving() = state.update {
        it.copy(
            doorOpen = it.doorOpen + (Door.DRIVER_FRONT to false),
            isUserPresent = false,
        )
    }

    override suspend fun connect(vin: String, allowProbe: Boolean): Result<Unit> {
        _linkState.value = LinkState.Scanning
        delay(600)
        _linkState.value = LinkState.Connecting(rssi = -58)
        delay(400)
        _linkState.value = LinkState.Ready
        _enrollmentState.value = EnrollmentState.Enrolled
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        _linkState.value = LinkState.Idle
    }

    override suspend fun requestKeyEnrollment(): Result<Unit> {
        _enrollmentState.value = EnrollmentState.AwaitingCardTap
        delay(2_000)
        _enrollmentState.value = EnrollmentState.Enrolled
        return Result.success(Unit)
    }

    override suspend fun send(command: VehicleCommand): Result<Unit> {
        delay(250)   // 실제 BLE 왕복 지연 흉내
        state.update { current ->
            when (command) {
                is VehicleCommand.ClimateOn -> current.copy(isClimateOn = true)
                is VehicleCommand.ClimateOff -> current.copy(isClimateOn = false)
                is VehicleCommand.SetTemperature -> current.copy(driverTempSettingC = command.celsius)
                is VehicleCommand.SetSeatCooler ->
                    current.copy(seatCooler = current.seatCooler + (command.seat to command.level))
                is VehicleCommand.SetSeatHeater ->
                    current.copy(seatHeater = current.seatHeater + (command.seat to command.level))
                is VehicleCommand.Lock -> current.copy(isLocked = true)
                is VehicleCommand.Unlock -> current.copy(isLocked = false)
                else -> current
            }
        }
        return Result.success(Unit)
    }

    override suspend fun read(category: StateCategory): Result<VehicleSnapshot> {
        delay(120)
        // 공조가 켜져 있으면 실내 온도가 목표를 향해 조금씩 내려간다
        state.update { current ->
            val target = current.driverTempSettingC ?: return@update current
            val inside = current.insideTempC ?: return@update current
            if (current.isClimateOn != true) current
            else current.copy(insideTempC = inside + (target - inside).coerceIn(-0.4, 0.4))
        }
        return Result.success(state.value.copy(timestampMillis = System.currentTimeMillis()))
    }
}
