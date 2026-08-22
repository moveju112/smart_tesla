package com.wemade.teslamacro.data.weather

import com.wemade.teslamacro.domain.macro.GeoPoint
import com.wemade.teslamacro.domain.macro.WeatherForecast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.round

/**
 * 오늘 예보를 받아 온다. Open-Meteo는 계정도 API 키도 없이 쓴다 —
 * 키가 있으면 그 키가 APK에 박히고, 박힌 키는 언젠가 새 나간다.
 *
 * 차의 외기온은 지금만 말한다. "내일 아침 영하면 6시에 예열"처럼
 * 사람이 손대기 전에 움직이려면 앞을 보는 값이 필요하다.
 */
class OpenMeteoClient(private val fetch: (String) -> String = ::httpGet) {

    /** 예보를 받아 온다. 실패하면 null — 날씨를 모른다고 차를 멋대로 움직이지 않는다 */
    suspend fun forecast(at: GeoPoint, nowMillis: Long): WeatherForecast? =
        withContext(Dispatchers.IO) {
            runCatching { parse(fetch(urlFor(at)), nowMillis) }.getOrNull()
        }

    /**
     * 좌표를 소수 둘째 자리까지만 보낸다 (약 1km).
     *
     * 날씨는 그 정도면 충분히 정확하고, 집 앞 주차 칸까지 남의 서버에 알릴 이유가 없다.
     * 진단 로그에 좌표 원문을 안 남기는 것과 같은 이유다.
     */
    internal fun urlFor(at: GeoPoint): String {
        val lat = round(at.latitude * 100) / 100
        val lng = round(at.longitude * 100) / 100
        return "$BASE?latitude=$lat&longitude=$lng" +
            "&daily=temperature_2m_min,temperature_2m_max,precipitation_probability_max" +
            "&timezone=auto&forecast_days=1"
    }

    internal fun parse(body: String, nowMillis: Long): WeatherForecast {
        val daily = (Json.parseToJsonElement(body) as JsonObject)["daily"] as? JsonObject
            ?: error("daily 없음")
        return WeatherForecast(
            todayMinTempC = daily.firstNumber("temperature_2m_min"),
            todayMaxTempC = daily.firstNumber("temperature_2m_max"),
            rainChancePercent = daily.firstNumber("precipitation_probability_max")?.toInt(),
            fetchedAtMillis = nowMillis,
        )
    }

    /** 하루치만 요청하므로 배열의 첫 값이 오늘이다. 없으면 null을 유지한다 */
    private fun JsonObject.firstNumber(key: String): Double? =
        ((this[key] as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.content?.toDoubleOrNull()

    private companion object {
        const val BASE = "https://api.open-meteo.com/v1/forecast"
    }
}

/** 응답이 안 오면 폴링 한 사이클이 통째로 멈춘다 — 연결·읽기 모두 5초에 끊는다 */
private fun httpGet(url: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 5_000
    connection.readTimeout = 5_000
    return connection.inputStream.bufferedReader().use { it.readText() }
}
