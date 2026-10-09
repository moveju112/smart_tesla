package com.wemade.teslamacro.data.nav

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

/** 수신함 확인과 실제 실행을 구분해 조건 대기를 전달 성공으로 소비하지 않는다. */
internal enum class DestinationReceiveResult { EMPTY, WAITING_FOR_CONDITIONS, DISPATCHED, FAILED, UNKNOWN }

/** 수신·인계·디스크 기록·지도 실행 순서를 하나로 고정해 중복 호출을 막는다. */
internal class DestinationReceiver(
    private val call: suspend (String, JsonObjectBuilder.() -> Unit) -> DestinationReply,
    private val readReceipt: () -> DestinationReceipt?,
    private val saveReceipt: (DestinationReceipt) -> Unit,
    private val clearReceipt: () -> Unit,
    private val elapsed: () -> Long,
    private val launch: suspend (DestinationPlace, suspend () -> Unit) -> Result<Unit>,
    private val report: (String) -> Unit,
) {
    private val mutex = Mutex()
    @Volatile private var declinedRequestId: String? = null

    /** 잠금 해제·탑승 변화 뒤에만 취소한 인증 요청을 다시 허용한다. */
    fun retryDeclined() { declinedRequestId = null }

    /** 자동 판정할 수 없는 이전 전달만 사용자 확인 대상으로 반환한다. */
    fun unresolvedRequestId(): String? = readReceipt()?.takeIf { it.launchAttempted && it.delivered == null }?.requestId

    /** 사용자 확인 결과를 동기화하되 이전 지도 요청을 다시 실행하지 않는다. */
    suspend fun resolveReceipt(delivered: Boolean) = mutex.withLock {
        val receipt = readReceipt()?.takeIf { it.launchAttempted && it.delivered == null } ?: return@withLock
        saveReceipt(receipt.copy(delivered = delivered))
        reconcileReceipt()
    }

    /** 실행 전 중단은 실패로 정리하되 전달 여부가 불명확하면 기록을 보존한다. */
    suspend fun reconcileReceipt(): Boolean {
        val receipt = readReceipt() ?: return true
        val delivered = receipt.delivered ?: if (!receipt.launchAttempted) false else {
            report("전달 결과 확인 필요 · 요청 ${receipt.requestId.take(8)} · 상태 새로고침에서 확인해 주세요")
            return false
        }
        try {
            call("complete") { put("requestId", receipt.requestId); put("delivered", delivered) }
            clearReceipt()
        } catch (error: DestinationApiException) {
            if (error.code == 404 || error.code == 409) clearReceipt() else throw error
        }
        return true
    }

    /** 수신함은 착석과 무관하게 확인하고 목적지가 있을 때만 차량 확인을 요청한다. */
    suspend fun receive(prepare: (suspend () -> Boolean)? = null, ready: () -> Boolean): DestinationReceiveResult = mutex.withLock {
        if (!reconcileReceipt()) return@withLock DestinationReceiveResult.UNKNOWN
        val started = elapsed()
        val inbox = call("inbox") {}
        val request = inbox.request ?: return@withLock DestinationReceiveResult.EMPTY
        if (request.legacyTestRequest || request.status != "pending") return@withLock DestinationReceiveResult.WAITING_FOR_CONDITIONS
        if (request.id == declinedRequestId) {
            report("잠금을 해제하면 목적지를 열어요")
            return@withLock DestinationReceiveResult.WAITING_FOR_CONDITIONS
        }
        var deadline = destinationDeadline(inbox.serverNow, request.expiresAt, started)
            ?: return@withLock DestinationReceiveResult.FAILED
        val trace = "요청 ${request.id.take(8)}"
        report("목적지 수신 · $trace · 착석 재확인")
        if (!(prepare?.invoke() ?: ready()) || !ready() || elapsed() >= deadline) return@withLock DestinationReceiveResult.WAITING_FOR_CONDITIONS
        var claimed = false
        var claimAttempted = false
        report("목적지 수신 · $trace · 네이버지도 전달 준비")
        try {
            val result = launch(request.destination) {
                // 인증·실험 종료 뒤 신선한 착석을 재확인하고 claim 전에 복구 기록부터 남긴다.
                check(elapsed() < deadline && (prepare?.invoke() ?: ready()) && ready() && elapsed() < deadline) { "실행 조건이 바뀌었거나 유효시간이 지났어요" }
                saveReceipt(DestinationReceipt(request.id, launchAttempted = false))
                claimAttempted = true
                val claimStarted = elapsed()
                val reply = call("claim") { put("requestId", request.id) }
                check(reply.request?.id == request.id && reply.request.status == "claimed")
                claimed = true
                deadline = destinationDeadline(reply.serverNow, request.expiresAt, claimStarted) ?: 0
                check(ready() && elapsed() < deadline) { "실행 조건이 바뀌었거나 유효시간이 지났어요" }
                saveReceipt(DestinationReceipt(request.id))
                report("목적지 수신 · $trace · 지도 실행 요청")
            }
            if (claimed) {
                saveReceipt(DestinationReceipt(request.id, result.isSuccess))
                report(if (result.isSuccess) "네이버지도로 전달했어요 · $trace" else result.exceptionOrNull()?.message ?: "네이버지도 전달에 실패했어요")
                reconcileReceipt()
                if (result.isSuccess) DestinationReceiveResult.DISPATCHED else DestinationReceiveResult.FAILED
            } else {
                if (result.exceptionOrNull() is DestinationUnlockDeclinedException) declinedRequestId = request.id
                report(result.exceptionOrNull()?.message ?: "네이버지도 실행을 기다리고 있어요")
                if (claimAttempted) { reconcileReceipt(); DestinationReceiveResult.FAILED }
                else DestinationReceiveResult.WAITING_FOR_CONDITIONS
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val receipt = readReceipt()
            if (receipt?.delivered == true) {
                report("네이버지도로 전달했어요 · $trace · 서버 상태 동기화 대기")
                DestinationReceiveResult.DISPATCHED
            } else if (receipt?.launchAttempted == true && receipt.delivered == null) {
                report("전달 결과 확인 필요 · $trace · 상태 새로고침에서 확인해 주세요")
                DestinationReceiveResult.UNKNOWN
            } else {
                report(error.message ?: "목적지를 다시 확인해 주세요")
                if (claimAttempted) { reconcileReceipt(); DestinationReceiveResult.FAILED }
                else DestinationReceiveResult.WAITING_FOR_CONDITIONS
            }
        }
    }
}
