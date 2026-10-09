package com.wemade.teslamacro.data.nav

import android.os.SystemClock
import android.util.AtomicFile
import com.wemade.teslable.DiagLog
import java.io.File
import java.time.Instant

/** 빈 수신함을 매번 로그로 쓰지 않고 마지막 관측을 공유하며 지연의 원인을 단정하지 않는다. */
internal class DestinationDiagnostics(
    private val elapsed: () -> Long,
    private val wall: () -> Long,
    private val report: (String) -> Unit,
) {
    private var sequence = 0L
    private var finishedSequence = 0L
    private var startedAt = -1L
    private var startWall = -1L
    private var finishWall = -1L
    private var gap = 0L
    private var duration = 0L
    private var active = 0
    private var result = "미조회"
    private var presenceStartWall = -1L
    private var presenceStartElapsed = -1L
    private var deliveryFinishedWall = -1L
    private var deliveryResult: DestinationReceiveResult? = null
    private var presence = "미확인"
    private var delivery = "미전달"
    private var file: AtomicFile? = null
    private var previous = ""
    private var persistedAt = -60_000L

    /** 같은 프로세스에서 다시 붙일 때 이전 요약을 덮지 않고 재시작 전 기록도 공유에 남긴다. */
    @Synchronized fun attach(path: File) {
        if (file != null) return
        file = AtomicFile(path)
        previous = runCatching { file!!.readFully().toString(Charsets.UTF_8).take(2_000) }.getOrDefault("")
    }

    /** 실제 HTTP 수신함 시작·종료를 기록하며 인증 대기 시간을 HTTP 시간으로 합치지 않는다. */
    suspend fun inbox(call: suspend () -> DestinationReply): DestinationReply {
        val started = elapsed()
        val token = synchronized(this) {
            gap = if (startedAt >= 0) (started - startedAt).coerceAtLeast(0) else 0L
            startedAt = started
            startWall = wall()
            active++
            Pair(++sequence, gap)
        }
        try {
            val reply = call()
            finished(token.first, token.second, started, if (reply.request == null) "EMPTY" else reply.request.status, false)
            return reply
        } catch (error: Exception) {
            finished(token.first, token.second, started, if (error is kotlinx.coroutines.CancellationException) "CANCELLED" else "ERROR:${error.javaClass.simpleName}", true)
            throw error
        }
    }

    /** 늦은 이전 응답은 더 최근 완료 기록을 덮지 않으며 지연·오류 때만 파일 로그로 출력한다. */
    @Synchronized private fun finished(token: Long, startGap: Long, started: Long, outcome: String, failed: Boolean) {
        active--
        val callDuration = (elapsed() - started).coerceAtLeast(0)
        if (token >= finishedSequence) {
            finishedSequence = token
            finishWall = wall()
            duration = callDuration
            result = outcome
        }
        val delayed = startGap > 15_000L || callDuration > 4_000L
        if (failed || delayed) report("목적지 조회 진단 #$token $outcome 간격=${startGap}ms 소요=${callDuration}ms — ${snapshot().replace("\n", " · ")}")
        persist(failed || delayed)
    }

    /** 차량 확인이 시작된 뒤 종료하지 못한 상황도 최근 요약으로 구분한다. */
    @Synchronized fun presenceStarted() {
        presenceStartWall = wall()
        presenceStartElapsed = elapsed()
        presence = "확인 시작=${time(presenceStartWall)} 경과=${presenceStartElapsed}ms"
        persist(true)
    }

    /** 응답이 없는 경우와 실제 관측 시각·착석 여부를 주소 없이 기록한다. */
    @Synchronized fun presenceFinished(present: Boolean?, observedAt: Long?) {
        presence = "확인 시작=${time(presenceStartWall)} 종료=${time(wall())} 소요=${elapsed() - presenceStartElapsed}ms 착석=$present 관측=${observedAt ?: -1}ms 관측나이=${observedAt?.let { elapsed() - it } ?: -1}ms"
        persist(true)
    }

    /** 지도 전달 직전 조건은 신선한 관측과 설정·통신 값으로만 기록한다. */
    @Synchronized fun deliveryChecked(enabled: Boolean, online: Boolean, present: Boolean?, observedAt: Long) {
        deliveryResult = null
        deliveryFinishedWall = -1L
        delivery = "전달 직전=${time(wall())} 조건검사=통과 수신=$enabled 인터넷=$online 착석=$present 관측나이=${elapsed() - observedAt}ms"
        persist(true)
    }

    /** 실제 화면 성공으로 부르지 않고 수신 경로가 확인한 전달 결과만 남긴다. */
    @Synchronized fun deliveryFinished(outcome: DestinationReceiveResult) {
        if (outcome != DestinationReceiveResult.EMPTY && outcome != deliveryResult) {
            deliveryResult = outcome
            deliveryFinishedWall = wall()
            persist(true)
        }
    }

    /** 공유 시점에도 마지막 조회와 현재 진행 수를 확인하고 이전 프로세스 기록과 구분한다. */
    @Synchronized fun snapshot(): String {
        val current = "목적지 수신 요약: 조회 #$sequence 시작=${time(startWall)} 완료 #$finishedSequence=${time(finishWall)} 결과=$result 진행=$active 간격=${gap}ms 소요=${duration}ms 마지막시작후=${if (startedAt < 0) -1 else elapsed() - startedAt}ms\n착석: $presence\n전달: $delivery 결과=$deliveryResult 완료=${time(deliveryFinishedWall)}"
        return if (previous.isBlank()) current else "$current\n이전 프로세스 요약:\n$previous"
    }

    /** 정상 조회는 분당 한 번만 저장하고 처리·오류 경계는 즉시 보존한다. */
    private fun persist(force: Boolean) {
        val target = file ?: return
        if (!force && elapsed() - persistedAt < 60_000L) return
        val text = snapshot().substringBefore("\n이전 프로세스 요약:")
        runCatching {
            val stream = target.startWrite()
            try { stream.write(text.toByteArray()); target.finishWrite(stream) }
            catch (error: Exception) { target.failWrite(stream); throw error }
            persistedAt = elapsed()
        }
    }

    /** 벽시계 시각은 UTC로 표시하고 간격 판단은 경과 시간만 사용한다. */
    private fun time(value: Long): String = if (value < 0) "없음" else Instant.ofEpochMilli(value).toString()

    companion object {
        val current = DestinationDiagnostics(SystemClock::elapsedRealtime, System::currentTimeMillis, DiagLog::add)
    }
}
