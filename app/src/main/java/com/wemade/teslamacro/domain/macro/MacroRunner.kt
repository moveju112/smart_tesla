package com.wemade.teslamacro.domain.macro

import com.wemade.teslamacro.domain.gateway.VehicleGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 실행 이력 한 줄. 화면 하단 로그와 디버깅에 쓴다 */
data class MacroLogEntry(
    val timestampMillis: Long,
    val ruleName: String,
    val message: String,
    val isError: Boolean = false,
)

/**
 * 매크로가 지금 어디까지 갔는지.
 *
 * "실행 중" 배지만 있으면 5분 대기 중인지 멈춘 건지 구분이 안 된다.
 * 남은 시간을 노출해서 기다리는 중임을 눈으로 보여준다.
 */
data class MacroProgress(
    val ruleId: String,
    val stepIndex: Int,
    val totalSteps: Int,
    /** 대기 중이면 끝나는 시각. 아니면 null */
    val waitEndsAtMillis: Long? = null,
    /** 조건 대기 중이면 그 조건 설명. 아니면 null */
    val waitingFor: String? = null,
) {
    fun remainingSeconds(nowMillis: Long): Int? =
        waitEndsAtMillis?.let { ((it - nowMillis) / 1000).coerceAtLeast(0).toInt() }
}

/**
 * 매크로 하나를 순서대로 실행한다.
 *
 * 같은 매크로가 겹쳐 도는 걸 막고, 새로 발동하면 기존 실행을 취소한다.
 * 대기 단계가 길어서(5분 등) 취소 가능성이 반드시 필요하다.
 */
class MacroRunner(
    private val gateway: VehicleGateway,
    private val scope: CoroutineScope,
    /** 조건 대기 판정에 쓰는 최신 차량 상태. 폴러가 갱신한다 */
    private val reading: StateFlow<Reading?>,
    /** "지도 안내" 걸음 처리기. 안드로이드 인텐트는 앱 계층이 안다 — 도메인은 결과만 받는다 */
    private val navigator: suspend (name: String, address: String) -> Result<Unit> =
        { _, _ -> Result.failure(IllegalStateException("지도 안내를 지원하지 않는 환경이에요")) },
    private val now: () -> Long = System::currentTimeMillis,
    private val maxLogSize: Int = 100,
) {
    private val jobs = mutableMapOf<String, Job>()
    private val lock = Mutex()

    private val _log = MutableStateFlow<List<MacroLogEntry>>(emptyList())
    val log: StateFlow<List<MacroLogEntry>> = _log.asStateFlow()

    private val _running = MutableStateFlow<Set<String>>(emptySet())
    val running: StateFlow<Set<String>> = _running.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, MacroProgress>>(emptyMap())
    val progress: StateFlow<Map<String, MacroProgress>> = _progress.asStateFlow()

    /** 매크로를 실행한다. 같은 매크로가 돌고 있으면 취소하고 새로 시작한다 */
    fun launch(rule: MacroRule, nowMillis: Long) {
        scope.launch {
            lock.withLock {
                jobs.remove(rule.id)?.cancel()
                jobs[rule.id] = scope.launch { execute(rule, nowMillis) }
            }
        }
    }

    /** 진행 중인 매크로 전부 중단 (사용자가 수동 개입할 때) */
    fun cancelAll() {
        scope.launch {
            lock.withLock {
                jobs.values.forEach { it.cancel() }
                jobs.clear()
                _running.value = emptySet()
                _progress.value = emptyMap()
            }
        }
    }

    /** 매크로 하나만 중단 */
    fun cancel(ruleId: String) {
        scope.launch {
            lock.withLock {
                jobs.remove(ruleId)?.cancel()
                _running.update { it - ruleId }
                _progress.update { it - ruleId }
            }
        }
    }

    private suspend fun execute(rule: MacroRule, startedAt: Long) {
        val myJob = kotlin.coroutines.coroutineContext[Job]
        _running.update { it + rule.id }
        append(startedAt, rule.name, "시작")
        try {
            rule.actions.forEachIndexed { index, step ->
                when (step) {
                    is ActionStep.Wait -> runFixedWait(rule, index, step)
                    is ActionStep.WaitUntil -> runConditionalWait(rule, index, step)
                    is ActionStep.Run -> runCommand(rule, index, step)
                    is ActionStep.Navigate -> runNavigate(rule, index, step)
                }
            }
            append(now(), rule.name, "완료")
        } finally {
            // 내 항목일 때만 지운다 — 재발동으로 방금 등록된 새 잡의 항목을
            // 취소된 이전 잡의 finally가 지우면, 새 잡이 추적을 벗어나
            // cancelAll(사람 조작 우선)이 못 멈추고 다음 발동과 겹쳐 돈다
            lock.withLock {
                if (jobs[rule.id] === myJob) {
                    jobs.remove(rule.id)
                    _running.update { it - rule.id }
                    _progress.update { it - rule.id }
                }
            }
        }
    }

    private suspend fun runFixedWait(rule: MacroRule, index: Int, step: ActionStep.Wait) {
        val endsAt = now() + step.seconds * 1000L
        _progress.update {
            it + (rule.id to MacroProgress(rule.id, index, rule.actions.size, endsAt))
        }
        delay(step.seconds * 1000L)
    }

    /**
     * 조건이 맞을 때까지 짧은 간격으로 다시 본다.
     * 폴링 주기(최소 2초)보다 촘촘히 볼 이유가 없어 1초로 충분하다.
     */
    private suspend fun runConditionalWait(
        rule: MacroRule,
        index: Int,
        step: ActionStep.WaitUntil,
    ) {
        val endsAt = now() + step.timeoutSeconds * 1000L
        _progress.update {
            it + (rule.id to MacroProgress(
                ruleId = rule.id,
                stepIndex = index,
                totalSteps = rule.actions.size,
                waitEndsAtMillis = endsAt,
                waitingFor = describe(step.condition),
            ))
        }

        while (now() < endsAt) {
            val current = reading.value
            if (current != null && ConditionEvaluator.holds(step.condition, current)) {
                append(now(), rule.name, "조건 충족 — ${describe(step.condition)}")
                return
            }
            delay(CONDITION_POLL_MS)
        }

        // 시간 초과는 실패가 아니라 "그만 기다린다"이다. 나머지 단계는 계속 간다
        append(
            now(), rule.name,
            "대기 시간 초과 — ${describe(step.condition)} (계속 진행)",
            isError = true,
        )
    }

    private suspend fun runCommand(rule: MacroRule, index: Int, step: ActionStep.Run) {
        _progress.update {
            it + (rule.id to MacroProgress(rule.id, index, rule.actions.size))
        }
        val result = gateway.send(step.command)
        // 한 단계 실패해도 나머지는 계속 간다. 통풍 실패로 공조까지 죽이지 않는다
        if (result.isFailure) {
            append(
                now(), rule.name,
                "${step.command.label} 실패: ${result.exceptionOrNull()?.message}",
                isError = true,
            )
        } else {
            append(now(), rule.name, step.command.label)
        }
    }

    private suspend fun runNavigate(rule: MacroRule, index: Int, step: ActionStep.Navigate) {
        _progress.update {
            it + (rule.id to MacroProgress(rule.id, index, rule.actions.size))
        }
        val result = navigator(step.destinationName, step.address)
        if (result.isFailure) {
            append(
                now(), rule.name,
                "${step.destinationName} 안내 실패: ${result.exceptionOrNull()?.message}",
                isError = true,
            )
        } else {
            append(now(), rule.name, "${step.destinationName} 안내 시작")
        }
    }

    private fun append(timestamp: Long, ruleName: String, message: String, isError: Boolean = false) {
        _log.update { (it + MacroLogEntry(timestamp, ruleName, message, isError)).takeLast(maxLogSize) }
        // 진단 로그에도 미러링 — "매크로가 왜 안 떴지"를 설정의 공유 버튼 한 번으로 조사 가능하게.
        // 여기 아무것도 없으면 발동 자체가 안 된 것, 실패가 찍혀 있으면 실행은 됐는데 걸음이 죽은 것
        com.wemade.teslable.DiagLog.add("매크로 [$ruleName] $message")
    }

    private companion object {
        const val CONDITION_POLL_MS = 1_000L
    }
}
