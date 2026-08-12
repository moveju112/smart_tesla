package com.wemade.teslamacro.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wemade.teslamacro.di.AppContainer
import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.domain.model.Level
import com.wemade.teslamacro.domain.model.SeatPosition
import com.wemade.teslamacro.domain.model.SeatMode
import com.wemade.teslamacro.domain.model.SeatClimate
import com.wemade.teslamacro.domain.model.VehicleSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 차량이 아직 확인해주지 않은, 화면에만 먼저 반영한 값.
 *
 * BLE 왕복 + 다음 폴링까지 최대 수십 초가 걸린다.
 * 그동안 화면이 안 바뀌면 사용자는 버튼이 안 먹은 줄 알고 다시 누른다.
 */
private data class Optimistic(
    val seatCooler: Map<SeatPosition, Level> = emptyMap(),
    val seatHeater: Map<SeatPosition, Level> = emptyMap(),
    val isClimateOn: Boolean? = null,
    val targetTemp: Double? = null,
    val isLocked: Boolean? = null,
    /** 언제 덮어썼는지. 이 시각 이후에 읽은 값이 오면 낙관 반영을 거둔다 */
    val appliedAtMillis: Long = 0L,
) {
    val isEmpty: Boolean
        get() = seatCooler.isEmpty() && seatHeater.isEmpty() &&
            isClimateOn == null && targetTemp == null && isLocked == null

    /** 차량이 실제로 보고한 값 위에 덮어쓴다 */
    fun applyTo(snapshot: VehicleSnapshot) = snapshot.copy(
        seatCooler = snapshot.seatCooler + seatCooler,
        seatHeater = snapshot.seatHeater + seatHeater,
        isClimateOn = isClimateOn ?: snapshot.isClimateOn,
        driverTempSettingC = targetTemp ?: snapshot.driverTempSettingC,
        isLocked = isLocked ?: snapshot.isLocked,
    )
}

class DashboardViewModel(private val container: AppContainer) : ViewModel() {

    private val optimistic = MutableStateFlow(Optimistic())
    private val pending = MutableStateFlow<VehicleCommand?>(null)
    private val error = MutableStateFlow<String?>(null)

    /** combine 인자 한도를 넘지 않게 부수 상태를 한 묶음으로 나른다 */
    private data class Aux(
        val pending: VehicleCommand?,
        val error: String?,
        val seats: Map<SeatPosition, SeatClimate>,
    )

    init {
        // 명령을 보낸 뒤에 읽은 값이 도착하면 그때부터는 차량 값이 진실이다
        viewModelScope.launch {
            container.poller.snapshot.collect { snapshot ->
                val overlay = optimistic.value
                if (!overlay.isEmpty && snapshot.timestampMillis > overlay.appliedAtMillis) {
                    optimistic.value = Optimistic()
                }
            }
        }
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        container.gateway.linkState,
        container.poller.snapshot,
        container.settingsStore.settings,
        optimistic,
        combine(pending, error, container.seatStore.state) { p, e, s -> Aux(p, e, s) },
    ) { link, snapshot, settings, overlay, aux ->
        val effective = overlay.applyTo(snapshot)
        DashboardUiState(
            seatClimate = seatClimateOf(effective, aux.seats),
            link = link,
            vehicleName = settings.vehicleName.ifBlank { if (settings.isPaired) "내 테슬라" else "차량 미등록" },
            insideTemp = effective.insideTempC.format(),
            outsideTemp = effective.outsideTempC.format(),
            targetTemp = effective.driverTempSettingC.format(),
            targetTempValue = effective.driverTempSettingC,
            isClimateOn = effective.isClimateOn == true,
            isLocked = effective.isLocked == true,
            seatCooler = effective.seatCooler,
            seatHeater = effective.seatHeater,
            isSimulated = container.isSimulated,
            // 상태를 한 번도 못 읽었으면 "0"이 아니라 "읽는 중"으로 보여야 한다
            hasReading = snapshot.timestampMillis > 0L,
            pendingCommand = aux.pending,
            errorMessage = aux.error,
            secondsSinceReading = snapshot.timestampMillis
                .takeIf { it > 0L }
                ?.let { (System.currentTimeMillis() - it) / 1000 },
            batteryPercent = effective.batteryLevelPercent,
            automationEnabled = settings.automationEnabled,
            runningMacroCount = container.runner.running.value.size,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = initialState(),
    )

    /**
     * 사용자가 직접 누른 명령.
     * 실행 중인 매크로가 있으면 먼저 멈춘다 — 사람 조작이 항상 우선이다.
     */
    fun send(command: VehicleCommand) {
        viewModelScope.launch {
            container.runner.cancelAll()

            // 1. 화면에 먼저 반영해 반응을 즉시 보여준다
            applyOptimistic(command)
            pending.value = command
            error.value = null

            // 2. 실제 전송. 실패하면 낙관 반영을 되돌린다
            val result = container.gateway.send(command)
            pending.value = null

            if (result.isFailure) {
                revertOptimistic(command)
                error.value = "${command.label} 실패 — " +
                    (result.exceptionOrNull()?.message ?: "원인 불명")
            }
        }
    }

    /**
     * 좌석 하나의 통풍/열선을 설정한다.
     *
     * 통풍/열선은 동시에 켤 수 없으므로 항상 반대 모드를 끈다.
     * 운전석/동승석은 따로 조작한다 — 동승석이 비어 있는데 같이 켜지면 낭비다.
     * 고른 값은 먼저 기기에 저장(클라 기준)하고, 그 뒤 차량에 전송한다.
     */
    fun setSeatClimate(seat: SeatPosition, mode: SeatMode, level: Level) {
        viewModelScope.launch {
            // 1. 클라 저장 — 화면은 이 값을 즉시 따른다
            container.seatStore.set(seat, SeatClimate(mode, level))

            // 2. 사람 조작이 우선이라 매크로는 멈춘다
            container.runner.cancelAll()
            error.value = null

            // 3. 상호배타로 전송 (반대 모드는 끈다)
            val commands = when {
                level == Level.OFF -> listOf(
                    VehicleCommand.SetSeatCooler(seat, Level.OFF),
                    VehicleCommand.SetSeatHeater(seat, Level.OFF),
                )
                mode == SeatMode.COOL -> listOf(
                    VehicleCommand.SetSeatHeater(seat, Level.OFF),
                    VehicleCommand.SetSeatCooler(seat, level),
                )
                else -> listOf(
                    VehicleCommand.SetSeatCooler(seat, Level.OFF),
                    VehicleCommand.SetSeatHeater(seat, level),
                )
            }

            commands.forEach { command ->
                pending.value = command
                val result = container.gateway.send(command)
                if (result.isFailure) {
                    error.value = "좌석 ${mode.label} 실패 — " +
                        (result.exceptionOrNull()?.message ?: "원인 불명")
                }
            }
            pending.value = null
        }
    }

    fun dismissError() {
        error.value = null
    }

    fun retryConnect() {
        viewModelScope.launch {
            error.value = null
            val vin = container.settingsStore.settings.first().vin
            val result = if (vin.isNotBlank()) {
                container.gateway.connect(vin, allowProbe = true)
            } else {
                Result.failure(IllegalStateException("등록된 차량이 없어요"))
            }
            if (result.isFailure) {
                error.value = result.exceptionOrNull()?.message ?: "연결에 실패했다"
            }
        }
    }

    private fun applyOptimistic(command: VehicleCommand) = optimistic.update { base ->
        val current = base.copy(appliedAtMillis = System.currentTimeMillis())
        when (command) {
            is VehicleCommand.SetSeatCooler ->
                current.copy(seatCooler = current.seatCooler + (command.seat to command.level))
            is VehicleCommand.SetSeatHeater ->
                current.copy(seatHeater = current.seatHeater + (command.seat to command.level))
            is VehicleCommand.ClimateOn -> current.copy(isClimateOn = true)
            is VehicleCommand.ClimateOff -> current.copy(isClimateOn = false)
            is VehicleCommand.SetTemperature -> current.copy(targetTemp = command.celsius)
            is VehicleCommand.Lock -> current.copy(isLocked = true)
            is VehicleCommand.Unlock -> current.copy(isLocked = false)
            else -> current   // 화면에 표시되지 않는 명령은 되돌릴 것도 없다
        }
    }

    private fun revertOptimistic(command: VehicleCommand) = optimistic.update { current ->
        when (command) {
            is VehicleCommand.SetSeatCooler ->
                current.copy(seatCooler = current.seatCooler - command.seat)
            is VehicleCommand.SetSeatHeater ->
                current.copy(seatHeater = current.seatHeater - command.seat)
            is VehicleCommand.ClimateOn, is VehicleCommand.ClimateOff ->
                current.copy(isClimateOn = null)
            is VehicleCommand.SetTemperature -> current.copy(targetTemp = null)
            is VehicleCommand.Lock, is VehicleCommand.Unlock -> current.copy(isLocked = null)
            else -> current
        }
    }

    private fun initialState() = DashboardUiState(
        link = container.gateway.linkState.value,
        vehicleName = "차량",
        insideTemp = "--",
        outsideTemp = "--",
        targetTemp = "--",
        targetTempValue = null,
        isClimateOn = false,
        isLocked = true,
        seatCooler = emptyMap(),
        seatHeater = emptyMap(),
        isSimulated = container.isSimulated,
        hasReading = false,
        pendingCommand = null,
        errorMessage = null,
    )
}

/**
 * 화면에 보여줄 좌석 통풍/열선 상태.
 *
 * 차가 보고한 값이 진실이다 — 차 화면에서 켠 통풍도 여기 반영돼야 한다.
 * 아직 못 읽었으면 마지막 저장값을, 둘 다 꺼져 있으면 모드만 기억하고 단계는 끔으로 보여준다.
 */
internal fun seatClimateOf(
    snapshot: VehicleSnapshot,
    stored: Map<SeatPosition, SeatClimate>,
): Map<SeatPosition, SeatClimate> =
    listOf(SeatPosition.FRONT_LEFT, SeatPosition.FRONT_RIGHT).associateWith { seat ->
        val cooler = snapshot.seatCooler[seat]
        val heater = snapshot.seatHeater[seat]
        val saved = stored[seat] ?: SeatClimate()
        when {
            heater != null && heater != Level.OFF -> SeatClimate(SeatMode.HEAT, heater)
            cooler != null && cooler != Level.OFF -> SeatClimate(SeatMode.COOL, cooler)
            cooler == null && heater == null -> saved
            else -> SeatClimate(saved.mode, Level.OFF)
        }
    }

/** 소수 한 자리. 아직 못 읽었으면 대시 */
private fun Double?.format(): String = this?.let { "%.1f".format(it) } ?: "--"
