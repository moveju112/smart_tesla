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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 다음 충전 1회만 전류를 조절하고 원래 전류와 연결 보호 정책을 복구한다. */
class StealthChargeController(
    private val gateway: VehicleGateway,
    private val poller: StatePoller,
    private val settingsStore: SettingsStore,
    /** 상한과 현재 전류를 모두 못 읽었을 때만 쓰는 보수적 기본값. */
    private val fallbackMaxAmps: Int = DEFAULT_MAX_AMPS,
) {
    private var job: Job? = null

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
            StealthChargeAction.COMPLETE -> restoreAndClose(settings, "충전 완료")
            StealthChargeAction.RUN -> runLoop()
            StealthChargeAction.WAIT -> Unit
        }
    }

    /** 충전 중에는 시간대와 실제 상한을 매 스텝 다시 확인한다. */
    private suspend fun runLoop() {
        var current = poller.snapshot.value.chargingAmps ?: currentMaxAmps()
        var stepCount = 0
        var waitingLogged = false

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
                val originalAmps = poller.snapshot.value.chargingAmps ?: current
                settingsStore.beginStealthCharge(originalAmps)
                com.wemade.teslable.DiagLog.add(
                    "스텔스 충전 1회 시작 — 원래 전류 ${originalAmps}A"
                )
                settings = settingsStore.settings.first()
            }

            val maxAmps = currentMaxAmps()
            val step = StealthChargePlan.next(current, MIN_AMPS, maxAmps)
            val sent = sendWithRetry(step.amps)
            stepCount++
            if (sent.isSuccess) {
                current = step.amps
                settingsStore.setStealthChargeModified(step.amps != settings.stealthChargeOriginalAmps)
            }
            when {
                sent.isFailure -> com.wemade.teslable.DiagLog.add(
                    "스텔스 충전 전송 실패 — 3회 재시도 · ${sent.exceptionOrNull()?.message}"
                )
                stepCount == 1 || stepCount % 10 == 0 -> com.wemade.teslable.DiagLog.add(
                    "스텔스 충전 진행 중 (${stepCount}스텝, 현재 ${step.amps}A / 상한 ${maxAmps}A)"
                )
            }
            delay(step.holdSeconds * 1000L)
        }
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
        val result = sendWithRetry(originalAmps)
        if (result.isSuccess) {
            com.wemade.teslable.DiagLog.add("스텔스 충전 전류 원복 완료 — ${originalAmps}A")
        }
        return result.isSuccess
    }

    /** 전류 변경은 멱등 명령이라 짧은 간격으로 최대 3회 재시도한다. */
    private suspend fun sendWithRetry(amps: Int): Result<Unit> {
        var last = Result.failure<Unit>(IllegalStateException("전류 명령 미실행"))
        repeat(SEND_ATTEMPTS) { attempt ->
            last = gateway.send(VehicleCommand.SetChargingAmps(amps))
            if (last.isSuccess) return last
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
        val scheduleEnabled: Boolean,
        val startMinutes: Int,
        val endMinutes: Int,
    )

    private companion object {
        const val MIN_AMPS = 5
        const val DEFAULT_MAX_AMPS = 16
        const val SEND_ATTEMPTS = 3
        const val WINDOW_CHECK_MILLIS = 60_000L
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

/** 차량 상한, 현재 전류, 폴백 순으로 고르되 명령 가능한 최솟값은 지킨다. */
internal fun effectiveMaxChargingAmps(
    reportedMaxAmps: Int?,
    currentAmps: Int?,
    fallbackMaxAmps: Int,
    minAmps: Int,
): Int = (reportedMaxAmps ?: currentAmps ?: fallbackMaxAmps).coerceAtLeast(minAmps)
