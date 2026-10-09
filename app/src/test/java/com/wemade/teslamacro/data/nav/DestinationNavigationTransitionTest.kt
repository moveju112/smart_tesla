package com.wemade.teslamacro.data.nav

import app.cash.paparazzi.Paparazzi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DestinationNavigationTransitionTest {
    @get:Rule val paparazzi = Paparazzi()

    /** 기존 실험의 강제 종료 정리가 완료되기 전에는 새 목적지가 실행되지 않는다. */
    @Test fun destinationWaitsForConfirmedCleanup() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        var stopped = false
        val session = launch {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { cleanup.await(); stopped = true } }
        }
        runCurrent()
        val transition = async { stopNavigationForDestination(session, true) { stopped } }
        runCurrent()
        assertFalse(transition.isCompleted)
        cleanup.complete(Unit)
        transition.await()
        assertTrue(stopped)
        assertTrue(session.isCompleted)
    }

    /** 종료 응답 실패는 기존 실험과 새 목적지가 겹치는 실행으로 우회하지 않는다. */
    @Test fun failedCleanupPreventsDestination() = runTest {
        val session = launch { awaitCancellation() }
        runCurrent()
        val result = runCatching { stopNavigationForDestination(session, true) { false } }
        assertTrue(result.exceptionOrNull() is DestinationLaunchException)
        assertTrue(session.isCompleted)
    }

    /** 종료 정리가 응답하지 않아도 15초 뒤 전환을 거절하고 새 안내를 실행하지 않는다. */
    @Test fun cleanupTimeoutPreventsDestination() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val session = launch {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { cleanup.await() } }
        }
        runCurrent()
        val transition = async { runCatching { stopNavigationForDestination(session, true) { false } } }
        runCurrent()
        advanceTimeBy(15_000L)
        runCurrent()
        assertTrue(transition.await().exceptionOrNull() is DestinationLaunchException)
        assertFalse(session.isCompleted)
        cleanup.complete(Unit)
        session.join()
    }

    /** 준비 작업만 존재할 때는 실험 종료나 실행 성공으로 오인하지 않는다. */
    @Test fun noExperimentDoesNotCancelPreparation() = runTest {
        val preparation = launch { awaitCancellation() }
        runCurrent()
        stopNavigationForDestination(preparation, false) { false }
        assertTrue(preparation.isActive)
        preparation.cancel()
    }

    /** 전달 전 권한 실패는 한 번 더 시도하고 전달 성공 뒤에는 요청을 소비한다. */
    @Test fun boardingRetriesOnlyKnownPreflightFailure() = runTest {
        var attempts = 0
        val request = BoardingNavigationRequest(0L) {
            attempts++
            if (attempts == 1) Result.failure(SafeDrivePreflightException("권한 필요")) else Result.success(Unit)
        }
        assertFalse(request.execute(8_000L))
        assertEquals(13_000L, request.retryAt)
        assertTrue(request.execute(13_000L))
        assertEquals(2, attempts)
    }

    /** 인증 취소·불명확한 실패는 자동으로 두 번째 화면 실행을 만들지 않는다. */
    @Test fun boardingDoesNotRetryCancelledOrUnknownDelivery() = runTest {
        for (error in listOf(DestinationUnlockDeclinedException(), DestinationLaunchException("불명확", uncertain = true))) {
            val request = BoardingNavigationRequest(0L) { Result.failure(error) }
            assertTrue(request.execute(8_000L))
        }
    }

    /** 오프라인·서버 장애는 8초 뒤 안심운전을 허용하고 늦은 목적지 대기는 우선권을 유지한다. */
    @Test fun offlineFallbackHasBoundedWaitAndDestinationPriority() {
        val request = BoardingNavigationRequest(1_000L) { Result.success(Unit) }
        assertFalse(request.canExecute(null, false, 8_999L))
        assertTrue(request.canExecute(null, false, 9_000L))
        assertTrue(request.canExecute(DestinationReceiveResult.EMPTY, false, 1_000L))
        assertFalse(request.canExecute(DestinationReceiveResult.WAITING_FOR_CONDITIONS, false, 9_000L))
        assertFalse(request.canExecute(DestinationReceiveResult.UNKNOWN, false, 9_000L))
        assertFalse(request.canExecute(DestinationReceiveResult.DISPATCHED, true, 9_000L))
        assertFalse(request.canExecute(null, false, 91_001L))
    }

    /** 이전 탑승과 늦은 조회 응답은 새 탑승의 8초·90초 오프라인 실행을 막지 않는다. */
    @Test fun previousBoardingWaitingDoesNotBlockOfflineFallback() {
        val previous = DestinationInboxObservation(1L, DestinationReceiveResult.WAITING_FOR_CONDITIONS, 200_000L)
        val request = BoardingNavigationRequest(1_000L) { Result.success(Unit) }
        for (now in listOf(9_000L, 91_000L)) {
            assertFalse(request.canExecute(previous.resultFor(1L, now), false, now))
            assertTrue(request.canExecute(previous.resultFor(2L, now), false, now))
        }
    }

    /** 같은 탑승의 대기 목적지만 우선하고 목적지 유효시간 경계부터 캐시를 폐기한다. */
    @Test fun waitingObservationExpiresWithDestination() {
        val observed = DestinationInboxObservation(1L, DestinationReceiveResult.WAITING_FOR_CONDITIONS, 10_000L)
        val request = BoardingNavigationRequest(0L) { Result.success(Unit) }
        assertFalse(request.canExecute(observed.resultFor(1L, 9_999L), false, 9_999L))
        assertTrue(request.canExecute(observed.resultFor(1L, 10_000L), false, 10_000L))
    }

    /** 반복되는 전달 전 실패도 탑승 세션당 두 번에서 끝낸다. */
    @Test fun boardingRetryIsBounded() = runTest {
        val request = BoardingNavigationRequest(0L) { Result.failure(SafeDrivePreflightException("실행 중")) }
        assertFalse(request.execute(0L))
        assertTrue(request.execute(5_000L))
    }
}
