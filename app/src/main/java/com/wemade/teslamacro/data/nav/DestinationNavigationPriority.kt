package com.wemade.teslamacro.data.nav

/** 목적지를 수신한 뒤 준비 중이던 매크로의 오래된 지도 요청이 덮어쓰지 않게 한다. */
internal class DestinationNavigationPriority(private val elapsed: () -> Long) {
    private var generation = 0L
    private var requestId: String? = null
    private var deadline = 0L
    private val completed = ArrayDeque<String>()

    /** 매크로 준비 시작의 우선권 세대를 기록해 준비 도중 수신한 목적지를 감지한다. */
    @Synchronized fun checkpoint(): Long = generation

    /** 같은 요청의 재조회는 새 실행으로 세지 않고 새 수신 요청만 우선권을 갱신한다. */
    @Synchronized fun pending(id: String, expiresAt: Long) {
        if (id in completed) return
        if (id != requestId) generation++
        requestId = id
        deadline = expiresAt
    }

    /** 늦은 빈 수신함 응답은 이후 발견한 새 목적지의 우선권을 지우지 않는다. */
    @Synchronized fun empty(observedGeneration: Long) {
        if (observedGeneration == generation) { requestId = null; deadline = 0L }
    }

    /** 완료를 확인한 요청은 늦은 수신함 응답으로 다시 대기 상태가 되지 않는다. */
    @Synchronized fun complete(id: String) {
        if (id !in completed) completed.addLast(id)
        while (completed.size > 32) completed.removeFirst()
        if (requestId == id) { requestId = null; deadline = 0L }
    }

    /** 수신·불명확 전달 또는 준비 중 우선권 변화가 있으면 매크로 지도 걸음만 중단한다. */
    @Synchronized fun ensureMacroAllowed(token: Long, receiptPending: Boolean, receiving: Boolean = true) {
        if (receiptPending || token != generation || (receiving && requestId != null && elapsed() < deadline)) {
            throw DestinationPriorityException()
        }
    }
}

/** 매크로의 다른 차량 명령을 취소하지 않고 지도 안내 실패 결과로 우선권 양보를 알린다. */
internal class DestinationPriorityException : IllegalStateException("목적지 전송을 우선해 매크로 지도 안내를 건너뛰었어요")
