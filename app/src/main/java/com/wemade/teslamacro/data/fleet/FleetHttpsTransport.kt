package com.wemade.teslamacro.data.fleet

import com.wemade.teslable.CommandDeadline
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** 고정 HTTPS 서버만 사용한다. 리다이렉트·명령 재시도·응답/토큰 로깅은 하지 않는다. */
class FleetHttpsTransport : FleetHttpTransport {
    /** 취소 시 소켓을 닫아 조회를 중단하지만 이미 제출된 서버 명령의 취소를 의미하지 않는다. */
    override suspend fun request(method: String, path: String, token: String, body: String?, key: String?): FleetHttpResponse =
        withContext(Dispatchers.IO) {
            require(token.isNotBlank() && token.length <= 8192 && token.all { it.code in 33..126 }) { "Fleet API 토큰을 확인해 주세요" }
            val validPath = if (method == "POST") Regex("/v1/vehicles/[A-HJ-NPR-Z0-9]{17}/commands").matches(path)
                else method == "GET" && (path == "/v1/vehicles" || Regex("/v1/commands/[A-Za-z0-9_-]{1,128}").matches(path))
            require(validPath) { "허용하지 않은 Fleet 요청 경로예요" }
            require(method != "POST" || (body != null && key != null && Regex("[a-fA-F0-9-]{36}").matches(key)))
            val connection = URL(UnconfiguredFleetApi.BASE_URL + path).openConnection() as HttpsURLConnection
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { connection.disconnect() }
                try {
                    continuation.context.ensureActive()
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 7_500
                    connection.readTimeout = 10_000
                    connection.requestMethod = method
                    connection.setRequestProperty("Authorization", "Bearer $token")
                    connection.setRequestProperty("Accept", "application/json")
                    connection.useCaches = false
                    if (method == "POST") {
                        connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "application/json")
                        connection.setRequestProperty("Idempotency-Key", key)
                        val bytes = checkNotNull(body).toByteArray(Charsets.UTF_8)
                        // 스트리밍 본문은 인증/리다이렉트 등을 이유로 자동 재전송하지 않는다.
                        connection.setFixedLengthStreamingMode(bytes.size)
                        continuation.context.ensureActive()
                        continuation.context[CommandDeadline]?.check()
                        connection.outputStream.use { it.write(bytes) }
                    }
                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val bytes = stream?.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            continuation.context.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            check(output.size() + count <= 262_144) { "Fleet 응답 크기 초과" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: ByteArray(0)
                    if (continuation.isActive) continuation.resume(FleetHttpResponse(code, bytes.toString(Charsets.UTF_8)))
                } catch (_: Exception) {
                    // 예외의 URL/헤더/응답 원문에 VIN·토큰이 섞일 수 있으므로 전달하지 않는다.
                    if (continuation.isActive) continuation.resumeWithException(IOException("Fleet 통신 결과를 확인하지 못했어요"))
                } finally {
                    connection.disconnect()
                }
            }
        }
}
