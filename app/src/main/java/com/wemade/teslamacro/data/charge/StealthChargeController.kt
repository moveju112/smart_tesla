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
    private val maxAmps: Int = DEFAULT_MAX_AMPS,
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
        var current = poller.snapshot.value.chargingAmps ?: maxAmps
        while (true) {
            val step = StealthChargePlan.next(current, MIN_AMPS, maxAmps)
            gateway.send(VehicleCommand.SetChargingAmps(step.amps))
            current = step.amps
            com.wemade.teslable.DiagLog.add("스텔스 충전 → ${step.amps}A (${step.holdSeconds}s)")
            delay(step.holdSeconds * 1000L)   // 취소되면 여기서 CancellationException으로 빠져나간다
        }
    }

    private companion object {
        const val MIN_AMPS = 5
        const val DEFAULT_MAX_AMPS = 32
    }
}
