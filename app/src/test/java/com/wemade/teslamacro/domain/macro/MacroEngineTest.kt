package com.wemade.teslamacro.domain.macro

import com.wemade.teslamacro.data.macro.MacroPresets
import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.domain.model.Door
import com.wemade.teslamacro.domain.model.ShiftState
import com.wemade.teslamacro.domain.model.Signal
import com.wemade.teslamacro.domain.model.StateCategory
import com.wemade.teslamacro.domain.model.VehicleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 매크로 판정 로직 검증.
 *
 * 규칙 한 줄: **트리거 중 하나가 방금 발생했고, 조건이 전부 참이면 실행한다.**
 * 여기가 틀리면 차가 멋대로 움직이거나 아무것도 안 하므로 가장 촘촘하게 본다.
 */
class MacroEngineTest {

    private val engine = MacroEngine()
    private val defaultNow = 1_000_000L

    private fun reading(
        inside: Double? = null,
        outside: Double? = null,
        doorOpen: Boolean = false,
        userPresent: Boolean? = null,
        shift: ShiftState = ShiftState.UNKNOWN,
        epochMillis: Long = defaultNow,
        minutesOfDay: Int = 9 * 60,
        dayOfWeek: Int = 1,
    ) = Reading(
        snapshot = VehicleSnapshot(
            timestampMillis = epochMillis,
            insideTempC = inside,
            outsideTempC = outside,
            isUserPresent = userPresent,
            shiftState = shift,
            doorOpen = mapOf(Door.DRIVER_FRONT to doorOpen),
        ),
        time = TimeContext(epochMillis, minutesOfDay, dayOfWeek),
    )

    private fun evaluate(
        rules: List<MacroRule>,
        previous: Reading?,
        current: Reading,
        lastFired: Map<String, Long> = emptyMap(),
    ) = engine.evaluate(rules, previous, current, lastFired)

    private fun rule(
        triggers: List<Trigger>,
        conditions: List<Condition> = emptyList(),
        cooldown: Int = 0,
    ) = MacroRule(
        id = "test",
        name = "테스트",
        triggers = triggers,
        conditions = conditions,
        actions = listOf(ActionStep.Run(VehicleCommand.ClimateOn)),
        cooldownSeconds = cooldown,
    )

    // ---- 트리거와 조건의 분리 ----

    @Test
    fun `트리거가 없으면 조건이 다 맞아도 발동하지 않는다`() {
        // 이게 핵심 안전장치다. 조건만으로 발동하면 폴링마다 계속 실행된다
        val onlyConditions = rule(
            triggers = emptyList(),
            conditions = listOf(Condition.InRange(Signal.INSIDE_TEMP, gte = 20.0)),
        )
        assertTrue(evaluate(listOf(onlyConditions), reading(inside = 31.0), reading(inside = 31.0)).isEmpty())
    }

    @Test
    fun `조건이 없으면 트리거만으로 발동한다`() {
        val fired = evaluate(
            rules = listOf(rule(listOf(Trigger.SignalBecomes(Signal.DOOR_DRIVER_FRONT, true)))),
            previous = reading(doorOpen = false),
            current = reading(doorOpen = true),
        )
        assertEquals(1, fired.size)
    }

    @Test
    fun `재시작 직후 이미 타 있으면 탑승 트리거는 1회 발동한다`() {
        // 직전 값이 없으면(재부팅) 엣지를 못 보지만, 탑승만은 놓치면 안 된다
        val fired = evaluate(
            rules = listOf(rule(listOf(Trigger.SignalBecomes(Signal.USER_PRESENT, true)))),
            previous = null,
            current = reading(userPresent = true),
        )
        assertEquals(1, fired.size)
    }

    @Test
    fun `재시작 직후 탑승 외 신호는 발동하지 않는다`() {
        // 예: 문 열림 매크로가 앱 시작만으로 터지면 오발동이다
        val fired = evaluate(
            rules = listOf(rule(listOf(Trigger.SignalBecomes(Signal.DOOR_DRIVER_FRONT, true)))),
            previous = null,
            current = reading(doorOpen = true),
        )
        assertEquals(0, fired.size)
    }

    @Test
    fun `트리거는 하나만 맞아도 발동한다`() {
        val multi = rule(
            triggers = listOf(
                Trigger.SignalBecomes(Signal.DOOR_DRIVER_FRONT, true),
                Trigger.AtTime(18 * 60),
            )
        )
        val byTime = evaluate(
            rules = listOf(multi),
            previous = reading(minutesOfDay = 18 * 60 - 1),
            current = reading(minutesOfDay = 18 * 60),
        )
        assertEquals(1, byTime.size)
    }

    @Test
    fun `조건은 전부 맞아야 발동한다`() {
        val strict = rule(
            triggers = listOf(Trigger.SignalBecomes(Signal.DOOR_DRIVER_FRONT, true)),
            conditions = listOf(
                Condition.InRange(Signal.INSIDE_TEMP, gte = 27.0),
                Condition.SignalIs(Signal.PARKED, value = true),
            ),
        )
        // 온도는 맞지만 주차가 아니다
        val partial = evaluate(
            rules = listOf(strict),
            previous = reading(inside = 31.0, doorOpen = false, shift = ShiftState.DRIVE),
            current = reading(inside = 31.0, doorOpen = true, shift = ShiftState.DRIVE),
        )
        assertTrue(partial.isEmpty())

        val full = evaluate(
            rules = listOf(strict),
            previous = reading(inside = 31.0, doorOpen = false, shift = ShiftState.PARK),
            current = reading(inside = 31.0, doorOpen = true, shift = ShiftState.PARK),
        )
        assertEquals(1, full.size)
    }

    // ---- 여름 프리셋 ----

    @Test
    fun `문이 열리고 27도가 넘으면 여름 매크로가 발동한다`() {
        val preset = MacroPresets.summerBoarding()
        val fired = evaluate(
            rules = listOf(preset),
            previous = reading(inside = 31.0, doorOpen = false),
            current = reading(inside = 31.0, doorOpen = true),
        )
        assertEquals(listOf(preset.id), fired.map { it.id })
    }

    @Test
    fun `온도가 낮으면 문을 열어도 발동하지 않는다`() {
        val fired = evaluate(
            rules = listOf(MacroPresets.summerBoarding()),
            previous = reading(inside = 20.0, doorOpen = false),
            current = reading(inside = 20.0, doorOpen = true),
        )
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `문이 계속 열려 있는 상태는 트리거가 아니다`() {
        val fired = evaluate(
            rules = listOf(MacroPresets.summerBoarding()),
            previous = reading(inside = 31.0, doorOpen = true),
            current = reading(inside = 31.0, doorOpen = true),
        )
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `직전 상태가 없으면 트리거를 판정할 수 없어 발동하지 않는다`() {
        val fired = evaluate(
            rules = listOf(MacroPresets.summerBoarding()),
            previous = null,
            current = reading(inside = 31.0, doorOpen = true),
        )
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `아직 못 읽은 값은 조건 충족으로 보지 않는다`() {
        // 온도를 못 읽었는데 "27도 이상"이 참이 되면 엉뚱하게 통풍이 켜진다
        val fired = evaluate(
            rules = listOf(MacroPresets.summerBoarding()),
            previous = reading(inside = null, doorOpen = false),
            current = reading(inside = null, doorOpen = true),
        )
        assertTrue(fired.isEmpty())
    }

    // ---- 안전장치 ----

    @Test
    fun `쿨다운 안에서는 다시 발동하지 않는다`() {
        val preset = MacroPresets.summerBoarding()   // cooldown 600초
        val fired = evaluate(
            rules = listOf(preset),
            previous = reading(inside = 31.0, doorOpen = false),
            current = reading(inside = 31.0, doorOpen = true),
            lastFired = mapOf(preset.id to defaultNow - 300_000L),
        )
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `쿨다운이 지나면 다시 발동한다`() {
        val preset = MacroPresets.summerBoarding()
        val fired = evaluate(
            rules = listOf(preset),
            previous = reading(inside = 31.0, doorOpen = false),
            current = reading(inside = 31.0, doorOpen = true),
            lastFired = mapOf(preset.id to defaultNow - 700_000L),
        )
        assertEquals(1, fired.size)
    }

    @Test
    fun `꺼진 매크로는 조건이 맞아도 발동하지 않는다`() {
        val fired = evaluate(
            rules = listOf(MacroPresets.summerBoarding().copy(enabled = false)),
            previous = reading(inside = 31.0, doorOpen = false),
            current = reading(inside = 31.0, doorOpen = true),
        )
        assertTrue(fired.isEmpty())
    }

    // ---- 시각 트리거 ----

    @Test
    fun `지정한 분에 들어서는 순간 발동한다`() {
        val fired = evaluate(
            rules = listOf(rule(listOf(Trigger.AtTime(18 * 60)))),
            previous = reading(minutesOfDay = 18 * 60 - 1),
            current = reading(minutesOfDay = 18 * 60),
        )
        assertEquals(1, fired.size)
    }

    @Test
    fun `같은 분에 두 번 폴링해도 한 번만 발동한다`() {
        // 30초 폴링이면 같은 분에 두 번 들어온다. 여기서 중복 발동하면 안 된다
        val fired = evaluate(
            rules = listOf(rule(listOf(Trigger.AtTime(18 * 60)))),
            previous = reading(minutesOfDay = 18 * 60),
            current = reading(minutesOfDay = 18 * 60),
        )
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `트리거 요일이 맞지 않으면 발동하지 않는다`() {
        val fired = evaluate(
            rules = listOf(rule(listOf(Trigger.AtTime(18 * 60, days = setOf(6, 7))))),
            previous = reading(minutesOfDay = 18 * 60 - 1, dayOfWeek = 3),
            current = reading(minutesOfDay = 18 * 60, dayOfWeek = 3),
        )
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `요일을 비우면 매일 발동한다`() {
        (1..7).forEach { day ->
            val fired = evaluate(
                rules = listOf(rule(listOf(Trigger.AtTime(18 * 60)))),
                previous = reading(minutesOfDay = 18 * 60 - 1, dayOfWeek = day),
                current = reading(minutesOfDay = 18 * 60, dayOfWeek = day),
            )
            assertEquals("$day 요일에 발동해야 한다", 1, fired.size)
        }
    }

    // ---- 시간 조건 ----

    @Test
    fun `시간대 조건이 구간 안에서만 통과한다`() {
        val night = rule(
            triggers = listOf(Trigger.SignalBecomes(Signal.DOOR_DRIVER_FRONT, true)),
            conditions = listOf(Condition.TimeWindow(9 * 60, 18 * 60)),
        )
        val inside = evaluate(
            listOf(night),
            reading(doorOpen = false, minutesOfDay = 12 * 60),
            reading(doorOpen = true, minutesOfDay = 12 * 60),
        )
        assertEquals(1, inside.size)

        val outside = evaluate(
            listOf(night),
            reading(doorOpen = false, minutesOfDay = 20 * 60),
            reading(doorOpen = true, minutesOfDay = 20 * 60),
        )
        assertTrue(outside.isEmpty())
    }

    @Test
    fun `자정을 넘는 시간대도 처리한다`() {
        // 22:00~06:00 같은 구간이 끊기면 야간 매크로가 통째로 안 돈다
        val overnight = rule(
            triggers = listOf(Trigger.SignalBecomes(Signal.DOOR_DRIVER_FRONT, true)),
            conditions = listOf(Condition.TimeWindow(22 * 60, 6 * 60)),
        )
        listOf(23 * 60, 2 * 60).forEach { minutes ->
            val fired = evaluate(
                listOf(overnight),
                reading(doorOpen = false, minutesOfDay = minutes),
                reading(doorOpen = true, minutesOfDay = minutes),
            )
            assertEquals("$minutes 분에 통과해야 한다", 1, fired.size)
        }

        val daytime = evaluate(
            listOf(overnight),
            reading(doorOpen = false, minutesOfDay = 12 * 60),
            reading(doorOpen = true, minutesOfDay = 12 * 60),
        )
        assertTrue(daytime.isEmpty())
    }

    @Test
    fun `요일 조건이 걸러낸다`() {
        val weekdayOnly = rule(
            triggers = listOf(Trigger.SignalBecomes(Signal.DOOR_DRIVER_FRONT, true)),
            conditions = listOf(Condition.OnDays(setOf(1, 2, 3, 4, 5))),
        )
        val saturday = evaluate(
            listOf(weekdayOnly),
            reading(doorOpen = false, dayOfWeek = 6),
            reading(doorOpen = true, dayOfWeek = 6),
        )
        assertTrue(saturday.isEmpty())
    }

    // ---- 폴링 계획 ----

    @Test
    fun `시각 트리거만 있으면 차량 폴링을 요구하지 않는다`() {
        // 시각만 보는 매크로 때문에 인포테인먼트를 깨우면 방전된다
        assertTrue(rule(listOf(Trigger.AtTime(18 * 60))).requiredCategories.isEmpty())
    }

    @Test
    fun `프리셋이 필요로 하는 폴링 카테고리를 정확히 계산한다`() {
        assertEquals(
            setOf(StateCategory.BODY_CONTROLLER, StateCategory.CLIMATE),
            MacroPresets.summerBoarding().requiredCategories,
        )
    }

    // ---- 문장 변환 ----

    @Test
    fun `매크로가 사람이 읽는 문장으로 바뀐다`() {
        assertEquals(
            "운전석 도어 열림 시, 실내 온도 27℃ 이상이면",
            describeRule(MacroPresets.summerBoarding()),
        )
    }

    @Test
    fun `조건이 없으면 문장에서도 생략된다`() {
        assertEquals("매일 18:00에", describeRule(rule(listOf(Trigger.AtTime(18 * 60)))))
    }

    @Test
    fun `퇴근 예열 프리셋은 평일 18시 + 외부온도를 함께 본다`() {
        val preset = MacroPresets.eveningPrecondition().copy(enabled = true)

        val tooCold = evaluate(
            rules = listOf(preset),
            previous = reading(outside = 20.0, minutesOfDay = 18 * 60 - 1, dayOfWeek = 3),
            current = reading(outside = 20.0, minutesOfDay = 18 * 60, dayOfWeek = 3),
        )
        assertTrue(tooCold.isEmpty())

        val hot = evaluate(
            rules = listOf(preset),
            previous = reading(outside = 31.0, minutesOfDay = 18 * 60 - 1, dayOfWeek = 3),
            current = reading(outside = 31.0, minutesOfDay = 18 * 60, dayOfWeek = 3),
        )
        assertEquals(1, hot.size)

        val weekend = evaluate(
            rules = listOf(preset),
            previous = reading(outside = 31.0, minutesOfDay = 18 * 60 - 1, dayOfWeek = 7),
            current = reading(outside = 31.0, minutesOfDay = 18 * 60, dayOfWeek = 7),
        )
        assertTrue(weekend.isEmpty())
    }
}
