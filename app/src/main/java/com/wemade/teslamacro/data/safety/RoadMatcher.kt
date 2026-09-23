package com.wemade.teslamacro.data.safety

import com.wemade.teslamacro.BuildConfig
import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.resume

internal data class RoadPoint(val latitude: Double, val longitude: Double, val timestamp: Long, val accuracyMeters: Double)
internal data class MatchedRoad(val latitude: Double, val longitude: Double)
internal data class RoadMatchResponse(val road: MatchedRoad? = null, val code: Int = 0)

/** 도로 매칭은 선택 기능이다. 응답·오류에 위치 또는 인증 정보가 섞일 수 있어 로그를 남기지 않는다. */
internal class RoadMatcher(private val token: String = BuildConfig.ROAD_MATCH_TOKEN) {
    val available: Boolean get() = token.isNotBlank()

    /** URL에 좌표를 넣지 않고 고정 HTTPS 본문으로만 보내며, 실패 시 오프라인 판단을 유지한다. */
    suspend fun match(points: List<RoadPoint>): RoadMatchResponse = withContext(Dispatchers.IO) {
        if (!available || points.size !in 2..32 || points.zipWithNext().any { (a, b) ->
                b.timestamp <= a.timestamp || b.timestamp - a.timestamp > 30
            }) return@withContext RoadMatchResponse()
        val body = buildJsonObject {
            put("points", buildJsonArray {
                points.forEach { point ->
                    add(buildJsonObject {
                        put("coordinate", buildJsonArray { add(point.longitude); add(point.latitude) })
                        put("timestamp", point.timestamp)
                        put("accuracyMeters", point.accuracyMeters)
                    })
                }
            })
        }.toString().toByteArray(Charsets.UTF_8)
        val connection = URL(ENDPOINT).openConnection() as HttpsURLConnection
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { connection.disconnect() }
            try {
                continuation.context.ensureActive()
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 2_000
                connection.readTimeout = 2_000
                connection.useCaches = false
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
                val code = connection.responseCode
                val result = if (code == 200) {
                    val output = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(4096)
                        while (true) {
                            continuation.context.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            check(output.size() + count <= 65_536)
                            output.write(buffer, 0, count)
                        }
                    }
                    parseRoadMatch(output.toString(Charsets.UTF_8.name()))
                } else null
                if (continuation.isActive) continuation.resume(RoadMatchResponse(result, code))
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resume(RoadMatchResponse())
            } finally {
                connection.disconnect()
            }
        }
    }

    private companion object {
        const val ENDPOINT = "https://gps-map.choondoggy.com/v1/match"
    }
}

/** HTTP 200이어도 uncertain/failed 또는 불완전한 경로는 절대 도로 위치로 쓰지 않는다. */
internal fun parseRoadMatch(body: String): MatchedRoad? = runCatching {
    val root = Json.parseToJsonElement(body) as JsonObject
    if ((root["status"] as? JsonPrimitive)?.content != "matched") return@runCatching null
    if ((root["unmatchedCount"] as? JsonPrimitive)?.content?.toIntOrNull() != 0) return@runCatching null
    val routes = root["matchings"] as? JsonArray ?: return@runCatching null
    if (routes.size != 1) return@runCatching null
    val route = routes[0] as? JsonObject ?: return@runCatching null
    val confidence = (route["confidence"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return@runCatching null
    if (!confidence.isFinite() || confidence < 0.8) return@runCatching null
    val geometry = route["geometry"] as? JsonObject ?: return@runCatching null
    if ((geometry["type"] as? JsonPrimitive)?.content != "LineString") return@runCatching null
    val coordinates = geometry["coordinates"] as? JsonArray ?: return@runCatching null
    if (coordinates.size < 2) return@runCatching null
    val last = coordinates.last() as? JsonArray ?: return@runCatching null
    if (last.size != 2) return@runCatching null
    val longitude = (last[0] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return@runCatching null
    val latitude = (last[1] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return@runCatching null
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return@runCatching null
    MatchedRoad(latitude, longitude)
}.getOrNull()
