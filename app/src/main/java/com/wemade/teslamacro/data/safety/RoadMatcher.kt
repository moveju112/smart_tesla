package com.wemade.teslamacro.data.safety

import android.content.Context
import com.wemade.teslamacro.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

internal data class RoadPoint(val latitude: Double, val longitude: Double, val timestamp: Long, val accuracyMeters: Double)
internal data class MatchedRoad(
    val latitude: Double,
    val longitude: Double,
    val bearingDegrees: Double? = null,
    /** 서버가 준 마지막 측위의 도로명. 없거나 이전 서버면 null이라 도로명 대조를 건너뛴다. */
    val roadName: String? = null,
)
internal data class RoadMatchResponse(val road: MatchedRoad? = null, val code: Int = 0)
internal data class RoadHttpResponse(val code: Int = 0, val body: String = "")

/** 도로 매칭 응답·오류에 위치 또는 인증 정보가 섞일 수 있어 로그를 남기지 않는다. */
internal class RoadMatcher(
    context: Context,
    private val bootstrapToken: String = BuildConfig.ROAD_MATCH_TOKEN,
    identityOverride: RoadDeviceIdentity? = null,
    private val requestOverride: (suspend (String, ByteArray, String?) -> RoadHttpResponse)? = null,
) {
    private val api = DeviceApiClient(context, bootstrapToken, identityOverride, requestOverride)
    val available: Boolean get() = api.available

    /** 위치는 기기별 단기 토큰으로만 보내고 인증 실패 시 한 번 자동 갱신한다. */
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
        val response = api.authenticatedPost("/v1/match", body)
        RoadMatchResponse(if (response.code == 200) parseRoadMatch(response.body) else null, response.code)
    }


}

/** 인증 응답에서 문자열이 아닌 값은 헤더·파일로 보내지 않는다. */
private fun kotlinx.serialization.json.JsonElement?.asString(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** 서버가 보낸 30분 이하의 기기별 토큰만 매칭 헤더에 사용한다. */
internal fun parseRoadAccess(body: String): Pair<String, Long>? = runCatching {
    val result = Json.parseToJsonElement(body) as? JsonObject ?: return@runCatching null
    val token = result["accessToken"].asString()
        ?.takeIf { it.length in 8..1024 && it.matches(Regex("rm1\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) }
        ?: return@runCatching null
    val expires = (result["expiresInSeconds"] as? JsonPrimitive)
        ?.takeUnless { it.isString }?.content?.toLongOrNull()
        ?.takeIf { it in 1..1_800 } ?: return@runCatching null
    token to expires
}.getOrNull()

/** HTTP 200이어도 uncertain/failed 또는 불완전한 경로는 절대 도로 위치로 쓰지 않는다. */
internal fun parseRoadMatch(body: String): MatchedRoad? = runCatching {
    val root = Json.parseToJsonElement(body) as JsonObject
    if ((root["status"] as? JsonPrimitive)?.content != "matched") return@runCatching null
    if ((root["unmatchedCount"] as? JsonPrimitive)?.content?.toIntOrNull() != 0) return@runCatching null
    val routes = root["matchings"] as? JsonArray ?: return@runCatching null
    if (routes.size != 1) return@runCatching null
    val route = routes[0] as? JsonObject ?: return@runCatching null
    val confidence = (route["confidence"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return@runCatching null
    if (confidence !in 0.8..1.0) return@runCatching null
    val geometry = route["geometry"] as? JsonObject ?: return@runCatching null
    if ((geometry["type"] as? JsonPrimitive)?.content != "LineString") return@runCatching null
    val coordinates = geometry["coordinates"] as? JsonArray ?: return@runCatching null
    if (coordinates.size < 2) return@runCatching null
    val last = coordinates.last() as? JsonArray ?: return@runCatching null
    if (last.size != 2) return@runCatching null
    val longitude = (last[0] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return@runCatching null
    val latitude = (last[1] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return@runCatching null
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return@runCatching null
    // 도로명은 보조 근거라 형식이 틀려도 매칭 좌표는 그대로 쓰고 이름만 버린다.
    val roadName = root["roadName"].asString()?.trim()?.takeIf { it.length in 1..64 }
    matchedRoadFromGeometry(coordinates, latitude, longitude)?.copy(roadName = roadName)
}.getOrNull()

/** 경로의 모든 꼭짓점을 검증하고 마지막 5m 이상 구간에서 진행방향을 구한다. 손상된 경로는 쓰지 않는다. */
private fun matchedRoadFromGeometry(coordinates: JsonArray, latitude: Double, longitude: Double): MatchedRoad? {
    var bearingDegrees: Double? = null
    for (index in coordinates.size - 2 downTo 0) {
        val point = coordinates[index] as? JsonArray ?: return null
        if (point.size != 2) return null
        val previousLongitude = (point[0] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return null
        val previousLatitude = (point[1] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return null
        if (previousLatitude !in -90.0..90.0 || previousLongitude !in -180.0..180.0) return null
        if (bearingDegrees != null) continue
        val meanLatitude = Math.toRadians((latitude + previousLatitude) / 2.0)
        val northMeters = (latitude - previousLatitude) * 111_195.0
        val eastMeters = (longitude - previousLongitude) * 111_195.0 * cos(meanLatitude)
        if (hypot(northMeters, eastMeters) < 5.0) continue
        bearingDegrees = (Math.toDegrees(atan2(eastMeters, northMeters)) + 360.0) % 360.0
    }
    return MatchedRoad(latitude, longitude, bearingDegrees)
}
