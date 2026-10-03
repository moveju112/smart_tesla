package com.wemade.teslamacro.data.safety

import android.content.Context
import com.wemade.teslamacro.BuildConfig
import com.wemade.teslable.DiagLog
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.coroutines.resume

/** 지도·목적지 API가 같은 기기 인증을 사용하고 비밀값을 로그에 남기지 않게 한다. */
internal class DeviceApiClient(
    context: Context,
    private val bootstrapToken: String = BuildConfig.ROAD_MATCH_TOKEN,
    identityOverride: RoadDeviceIdentity? = null,
    private val requestOverride: (suspend (String, ByteArray, String?) -> RoadHttpResponse)? = null,
) {
    private val identity by lazy { identityOverride ?: RoadDeviceIdentity(context) }
    private var lastFailure: String? = null
    private var accessToken: String? = null
    private var accessExpiresAt: Long = 0
    val available: Boolean get() = bootstrapToken.isNotBlank()

    /** 인증 실패만 한 번 갱신하며 목적지 전송의 HTTP 재시도는 요청 ID를 가진 호출자가 결정한다. */
    suspend fun authenticatedPost(path: String, body: ByteArray): RoadHttpResponse = withContext(Dispatchers.IO) {
        if (!available) return@withContext RoadHttpResponse()
        try {
            val (token, code) = sessionMutex.withLock { sessionToken() }
            if (token == null) return@withContext RoadHttpResponse(code)
            val response = post(path, body, token)
            if (response.code != 401) {
                if (response.code == 200) lastFailure = null
                else if (response.code != 0) reportFailure("요청 HTTP ${response.code}")
                return@withContext response
            }
            val (nextToken, nextCode) = sessionMutex.withLock {
                if (accessToken == token) accessToken = null
                sessionToken()
            }
            if (nextToken == null) RoadHttpResponse(nextCode) else post(path, body, nextToken)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            reportFailure("인증 처리 ${error.javaClass.simpleName}")
            RoadHttpResponse()
        }
    }

    /** 같은 실패를 반복 기록하지 않고 단계·예외 종류만 남겨 비밀값 노출을 막는다. */
    private fun reportFailure(reason: String) {
        if (lastFailure == reason) return
        lastFailure = reason
        DiagLog.add("기기 API · $reason")
    }

    /** 기기 인증서로 30분 토큰을 받고, 인증서가 만료되면 빌드 토큰으로 다시 등록한다. */
    private suspend fun sessionToken(): Pair<String?, Int> {
        val now = System.currentTimeMillis() / 1_000
        accessToken?.takeIf { now + 60 < accessExpiresAt }?.let { return it to 200 }
        repeat(2) {
            var certificate = identity.readCertificate()
            if (certificate == null) {
                val publicKey = runCatching { identity.publicKey() }.getOrElse {
                    reportFailure("공개키 ${it.javaClass.simpleName}")
                    return null to 0
                }
                val registered = post("/v1/devices", buildJsonObject { put("publicKey", publicKey) }.toString().toByteArray(), bootstrapToken)
                if (registered.code != 200) {
                    if (registered.code != 0) reportFailure("등록 HTTP ${registered.code}")
                    return null to registered.code
                }
                certificate = parseObject(registered.body)?.get("certificate")?.asString()
                    ?.takeIf { it.startsWith("dc1.") && it.length <= 1024 } ?: run {
                    reportFailure("인증서 응답 형식")
                    return null to 0
                }
                runCatching { identity.saveCertificate(certificate) }.getOrElse {
                    reportFailure("인증서 저장 ${it.javaClass.simpleName}")
                    return null to 0
                }
            }
            val timestamp = System.currentTimeMillis() / 1_000
            val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
                .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
            val signature = runCatching { identity.sign(certificate, timestamp, nonce) }.getOrElse {
                reportFailure("서명 ${it.javaClass.simpleName}")
                return null to 0
            }
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
            if (issued.code != 200) {
                if (issued.code != 0) reportFailure("세션 HTTP ${issued.code}")
                return null to issued.code
            }
            val (token, seconds) = parseRoadAccess(issued.body) ?: run {
                reportFailure("세션 응답 형식")
                return null to 0
            }
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
        catch (error: Exception) {
            reportFailure("연결 준비 ${error.javaClass.simpleName}")
            return RoadHttpResponse()
        }
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
                (if (code in 200..299) connection.inputStream else connection.errorStream)?.use { input ->
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
            } catch (error: Exception) {
                if (continuation.isActive) {
                    reportFailure("통신 ${error.javaClass.simpleName}")
                    continuation.resume(RoadHttpResponse())
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private companion object {
        val sessionMutex = Mutex()
        const val BASE_URL = "https://gps-map.choondoggy.com"
    }
}

/** 인증 필드의 문자열 타입을 검사한다. */
private fun JsonElement?.asString(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
