package com.wemade.teslamacro.data.weather

import com.wemade.teslamacro.domain.macro.Condition
import com.wemade.teslamacro.domain.macro.ConditionEvaluator
import com.wemade.teslamacro.domain.macro.ForecastMetric
import com.wemade.teslamacro.domain.macro.GeoPoint
import com.wemade.teslamacro.domain.macro.Reading
import com.wemade.teslamacro.domain.macro.TimeContext
import com.wemade.teslamacro.domain.model.VehicleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 예보 조회와 예보 조건.
 *
 * 차의 외기온은 지금만 말한다 — "내일 아침 영하면 예열"을 하려면 앞을 보는 값이 필요하다.
 * 동시에, 예보를 못 받았을 때 차가 멋대로 움직이면 안 된다.
 */
class OpenMeteoClientTest {

    private val client = OpenMeteoClient()

    private val sample = """
        {"daily":{"time":["2026-08-22"],
         "temperature_2m_min":[-3.4],
         "temperature_2m_max":[8.1],
         "precipitation_probability_max":[70]}}
    """.trimIndent()

    @Test
    fun `오늘 최저 최고 강수확률을 뽑는다`() {
        val forecast = client.parse(sample, nowMillis = 1_000L)
        assertEquals(-3.4, forecast.todayMinTempC!!, 0.001)
        assertEquals(8.1, forecast.todayMaxTempC!!, 0.001)
        assertEquals(70, forecast.rainChancePercent)
        assertEquals(1_000L, forecast.fetchedAtMillis)
    }

    /** 어떤 항목은 빠져서 올 수 있다. 없는 값을 0으로 채우면 매크로가 오작동한다 */
    @Test
    fun `없는 항목은 null로 남는다`() {
        val partial = """{"daily":{"time":["2026-08-22"],"temperature_2m_min":[1.0]}}"""
        val forecast = client.parse(partial, nowMillis = 0L)
        assertEquals(1.0, forecast.todayMinTempC!!, 0.001)
        assertNull(forecast.todayMaxTempC)
        assertNull(forecast.rainChancePercent)
    }

    /** 집 앞 주차 칸까지 남의 서버에 알릴 이유가 없다 — 소수 2자리(약 1km)로 자른다 */
    @Test
    fun `좌표는 소수 두 자리까지만 보낸다`() {
        val url = client.urlFor(GeoPoint(37.566826, 126.978656))
        assertTrue(url, url.contains("latitude=37.57"))
        assertTrue(url, url.contains("longitude=126.98"))
        assertFalse(url, url.contains("37.566826"))
    }

    // ---- 조건 판정 ----

    private fun readingWith(forecast: com.wemade.teslamacro.domain.macro.WeatherForecast?) =
        Reading(VehicleSnapshot.Empty, TimeContext.of(0L), null, forecast)

    @Test
    fun `최저 기온이 임계 이하면 충족된다`() {
        val condition = Condition.ForecastInRange(ForecastMetric.MIN_TEMP, lte = 0.0)
        val cold = client.parse(sample, 0L)
        assertTrue(ConditionEvaluator.holds(condition, readingWith(cold)))
    }

    @Test
    fun `임계를 넘으면 충족되지 않는다`() {
        val condition = Condition.ForecastInRange(ForecastMetric.MAX_TEMP, gte = 30.0)
        assertFalse(ConditionEvaluator.holds(condition, readingWith(client.parse(sample, 0L))))
    }

    /** 예보를 못 받았으면 불충족이다. 모르는 걸 참으로 치면 차가 멋대로 움직인다 */
    @Test
    fun `예보가 없으면 불충족이다`() {
        val condition = Condition.ForecastInRange(ForecastMetric.MIN_TEMP, lte = 0.0)
        assertFalse(ConditionEvaluator.holds(condition, readingWith(null)))
    }

    @Test
    fun `그 항목만 못 받았어도 불충족이다`() {
        val partial = client.parse("""{"daily":{"temperature_2m_min":[1.0]}}""", 0L)
        val condition = Condition.ForecastInRange(ForecastMetric.RAIN_CHANCE, gte = 60.0)
        assertFalse(ConditionEvaluator.holds(condition, readingWith(partial)))
    }
}
