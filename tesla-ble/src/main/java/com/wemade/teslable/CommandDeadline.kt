package com.wemade.teslable

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 요청의 절대 만료를 전송 직전까지 전달해 늦게 깨어난 기기도 오래된 명령을 보내지 않는다. */
class CommandDeadline(val expiresAt: Long, private val now: () -> Long) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CommandDeadline>

    /** 절전 시간을 포함한 남은 수명을 사용한다. */
    fun remainingMillis(): Long = (expiresAt - now()).coerceAtLeast(0L)

    /** 타이머 실행이 늦어져도 전송 시각 기준으로 만료를 차단한다. */
    fun check() {
        if (remainingMillis() == 0L) throw CommandExpiredException()
    }
}

class CommandExpiredException : CancellationException("요청 후 2분이 지나 취소했어요")

/** 취소된 요청과 절대 시각이 지난 요청 모두 GATT 제출 전에 차단한다. */
suspend fun ensureCommandActive() {
    val context = currentCoroutineContext()
    context.ensureActive()
    context[CommandDeadline]?.check()
}
