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
 * 핵심 규칙 둘:
 * 1. **문턱에서 발동한다** — 갖춰져 있는 동안 폴링마다 반복 발동하면 안 된다.
 * 2. **"이미 참"도 첫 판정·탑승 직후엔 1회 발동한다** — 22.5℃에 "22~24℃면 통풍"을
 *    만들었더니 영영 안 터진 실사용 버그의 회귀 방지.
 *
 * 엔진은 룰별 래치를 기억하므로 같은 인스턴스로 표본을 순서대로 먹인다.
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

    private fun reading(insideTemp: Double?, present: Boolean = true) = Reading(
        snapshot = VehicleSnapshot(
            timestampMillis = 0L,
            insideTempC = insideTemp,
            isUserPresent = present,
        ),
        time = TimeContext(0L, minutesOfDay = 9 * 60, dayOfWeek = 1),
    )

    private var previous: Reading? = null

    /** 표본 하나를 먹이고 발동한 룰 id를 돌려준다. 직전 표본은 자동으로 이어진다 */
    private fun step(
        insideTemp: Double?,
        present: Boolean = true,
        rules: List<MacroRule> = listOf(vent3, vent2),
    ): List<String> {
        val current = reading(insideTemp, present)
        val fired = engine.evaluate(rules, previous, current, emptyMap()).map { it.id }
        previous = current
        return fired
    }

    @Test
    fun `29도에서 27도로 내려오면 3단 구간만 발동한다`() {
        assertTrue(step(29.0).isEmpty())
        assertEquals(listOf("vent3"), step(27.0))
    }

    @Test
    fun `구간 안에 머무는 동안은 다시 발동하지 않는다`() {
        step(29.0)
        assertEquals(listOf("vent3"), step(27.0))
        // 문턱은 이미 지났다. 27 → 26.5는 같은 구간 안 이동일 뿐
        assertTrue(step(26.5).isEmpty())
    }

    @Test
    fun `27도에서 25도로 내려오면 2단 구간이 발동한다`() {
        step(29.0)
        step(27.0)
        assertEquals(listOf("vent2"), step(25.0))
    }

    @Test
    fun `구간을 나갔다 다시 들어오면 또 발동한다`() {
        step(29.0)
        step(27.0)
        step(25.0)
        assertEquals(listOf("vent3"), step(27.0))
    }

    @Test
    fun `만들 때 이미 조건 안이면 첫 판정에서 1회 발동한다`() {
        // 22.5℃에 "22~24℃" 매크로를 만든 실사용 케이스 — 첫 판정 발동, 이후 침묵
        assertEquals(listOf("vent3"), step(27.0))
        assertTrue(step(27.0).isEmpty())
    }

    @Test
    fun `탑승하면 조건이 이미 참이어도 다시 1회 발동한다`() {
        assertEquals(listOf("vent3"), step(27.0))
        // 하차 — 빈 차에선 온도가 동결된 채 참으로 남는다
        assertTrue(step(27.0, present = false).isEmpty())
        // 재탑승 — 래치 리셋으로 1회 발동, 머무는 동안은 침묵
        assertEquals(listOf("vent3"), step(27.0, present = true))
        assertTrue(step(27.0, present = true).isEmpty())
    }

    @Test
    fun `껐다 켜면 조건이 이미 참일 때 다시 1회 발동한다`() {
        assertEquals(listOf("vent3"), step(27.0))
        // 끄면 래치를 잊는다
        assertTrue(step(27.0, rules = listOf(vent3.copy(enabled = false), vent2)).isEmpty())
        // 다시 켜면 "이미 참"도 1회 발동
        assertEquals(listOf("vent3"), step(27.0))
    }

    @Test
    fun `온도를 못 읽으면 발동하지 않는다`() {
        step(29.0)
        assertTrue(step(null).isEmpty())
    }

    @Test
    fun `조건 없는 항상 트리거는 절대 발동하지 않는다`() {
        val noCondition = vent3.copy(id = "none", conditions = emptyList())
        assertTrue(step(29.0, rules = listOf(noCondition)).isEmpty())
        assertTrue(step(27.0, rules = listOf(noCondition)).isEmpty())
    }
}
