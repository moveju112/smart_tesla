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
        knownPresenceBeforeRestart: Boolean? = null,
    ) = engine.evaluate(rules, previous, current, lastFired, knownPresenceBeforeRestart)

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
    fun `깊은 유휴(2분 폴링)에서도 시각 트리거를 놓치지 않는다`() {
        // 17:59 → 18:01 표본이면 18:00을 건너뛰지만, (직전, 현재] 창 판정으로 잡는다
        val fired = evaluate(
            rules = listOf(rule(listOf(Trigger.AtTime(18 * 60)))),
            previous = reading(minutesOfDay = 18 * 60 - 1),
            current = reading(minutesOfDay = 18 * 60 + 1),
        )
        assertEquals(1, fired.size)
    }

    @Test
    fun `시각 트리거 소급은 15분까지만 — 몇 시간 뒤 뒷북 발동은 버린다`() {
        val fired = evaluate(
            rules = listOf(rule(listOf(Trigger.AtTime(8 * 60)))),
            previous = reading(minutesOfDay = 7 * 60),
            current = reading(minutesOfDay = 10 * 60),   // Doze로 3시간 공백
        )
        assertEquals(0, fired.size)
    }

    @Test
    fun `쿨다운 중에도 Always 래치는 갱신된다`() {
        // 쿨다운 중 조건 이탈을 래치가 봐야, 쿨다운이 끝난 뒤 재진입 없이도 발동한다
        val always = rule(
            triggers = listOf(Trigger.Always),
            conditions = listOf(Condition.InRange(Signal.INSIDE_TEMP, gte = 22.0, lte = 24.0)),
            cooldown = 300,
        )
        val base = defaultNow
        // t=0 발동 (조건 진입)
        evaluate(listOf(always), reading(inside = 30.0, epochMillis = base), reading(inside = 23.0, epochMillis = base))
        val firedAt = mapOf("test" to base)
        // t=60 쿨다운 중 조건 이탈 — 래치가 이 이탈을 기억해야 한다
        evaluate(listOf(always), reading(inside = 23.0, epochMillis = base + 60_000), reading(inside = 26.0, epochMillis = base + 60_000), firedAt)
        // t=200 쿨다운 중 재진입 — 발동은 막히지만 래치는 참으로
        evaluate(listOf(always), reading(inside = 26.0, epochMillis = base + 200_000), reading(inside = 23.0, epochMillis = base + 200_000), firedAt)
        // t=310 쿨다운 만료 후 이탈 → t=320 재진입이면 발동해야 한다
        evaluate(listOf(always), reading(inside = 23.0, epochMillis = base + 310_000), reading(inside = 26.0, epochMillis = base + 310_000), firedAt)
        val fired = evaluate(listOf(always), reading(inside = 26.0, epochMillis = base + 320_000), reading(inside = 23.0, epochMillis = base + 320_000), firedAt)
        assertEquals(1, fired.size)
    }

    @Test
    fun `조건이 막으면 어떤 조건이 막았는지 알려준다`() {
        // "왜 안 터졌지" 진단용 — 탑승은 감지됐는데 위치·시간 조건이 막은 경우를 로그로 본다
        val blocked = mutableListOf<Pair<String, List<Condition>>>()
        engine.evaluate(
            rules = listOf(
                rule(
                    triggers = listOf(Trigger.SignalBecomes(Signal.USER_PRESENT, true)),
                    conditions = listOf(
                        Condition.TimeWindow(18 * 60, 19 * 60),
                        Condition.NearLocation(latitude = 37.0, longitude = 127.0),
                    ),
                )
            ),
            previous = reading(userPresent = false),
            current = reading(userPresent = true, minutesOfDay = 9 * 60),
            lastFiredAtMillis = emptyMap(),
            onBlocked = { r, unmet -> blocked += r.name to unmet },
        )
        assertEquals(1, blocked.size)
        // 시간대(9시)와 위치(측위 없음) 둘 다 불충족으로 보고돼야 한다
        assertEquals(2, blocked[0].second.size)
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
    fun `재시작 전 이미 타 있었다는 기록이 있으면 탑승 트리거는 발동하지 않는다`() {
        // 0.8.22 실차: 주행 중 업데이트 설치로 앱이 되살아나 출근 안내가 오발동했다.
        // 예전 가드는 기어(D)를 봤는데 DRIVE 카테고리를 평소에 안 읽어 항상 UNKNOWN이었다
        val fired = evaluate(
            rules = listOf(rule(listOf(Trigger.SignalBecomes(Signal.USER_PRESENT, true)))),
            previous = null,
            current = reading(userPresent = true),
            knownPresenceBeforeRestart = true,
        )
        assertEquals(0, fired.size)
    }

    @Test
    fun `재시작 전 안 타고 있었으면 탑승 트리거는 발동한다`() {
        // 밤새 재부팅된 태블릿 — 첫 판정이 곧 탑승 순간이라 놓치면 안 된다
        val fired = evaluate(
            rules = listOf(rule(listOf(Trigger.SignalBecomes(Signal.USER_PRESENT, true)))),
            previous = null,
            current = reading(userPresent = true),
            knownPresenceBeforeRestart = false,
        )
        assertEquals(1, fired.size)
    }

    @Test
    fun `기록이 오래돼 없으면 예전처럼 1회 발동한다`() {
        // 호출부가 신선하지 않은 기록을 null로 걸러 넣는다
        val fired = evaluate(
            rules = listOf(rule(listOf(Trigger.SignalBecomes(Signal.USER_PRESENT, true)))),
            previous = null,
            current = reading(userPresent = true),
            knownPresenceBeforeRestart = null,
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
