package com.wemade.teslamacro.data.nav

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import com.wemade.teslamacro.data.safety.DeviceApiClient
import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** 새 전송은 검색어만 담고, 이전 앱이 보낸 좌표형 목적지도 수신할 수 있게 유지한다. */
@Serializable
data class DestinationPlace(
    val name: String,
    val address: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    val isSearch: Boolean get() = address == null && latitude == null && longitude == null

    /** 검색어와 완전한 좌표형만 허용해 일부 좌표 누락을 검색 요청으로 오인하지 않는다. */
    fun valid(): Boolean = name.isNotBlank() && name.length <= 120 && name.none { it.code < 32 } &&
        (isSearch || (address != null && address.isNotBlank() && address.length <= 300 &&
            address.none { it.code < 32 } && latitude != null && latitude in 31.43..44.35 &&
            longitude != null && longitude in 122.37..132.0))
}

/** 전달 중은 실행 확정이 아니므로 화면에서도 별도 상태로 남긴다. */
@Serializable
data class DestinationRequest(
    val id: String,
    val destination: DestinationPlace,
    val createdAt: Long,
    val expiresAt: Long,
    val status: String,
    val selfTest: Boolean,
)

@Serializable
internal data class DestinationReply(
    val serverNow: Long,
    val request: DestinationRequest? = null,
    val receiverName: String? = null,
    val code: String? = null,
    val expiresAt: Long? = null,
)

/** 서버 응답으로 받은 남은 시간은 절전 시간을 포함하는 단조시계로 줄인다. */
internal fun destinationDeadline(serverNow: Long, expiresAt: Long, startedAt: Long): Long? {
    val remaining = expiresAt - serverNow
    return (startedAt + remaining).takeIf { serverNow > 0 && remaining in 1..7_200_000 && it >= startedAt }
}

/** 전원·인터넷 복구 순서와 무관하게 현재의 신선한 착석 확인만 자동 실행 근거로 쓴다. */
internal fun destinationReady(enabled: Boolean, present: Boolean?, observedAt: Long, now: Long): Boolean =
    enabled && present == true && observedAt >= 0 && now - observedAt in 0..30_000

/** 서버의 고정 오류를 사용자 복구 동작으로 바꾸고 원문 응답은 노출하지 않는다. */
internal class DestinationApiException(val code: Int, message: String) : Exception(message)

/** 지도 API의 기존 기기 서명·가입·세션 갱신 경로를 그대로 재사용한다. */
internal class DestinationClient(private val api: DeviceApiClient) {
    private val json = Json { ignoreUnknownKeys = true }

    /** 모든 동작은 목적지 전용 경로로 보내며 본문과 기기 식별자는 로그에 남기지 않는다. */
    suspend fun call(operation: String, fields: JsonObjectBuilder.() -> Unit = {}): DestinationReply {
        if (!api.available) throw DestinationApiException(0, "서버 연결 설정이 없는 빌드예요")
        val body = buildJsonObject { put("operation", operation); fields() }.toString().toByteArray()
        val response = api.authenticatedPost("/v1/destinations", body)
        if (response.code != 200) {
            val reason = runCatching {
                Json.parseToJsonElement(response.body).jsonObject["error"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            val message = when (response.code) {
                0 -> "연결을 확인하지 못했어요"
                400 -> "입력값이나 연결 코드가 유효하지 않아요. 다시 확인해 주세요"
                401, 403 -> "기기 인증에 실패했어요. 인터넷과 기기 시간을 확인해 주세요"
                404 -> "목적지 수신 서버를 사용할 수 없어요"
                409 -> when (reason) {
                    "receiver_not_paired" -> "받는 기기 연결이 필요해요. 앱의 목적지 설정에서 연결해 주세요"
                    "request_conflict" -> "전송 요청이 충돌했어요. 다시 보내 주세요"
                    "request_not_pending" -> "이미 처리되었거나 취소된 목적지예요. 상태를 확인해 주세요"
                    else -> "요청 상태가 바뀌었어요. 앱에서 상태를 확인해 주세요"
                }
                429 -> "요청이 많아요. 잠시 후 다시 시도해 주세요"
                in 500..599 -> "서버 오류 (${response.code}) · 잠시 후 재확인해 주세요"
                else -> "응답 오류 (${response.code})"
            }
            throw DestinationApiException(response.code, message)
        }
        val reply = runCatching { json.decodeFromString<DestinationReply>(response.body) }.getOrNull()
            ?: throw DestinationApiException(0, "서버 응답을 읽지 못했어요")
        require(reply.serverNow > 0)
        reply.request?.let {
            require(it.destination.valid() && runCatching { UUID.fromString(it.id) }.isSuccess)
            require(it.expiresAt - it.createdAt in 60_000..7_200_000)
            require(it.status in setOf("pending", "claimed", "delivered", "failed", "expired", "cancelled", "replaced"))
        }
        return reply
    }

    /** 재인증 재시도에서도 요청 ID와 목적지·유효시간을 바꾸지 않는다. */
    suspend fun send(place: DestinationPlace, minutes: Int, selfTest: Boolean): DestinationReply {
        require(place.valid() && minutes in 1..120)
        val requestId = UUID.randomUUID().toString()
        return call("send") {
            put("requestId", requestId)
            put("destination", json.encodeToJsonElement(place))
            put("validityMinutes", minutes)
            put("selfTest", selfTest)
        }
    }
}

@Serializable
internal data class DestinationReceipt(val requestId: String, val delivered: Boolean? = null)

/** 앱 종료 직전 인계를 디스크에 남겨 결과가 불명확한 요청을 자동 재실행하지 않는다. */
internal class DestinationJournal(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "destination-receipt.json"))

    /** 파일이 손상돼도 서버의 단일 인계 상태가 재실행을 막는다. */
    @Synchronized
    fun read(): DestinationReceipt? = runCatching {
        Json.decodeFromString<DestinationReceipt>(file.readFully().toString(Charsets.UTF_8))
    }.getOrNull()

    /** 실행 직전 기록 실패는 호출자에게 돌려 네이버 실행을 중단한다. */
    @Synchronized
    fun save(receipt: DestinationReceipt) {
        val stream = file.startWrite()
        try {
            stream.write(Json.encodeToString(receipt).toByteArray())
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    /** 서버가 결과를 확인한 기록만 정리한다. */
    @Synchronized
    fun clear() = file.delete()
}
