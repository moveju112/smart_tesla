package com.wemade.teslamacro.data.nav

import app.cash.paparazzi.Paparazzi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DestinationDiagnosticsTest {
    @get:Rule val paparazzi = Paparazzi()
    @get:Rule val temporary = TemporaryFolder()

    /** 정상 빈 수신함은 상세 로그 없이 시작·종료·결과·실제 간격을 마지막 요약으로 유지한다. */
    @Test fun emptyPollingUpdatesSnapshotWithoutLogSpam() = runTest {
        var now = 1_000L
        val reports = mutableListOf<String>()
        val diagnostics = DestinationDiagnostics({ now }, { 100_000L + now }, reports::add)
        diagnostics.inbox { now += 100L; DestinationReply(100_000L) }
        now = 6_000L
        diagnostics.inbox { now += 100L; DestinationReply(100_000L) }
        assertTrue(reports.isEmpty())
        val snapshot = diagnostics.snapshot()
        assertTrue(snapshot.contains("결과=EMPTY"))
        assertTrue(snapshot.contains("간격=5000ms"))
        assertTrue(snapshot.contains("소요=100ms"))
        assertTrue(snapshot.contains("진행=0"))
    }

    /** 예정 간격을 크게 넘기거나 조회 오류가 나면 요약만 출력하고 원문 응답은 남기지 않는다. */
    @Test fun delayAndErrorEmitBoundedDiagnosticSummary() = runTest {
        var now = 1_000L
        val reports = mutableListOf<String>()
        val diagnostics = DestinationDiagnostics({ now }, { now }, reports::add)
        diagnostics.inbox { DestinationReply(100_000L) }
        now += 20_000L
        diagnostics.inbox { DestinationReply(100_000L) }
        assertEquals(1, reports.size)
        assertTrue(reports.single().contains("간격=20000ms"))
        val failure = runCatching { diagnostics.inbox { throw DestinationApiException(503, "private payload") } }
        assertTrue(failure.isFailure)
        assertEquals(2, reports.size)
        assertFalse(reports.last().contains("private payload"))
        assertTrue(diagnostics.snapshot().contains("ERROR:DestinationApiException"))
    }

    /** HTTP가 끝나지 않은 상태는 완료된 빈 응답과 다르게 공유한다. */
    @Test fun inFlightRequestIsVisibleAndOldReplyCannotOverwriteNewResult() = runTest {
        val diagnostics = DestinationDiagnostics({ 1_000L }, { 100_000L }, {})
        val held = CompletableDeferred<DestinationReply>()
        val started = CompletableDeferred<Unit>()
        val first = async { runCatching { diagnostics.inbox { started.complete(Unit); held.await() } } }
        started.await()
        assertTrue(diagnostics.snapshot().contains("진행=1"))
        diagnostics.inbox { DestinationReply(100_000L) }
        held.completeExceptionally(DestinationApiException(503, "old reply"))
        assertTrue(first.await().isFailure)
        assertTrue(diagnostics.snapshot().contains("완료 #2="))
        assertTrue(diagnostics.snapshot().contains("결과=EMPTY"))
        assertTrue(diagnostics.snapshot().contains("진행=0"))
    }

    /** 착석 시작·종료·관측 나이와 전달 조건·확인 수준을 함께 공유한다. */
    @Test fun presenceAndDeliverySnapshotPreserveObservationTimes() {
        var now = 1_000L
        val diagnostics = DestinationDiagnostics({ now }, { 100_000L + now }, {})
        diagnostics.presenceStarted()
        now += 200L
        diagnostics.presenceFinished(true, 1_000L)
        diagnostics.deliveryChecked(true, true, true, 1_000L)
        diagnostics.deliveryFinished(DestinationReceiveResult.DISPATCHED)
        val snapshot = diagnostics.snapshot()
        assertTrue(snapshot.contains("소요=200ms"))
        assertTrue(snapshot.contains("관측=1000ms"))
        assertTrue(snapshot.contains("관측나이=200ms"))
        assertTrue(snapshot.contains("결과=DISPATCHED"))
    }

    /** 새 지도 전달은 이전 완료 시각을 이어받지 않아 진행 중 상태를 구별한다. */
    @Test fun newDeliveryDoesNotReusePreviousCompletionTime() {
        var now = 1_000L
        val diagnostics = DestinationDiagnostics({ now }, { now }, {})
        diagnostics.deliveryChecked(true, true, true, now)
        diagnostics.deliveryFinished(DestinationReceiveResult.DISPATCHED)
        now += 1_000L
        diagnostics.deliveryChecked(true, true, true, now)
        assertTrue(diagnostics.snapshot().substringAfter("전달:").contains("결과=null 완료=없음"))
    }

    /** 정상 조회 요약도 재시작 뒤 이전 프로세스 기록으로 구분해 읽을 수 있다. */
    @Test fun lastSummarySurvivesProcessRestart() = runTest {
        val path = java.io.File(temporary.root, "diagnostics.txt")
        val first = DestinationDiagnostics({ 1_000L }, { 100_000L }, {})
        first.attach(path)
        first.inbox { DestinationReply(100_000L) }
        assertTrue(path.exists())
        val restarted = DestinationDiagnostics({ 2_000L }, { 200_000L }, {})
        restarted.attach(path)
        assertTrue(restarted.snapshot().contains("이전 프로세스 요약:"))
        assertTrue(restarted.snapshot().contains("결과=EMPTY"))
    }
}
