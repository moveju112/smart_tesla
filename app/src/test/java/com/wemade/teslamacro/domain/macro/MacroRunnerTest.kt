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

    /** 하차 종료가 이전 열선 타이머를 취소해 재탑승 통풍을 뒤늦게 끄지 않는다. */
    @Test
    fun `seat exit cancels old heater timer`() = runTest {
        val presets = com.wemade.teslamacro.data.macro.SeatComfortPresets.defaults()
        val heater = presets.single { it.id == "preset-seat-driver-heat" }
        val exit = presets.single { it.id == "preset-seat-exit-off" }
        val cooler = presets.single { it.id == "preset-seat-driver-cool-3" }
        val runner = MacroRunner(gateway, this, MutableStateFlow<Reading?>(readingWith(10.0)),
            now = { currentTimeMs() }, diagnosticLogger = {})
        runner.launch(heater, 0L)
        advanceTimeBy(1000L)
        assertEquals(1, sent.size)
        runner.launch(exit, currentTimeMs())
        advanceTimeBy(1000L)
        runner.launch(cooler, currentTimeMs())
        advanceTimeBy(1000L)
        val afterNewBoarding = sent.toList()
        advanceTimeBy(900_000L)
        advanceUntilIdle()
        assertEquals(afterNewBoarding, sent.toList())
        assertTrue(heater.id !in runner.running.value)
    }

    /** 열선은 900초 전에는 끄지 않고 시간이 지난 뒤 정확히 한 번 끈다. */
    @Test
    fun `preset heater stops at fifteen minutes`() = runTest {
        val heater = com.wemade.teslamacro.data.macro.SeatComfortPresets.defaults()
            .single { it.id == "preset-seat-passenger-heat" }
        val runner = MacroRunner(gateway, this, MutableStateFlow<Reading?>(readingWith(10.0)),
            now = { currentTimeMs() }, diagnosticLogger = {})
        runner.launch(heater, 0L)
        advanceTimeBy(899_999L)
        assertEquals(1, sent.size)
        advanceUntilIdle()
        assertEquals(2, sent.size)
        assertEquals((heater.actions.last() as ActionStep.Run).command, sent.last())
    }

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
        val runner = MacroRunner(
            gateway, this, reading,
            now = { currentTimeMs() },
            diagnosticLogger = {},
        )

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
        val runner = MacroRunner(
            gateway, this, reading,
            now = { currentTimeMs() },
            diagnosticLogger = {},
        )

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
        val runner = MacroRunner(
            gateway, this, reading,
            now = { currentTimeMs() },
            diagnosticLogger = {},
        )

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
        val runner = MacroRunner(
            gateway, this, reading,
            now = { currentTimeMs() },
            diagnosticLogger = {},
        )

        runner.launch(rule(ActionStep.Wait(120)), nowMillis = 0L)
        advanceTimeBy(1_000)

        val progress = runner.progress.value["r"]
        assertTrue("대기 중이면 종료 시각이 있어야 한다", progress?.waitEndsAtMillis != null)
        assertEquals(119, progress?.remainingSeconds(currentTimeMs()))
    }

    @Test
    fun `중단하면 대기가 즉시 끊긴다`() = runTest {
        val reading = MutableStateFlow<Reading?>(readingWith(30.0))
        val runner = MacroRunner(
            gateway, this, reading,
            now = { currentTimeMs() },
            diagnosticLogger = {},
        )

        runner.launch(
            rule(ActionStep.Wait(600), ActionStep.Run(VehicleCommand.ClimateOff)),
            nowMillis = 0L,
        )
        advanceTimeBy(1_000)
        runner.cancelAll()
        advanceUntilIdle()

        assertTrue("중단했으면 이후 명령이 나가면 안 된다", sent.isEmpty())
    }

    @Test
    fun `스텔스 충전 설정을 바꾸고 다음 걸음을 계속 실행한다`() = runTest {
        val reading = MutableStateFlow<Reading?>(readingWith(30.0))
        val changes = mutableListOf<Boolean>()
        val runner = MacroRunner(
            gateway,
            this,
            reading,
            stealthChargingSetter = {
                changes += it
                Result.success(Unit)
            },
            now = { currentTimeMs() },
            diagnosticLogger = {},
        )

        runner.launch(
            rule(
                ActionStep.SetStealthCharging(true),
                ActionStep.Run(VehicleCommand.ClimateOff),
            ),
            nowMillis = 0L,
        )
        advanceUntilIdle()

        assertEquals(listOf(true), changes)
        assertEquals(listOf(VehicleCommand.ClimateOff), sent.toList())
        assertTrue(runner.log.value.any { it.message == "스텔스 충전 1회 켜기" })
    }

    /** 가상 시계의 현재 시각. 실제 벽시계를 쓰면 테스트가 흔들린다 */
    /** 실행 중 무시한 요청은 쿨다운을 갱신하지 않는다. */
    @Test
    fun `실행 수락 기록은 중복 요청에서 반복되지 않는다`() = runTest {
        val runner = MacroRunner(gateway, this, MutableStateFlow(readingWith(30.0)),
            now = { currentTimeMs() }, diagnosticLogger = {})
        var accepted = 0
        val waiting = rule(ActionStep.Wait(10))
        runner.launch(waiting, 0L, onAccepted = { accepted++ })
        advanceTimeBy(1_000)
        runner.launch(waiting, 1_000L, onAccepted = { accepted++ })
        advanceUntilIdle()
        assertEquals(1, accepted)
        runner.launch(waiting, 10_000L, onAccepted = { accepted++ })
        advanceUntilIdle()
        assertEquals(2, accepted)
    }

    /** 저장 실패 시 차량 명령을 먼저 보내면 재시작 중복 방지가 무너진다. */
    @Test
    fun `실행 기록 저장 실패는 명령을 보내지 않고 알린다`() = runTest {
        val runner = MacroRunner(gateway, this, MutableStateFlow(readingWith(30.0)), diagnosticLogger = {})
        runner.launch(rule(ActionStep.Run(VehicleCommand.ClimateOn)), 0L,
            onAccepted = { throw java.io.IOException("테스트 저장 실패") })
        advanceUntilIdle()
        assertTrue(sent.isEmpty())
        assertTrue(runner.running.value.isEmpty())
        assertTrue(runner.log.value.last().isError)
        assertTrue(runner.log.value.last().message.contains("실행 기록 저장 실패"))
    }

    /** 저장 대기 중 사용자 중단도 즉시 잡을 취소해 다음 차량 명령을 막는다. */
    @Test
    fun `실행 기록 저장 중 취소하면 명령을 보내지 않는다`() = runTest {
        val runner = MacroRunner(gateway, this, MutableStateFlow(readingWith(30.0)), diagnosticLogger = {})
        runner.launch(rule(ActionStep.Run(VehicleCommand.ClimateOn)), 0L,
            onAccepted = { kotlinx.coroutines.delay(10_000) })
        advanceTimeBy(1_000)
        runner.cancelAll()
        advanceUntilIdle()
        assertTrue(sent.isEmpty())
        assertTrue(runner.running.value.isEmpty())
    }

    /** 안내 실패 뒤 다른 단계는 유지하되 전체 성공으로 오인시키지 않는다. */
    @Test
    fun `안내 요청 실패는 최종 결과에도 남는다`() = runTest {
        val runner = MacroRunner(gateway, this, MutableStateFlow(readingWith(30.0)),
            navigator = { _, _ -> Result.failure(IllegalStateException("실행 불가")) }, diagnosticLogger = {})
        runner.launch(rule(ActionStep.Navigate("목적지", "테스트 주소"), ActionStep.Run(VehicleCommand.ClimateOn)), 0L)
        advanceUntilIdle()
        assertEquals(listOf(VehicleCommand.ClimateOn), sent)
        assertTrue(runner.log.value.last().isError)
        assertTrue(runner.log.value.last().message.contains("1단계"))
    }

    /** 의존 코드 예외도 실행 상태를 정리하며 서비스 전체로 전파하지 않는다. */
    @Test
    fun `안내 예외는 오류 종료로 기록한다`() = runTest {
        val runner = MacroRunner(gateway, this, MutableStateFlow(readingWith(30.0)),
            navigator = { _, _ -> error("테스트 예외") }, diagnosticLogger = {})
        runner.launch(rule(ActionStep.Navigate("목적지", "테스트 주소")), 0L)
        advanceUntilIdle()
        assertTrue(runner.running.value.isEmpty())
        assertTrue(runner.log.value.last().message.contains("오류로 중단"))
    }

    /** 가상 시각을 러너에 주입한다. */
    private fun TestScope.currentTimeMs(): Long = testScheduler.currentTime
}
