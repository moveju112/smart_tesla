package com.wemade.teslamacro.domain.macro

/**
 * 오늘 날씨 예보. 차가 주는 값이 아니라 밖에서 받아 온다.
 *
 * 차의 외기온은 **지금 이 순간**만 말한다. "내일 아침 영하면 6시에 예열"처럼
 * 손대기 전에 미리 움직이려면 앞을 보는 값이 있어야 한다 — 그게 이 모델의 존재 이유다.
 *
 * 못 받아 왔으면 필드가 null이고, 조건은 "불충족"으로 넘어간다.
 * 날씨를 모른다고 차를 멋대로 예열하지 않는다.
 */
data class WeatherForecast(
    val todayMinTempC: Double? = null,
    val todayMaxTempC: Double? = null,
    /** 오늘 강수 확률(%). 0~100 */
    val rainChancePercent: Int? = null,
    /** 받아 온 시각. 오래된 값을 계속 쓰지 않도록 폴러가 본다 */
    val fetchedAtMillis: Long = 0L,
) {
    fun valueOf(metric: ForecastMetric): Double? = when (metric) {
        ForecastMetric.MIN_TEMP -> todayMinTempC
        ForecastMetric.MAX_TEMP -> todayMaxTempC
        ForecastMetric.RAIN_CHANCE -> rainChancePercent?.toDouble()
    }
}

/**
 * 예보에서 조건이 참조할 수 있는 값.
 *
 * **확장 지점**: 예보 조건을 늘리려면 여기에 항목을 추가하고
 * [WeatherForecast.valueOf]에 분기 하나를 더한다. 편집 UI는 이 enum을 그대로 나열한다.
 */
enum class ForecastMetric(val label: String, val unit: String) {
    MIN_TEMP("오늘 최저 기온", "℃"),
    MAX_TEMP("오늘 최고 기온", "℃"),
    RAIN_CHANCE("오늘 강수 확률", "%"),
}
