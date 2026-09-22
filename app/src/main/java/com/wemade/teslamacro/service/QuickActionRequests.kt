package com.wemade.teslamacro.service

import com.wemade.teslable.CommandExpiredException
import com.wemade.teslable.DiagLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job

/** 연결 대기 취소와 전송 진입을 같은 잠금으로 결정해 취소된 명령의 뒤늦은 전송을 막는다. */
class QuickActionRequests(private val diagnosticLogger: (String) -> Unit = DiagLog::add) {
    enum class Status(val message: String) {
        Waiting("차량 연결 대기 중"),
        Sending("전송 처리 중 · 이미 전달된 명령은 취소할 수 없어요"),
        Cancelled("취소 완료 · 차량에 명령을 보내지 않았어요"),
        Expired("유효시간 만료 · 이미 전달된 명령은 취소할 수 없어요"),
        Finished("요청 처리 종료 · 결과는 진단 로그에서 확인하세요"),
        Failed("요청 중단 · 차량 전달 여부는 진단 로그에서 확인하세요"),
    }

    data class Request(val id: Long, val label: String, val status: Status) {
        val active: Boolean get() = status == Status.Waiting || status == Status.Sending
    }

    private val lock = Any()
    private var nextId = 0L
    private val jobs = mutableMapOf<Long, Job>()
    private val mutableRequests = MutableStateFlow<List<Request>>(emptyList())
    val requests = mutableRequests.asStateFlow()

    /** 서비스 작업을 추적하되 화면 재생성이나 화면 이동으로 대기 명령이 사라지지 않게 한다. */
    suspend fun track(label: String, execute: suspend (beforeDispatch: () -> Unit) -> Unit) {
        val job = currentCoroutineContext().job
        val id = synchronized(lock) {
            val id = ++nextId
            jobs[id] = job
            mutableRequests.value += Request(id, label, Status.Waiting)
            id
        }
        try {
            execute {
                synchronized(lock) {
                    job.ensureActive()
                    check(mutableRequests.value.first { it.id == id }.status == Status.Waiting)
                    setStatus(id, Status.Sending)
                }
            }
            synchronized(lock) { if (mutableRequests.value.any { it.id == id && it.active }) setStatus(id, Status.Finished) }
        } catch (expired: CommandExpiredException) {
            synchronized(lock) { if (mutableRequests.value.any { it.id == id && it.active }) setStatus(id, Status.Expired) }
        } catch (cancelled: CancellationException) {
            synchronized(lock) {
                val request = mutableRequests.value.firstOrNull { it.id == id }
                if (request?.active == true) setStatus(id, if (request.status == Status.Waiting) Status.Cancelled else Status.Failed)
            }
            throw cancelled
        } catch (error: Exception) {
            synchronized(lock) { if (mutableRequests.value.any { it.id == id && it.active }) setStatus(id, Status.Failed) }
            diagnosticLogger("빠른 명령 [$label] 중단 — ${error.message}")
        } finally {
            synchronized(lock) { jobs.remove(id) }
        }
    }

    /** 전송 진입보다 취소가 먼저일 때만 성공을 돌려주고 대기 코루틴도 중단한다. */
    fun cancel(id: Long): Boolean = synchronized(lock) {
        val request = mutableRequests.value.firstOrNull { it.id == id } ?: return false
        if (request.status != Status.Waiting) return false
        setStatus(id, Status.Cancelled)
        jobs[id]?.cancel()
        diagnosticLogger("빠른 명령 [${request.label}] 사용자 취소 — 차량 명령 미전송")
        true
    }

    /** 종료 안내만 닫을 수 있어 대기 명령이 화면에서 숨겨지지 않는다. */
    fun dismiss(id: Long) = synchronized(lock) {
        mutableRequests.value = mutableRequests.value.filterNot { it.id == id && !it.active }
    }

    /** 진행 중인 요청은 보존하고 종료 안내만 최근 세 개로 제한한다. 호출자는 잠금을 보유한다. */
    private fun setStatus(id: Long, status: Status) {
        val updated = mutableRequests.value.map { if (it.id == id) it.copy(status = status) else it }
        val recent = updated.filterNot { it.active }.takeLast(3).map { it.id }.toSet()
        mutableRequests.value = updated.filter { it.active || it.id in recent }
    }
}
