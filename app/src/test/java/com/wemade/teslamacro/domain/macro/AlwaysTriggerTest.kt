package com.wemade.teslamacro.domain.macro

import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.domain.model.Level
import com.wemade.teslamacro.domain.model.SeatPosition
import com.wemade.teslamacro.domain.model.Signal
import com.wemade.teslamacro.domain.model.VehicleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "조건이 되면 (항상 감시)" 트리거 검증.
 *
 * 핵심 규칙: **문턱에서만 발동한다** — 안 갖춰졌다가 갖춰지는 순간 1회.
 * 폴링마다 반복 발동하면 차가 쉴 새 없이 명령을 받으므로 이게 깨지면 안 된다.
 */
class AlwaysTriggerTest {

    private val engine = MacroEngine()

    /** 실사용 시나리오 — 에어컨 켜두면 온도 따라 통풍이 단계적으로 내려간다 */
    private val vent3 = bandRule("vent3", gte = 26.0, lte = 28.0, level = Level.HIGH)
    private val vent2 = bandRule("vent2", gte = 24.0, lte = 26.0, level = Level.MEDIUM)

    private fun bandRule(id: String, gte: Double, lte: Double, level: Level) = MacroRule(
        id = id,
        name = id,
        triggers = listOf(Trigger.Always),
        conditions = listOf(Condition.InRange(Signal.INSIDE_TEMP, gte = gte, lte = lte)),
        actions = listOf(
            ActionStep.Run(VehicleCommand.SetSeatCooler(SeatPosition.FRONT_LEFT, level)),
        ),
        cooldownSeconds = 0,
    )

    private fun reading(insideTemp: Double?) = Reading(
        snapshot = VehicleSnapshot(timestampMillis = 0L, insideTempC = insideTemp),
        time = TimeContext(0L, minutesOfDay = 9 * 60, dayOfWeek = 1),
    )

    private fun fired(previous: Double?, current: Double?): List<String> =
        engine.evaluate(
            listOf(vent3, vent2),
            previous?.let { reading(it) },
            reading(current),
            emptyMap(),
        ).map { it.id }

    @Test
    fun `29도에서 27도로 내려오면 3단 구간만 발동한다`() {
        assertEquals(listOf("vent3"), fired(previous = 29.0, current = 27.0))
    }

    @Test
    fun `구간 안에 머무는 동안은 다시 발동하지 않는다`() {
        // 문턱은 이미 지났다. 27 → 26.5는 같은 구간 안 이동일 뿐
        assertTrue(fired(previous = 27.0, current = 26.5).isEmpty())
    }

    @Test
    fun `27도에서 25도로 내려오면 2단 구간이 발동한다`() {
        assertEquals(listOf("vent2"), fired(previous = 27.0, current = 25.0))
    }

    @Test
    fun `구간을 나갔다 다시 들어오면 또 발동한다`() {
        assertEquals(listOf("vent3"), fired(previous = 25.0, current = 27.0))
    }

    @Test
    fun `직전 값이 없으면(앱 시작 직후) 발동하지 않는다`() {
        // 이미 조건 안에서 시작해도 침묵 — 재시작할 때마다 명령이 나가면 안 된다
        assertTrue(fired(previous = null, current = 27.0).isEmpty())
    }

    @Test
    fun `온도를 못 읽으면 발동하지 않는다`() {
        assertTrue(fired(previous = 29.0, current = null).isEmpty())
    }

    @Test
    fun `조건 없는 항상 트리거는 절대 발동하지 않는다`() {
        val noCondition = vent3.copy(id = "none", conditions = emptyList())
        val result = engine.evaluate(
            listOf(noCondition), reading(29.0), reading(27.0), emptyMap(),
        )
        assertTrue(result.isEmpty())
    }
}
