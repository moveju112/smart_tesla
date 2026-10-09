package com.wemade.teslamacro.data.nav

import org.junit.Assert.*
import org.junit.Test

class DestinationNavigationPriorityTest {
    /** 수신 목적지가 대기·인증 중이면 먼저 준비한 매크로도 지도에 전달하지 않는다. */
    @Test fun pendingDestinationPreemptsMacro() {
        val priority = DestinationNavigationPriority { 1_000L }
        val macro = priority.checkpoint()
        priority.pending("destination", 10_000L)
        assertTrue(runCatching { priority.ensureMacroAllowed(macro, false) }.exceptionOrNull() is DestinationPriorityException)
        assertTrue(runCatching { priority.ensureMacroAllowed(priority.checkpoint(), false) }.isFailure)
    }

    /** 지오코딩·화면 대기 중 목적지 수신이 끝나도 옛 매크로가 뒤늦게 덮어쓰지 않는다. */
    @Test fun completedDestinationStillInvalidatesOlderMacroPreparation() {
        val priority = DestinationNavigationPriority { 1_000L }
        val macro = priority.checkpoint()
        priority.pending("destination", 10_000L)
        priority.complete("destination")
        assertTrue(runCatching { priority.ensureMacroAllowed(macro, false) }.isFailure)
        priority.ensureMacroAllowed(priority.checkpoint(), false)
    }

    /** 늦은 빈 응답과 완료한 요청의 재조회는 우선권을 반대로 뒤집지 않는다. */
    @Test fun staleInboxRepliesCannotEraseOrResurrectPriority() {
        val priority = DestinationNavigationPriority { 1_000L }
        val empty = priority.checkpoint()
        priority.pending("destination", 10_000L)
        priority.empty(empty)
        assertTrue(runCatching { priority.ensureMacroAllowed(priority.checkpoint(), false) }.isFailure)
        priority.complete("destination")
        priority.pending("destination", 10_000L)
        priority.ensureMacroAllowed(priority.checkpoint(), false)
    }

    /** 미확인 기록은 수신 설정이 꺼져도 매크로 지도 실행으로 우회하지 않는다. */
    @Test fun unresolvedReceiptAlwaysBlocksMacro() {
        val priority = DestinationNavigationPriority { 1_000L }
        assertTrue(runCatching { priority.ensureMacroAllowed(priority.checkpoint(), true, false) }.isFailure)
        priority.ensureMacroAllowed(priority.checkpoint(), false, false)
    }

    /** 요청 만료·현재 빈 수신함 확인 후 시작한 새 매크로는 실행 가능하다. */
    @Test fun expiryAndConfirmedEmptyAllowNewMacro() {
        var now = 1_000L
        val priority = DestinationNavigationPriority { now }
        priority.pending("destination", 10_000L)
        now = 10_000L
        priority.ensureMacroAllowed(priority.checkpoint(), false)
        priority.pending("another", 20_000L)
        priority.empty(priority.checkpoint())
        priority.ensureMacroAllowed(priority.checkpoint(), false)
    }
}
