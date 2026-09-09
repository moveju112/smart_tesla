package com.wemade.teslamacro.data.nav

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

internal const val SAFE_DRIVE_UNLOCK_TIMEOUT_MILLIS = 60_000L

/** 메인 스레드에서 인증 요청 한 건의 만료·취소·늦은 콜백을 함께 관리한다. */
internal class SafeDriveUnlockGate(private val nowMillis: () -> Long) {
    private data class Request(
        val expiresAtMillis: Long,
        val id: String = UUID.randomUUID().toString(),
        val answer: CompletableDeferred<Boolean> = CompletableDeferred(),
    )

    private var active: Request? = null

    /** 인증 완료 후 전달까지 화면을 유지하고, 어떤 종료 경로에서도 요청을 폐기한다. */
    suspend fun run(
        showPrompt: suspend (String) -> Unit,
        closePrompt: (String) -> Unit,
        launch: suspend () -> Unit,
    ): Boolean {
        check(active == null) { "이미 잠금 해제를 기다리고 있어요" }
        val request = Request(expiresAtMillis = nowMillis() + SAFE_DRIVE_UNLOCK_TIMEOUT_MILLIS)
        active = request
        try {
            val unlocked = withTimeoutOrNull(SAFE_DRIVE_UNLOCK_TIMEOUT_MILLIS) {
                showPrompt(request.id)
                request.answer.await()
            } == true
            if (!unlocked || nowMillis() >= request.expiresAtMillis) return false
            launch()
            return true
        } finally {
            request.answer.complete(false)
            active = null
            closePrompt(request.id)
        }
    }

    /** 만료된 알림·재생성된 화면은 새 탑승 요청을 이어받지 못한다. */
    fun isPending(id: String): Boolean = isCurrent(id) && active?.answer?.isCompleted == false

    /** 인증 직후 화면 재생성은 허용하되 전달마다 원래 만료 시각을 지킨다. */
    fun isCurrent(id: String): Boolean = active?.let {
        it.id == id && nowMillis() < it.expiresAtMillis
    } == true

    /** 같은 요청의 첫 인증 결과만 받아 중복 전달과 취소 뒤 실행을 막는다. */
    fun complete(id: String, unlocked: Boolean): Boolean {
        if (!isPending(id)) return false
        val request = active ?: return false
        return request.answer.complete(unlocked)
    }
}
