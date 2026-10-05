package com.wemade.teslamacro.data.nav

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

/** 수신·인계·디스크 기록·지도 실행 순서를 하나로 고정해 복구 이벤트가 겹쳐도 중복 호출하지 않는다. */
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

    /** 잠금 해제를 거절한 요청. 사용자가 직접 잠금을 풀거나 탑승이 바뀔 때까지 인증 화면을 다시 띄우지 않는다. */
    @Volatile
    private var declinedRequestId: String? = null

    // 잠금 해제 거절 해제 (잠금 해제·탑승 변화 -> 다음 수신에서 재시도)
    fun retryDeclined() {
        declinedRequestId = null
    }

    /** 실제 전달 결과가 저장된 경우만 서버에 재확인하고 지도를 다시 열지 않는다. */
    suspend fun reconcileReceipt() {
        val receipt = readReceipt() ?: return
        val delivered = receipt.delivered ?: return
        try {
            call("complete") { put("requestId", receipt.requestId); put("delivered", delivered) }
            clearReceipt()
        } catch (error: DestinationApiException) {
            if (error.code == 404 || error.code == 409) clearReceipt() else throw error
        }
    }

    /** false는 서버에서 대기 목적지가 없음을 확인한 경우에만 반환한다. */
    suspend fun receive(ready: () -> Boolean): Boolean = mutex.withLock {
        reconcileReceipt()
        if (!ready()) return@withLock true
        val started = elapsed()
        val inbox = call("inbox") {}
        val request = inbox.request ?: return@withLock false
        if (request.legacyTestRequest || request.status != "pending") return@withLock true
        if (request.id == declinedRequestId) {
            report("잠금을 해제하면 목적지를 열어요")
            return@withLock true
        }
        val deadline = destinationDeadline(inbox.serverNow, request.expiresAt, started) ?: return@withLock true
        var claimed = false
        var launchDeadline = deadline
        report("목적지 수신 · 네이버지도 전달 준비")
        try {
            val result = launch(request.destination) {
                // 잠금 해제 대기 동안 하차·취소·교체·만료될 수 있어 실제 실행 직전에 인계한다.
                check(ready() && elapsed() < launchDeadline) { "실행 조건이 바뀌었거나 유효시간이 지났어요" }
                val claimStarted = elapsed()
                val reply = call("claim") { put("requestId", request.id) }
                check(reply.request?.id == request.id && reply.request.status == "claimed")
                claimed = true
                launchDeadline = destinationDeadline(reply.serverNow, request.expiresAt, claimStarted) ?: 0
                saveReceipt(DestinationReceipt(request.id))
                check(ready() && elapsed() < launchDeadline) { "실행 조건이 바뀌었거나 유효시간이 지났어요" }
            }
            if (claimed) {
                saveReceipt(DestinationReceipt(request.id, result.isSuccess))
                report(if (result.isSuccess) "네이버지도로 전달했어요" else result.exceptionOrNull()?.message ?: "네이버지도 전달에 실패했어요")
                reconcileReceipt()
            } else if (result.isFailure) {
                if (result.exceptionOrNull() is DestinationUnlockDeclinedException) declinedRequestId = request.id
                report(result.exceptionOrNull()?.message ?: "네이버지도 실행을 기다리고 있어요")
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // 인계 뒤 불명확한 실패는 자동 재실행하지 않고 디스크·서버의 claimed 상태를 남긴다.
            report(if (claimed) "전달 결과 확인이 필요해요. 상태를 새로고침해 주세요" else error.message ?: "목적지를 다시 확인해 주세요")
        }
        true
    }
}
