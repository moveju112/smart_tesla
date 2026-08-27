package com.wemade.teslamacro.data.charge

import com.wemade.teslamacro.data.poll.StatePoller
import com.wemade.teslamacro.data.settings.SettingsStore
import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.domain.gateway.LinkState
import com.wemade.teslamacro.domain.gateway.VehicleGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 스텔스 충전 실행부 — [StealthChargePlan]이 정한 전류를 실제로 차에 흘려보낸다.
 *
 * 설정이 켜져 있고 · 연결돼 있고 · 실제로 충전 중일 때만 돈다.
 * 셋 중 하나라도 아니면 멈추고, 다시 갖춰지면 재개한다.
 */
class StealthChargeController(
    private val gateway: VehicleGateway,
    private val poller: StatePoller,
    private val settingsStore: SettingsStore,
    /** 차가 상한을 안 알려줄 때만 쓰는 값. 실제 상한은 매 스텝 스냅샷에서 읽는다 */
    private val fallbackMaxAmps: Int = DEFAULT_MAX_AMPS,
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            // 켜짐 · 연결됨 · 충전중 을 하나의 on/off 신호로 합친다
            combine(
                settingsStore.settings,
                gateway.linkState,
                poller.snapshot,
            ) { settings, link, snapshot ->
                settings.stealthCharging &&
                    link is LinkState.Ready &&
                    snapshot.isCharging == true
            }
                .distinctUntilChanged()
                // collectLatest: 조건이 false로 바뀌면 실행 중이던 runLoop을 취소한다.
                // 평범한 collect면 runLoop의 무한 delay에 갇혀 다음 값을 못 받는다
                .collectLatest { active -> if (active) runLoop() }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** 활성인 동안 전류를 계속 흔든다. 조건이 깨지면 collectLatest가 이 코루틴을 취소한다 */
    private suspend fun runLoop() {
        // 시작값은 차가 보고한 현재 전류, 없으면 상한
        var current = poller.snapshot.value.chargingAmps ?: currentMaxAmps()
        var stepCount = 0
        while (true) {
            // 상한은 매 스텝 다시 본다 — 같은 밤에도 충전기를 바꿔 물면 값이 달라진다
            val maxAmps = currentMaxAmps()
            val step = StealthChargePlan.next(current, MIN_AMPS, maxAmps)
            val sent = gateway.send(VehicleCommand.SetChargingAmps(step.amps))
            current = step.amps
            stepCount++
            // 스텝마다 적으면(평균 75초) 밤샘 충전이 300줄 버퍼를 한 바퀴 돌린다 —
            // 시작 1회 + 10스텝마다 + 실패 시에만 남긴다
            when {
                sent.isFailure -> com.wemade.teslable.DiagLog.add(
                    "스텔스 충전 전송 실패 — ${sent.exceptionOrNull()?.message}"
                )
                stepCount == 1 || stepCount % 10 == 0 -> com.wemade.teslable.DiagLog.add(
                    "스텔스 충전 진행 중 (${stepCount}스텝, 현재 ${step.amps}A / 상한 ${maxAmps}A)"
                )
            }
            delay(step.holdSeconds * 1000L)   // 취소되면 여기서 CancellationException으로 빠져나간다
        }
    }

    /**
     * 지금 쓸 수 있는 상한(A). 차가 알려준 값을 쓰고, 없을 때만 폴백.
     *
     * 16A 콘센트에 물렸는데 5~32A로 흔들면 위쪽 절반이 통째로 헛값이라
     * "16A에 눌러앉음"이 되어 위장이 아니라 그냥 상한 고정이 된다
     */
    private fun currentMaxAmps(): Int =
        effectiveMaxChargingAmps(
            reportedMaxAmps = poller.snapshot.value.maxChargingAmps,
            fallbackMaxAmps = fallbackMaxAmps,
            minAmps = MIN_AMPS,
        )

    private companion object {
        const val MIN_AMPS = 5
        const val DEFAULT_MAX_AMPS = 32
    }
}

/** 차량 상한을 우선하고, 누락됐을 때만 폴백을 쓰되 명령 가능한 최솟값은 지킨다 */
internal fun effectiveMaxChargingAmps(
    reportedMaxAmps: Int?,
    fallbackMaxAmps: Int,
    minAmps: Int,
): Int = (reportedMaxAmps ?: fallbackMaxAmps).coerceAtLeast(minAmps)
