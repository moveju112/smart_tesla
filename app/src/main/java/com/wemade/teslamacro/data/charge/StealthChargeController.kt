package com.wemade.teslamacro.data.charge

import com.wemade.teslamacro.data.poll.StatePoller
import com.wemade.teslamacro.data.settings.AppSettings
import com.wemade.teslamacro.data.settings.SettingsStore
import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.domain.gateway.LinkState
import com.wemade.teslamacro.domain.gateway.VehicleGateway
import com.wemade.teslamacro.domain.macro.TimeContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 화면과 서비스가 함께 쓰는 스텔스 충전 실행 상태. */
data class StealthChargeRuntime(
    val running: Boolean = false,
    val secondsUntilNextChange: Int? = null,
)

/** 다음 충전 1회만 전류를 조절하고 원래 전류와 연결 보호 정책을 복구한다. */
class StealthChargeController(
    private val gateway: VehicleGateway,
    private val poller: StatePoller,
    private val settingsStore: SettingsStore,
    /** 상한과 현재 전류를 모두 못 읽었을 때만 쓰는 보수적 기본값. */
    private val fallbackMaxAmps: Int = DEFAULT_MAX_AMPS,
) {
    private var job: Job? = null
    private val _runtime = MutableStateFlow(StealthChargeRuntime())
    val runtime: StateFlow<StealthChargeRuntime> = _runtime.asStateFlow()

    /** 설정·연결·충전 상태가 바뀌면 기존 작업을 취소하고 필요한 동작만 다시 고른다. */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            combine(
                settingsStore.settings,
                gateway.linkState,
                poller.snapshot,
            ) { settings, link, snapshot ->
                Gate(
                    enabled = settings.stealthCharging,
                    linked = link is LinkState.Ready,
                    isCharging = snapshot.isCharging,
                    maxAmps = settings.stealthMaxAmps,
                    minAmps = settings.stealthMinAmps,
                    scheduleEnabled = settings.stealthScheduleEnabled,
                    startMinutes = settings.stealthStartMinutes,
                    endMinutes = settings.stealthEndMinutes,
                )
            }
                .distinctUntilChanged()
                .collectLatest { gate -> handleGate(gate) }
        }
    }

    /** 서비스가 멈추면 실행 중인 전류 조절도 함께 취소한다. */
    fun stop() {
        job?.cancel()
        job = null
    }

    /** 현재 상태에서 대기·실행·완료·수동 해제 복구 중 하나만 수행한다. */
    private suspend fun handleGate(gate: Gate) {
        val settings = settingsStore.settings.first()
        when (
            stealthChargeAction(
                enabled = gate.enabled,
                started = settings.stealthChargeStarted,
                modified = settings.stealthChargeModified,
                linked = gate.linked,
                isCharging = gate.isCharging,
            )
        ) {
            StealthChargeAction.RESTORE_DISABLED -> restoreAndClose(settings, "사용자 해제")
            StealthChargeAction.COMPLETE -> confirmStoppedThenClose()
            StealthChargeAction.RUN -> runLoop()
            StealthChargeAction.WAIT -> Unit
        }
    }

    /**
     * 충전 중단은 한 번 본 것으로 끝내지 않는다.
     *
     * isCharging은 charger_power(kW 정수)가 0보다 큰지로 판정한다. 저전류 구간에서는 0으로
     * 보고될 수 있고, 차량이 전력을 잠시 거두기도 한다. 한 번의 false로 1회 세션을 닫으면
     * 사용자는 아직 충전 중인데 조절이 끝나 버린다. 다시 충전이 보이면 collectLatest가
     * 이 대기를 취소하고 RUN으로 돌아간다.
     */
    private suspend fun confirmStoppedThenClose() {
        delay(CHARGE_STOP_CONFIRM_MILLIS)
        if (poller.snapshot.value.isCharging == true) {
            com.wemade.teslable.DiagLog.add("스텔스 충전 — 충전 중단이 아니었다, 조절 계속")
            return
        }
        // 3분을 기다렸으니 원복에 쓸 값은 지금 저장된 것으로 다시 읽는다
        restoreAndClose(settingsStore.settings.first(), "충전 완료")
    }

    /** 충전 중에는 시간대와 실제 상한을 매 스텝 다시 확인한다. */
    private suspend fun runLoop() {
        try {
            var current = poller.snapshot.value.chargingAmps ?: currentMaxAmps()
            var stepCount = 0
            var waitingLogged = false
            var ampsWaitLogged = false

            while (true) {
                var settings = settingsStore.settings.first()
                val nowMinutes = TimeContext.of(System.currentTimeMillis()).minutesOfDay
                val inWindow = isWithinStealthChargeWindow(
                    nowMinutes = nowMinutes,
                    enabled = settings.stealthScheduleEnabled,
                    startMinutes = settings.stealthStartMinutes,
                    endMinutes = settings.stealthEndMinutes,
                )
                if (!inWindow) {
                    _runtime.value = StealthChargeRuntime()
                    if (settings.stealthChargeModified) {
                        val restored = restoreOriginalAmps(settings)
                        settingsStore.setStealthChargeModified(false)
                        if (restored) {
                            current = settings.stealthChargeOriginalAmps ?: current
                        } else {
                            com.wemade.teslable.DiagLog.add(
                                "스텔스 충전 시간대 종료 원복 실패 — 휴대폰 키 보호 정책부터 복귀"
                            )
                        }
                    }
                    if (!waitingLogged) {
                        com.wemade.teslable.DiagLog.add("스텔스 충전 1회 대기 — 설정 시간대 밖")
                        waitingLogged = true
                    }
                    delay(WINDOW_CHECK_MILLIS)
                    continue
                }
                waitingLogged = false

                if (!settings.stealthChargeStarted) {
                    // 원래 전류를 모르는 채 시작하면 끝나고 엉뚱한 값(차량 상한·폴백 16A)으로
                    // 되돌린다. CHARGE를 실제로 읽을 때까지 기다린다
                    val originalAmps = poller.snapshot.value.chargingAmps
                    if (originalAmps == null) {
                        if (!ampsWaitLogged) {
                            com.wemade.teslable.DiagLog.add(
                                "스텔스 충전 대기 — 현재 전류를 아직 못 읽었다"
                            )
                            ampsWaitLogged = true
                        }
                        delay(AMPS_WAIT_MILLIS)
                        continue
                    }
                    ampsWaitLogged = false
                    current = originalAmps
                    settingsStore.beginStealthCharge(originalAmps)
                    com.wemade.teslable.DiagLog.add(
                        "스텔스 충전 1회 시작 — 원래 전류 ${originalAmps}A"
                    )
                    settings = settingsStore.settings.first()
                }

                val maxAmps = minOf(currentMaxAmps(), settings.stealthMaxAmps).coerceAtLeast(MIN_AMPS)
                // 하한을 직접 정했으면 그 값을, 안 정했으면 예전처럼 상한의 75% 자동 하한을 쓴다
                val minAmps = settings.stealthMinAmps
                    ?.coerceIn(MIN_AMPS, maxAmps)
                    ?: StealthChargePlan.autoMinAmps(MIN_AMPS, maxAmps)
                val step = StealthChargePlan.next(minAmps, maxAmps)
                _runtime.value = StealthChargeRuntime(running = true)
                val sent = sendWithRetry(step.amps)
                stepCount++
                if (sent.isSuccess) {
                    current = step.amps
                    settingsStore.setStealthChargeModified(
                        step.amps != settings.stealthChargeOriginalAmps
                    )
                }
                // 스텝마다 남긴다. 10스텝에 한 줄만 남기던 때는 "전류가 안 바뀐다"는 의심이 들어도
                // 무엇을 보냈고 먹혔는지 확인할 방법이 없었다
                com.wemade.teslable.DiagLog.add(
                    if (sent.isSuccess) {
                        "스텔스 충전 ${stepCount}스텝 · ${step.amps}A 전송 성공 " +
                            "(범위 ${minAmps}~${maxAmps}A, 다음 ${step.holdSeconds}초 뒤)"
                    } else {
                        "스텔스 충전 ${stepCount}스텝 · ${step.amps}A 전송 실패 — 3회 재시도 · " +
                            "${sent.exceptionOrNull()?.message}"
                    }
                )
                waitForNextStep(step.holdSeconds)
            }
        } finally {
            _runtime.value = StealthChargeRuntime()
        }
    }

    /** 화면 잠금 중에도 같은 기준 시각을 보도록 1초마다 남은 시간을 발행한다. */
    private suspend fun waitForNextStep(holdSeconds: Int) {
        for (remaining in holdSeconds downTo 1) {
            _runtime.value = StealthChargeRuntime(
                running = true,
                secondsUntilNextChange = remaining,
            )
            delay(1_000L)
        }
        _runtime.value = StealthChargeRuntime(running = true)
    }

    /** 원래 전류 복구를 시도한 뒤 결과와 무관하게 1회 상태를 닫고 보호 정책을 되살린다. */
    private suspend fun restoreAndClose(settings: AppSettings, reason: String) {
        val restored = !settings.stealthChargeModified || restoreOriginalAmps(settings)
        if (!restored) {
            com.wemade.teslable.DiagLog.add(
                "스텔스 충전 원래 전류 복구 실패 — 휴대폰 키 보호 정책부터 복귀"
            )
        }
        withContext(NonCancellable) {
            settingsStore.completeStealthCharge()
            com.wemade.teslable.DiagLog.add("스텔스 충전 1회 종료 — $reason · 연결 보호 정책 복귀")
            poller.enforceConnectionGuard()
        }
    }

    /** 원래 전류가 있고 실제로 바꾼 세션만 최대 3회 복구한다. */
    private suspend fun restoreOriginalAmps(settings: AppSettings): Boolean {
        val originalAmps = settings.stealthChargeOriginalAmps ?: return true
        com.wemade.teslable.DiagLog.add("스텔스 충전 원복 시도 — ${originalAmps}A")
        val result = sendWithRetry(originalAmps)
        com.wemade.teslable.DiagLog.add(
            if (result.isSuccess) "스텔스 충전 전류 원복 완료 — ${originalAmps}A"
            else "스텔스 충전 전류 원복 실패 — ${originalAmps}A · ${result.exceptionOrNull()?.message}"
        )
        return result.isSuccess
    }

    /** 전류 변경은 멱등 명령이라 짧은 간격으로 최대 3회 재시도한다. */
    private suspend fun sendWithRetry(amps: Int): Result<Unit> {
        var last = Result.failure<Unit>(IllegalStateException("전류 명령 미실행"))
        repeat(SEND_ATTEMPTS) { attempt ->
            last = gateway.send(VehicleCommand.SetChargingAmps(amps))
            if (last.isSuccess) return last
            // 몇 번째 시도가 왜 실패했는지 남긴다 — 차량 수면·무응답과 거부를 구분해야 한다
            com.wemade.teslable.DiagLog.add(
                "스텔스 충전 ${amps}A 전송 ${attempt + 1}/$SEND_ATTEMPTS 실패 · " +
                    "${last.exceptionOrNull()?.message}"
            )
            if (attempt < SEND_ATTEMPTS - 1) delay(RETRY_DELAYS_MILLIS[attempt])
        }
        return last
    }

    /** 차량 보고 상한, 현재 전류, 16A 기본값 순으로 가장 현실적인 상한을 고른다. */
    private fun currentMaxAmps(): Int = effectiveMaxChargingAmps(
        reportedMaxAmps = poller.snapshot.value.maxChargingAmps,
        currentAmps = poller.snapshot.value.chargingAmps,
        fallbackMaxAmps = fallbackMaxAmps,
        minAmps = MIN_AMPS,
    )

    private data class Gate(
        val enabled: Boolean,
        val linked: Boolean,
        val isCharging: Boolean?,
        val maxAmps: Int,
        val minAmps: Int?,
        val scheduleEnabled: Boolean,
        val startMinutes: Int,
        val endMinutes: Int,
    )

    private companion object {
        const val MIN_AMPS = 5
        const val DEFAULT_MAX_AMPS = 16
        const val SEND_ATTEMPTS = 3
        const val WINDOW_CHECK_MILLIS = 60_000L
        const val CHARGE_STOP_CONFIRM_MILLIS = 180_000L
        const val AMPS_WAIT_MILLIS = 15_000L
        val RETRY_DELAYS_MILLIS = longArrayOf(2_000L, 5_000L)
    }
}

/** 수집된 상태를 1회 충전 수명주기의 한 동작으로 줄인다. */
internal enum class StealthChargeAction {
    WAIT,
    RUN,
    COMPLETE,
    RESTORE_DISABLED,
}

/** 연결 전에는 기다리고, 시작된 충전만 완료 처리하며, 수동 해제의 원복을 우선한다. */
internal fun stealthChargeAction(
    enabled: Boolean,
    started: Boolean,
    modified: Boolean,
    linked: Boolean,
    isCharging: Boolean?,
): StealthChargeAction = when {
    !linked -> StealthChargeAction.WAIT
    !enabled && (started || modified) -> StealthChargeAction.RESTORE_DISABLED
    !enabled -> StealthChargeAction.WAIT
    started && isCharging == false -> StealthChargeAction.COMPLETE
    isCharging == true -> StealthChargeAction.RUN
    else -> StealthChargeAction.WAIT
}

/** 자정을 넘는 시간대와 시작·종료가 같은 하루 종일 설정을 함께 판정한다. */
internal fun isWithinStealthChargeWindow(
    nowMinutes: Int,
    enabled: Boolean,
    startMinutes: Int,
    endMinutes: Int,
): Boolean {
    if (!enabled || startMinutes == endMinutes) return true
    return if (startMinutes < endMinutes) {
        nowMinutes in startMinutes..endMinutes
    } else {
        nowMinutes >= startMinutes || nowMinutes <= endMinutes
    }
}

/**
 * 1회 충전을 위해 지금 차량 연결을 붙잡아야 하는가.
 *
 * 전류를 이미 바꿨으면 원복할 때까지 놓지 않는다. 아직 충전이 확인되지 않은 동안에는
 * 계속 붙어 있지 않는다 — 거치 태블릿이 밤새 연결을 잡으면 배터리도 먹고 공식 휴대폰 키의
 * 근접 판정도 방해한다. 대신 [STEALTH_PROBE_PERIOD_MILLIS] 주기의 앞
 * [STEALTH_PROBE_ON_MILLIS] 동안만 붙어 충전이 시작됐는지 확인한다. 연결을 아예 놓으면
 * 충전이 시작된 것을 읽을 방법이 없기 때문이다.
 */
internal fun stealthChargeNeedsConnection(
    modified: Boolean,
    enabled: Boolean,
    inWindow: Boolean,
    chargingConfirmed: Boolean,
    nowMillis: Long,
): Boolean {
    if (modified) return true
    if (!enabled || !inWindow) return false
    return chargingConfirmed || isStealthProbeWindow(nowMillis)
}

/** 벽시계를 주기로 잘라 앞부분만 확인 창으로 쓴다. 기기 상태 없이 판정해 재시작에도 흔들리지 않는다. */
internal fun isStealthProbeWindow(
    nowMillis: Long,
    periodMillis: Long = STEALTH_PROBE_PERIOD_MILLIS,
    onMillis: Long = STEALTH_PROBE_ON_MILLIS,
): Boolean = nowMillis.mod(periodMillis) < onMillis

internal const val STEALTH_PROBE_PERIOD_MILLIS = 10 * 60_000L
internal const val STEALTH_PROBE_ON_MILLIS = 3 * 60_000L

/** 차량 상한, 현재 전류, 폴백 순으로 고르되 명령 가능한 최솟값은 지킨다. */
internal fun effectiveMaxChargingAmps(
    reportedMaxAmps: Int?,
    currentAmps: Int?,
    fallbackMaxAmps: Int,
    minAmps: Int,
): Int = (reportedMaxAmps ?: currentAmps ?: fallbackMaxAmps).coerceAtLeast(minAmps)
