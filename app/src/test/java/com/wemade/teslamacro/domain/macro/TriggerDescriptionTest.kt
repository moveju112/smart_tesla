package com.wemade.teslamacro.domain.macro

import org.junit.Assert.assertEquals
import org.junit.Test

class TriggerDescriptionTest {
    /** 양쪽 경계가 있는 예보 조건을 하한만 있는 조건처럼 표시하지 않는다. */
    @Test fun forecastDescriptionIncludesBothBounds() {
        assertEquals("오늘 최저 기온 5~10℃",
            describe(Condition.ForecastInRange(ForecastMetric.MIN_TEMP, gte = 5.0, lte = 10.0)))
        assertEquals("오늘 최저 기온 5℃ 이상",
            describe(Condition.ForecastInRange(ForecastMetric.MIN_TEMP, gte = 5.0)))
        assertEquals("오늘 최저 기온 10℃ 이하",
            describe(Condition.ForecastInRange(ForecastMetric.MIN_TEMP, lte = 10.0)))
    }
}
