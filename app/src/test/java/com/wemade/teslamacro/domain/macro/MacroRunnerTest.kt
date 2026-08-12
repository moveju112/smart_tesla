package com.wemade.teslamacro.domain.macro

import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.domain.gateway.EnrollmentState
import com.wemade.teslamacro.domain.gateway.LinkState
import com.wemade.teslamacro.domain.gateway.VehicleGateway
import com.wemade.teslamacro.domain.model.Signal
import com.wemade.teslamacro.domain.model.StateCategory
import com.wemade.teslamacro.domain.model.VehicleSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 대기 동작 검증.
 *
 * 대기는 눈으로 확인하기 가장 어려운 부분이다 (5분을 기다려봐야 안다).
 * 가상 시계로 시간을 앞당겨 실제로 기다리지 않고 검증한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MacroRunnerTest {

    private val sent = mutableListOf<VehicleCommand>()

    private val gateway = object : VehicleGateway {
        override val linkState: StateFlow<LinkState> =
            MutableStateFlow(LinkState.Ready)
        override val enrollmentState: StateFlow<EnrollmentState> =
            MutableStateFlow(EnrollmentState.Enrolled)

        override suspend fun connect(vin: String, allowProbe: Boolean) = Result.success(Unit)
        override suspend fun disconnect() = Unit
        override suspend fun requestKeyEnrollment() = Result.success(Unit)
        override suspend fun send(command: VehicleCommand): Result<Unit> {
            sent += command
            return Result.success(Unit)
        }

        override suspend fun read(category: StateCategory) =
            Result.success(VehicleSnapshot.Empty)
    }

    private fun readingWith(inside: Double?): Reading = Reading(
        snapshot = VehicleSnapshot(timestampMillis = 1L, insideTempC = inside),
        time = TimeContext(1L, 9 * 60, 1),
    )

    private fun rule(vararg steps: ActionStep) = MacroRule(
        id = "r",
        name = "테스트",
        triggers = listOf(Trigger.AtTime(0)),
        actions = steps.toList(),
    )

    @Test
    fun `고정 대기가 지나야 다음 명령이 나간다`() = runTest {
        val reading = MutableStateFlow<Reading?>(readingWith(30.0))
        val runner = MacroRunner(gateway, TestScope(testScheduler), reading, now = { currentTimeMs() })

        runner.launch(
            rule(
                ActionStep.Run(VehicleCommand.ClimateOn),
                ActionStep.Wait(300),
                ActionStep.Run(VehicleCommand.ClimateOff),
            ),
            nowMillis = 0L,
        )

        advanceTimeBy(1_000)
        assertEquals(listOf(VehicleCommand.ClimateOn), sent.toList())

        // 5분이 지나기 전에는 두 번째 명령이 나가면 안 된다
        advanceTimeBy(299_000)
        assertEquals(1, sent.size)

        advanceUntilIdle()
        assertEquals(2, sent.size)
    }

    @Test
    fun `조건이 맞으면 대기를 즉시 끝낸다`() = runTest {
        val reading = MutableStateFlow<Reading?>(readingWith(31.0))
        val runner = MacroRunner(gateway, TestScope(testScheduler), reading, now = { currentTimeMs() })

        runner.launch(
            rule(
                ActionStep.WaitUntil(
                    condition = Condition.InRange(Signal.INSIDE_TEMP, lte = 24.0),
                    timeoutSeconds = 600,
                ),
                ActionStep.Run(VehicleCommand.ClimateOff),
            ),
            nowMillis = 0L,
        )

        advanceTimeBy(5_000)
        assertTrue("아직 31℃라 대기해야 한다", sent.isEmpty())

        // 차가 식었다
        reading.value = readingWith(23.5)
        advanceTimeBy(2_000)
        assertEquals(listOf(VehicleCommand.ClimateOff), sent.toList())
    }

    @Test
    fun `조건이 끝내 안 맞아도 시간이 지나면 다음으로 넘어간다`() = runTest {
        // 무한 대기하면 매크로가 영원히 안 끝나고 다음 발동도 막힌다
        val reading = MutableStateFlow<Reading?>(readingWith(31.0))
        val runner = MacroRunner(gateway, TestScope(testScheduler), reading, now = { currentTimeMs() })

        runner.launch(
            rule(
                ActionStep.WaitUntil(
                    condition = Condition.InRange(Signal.INSIDE_TEMP, lte = 24.0),
                    timeoutSeconds = 60,
                ),
                ActionStep.Run(VehicleCommand.ClimateOff),
            ),
            nowMillis = 0L,
        )

        advanceUntilIdle()
        assertEquals(listOf(VehicleCommand.ClimateOff), sent.toList())
        assertTrue(runner.log.value.any { it.isError && it.message.contains("시간 초과") })
    }

    @Test
    fun `대기 중에는 남은 시간이 진행 상황에 노출된다`() = runTest {
        val reading = MutableStateFlow<Reading?>(readingWith(30.0))
        val runner = MacroRunner(gateway, TestScope(testScheduler), reading, now = { currentTimeMs() })

        runner.launch(rule(ActionStep.Wait(120)), nowMillis = 0L)
        advanceTimeBy(1_000)

        val progress = runner.progress.value["r"]
        assertTrue("대기 중이면 종료 시각이 있어야 한다", progress?.waitEndsAtMillis != null)
        assertEquals(119, progress?.remainingSeconds(currentTimeMs()))
    }

    @Test
    fun `중단하면 대기가 즉시 끊긴다`() = runTest {
        val reading = MutableStateFlow<Reading?>(readingWith(30.0))
        val runner = MacroRunner(gateway, TestScope(testScheduler), reading, now = { currentTimeMs() })

        runner.launch(
            rule(ActionStep.Wait(600), ActionStep.Run(VehicleCommand.ClimateOff)),
            nowMillis = 0L,
        )
        advanceTimeBy(1_000)
        runner.cancelAll()
        advanceUntilIdle()

        assertTrue("중단했으면 이후 명령이 나가면 안 된다", sent.isEmpty())
    }

    /** 가상 시계의 현재 시각. 실제 벽시계를 쓰면 테스트가 흔들린다 */
    private fun TestScope.currentTimeMs(): Long = testScheduler.currentTime
}
