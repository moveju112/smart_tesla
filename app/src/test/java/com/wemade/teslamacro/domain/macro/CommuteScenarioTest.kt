package com.wemade.teslamacro.domain.macro

import com.wemade.teslamacro.domain.model.VehicleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 실사용 시나리오 백테스트 — "퇴근 안내" 매크로.
 *
 * 사용자가 실제로 만든 구성 그대로:
 * **운전자 탑승 시, [평일 + 18:00~19:10 + 저장 위치 반경 400m]이면 → 집으로 안내.**
 * (주소·좌표는 규칙에 따라 더미)
 */
class CommuteScenarioTest {

    private val engine = MacroEngine()

    // 저장해 둔 출발지 (더미 좌표)
    private val office = GeoPoint(37.5000, 127.0000)

    private val rule = MacroRule(
        id = "commute-home",
        name = "집 안내",
        triggers = listOf(
            Trigger.SignalBecomes(com.wemade.teslamacro.domain.model.Signal.USER_PRESENT, to = true),
        ),
        conditions = listOf(
            Condition.OnDays(setOf(1, 2, 3, 4, 5)),
            Condition.TimeWindow(fromMinutes = 18 * 60, toMinutes = 19 * 60 + 10),
            Condition.NearLocation(office.latitude, office.longitude, radiusMeters = 400),
        ),
        actions = listOf(ActionStep.Navigate(destinationName = "집", address = "더미로 10")),
        cooldownSeconds = 300,
    )

    /** 판정 한 번을 통째로 돌린다. previous → current 변화가 트리거의 재료다 */
    private fun fires(
        userPresentBefore: Boolean,
        userPresentNow: Boolean,
        minutesOfDay: Int,
        dayOfWeek: Int,
        location: GeoPoint?,
    ): Boolean {
        val previous = reading(userPresentBefore, minutesOfDay - 1, dayOfWeek, location)
        val current = reading(userPresentNow, minutesOfDay, dayOfWeek, location)
        return engine.evaluate(listOf(rule), previous, current, emptyMap()).isNotEmpty()
    }

    private fun reading(userPresent: Boolean, minutesOfDay: Int, dayOfWeek: Int, location: GeoPoint?) =
        Reading(
            snapshot = VehicleSnapshot(timestampMillis = 0L, isUserPresent = userPresent),
            time = TimeContext(minutesOfDay * 60_000L, minutesOfDay, dayOfWeek),
            location = location,
        )

    // ---- 정상 발동 ----

    @Test
    fun `평일 18시 30분, 회사 주차장에서 탑승 - 발동한다`() {
        // 반경 400m 안 (약 111m 거리)
        val nearOffice = GeoPoint(office.latitude + 0.001, office.longitude)
        assertTrue(
            fires(
                userPresentBefore = false, userPresentNow = true,
                minutesOfDay = 18 * 60 + 30, dayOfWeek = 3, location = nearOffice,
            )
        )
    }

    @Test
    fun `경계값 - 18시 00분 정각과 19시 10분에도 발동한다`() {
        val here = office
        assertTrue(fires(false, true, 18 * 60, 1, here))
        assertTrue(fires(false, true, 19 * 60 + 10, 5, here))
    }

    // ---- 걸러져야 하는 경우 ----

    @Test
    fun `17시 59분 - 시간대 밖이라 발동하지 않는다`() {
        assertEquals(false, fires(false, true, 17 * 60 + 59, 3, office))
    }

    @Test
    fun `19시 11분 - 시간대 밖이라 발동하지 않는다`() {
        assertEquals(false, fires(false, true, 19 * 60 + 11, 3, office))
    }

    @Test
    fun `토요일 - 요일 밖이라 발동하지 않는다`() {
        assertEquals(false, fires(false, true, 18 * 60 + 30, 6, office))
    }

    @Test
    fun `회사에서 1km 떨어진 곳 - 반경 밖이라 발동하지 않는다`() {
        val farAway = GeoPoint(office.latitude + 0.01, office.longitude)
        assertEquals(false, fires(false, true, 18 * 60 + 30, 3, farAway))
    }

    @Test
    fun `위치를 못 읽음 - 발동하지 않는다 (fail closed)`() {
        assertEquals(false, fires(false, true, 18 * 60 + 30, 3, location = null))
    }

    @Test
    fun `이미 타고 있던 상태 - 탑승 "순간"이 아니라 발동하지 않는다`() {
        assertEquals(false, fires(true, true, 18 * 60 + 30, 3, office))
    }

    @Test
    fun `쿨다운 5분 안의 재탑승 - 발동하지 않는다`() {
        val now = reading(true, 18 * 60 + 40, 3, office)
        val before = reading(false, 18 * 60 + 39, 3, office)
        // 2분 전에 이미 발동했다고 기록
        val lastFired = mapOf(rule.id to now.time.epochMillis - 2 * 60_000L)
        assertTrue(engine.evaluate(listOf(rule), before, now, lastFired).isEmpty())
    }
}
