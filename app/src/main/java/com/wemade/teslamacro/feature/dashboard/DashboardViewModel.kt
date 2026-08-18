package com.wemade.teslamacro.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wemade.teslamacro.di.AppContainer
import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.domain.command.confirmCategory
import com.wemade.teslamacro.domain.model.Level
import com.wemade.teslamacro.domain.model.SeatPosition
import com.wemade.teslamacro.domain.model.SeatMode
import com.wemade.teslamacro.domain.model.SeatClimate
import com.wemade.teslamacro.domain.model.StateCategory
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
    /** 공조 낙관값을 덮어쓴 시각 — 그 뒤에 공조를 실제로 읽었을 때만 거둔다 */
    val climateAppliedAtMillis: Long = 0L,
    /** 잠금 낙관값을 덮어쓴 시각 — 차체(VCSEC)를 읽은 뒤에만 거둔다 */
    val lockAppliedAtMillis: Long = 0L,
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
        // 명령을 보낸 뒤 "그 카테고리를 실제로 읽은" 값이 도착하면 그때부터는 차량 값이 진실이다.
        // 전체 타임스탬프로 지우면 매 사이클 도는 차체 읽기가 공조 낙관값을 과거값으로 되돌린다
        viewModelScope.launch {
            container.poller.snapshot.collect { snapshot ->
                optimistic.update { overlay ->
                    if (overlay.isEmpty) return@update overlay
                    val climateReadAt = snapshot.categoryReadAt[StateCategory.CLIMATE] ?: 0L
                    // 잠금은 VCSEC 계열(차체/도어) 어느 쪽을 읽어도 확정된다
                    val lockReadAt = maxOf(
                        snapshot.categoryReadAt[StateCategory.BODY_CONTROLLER] ?: 0L,
                        snapshot.categoryReadAt[StateCategory.CLOSURES] ?: 0L,
                    )
                    var next = overlay
                    if (climateReadAt > overlay.climateAppliedAtMillis) {
                        next = next.copy(
                            seatCooler = emptyMap(),
                            seatHeater = emptyMap(),
                            isClimateOn = null,
                            targetTemp = null,
                        )
                    }
                    if (lockReadAt > overlay.lockAppliedAtMillis) {
                        next = next.copy(isLocked = null)
                    }
                    next
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
            // 상태를 한 번도 못 읽었으면 "0"이 아니라 "읽는 중"으로 보여야 한다.
            // 전역 타임스탬프는 아무 카테고리 하나만 성공해도 갱신되므로,
            // 잠금(VCSEC)·공조(CLIMATE)는 해당 카테고리를 실제로 읽었는지로 따로 가린다
            hasReading = snapshot.timestampMillis > 0L,
            hasBodyReading = StateCategory.BODY_CONTROLLER in snapshot.categoryReadAt ||
                StateCategory.CLOSURES in snapshot.categoryReadAt,
            hasClimateReading = StateCategory.CLIMATE in snapshot.categoryReadAt,
            pendingCommand = aux.pending,
            errorMessage = aux.error,
            secondsSinceReading = snapshot.timestampMillis
                .takeIf { it > 0L }
                ?.let { (System.currentTimeMillis() - it) / 1000 },
            batteryPercent = effective.batteryLevelPercent,
            isCharging = effective.isCharging,
            chargeLimitPercent = effective.chargeLimitPercent,
            chargingAmps = effective.chargingAmps,
            stealthCharging = settings.stealthCharging,
            automationEnabled = settings.automationEnabled,
            runningMacroCount = container.runner.running.value.size,
            rangeKm = effective.rangeKm?.toInt(),
            // 열린 문만 추린다. 다 닫혀 있으면 빈 목록 = 화면은 "모두 닫힘"
            openings = effective.doorOpen.filterValues { it }.keys.map { it.label },
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
    /** 스텔스 충전 on/off. 실제 전류 조작은 백그라운드 컨트롤러가 한다 */
    fun setStealthCharging(enabled: Boolean) {
        viewModelScope.launch { container.settingsStore.setStealthCharging(enabled) }
    }

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
                // 배너도 한 문장 = 한 줄 — 원인은 둘째 줄로
                error.value = "${command.label} 실패\n" +
                    (result.exceptionOrNull()?.message ?: "원인 불명")
            } else {
                // 3. 성공 — 낙관 시계를 전송 완료 시각으로 미룬다.
                //    안 하면 전송 중에 시작된 폴링 읽기(명령 전 값)가 낙관 표시를
                //    옛값으로 조기 해제한다. 그 뒤 결과 카테고리를 즉시 다시 읽어 확정한다
                bumpOptimisticClock(command)
                container.poller.focusOn(command.confirmCategory())
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
            // 1. 클라 저장 + 낙관 반영 — 저장만 하면 차량 보고값(옛값)이 우선돼
            //    누른 단계가 한 프레임 만에 "끔"으로 튕긴다 (send() 경로와 같은 규칙)
            container.seatStore.set(seat, SeatClimate(mode, level))
            optimistic.update {
                it.copy(
                    seatCooler = it.seatCooler + (seat to if (mode == SeatMode.COOL) level else Level.OFF),
                    seatHeater = it.seatHeater + (seat to if (mode == SeatMode.HEAT) level else Level.OFF),
                    climateAppliedAtMillis = System.currentTimeMillis(),
                )
            }

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

            var anySuccess = false
            commands.forEach { command ->
                pending.value = command
                val result = container.gateway.send(command)
                if (result.isFailure) {
                    error.value = "좌석 ${mode.label} 실패\n" +
                        (result.exceptionOrNull()?.message ?: "원인 불명")
                } else {
                    anySuccess = true
                }
            }
            pending.value = null
            if (anySuccess) {
                // 전송 완료 시각으로 낙관 시계를 미루고, 공조를 즉시 다시 읽어 확정한다
                optimistic.update { it.copy(climateAppliedAtMillis = System.currentTimeMillis()) }
                container.poller.focusOn(StateCategory.CLIMATE)
            } else {
                // 한 발도 못 갔으면 낙관 표시를 거둔다 — 화면이 거짓을 유지하면 안 된다
                optimistic.update {
                    it.copy(seatCooler = it.seatCooler - seat, seatHeater = it.seatHeater - seat)
                }
            }
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
        val now = System.currentTimeMillis()
        val climate = base.copy(climateAppliedAtMillis = now)
        when (command) {
            is VehicleCommand.SetSeatCooler ->
                climate.copy(seatCooler = climate.seatCooler + (command.seat to command.level))
            is VehicleCommand.SetSeatHeater ->
                climate.copy(seatHeater = climate.seatHeater + (command.seat to command.level))
            is VehicleCommand.ClimateOn -> climate.copy(isClimateOn = true)
            is VehicleCommand.ClimateOff -> climate.copy(isClimateOn = false)
            is VehicleCommand.SetTemperature -> climate.copy(targetTemp = command.celsius)
            is VehicleCommand.Lock -> base.copy(isLocked = true, lockAppliedAtMillis = now)
            is VehicleCommand.Unlock -> base.copy(isLocked = false, lockAppliedAtMillis = now)
            else -> base   // 화면에 표시되지 않는 명령은 되돌릴 것도 없다
        }
    }

    /**
     * 전송 완료 시각으로 낙관 시계만 미룬다 — 값은 건드리지 않는다.
     * 값까지 재적용하면 연속 명령에서 먼저 끝난 명령이 나중에 누른 낙관값을 되덮는다.
     */
    private fun bumpOptimisticClock(command: VehicleCommand) = optimistic.update { base ->
        val now = System.currentTimeMillis()
        when (command) {
            is VehicleCommand.SetSeatCooler,
            is VehicleCommand.SetSeatHeater,
            is VehicleCommand.ClimateOn,
            is VehicleCommand.ClimateOff,
            is VehicleCommand.SetTemperature,
            -> base.copy(climateAppliedAtMillis = now)

            is VehicleCommand.Lock,
            is VehicleCommand.Unlock,
            -> base.copy(lockAppliedAtMillis = now)

            else -> base   // 낙관 표시가 없는 명령은 미룰 시계도 없다
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
            // 저장된 모드 쪽 채널이 null(차가 안 알려줌)이면 저장값을 믿는다 —
            // 반대 채널의 "꺼짐" 보고만으로 이쪽까지 끔으로 단정하면 안 된다
            saved.mode == SeatMode.COOL && cooler == null -> saved
            saved.mode == SeatMode.HEAT && heater == null -> saved
            else -> SeatClimate(saved.mode, Level.OFF)
        }
    }

/** 소수 한 자리. 아직 못 읽었으면 대시 */
private fun Double?.format(): String = this?.let { "%.1f".format(it) } ?: "--"
