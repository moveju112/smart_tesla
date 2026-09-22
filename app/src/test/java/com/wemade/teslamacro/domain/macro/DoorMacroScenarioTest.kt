package com.wemade.teslamacro.domain.macro

import com.wemade.teslamacro.domain.model.Door
import com.wemade.teslamacro.domain.model.Signal
import com.wemade.teslamacro.domain.model.VehicleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoorMacroScenarioTest {
    private val engine = MacroEngine()
    private val home = GeoPoint(0.0, 0.0)
    private val rule = MacroRule(
        id = "door-scenario", name = "문 열림 안내",
        triggers = listOf(Trigger.SignalBecomes(Signal.DOOR_DRIVER_FRONT, true)),
        conditions = listOf(Condition.NearLocation(0.0, 0.0, 400)),
        actions = listOf(ActionStep.Navigate("목적지", "테스트 주소")),
        cooldownSeconds = 0,
    )

    // 실제 관측 순서대로 문별 값과 탑승 상태를 분리한다.
    private fun reading(seconds: Long, driver: Boolean = false, passenger: Boolean = false,
                        trunk: Boolean = false, present: Boolean = false, location: GeoPoint? = home) = Reading(
        VehicleSnapshot(timestampMillis = seconds * 1000, isUserPresent = present,
            doorOpen = mapOf(Door.DRIVER_FRONT to driver, Door.PASSENGER_FRONT to passenger, Door.TRUNK to trunk)),
        TimeContext(seconds * 1000, 540, 1), location,
    )

    // 같은 엔진에 연속 표본을 넣어 보류 이벤트와 실제 엣지를 함께 검증한다.
    private fun evaluate(previous: Reading?, current: Reading, selected: MacroRule = rule,
                         lastFired: Map<String, Long> = emptyMap(), fresh: Boolean = true) =
        engine.evaluate(listOf(selected), previous, current, lastFired, allowPendingDoorRetry = fresh)

    // 트렁크·동승석은 운전석 트리거를 소비하지 않는다.
    @Test fun `트렁크 동승석 운전석 순서에서 운전석에만 한 번 실행한다`() {
        val closed = reading(0)
        val trunk = reading(2, trunk = true)
        val passenger = reading(4, trunk = true, passenger = true)
        val driver = reading(6, trunk = true, passenger = true, driver = true)
        assertTrue(evaluate(closed, trunk).isEmpty())
        assertTrue(evaluate(trunk, passenger).isEmpty())
        assertEquals(listOf(rule), evaluate(passenger, driver))
        assertTrue(evaluate(driver, reading(8, driver = true, present = true)).isEmpty())
    }

    // 열린 문을 유지한 채 늦게 타도 탑승을 문 열림으로 바꾸지 않는다.
    @Test fun `문 열고 오래 뒤에 착석해도 재발동하지 않는다`() {
        val opened = reading(2, driver = true)
        assertEquals(listOf(rule), evaluate(reading(0), opened))
        assertTrue(evaluate(opened, reading(180, driver = true, present = true)).isEmpty())
    }

    // 위치가 늦게 와도 이미 관측한 문 이벤트는 짧은 시간 동안 한 번만 이어간다.
    @Test fun `문 닫고 착석한 뒤 위치를 얻어도 30초 안이면 한 번 실행한다`() {
        val opened = reading(2, driver = true, location = null)
        assertTrue(evaluate(reading(0), opened).isEmpty())
        val boarded = reading(10, present = true)
        assertEquals(listOf(rule), evaluate(opened, boarded))
        assertTrue(evaluate(boarded, reading(12, present = true)).isEmpty())
    }

    // 다른 문을 만져도 원래 이벤트의 유효 시간이 늘어나지 않는다.
    @Test fun `보류 중 동승석 변화는 30초 만료를 연장하지 않는다`() {
        val opened = reading(2, driver = true, location = null)
        evaluate(reading(0), opened)
        val passenger = reading(20, driver = true, passenger = true, location = null)
        assertTrue(evaluate(opened, passenger).isEmpty())
        assertTrue(evaluate(passenger, reading(32, driver = true, passenger = true)).isEmpty())
    }

    // 늦게 범위에 들어오는 것은 문 열림 당시의 미확인과 다르다.
    @Test fun `확인된 범위 밖이면 나중에 집으로 이동해도 실행하지 않는다`() {
        val opened = reading(2, driver = true, location = GeoPoint(1.0, 1.0))
        evaluate(reading(0), opened)
        assertTrue(evaluate(opened, reading(10, driver = true)).isEmpty())
    }

    // 첫 조회의 열린 문은 이전 닫힘을 관측한 증거가 아니다.
    @Test fun `첫 표본에서 이미 열린 문은 위치가 나중에 와도 소급하지 않는다`() {
        val opened = reading(2, driver = true, location = null)
        evaluate(null, opened)
        assertTrue(evaluate(opened, reading(10, driver = true)).isEmpty())
    }

    // 해제한 감시를 늦은 GPS 응답이 되살리지 않는다.
    @Test fun `세션 종료와 규칙 수정은 보류 이벤트를 폐기한다`() {
        val opened = reading(2, driver = true, location = null)
        evaluate(reading(0), opened)
        engine.discardPendingDoorEvents()
        assertTrue(evaluate(opened, reading(10, driver = true)).isEmpty())
        evaluate(reading(12), reading(14, driver = true, location = null))
        assertTrue(evaluate(opened, reading(16, driver = true), rule.copy(name = "수정됨")).isEmpty())
    }

    // 다른 조건이 거부한 이벤트는 GPS 보류 대상으로 만들지 않는다.
    @Test fun `미착석 조건이 막으면 나중 착석해도 실행하지 않는다`() {
        val selected = rule.copy(conditions = rule.conditions + Condition.SignalIs(Signal.USER_PRESENT, true))
        val opened = reading(2, driver = true, location = null)
        evaluate(reading(0), opened, selected)
        assertTrue(evaluate(opened, reading(10, driver = true, present = true), selected).isEmpty())
    }

    // 쿨다운 중에 열린 문을 쿨다운이 끝난 뒤 소급하지 않는다.
    @Test fun `쿨다운 중 문 열림은 GPS 보류되지 않는다`() {
        val selected = rule.copy(cooldownSeconds = 10)
        val opened = reading(2, driver = true, location = null)
        evaluate(reading(0), opened, selected, mapOf(rule.id to 0L))
        assertTrue(evaluate(opened, reading(12, driver = true), selected).isEmpty())
    }

    // BLE가 실패한 동안 위치만 돌아왔다고 차량 동작을 시작하지 않는다.
    @Test fun `새 차체 응답이 없으면 보류 실행을 기다린다`() {
        val opened = reading(2, driver = true, location = null)
        evaluate(reading(0), opened)
        val located = reading(10, driver = true)
        assertTrue(evaluate(opened, located, fresh = false).isEmpty())
        assertEquals(listOf(rule), evaluate(located, reading(12, driver = true)))
    }

    // 문+예보 규칙도 비동기 조회가 끝날 때 한 번만 평가한다.
    @Test fun `예보만 늦게 도착해도 조건을 만족하면 실행한다`() {
        val selected = rule.copy(conditions = rule.conditions + Condition.ForecastInRange(ForecastMetric.MAX_TEMP, gte = 20.0))
        val opened = reading(2, driver = true)
        assertTrue(evaluate(reading(0), opened, selected).isEmpty())
        val forecast = reading(10, driver = true).copy(weather = WeatherForecast(todayMaxTempC = 25.0))
        assertEquals(listOf(selected), evaluate(opened, forecast, selected))
        assertTrue(evaluate(forecast, forecast.copy(time = TimeContext(12_000, 540, 1)), selected).isEmpty())
    }

    // 양쪽 문 트리거를 사용하더라도 쿨다운은 공유한다.
    @Test fun `동승석과 운전석 연속 열림은 설정된 쿨다운으로 중복을 막는다`() {
        val selected = rule.copy(triggers = rule.triggers + Trigger.SignalBecomes(Signal.DOOR_PASSENGER_FRONT, true),
            cooldownSeconds = 300)
        val passenger = reading(2, passenger = true)
        assertEquals(listOf(selected), evaluate(reading(0), passenger, selected))
        assertTrue(evaluate(passenger, reading(4, passenger = true, driver = true), selected,
            mapOf(rule.id to 2_000L)).isEmpty())
    }

    // 불확실한 GPS 중심점만 반경 안이라고 자동 실행하지 않는다.
    @Test fun `측위 나이 오차 미래 시각을 확인한다`() {
        val condition = rule.conditions.single()
        assertTrue(ConditionEvaluator.holds(condition, reading(100, location = home.copy(accuracyMeters = 20.0, observedAtMillis = 99_000))))
        assertFalse(ConditionEvaluator.holds(condition, reading(130, location = home.copy(observedAtMillis = 0))))
        assertFalse(ConditionEvaluator.holds(condition, reading(100, location = home.copy(observedAtMillis = 101_000))))
        assertFalse(ConditionEvaluator.holds(condition, reading(100, location = home.copy(accuracyMeters = 500.0))))
        assertFalse(ConditionEvaluator.holds(condition, reading(100, location = home.copy(accuracyMeters = Double.NaN))))
    }
}
