package com.wemade.teslamacro.data.nav

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SafeDriveUnlockGateTest {
    /** 실차 실패 상태인 키가드만 남은 경우에도 인증 화면을 거쳐야 한다. */
    @Test
    fun `두 잠금이 모두 해제돼야 지도 전달을 허용한다`() {
        assertFalse(isSafeDriveUnlocked(keyguardLocked = true, deviceLocked = false))
        assertFalse(isSafeDriveUnlocked(keyguardLocked = true, deviceLocked = true))
        assertFalse(isSafeDriveUnlocked(keyguardLocked = false, deviceLocked = true))
        assertTrue(isSafeDriveUnlocked(keyguardLocked = false, deviceLocked = false))
    }

    /** 이미 잠겼다면 목적지 인계부터 막아 다음 잠금 해제 뒤 정상 수신할 여지를 남긴다. */
    @Test
    fun `다시 잠긴 목적지는 서버 인계를 시작하지 않는다`() = runTest {
        var claims = 0
        val result = runCatching { confirmUnlockedDestination({ false }) { claims++ } }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(0, claims)
    }

    /** 인계 응답 대기 중 키가드 또는 보안 잠금이 생기면 비ADB 경로도 지도에 전달하지 않는다. */
    @Test
    fun `목적지 인계 대기 중 다시 잠기면 전달하지 않는다`() = runTest {
        listOf(true to false, false to true).forEach { (keyguardLocked, deviceLocked) ->
            val claimed = CompletableDeferred<Unit>()
            var unlocked = true
            var launches = 0
            val result = async {
                runCatching {
                    confirmUnlockedDestination({ unlocked }) { claimed.await() }
                    launches++
                }
            }
            runCurrent()
            unlocked = isSafeDriveUnlocked(keyguardLocked, deviceLocked)
            claimed.complete(Unit)
            assertTrue(result.await().exceptionOrNull() is IllegalStateException)
            assertEquals(0, launches)
        }
    }

    /** 정상 인계는 한 번 이어가며 취소는 새 지도 전달로 바꾸지 않는다. */
    @Test
    fun `잠금 해제 상태를 유지한 요청만 인계 뒤 전달한다`() = runTest {
        var claims = 0
        var launches = 0
        confirmUnlockedDestination({ true }) { claims++ }
        launches++
        val claimed = CompletableDeferred<Unit>()
        val cancelled = async {
            confirmUnlockedDestination({ true }) { claimed.await() }
            launches++
        }
        runCurrent()
        cancelled.cancelAndJoin()
        claimed.complete(Unit)
        assertEquals(1, claims)
        assertEquals(1, launches)
    }

    /** 보안 인증만 풀린 콜백은 성공으로 처리하지 않아 배경 직접 실행으로 새지 않는다. */
    @Test
    fun `키가드가 남은 성공 콜백은 지도를 전달하지 않는다`() = runTest {
        val harness = Harness { testScheduler.currentTime }
        val result = async { harness.run() }
        runCurrent()
        harness.gate.complete(harness.token, isSafeDriveUnlocked(true, false))
        assertFalse(result.await())
        assertEquals(0, harness.launchCount)
        assertEquals(listOf("show", "close"), harness.events)
    }

    /** 인증 전에는 전달하지 않고, 첫 성공 뒤 전달이 끝난 다음 화면을 닫는다. */
    @Test
    fun `인증 전에는 기다리고 성공 한 번만 전달한다`() = runTest {
        val harness = Harness { testScheduler.currentTime }
        val result = async { harness.run() }
        runCurrent()

        advanceTimeBy(59_000)
        runCurrent()
        assertTrue(harness.gate.isPending(harness.token))
        assertFalse(result.isCompleted)
        assertEquals(0, harness.launchCount)

        assertTrue(harness.gate.complete(harness.token, true))
        assertFalse(harness.gate.complete(harness.token, true))
        assertTrue(result.await())
        assertEquals(1, harness.launchCount)
        assertEquals(listOf("show", "launch", "close"), harness.events)
        assertFalse(harness.gate.isPending(harness.token))
    }

    /** 취소 콜백 뒤 도착하는 성공이 같은 요청을 되살리지 못하게 한다. */
    @Test
    fun `취소 뒤 늦은 성공은 전달하지 않는다`() = runTest {
        val harness = Harness { testScheduler.currentTime }
        val result = async { harness.run() }
        runCurrent()

        assertTrue(harness.gate.complete(harness.token, false))
        assertFalse(harness.gate.complete(harness.token, true))
        assertFalse(result.await())
        assertFalse(harness.gate.complete(harness.token, true))
        assertEquals(0, harness.launchCount)
        assertEquals(listOf(harness.token), harness.closedTokens)
    }

    /** 사용자가 응답하지 않으면 60초에 화면과 요청을 함께 정리한다. */
    @Test
    fun `60초 동안 인증하지 않으면 만료한다`() = runTest {
        val harness = Harness { testScheduler.currentTime }
        val result = async { harness.run() }
        runCurrent()

        advanceTimeBy(59_999)
        runCurrent()
        assertTrue(harness.gate.isPending(harness.token))
        assertFalse(result.isCompleted)

        advanceTimeBy(1)
        runCurrent()
        assertFalse(result.await())
        assertFalse(harness.gate.complete(harness.token, true))
        assertEquals(0, harness.launchCount)
        assertEquals(listOf(harness.token), harness.closedTokens)
    }

    /** 서비스를 취소한 경우에도 인증 화면이 남거나 늦게 지도가 열리지 않게 한다. */
    @Test
    fun `호출 코루틴을 취소하면 요청과 화면을 정리한다`() = runTest {
        val harness = Harness { testScheduler.currentTime }
        val result = async { harness.run() }
        runCurrent()
        val cancelledToken = harness.token

        result.cancelAndJoin()

        assertTrue(result.isCancelled)
        assertFalse(harness.gate.isPending(cancelledToken))
        assertFalse(harness.gate.complete(cancelledToken, true))
        assertEquals(0, harness.launchCount)
        assertEquals(listOf(cancelledToken), harness.closedTokens)

        val next = async { harness.run() }
        runCurrent()
        assertTrue(harness.gate.complete(harness.token, true))
        assertTrue(next.await())
        assertEquals(1, harness.launchCount)
    }

    /** 이전 알림의 토큰이 새 탑승의 인증 결과로 받아들여지지 않는지 확인한다. */
    @Test
    fun `지난 요청의 성공은 새 요청에 영향을 주지 않는다`() = runTest {
        val harness = Harness { testScheduler.currentTime }
        val first = async { harness.run() }
        runCurrent()
        val oldToken = harness.token
        assertTrue(harness.gate.complete(oldToken, false))
        assertFalse(first.await())

        val next = async { harness.run() }
        runCurrent()
        val newToken = harness.token
        assertNotEquals(oldToken, newToken)
        assertFalse(harness.gate.complete(oldToken, true))
        assertTrue(harness.gate.isPending(newToken))
        assertFalse(next.isCompleted)
        assertEquals(0, harness.launchCount)

        assertTrue(harness.gate.complete(newToken, true))
        assertTrue(next.await())
        assertEquals(1, harness.launchCount)
        assertEquals(listOf(oldToken, newToken), harness.closedTokens)
    }

    /** 절전으로 타이머 실행이 밀려도 실제 경과 시간이 끝났으면 성공을 받지 않는다. */
    @Test
    fun `코루틴 타이머가 멈춰 있어도 경과 시간 만료 뒤 콜백은 거부한다`() = runTest {
        var elapsedOffset = 0L
        val harness = Harness { testScheduler.currentTime + elapsedOffset }
        val result = async { harness.run() }
        runCurrent()

        elapsedOffset = 60_000
        assertFalse(harness.gate.isPending(harness.token))
        assertFalse(harness.gate.complete(harness.token, true))
        runCurrent()
        assertEquals(0, harness.launchCount)

        advanceTimeBy(60_000)
        runCurrent()
        assertFalse(result.await())
        assertEquals(listOf(harness.token), harness.closedTokens)
    }

    /** 인증 콜백을 받은 뒤 전달 코루틴이 늦게 재개된 경우에도 만료를 다시 확인한다. */
    @Test
    fun `성공 콜백 뒤 전달 전 만료하면 지도를 열지 않는다`() = runTest {
        var elapsedOffset = 0L
        val harness = Harness { testScheduler.currentTime + elapsedOffset }
        val result = async { harness.run() }
        runCurrent()

        assertTrue(harness.gate.complete(harness.token, true))
        elapsedOffset = 60_000
        assertFalse(result.await())
        assertEquals(0, harness.launchCount)
        assertEquals(listOf(harness.token), harness.closedTokens)
    }

    /** 지도 전달이 예외로 끝나도 인증 화면을 닫고 다음 요청을 받을 수 있어야 한다. */
    @Test
    fun `지도 전달 예외 뒤에도 요청과 화면을 정리한다`() = runTest {
        val harness = Harness { testScheduler.currentTime }
        val failure = IllegalStateException("지도 전달 실패")
        val result = async {
            runCatching { harness.run { throw failure } }
        }
        runCurrent()
        val failedToken = harness.token

        assertTrue(harness.gate.complete(failedToken, true))
        assertSame(failure, result.await().exceptionOrNull())
        assertFalse(harness.gate.isPending(failedToken))
        assertEquals(listOf(failedToken), harness.closedTokens)

        val next = async { harness.run() }
        runCurrent()
        assertTrue(harness.gate.complete(harness.token, true))
        assertTrue(next.await())
        assertEquals(2, harness.launchCount)
    }

    /** 인증 제한 끝 직전의 성공도 별도 실행 예산 동안 화면을 유지한다. */
    @Test fun authenticatedDeliveryCanCrossOriginalDeadline() = runTest {
        val harness = Harness { testScheduler.currentTime }
        val result = async { harness.run {
            delay(10_000L)
            assertTrue(harness.gate.isCurrent(harness.token))
            assertFalse(harness.gate.isPending(harness.token))
        } }
        runCurrent()
        advanceTimeBy(55_000L)
        assertTrue(harness.gate.complete(harness.token, true))
        assertTrue(result.await())
        assertEquals(65_000L, testScheduler.currentTime)
    }

    /** 인증 후 작업도 30초에서 취소하고 화면·늦은 콜백을 함께 폐기한다. */
    @Test fun deliveryBudgetIsBoundedAfterAuthentication() = runTest {
        val harness = Harness { testScheduler.currentTime }
        var delivered = false
        val result = async { runCatching { harness.run { delay(30_001L); delivered = true } } }
        runCurrent()
        assertTrue(harness.gate.complete(harness.token, true))
        assertTrue(result.await().exceptionOrNull() is DestinationLaunchException)
        assertFalse(delivered)
        assertFalse(harness.gate.isCurrent(harness.token))
        assertFalse(harness.gate.complete(harness.token, true))
        assertEquals(listOf(harness.token), harness.closedTokens)
    }

    /** 타이머가 밀려도 인증 후 실행의 실제 경과 시간 경계에서 화면 유효성이 끝난다. */
    @Test fun deliveryExpiryUsesElapsedClockEvenBeforeTimerRuns() = runTest {
        var offset = 0L
        val harness = Harness { testScheduler.currentTime + offset }
        val result = async { harness.run {
            offset = SAFE_DRIVE_UNLOCK_DELIVERY_TIMEOUT_MILLIS
            assertFalse(harness.gate.isCurrent(harness.token))
        } }
        runCurrent()
        assertTrue(harness.gate.complete(harness.token, true))
        assertTrue(result.await()) // 실행 콜백이 끝난 뒤에는 전달 성공을 만료로 뒤집지 않음
    }

    /** 실제 게이트의 화면·전달 콜백 순서만 기록하는 테스트용 주변 장치다. */
    private class Harness(nowMillis: () -> Long) {
        val gate = SafeDriveUnlockGate(nowMillis)
        lateinit var token: String
        var launchCount = 0
        val events = mutableListOf<String>()
        val closedTokens = mutableListOf<String>()

        /** 인증 화면을 여는 토큰과 닫는 토큰을 기록해 정리 경로를 확인한다. */
        suspend fun run(onLaunch: suspend () -> Unit = {}): Boolean = gate.run(
            showPrompt = {
                token = it
                events += "show"
            },
            closePrompt = {
                closedTokens += it
                events += "close"
            },
            launch = {
                launchCount++
                events += "launch"
                onLaunch()
            },
        )
    }
}
