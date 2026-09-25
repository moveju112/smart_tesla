package com.wemade.teslamacro.data.safety

import android.content.Context
import com.wemade.teslamacro.BuildConfig
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.coroutines.resume

internal data class RoadPoint(val latitude: Double, val longitude: Double, val timestamp: Long, val accuracyMeters: Double)
internal data class MatchedRoad(val latitude: Double, val longitude: Double)
internal data class RoadMatchResponse(val road: MatchedRoad? = null, val code: Int = 0)
internal data class RoadHttpResponse(val code: Int = 0, val body: String = "")

/** 도로 매칭 응답·오류에 위치 또는 인증 정보가 섞일 수 있어 로그를 남기지 않는다. */
internal class RoadMatcher(
    context: Context,
    private val bootstrapToken: String = BuildConfig.ROAD_MATCH_TOKEN,
    identityOverride: RoadDeviceIdentity? = null,
    private val requestOverride: (suspend (String, ByteArray, String?) -> RoadHttpResponse)? = null,
) {
    // 안내 상태만 보는 동안은 저장소에 접근하지 않아 테스트·오프라인 시작을 방해하지 않는다.
    private val identity by lazy { identityOverride ?: RoadDeviceIdentity(context) }
    private var accessToken: String? = null
    private var accessExpiresAt: Long = 0
    val available: Boolean get() = bootstrapToken.isNotBlank()

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
        val (firstToken, firstCode) = try { sessionToken() }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            return@withContext RoadMatchResponse()
        }
        if (firstToken == null) return@withContext RoadMatchResponse(code = firstCode)
        var response = post("/v1/match", body, firstToken)
        if (response.code == 401) {
            accessToken = null
            val (nextToken, nextCode) = try { sessionToken() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                return@withContext RoadMatchResponse()
            }
            if (nextToken == null) return@withContext RoadMatchResponse(code = nextCode)
            response = post("/v1/match", body, nextToken)
        }
        RoadMatchResponse(if (response.code == 200) parseRoadMatch(response.body) else null, response.code)
    }

    /** 기기 인증서로 30분 토큰을 받고, 인증서가 만료되면 빌드 토큰으로 다시 등록한다. */
    private suspend fun sessionToken(): Pair<String?, Int> {
        val now = System.currentTimeMillis() / 1_000
        accessToken?.takeIf { now + 60 < accessExpiresAt }?.let { return it to 200 }
        repeat(2) {
            var certificate = identity.readCertificate()
            if (certificate == null) {
                val publicKey = runCatching { identity.publicKey() }.getOrNull() ?: return null to 0
                val registered = post("/v1/devices", buildJsonObject { put("publicKey", publicKey) }.toString().toByteArray(), bootstrapToken)
                if (registered.code != 200) return null to registered.code
                certificate = parseObject(registered.body)?.get("certificate")?.asString()
                    ?.takeIf { it.startsWith("dc1.") && it.length <= 1024 } ?: return null to 0
                if (runCatching { identity.saveCertificate(certificate) }.isFailure) return null to 0
            }
            val timestamp = System.currentTimeMillis() / 1_000
            val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
                .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
            val signature = runCatching { identity.sign(certificate, timestamp, nonce) }.getOrNull() ?: return null to 0
            val body = buildJsonObject {
                put("certificate", certificate)
                put("timestamp", timestamp)
                put("nonce", nonce)
                put("signature", signature)
            }.toString().toByteArray()
            val issued = post("/v1/session", body, null)
            if (issued.code == 401) {
                identity.clearCertificate()
                return@repeat
            }
            if (issued.code != 200) return null to issued.code
            val (token, seconds) = parseRoadAccess(issued.body) ?: return null to 0
            accessToken = token
            accessExpiresAt = System.currentTimeMillis() / 1_000 + seconds
            return token to 200
        }
        return null to 401
    }

    /** 인증 응답은 GPS 응답과 분리해 크기·형식 오류를 조용히 오프라인으로 돌린다. */
    private fun parseObject(body: String): JsonObject? = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    /** 인증서와 접속 토큰을 같은 HTTPS 경로 규칙으로 보내되 GPS는 매칭 경로에만 실어 보낸다. */
    private suspend fun post(path: String, body: ByteArray, bearer: String?): RoadHttpResponse {
        requestOverride?.let { return it(path, body, bearer) }
        val connection = try { URL(BASE_URL + path).openConnection() as HttpsURLConnection }
        catch (_: Exception) { return RoadHttpResponse() }
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { connection.disconnect() }
            try {
                continuation.context.ensureActive()
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 2_000
                connection.readTimeout = 2_000
                connection.useCaches = false
                connection.requestMethod = "POST"
                connection.doOutput = true
                if (bearer != null) connection.setRequestProperty("Authorization", "Bearer $bearer")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
                val code = connection.responseCode
                val output = ByteArrayOutputStream()
                if (code == 200) connection.inputStream.use { input ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        continuation.context.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(output.size() + count <= 65_536)
                        output.write(buffer, 0, count)
                    }
                }
                if (continuation.isActive) continuation.resume(RoadHttpResponse(code, output.toString(Charsets.UTF_8.name())))
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resume(RoadHttpResponse())
            } finally {
                connection.disconnect()
            }
        }
    }

    private companion object {
        const val BASE_URL = "https://gps-map.choondoggy.com"
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
