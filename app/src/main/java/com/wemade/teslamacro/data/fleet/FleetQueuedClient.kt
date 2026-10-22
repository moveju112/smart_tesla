package com.wemade.teslamacro.data.fleet

import com.wemade.teslamacro.data.gateway.ExternalQuickActionSound
import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslable.CommandDeadline
import com.wemade.teslable.ensureCommandActive
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/** 현재 서버의 접수 결과. 성공은 Tesla 응답일 뿐 실제 개폐 완료를 뜻하지 않는다. */
@kotlinx.serialization.Serializable
enum class FleetQueueStatus { Queued, Running, Succeeded, Failed, Unknown, Expired }

data class FleetVehicle(val vin: String, val displayName: String, val state: String)
@kotlinx.serialization.Serializable
data class FleetReceipt(
    val id: String?, val requestKey: String, val vin: String, val name: String,
    val status: FleetQueueStatus, val result: String? = null,
    internal val retryAfterMillis: Long? = null,
    internal val accountFingerprint: String? = null,
) {
    val pending: Boolean get() = status == FleetQueueStatus.Queued || status == FleetQueueStatus.Running
}

/** 확정된 서버 계약만 인코딩한다. rear는 방향 지정이 아닌 작동 명령이다. */
internal fun fleetCommandBody(command: VehicleCommand, expiresInSeconds: Int): JsonObject {
    require(expiresInSeconds in 5..300) { "Fleet 전송 유효시간은 5~300초여야 해요" }
    var parameters = buildJsonObject { }
    val type = when (command) {
        VehicleCommand.OpenFrunk -> {
            parameters = buildJsonObject { put("which_trunk", "front") }
            "actuate_trunk"
        }
        VehicleCommand.OpenTrunk, VehicleCommand.CloseTrunk -> {
            // 사용자 승인: 열어/닫아 모두 동일한 rear 작동을 요청하며 방향을 보장하지 않는다.
            parameters = buildJsonObject { put("which_trunk", "rear") }
            "actuate_trunk"
        }
        VehicleCommand.Lock -> "door_lock"
        VehicleCommand.ClimateOn -> "auto_conditioning_start"
        VehicleCommand.ClimateOff -> "auto_conditioning_stop"
        is VehicleCommand.SetCharging -> if (command.start) "charge_start" else "charge_stop"
        is VehicleCommand.SetTemperature -> {
            require(command.celsius.isFinite() && command.celsius in 15.0..28.0) { "Fleet 온도 범위는 15~28℃예요" }
            parameters = buildJsonObject { put("driver_temp", command.celsius); put("passenger_temp", command.celsius) }
            "set_temps"
        }
        is VehicleCommand.SetChargeLimit -> {
            require(command.percent in 50..100) { "Fleet 충전 한도는 50~100%예요" }
            parameters = buildJsonObject { put("percent", command.percent) }
            "set_charge_limit"
        }
        else -> throw IllegalArgumentException("서버가 아직 지원하지 않는 Fleet 명령이에요")
    }
    // 지연된 개폐 작동을 줄이기 위해 서버 예시의 15초를 상한으로 쓰되 남은 수명을 늘리지는 않는다.
    val lifetime = if (type == "actuate_trunk") expiresInSeconds.coerceAtMost(15) else expiresInSeconds
    return buildJsonObject { put("type", type); put("parameters", parameters); put("expiresInSeconds", lifetime) }
}

/** 깨우기 없는 현 서버 계약 전용. 기존 FleetCommandClient의 깨우기 흐름에 끼워 넣지 않는다. */
class FleetQueuedClient(
    private val transport: FleetHttpTransport,
    private val tokenProvider: suspend () -> String,
    private val onConfirmed: (VehicleCommand) -> Unit = {},
) {
    private val awaitingResults = AtomicInteger()

    /** 인증 URL은 브라우저로만 전달하고 Tesla 계정 비밀번호·토큰을 앱에서 받지 않는다. */
    suspend fun startOAuth(): String {
        return try {
            val response = transport.request("POST", "/v1/oauth/start", tokenProvider(), "{}", null)
            check(response.code == 200)
            val url = Json.parseToJsonElement(response.body).jsonObject
                .getValue("authorizationUrl").jsonPrimitive.content
            check(isTeslaAuthorizationUrl(url))
            url
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error("Tesla 연결을 시작하지 못했어요 · 토큰과 서버 연결을 확인해 주세요") }
    }

    /** 토큰/차량 정보는 로그나 오류 메시지에 포함하지 않는다. */
    suspend fun vehicles(): List<FleetVehicle> {
        return try {
            val response = transport.request("GET", "/v1/vehicles", tokenProvider(), null, null)
            check(response.code == 200)
            Json.parseToJsonElement(response.body).jsonObject.getValue("vehicles").jsonArray.map {
                val vehicle = it.jsonObject
                FleetVehicle(vehicle.getValue("vin").jsonPrimitive.content,
                    vehicle.getValue("display_name").jsonPrimitive.content,
                    vehicle.getValue("state").jsonPrimitive.content)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error("Fleet 차량 목록을 조회하지 못했어요 · 토큰과 서버 연결을 확인해 주세요") }
    }

    /** 요청 UUID는 실행마다 새로 생성한다. POST 오류 시 접수 여부를 모르므로 자동 재전송하지 않는다. */
    suspend fun submit(vin: String, command: VehicleCommand, beforeSubmit: () -> Unit): FleetReceipt {
        require(Regex("[A-HJ-NPR-Z0-9]{17}").matches(vin)) { "차량 식별값을 확인해 주세요" }
        val deadline = checkNotNull(coroutineContext[CommandDeadline]) { "Fleet 명령에는 유효시간이 필요해요" }
        // 미지원 명령은 인증·네트워크보다 먼저 차단한다.
        fleetCommandBody(command, 5)
        val token = tokenProvider()
        ensureCommandActive()
        val seconds = (deadline.remainingMillis() / 1000).coerceAtMost(300).toInt()
        require(seconds >= 5) { "남은 유효시간이 5초 미만이라 전송하지 않았어요" }
        beforeSubmit()
        ensureCommandActive()
        val body = fleetCommandBody(command, (deadline.remainingMillis() / 1000).coerceAtMost(300).toInt())
        val receipt = FleetReceipt(null, UUID.randomUUID().toString(), vin,
            body.getValue("type").jsonPrimitive.content, FleetQueueStatus.Unknown,
            accountFingerprint = fleetAccountFingerprint(token))
        return try {
            val response = transport.request("POST", "/v1/vehicles/$vin/commands", token, body.toString(), receipt.requestKey)
            when (response.code) {
                202 -> parseReceipt(response.body, receipt)
                400, 401, 403, 404, 422 -> receipt.copy(status = FleetQueueStatus.Failed, result = "http_${response.code}")
                else -> receipt.copy(result = "submission_unconfirmed")
            }
        } catch (cancelled: CancellationException) {
            // 여기서의 취소는 결과 확인 중단이지 서버 명령 취소가 아니다.
            throw cancelled
        } catch (_: Exception) {
            receipt.copy(result = "submission_unconfirmed")
        }
    }

    /** GET만 반복하며 만료·중단·unknown에서 새 POST를 만들지 않는다. */
    suspend fun awaitResult(initial: FleetReceipt, onUpdate: suspend (FleetReceipt) -> Unit = {}): FleetReceipt {
        var latest = initial
        onUpdate(latest)
        if (!latest.pending) return latest
        awaitingResults.incrementAndGet()
        return try {
            var retryAfterMillis = 0L
            withTimeoutOrNull(60_000L) {
                while (latest.pending) {
                    // 동시에 추적하는 명령끼리 조회 예산을 나누고 서버의 재조회 시각을 지킨다.
                    delay(maxOf(awaitingResults.get().coerceAtLeast(1) * 1_000L, retryAfterMillis))
                    val next = refresh(latest)
                    retryAfterMillis = next.retryAfterMillis ?: 0L
                    // 조회 실패는 결과가 아니다 — 같은 ID를 기한까지 GET만 다시 한다.
                    if (next.result == "result_unavailable" && latest.id != null) {
                        onUpdate(next)
                        continue
                    }
                    latest = next
                    onUpdate(latest)
                }
                latest
            } ?: latest.copy(status = FleetQueueStatus.Unknown, result = "result_wait_timeout").also { onUpdate(it) }
        } finally {
            awaitingResults.decrementAndGet()
        }
    }

    /** 이미 받은 ID로 결과를 다시 확인할 수 있으나 명령 재접수는 하지 않는다. */
    suspend fun refresh(receipt: FleetReceipt): FleetReceipt {
        val id = receipt.id ?: return receipt.copy(status = FleetQueueStatus.Unknown)
        require(Regex("[A-Za-z0-9_-]{1,128}").matches(id)) { "잘못된 Fleet 명령 ID예요" }
        coroutineContext.ensureActive()
        return try {
            val token = tokenProvider()
            if (receipt.accountFingerprint != null && receipt.accountFingerprint != fleetAccountFingerprint(token)) {
                return receipt.copy(status = FleetQueueStatus.Unknown, result = "credential_changed")
            }
            val response = transport.request("GET", "/v1/commands/$id", token, null, null)
            if (response.code == 200) parseReceipt(response.body, receipt)
            else receipt.copy(status = FleetQueueStatus.Unknown, result = "result_unavailable",
                retryAfterMillis = if (response.code == 429) response.retryAfterMillis ?: 5_000L else null)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { receipt.copy(status = FleetQueueStatus.Unknown, result = "result_unavailable") }
    }

    /** 완료 효과음은 접수/queued가 아니라 최종 성공 응답에 한 번만 연결한다. */
    suspend fun execute(vin: String, command: VehicleCommand, beforeSubmit: () -> Unit,
                        onUpdate: suspend (FleetReceipt) -> Unit = {}): FleetReceipt {
        val result = awaitResult(submit(vin, command, beforeSubmit), onUpdate)
        coroutineContext.ensureActive()
        // 외부 빠른 명령은 서비스의 최종 성공 분기가 두 번 울리므로 공유 단발음을 생략한다.
        if (result.status == FleetQueueStatus.Succeeded && coroutineContext[ExternalQuickActionSound.Key] == null) {
            runCatching { onConfirmed(command) }
        }
        return result
    }

    /** 응답의 차량·명령·ID 불일치나 미지 상태는 성공으로 인정하지 않는다. Location은 신뢰하지 않는다. */
    private fun parseReceipt(body: String, expected: FleetReceipt): FleetReceipt {
        val json = Json.parseToJsonElement(body).jsonObject
        val id = json.getValue("id").jsonPrimitive.content
        check(Regex("[A-Za-z0-9_-]{1,128}").matches(id))
        check(expected.id == null || expected.id == id)
        check(json.getValue("vin").jsonPrimitive.content == expected.vin)
        check(json.getValue("name").jsonPrimitive.content == expected.name)
        val status = when (json.getValue("status").jsonPrimitive.content) {
            "queued" -> FleetQueueStatus.Queued
            "running" -> FleetQueueStatus.Running
            "succeeded" -> FleetQueueStatus.Succeeded
            "failed" -> FleetQueueStatus.Failed
            "unknown" -> FleetQueueStatus.Unknown
            "expired" -> FleetQueueStatus.Expired
            else -> FleetQueueStatus.Unknown
        }
        // result는 서버 문자열을 그대로 UI/로그에 흘리지 않고 알려진 코드만 보존한다.
        val result = json["result"]?.jsonPrimitive?.contentOrNull?.takeIf { it in setOf(
            "tesla_acknowledged_not_state_verified", "tesla_rejected_command", "vehicle_access_denied",
            "tesla_transport_or_response_error", "server_restarted", "deadline_exceeded",
            "vehicle_not_parked", "park_state_unavailable", "waiting_for_vehicle_online", "sending_command",
            "vehicle_wake_failed", "vehicle_state_unknown", "worker_stopping", "user_disabled") }
        return expected.copy(id = id, status = status, result = result, retryAfterMillis = null)
    }
}

/** 서버 응답이 외부 로그인 페이지나 앱 딥링크로 바뀌어도 임의 주소를 열지 않는다. */
internal fun isTeslaAuthorizationUrl(value: String): Boolean = runCatching {
    val uri = java.net.URI(value)
    value.length <= 8192 && uri.scheme == "https" && uri.host == "auth.tesla.com" &&
        uri.port == -1 && uri.userInfo == null && uri.fragment == null &&
        uri.path == "/oauth2/v3/authorize" && !uri.rawQuery.isNullOrBlank()
}.getOrDefault(false)

/** 토큰이 교체되면 이전 계정의 명령 ID를 새 계정으로 조회하지 않는다. 원문은 보존하지 않는다. */
internal fun fleetAccountFingerprint(token: String): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/** 서버 코드를 그대로 출력하지 않고 접수·전송·성공 응답과 미확인을 구분한다. */
fun FleetReceipt.statusMessage(): String = when (status) {
    FleetQueueStatus.Queued -> "서버 접수 · 실행 대기"
    FleetQueueStatus.Running -> when (result) {
        "waiting_for_vehicle_online" -> "차량 깨우는 중 · 서버 명령 취소 불가"
        "sending_command" -> "차량에 명령 전송 중"
        else -> "차량 결과 확인 중"
    }
    FleetQueueStatus.Succeeded -> "차량 성공 응답 · 물리 상태 확인 아님"
    FleetQueueStatus.Expired -> "전송 전 유효시간 만료"
    FleetQueueStatus.Failed -> when (result) {
        "vehicle_not_parked" -> "P단이 아니어서 개폐하지 않았어요"
        "park_state_unavailable" -> "기어 미확인 · 개폐하지 않았어요"
        "vehicle_wake_failed" -> "차량 깨우기 실패"
        "http_401", "http_403", "vehicle_access_denied", "user_disabled" -> "토큰 또는 차량 접근 권한을 확인해 주세요"
        else -> "서버 또는 차량이 명령을 거부했어요"
    }
    FleetQueueStatus.Unknown -> when {
        result == "credential_changed" -> "토큰이 변경됐어요 · 이전 토큰으로 조회해 주세요"
        result == "result_unavailable" -> "결과 조회 실패 · 실행 여부 미확인"
        id == null -> "접수 여부 미확인 · 재전송하지 않아요"
        else -> "결과 미확인 · 재조회만 가능해요"
    }
}

/** 기록 화면에는 차량 식별자나 서버 원문 대신 허용된 명령 이름만 보여 준다. */
fun FleetReceipt.commandLabel(): String = when (name) {
    "actuate_trunk" -> "트렁크 작동"
    "door_lock" -> "문 잠금"
    "auto_conditioning_start" -> "공조 켜기"
    "auto_conditioning_stop" -> "공조 끄기"
    "charge_start" -> "충전 시작"
    "charge_stop" -> "충전 중지"
    "set_temps" -> "온도 설정"
    "set_charge_limit" -> "충전 한도 설정"
    else -> "차량 명령"
}

data class FleetHttpResponse(val code: Int, val body: String, val retryAfterMillis: Long? = null)

/** 토큰을 데이터 클래스/로그에 보관하지 않고 실제 HTTPS와 오프라인 시험을 분리한다. */
fun interface FleetHttpTransport {
    /** POST의 key는 요청마다 고정하며 구현체가 새 POST를 재시도해서는 안 된다. */
    suspend fun request(method: String, path: String, token: String, body: String?, key: String?): FleetHttpResponse
}
