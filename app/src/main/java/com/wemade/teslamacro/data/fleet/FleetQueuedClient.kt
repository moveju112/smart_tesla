package com.wemade.teslamacro.data.fleet

import com.wemade.teslamacro.data.gateway.ExternalQuickActionSound
import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslable.CommandDeadline
import com.wemade.teslable.ensureCommandActive
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/** 현재 서버의 접수 결과. 성공은 Tesla 응답일 뿐 실제 개폐 완료를 뜻하지 않는다. */
enum class FleetQueueStatus { Queued, Running, Succeeded, Failed, Unknown, Expired }

data class FleetVehicle(val vin: String, val displayName: String, val state: String)
data class FleetReceipt(
    val id: String?, val requestKey: String, val vin: String, val name: String,
    val status: FleetQueueStatus, val result: String? = null,
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
            body.getValue("type").jsonPrimitive.content, FleetQueueStatus.Unknown)
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
    suspend fun awaitResult(initial: FleetReceipt, onUpdate: (FleetReceipt) -> Unit = {}): FleetReceipt {
        var latest = initial
        onUpdate(latest)
        if (!latest.pending) return latest
        return withTimeoutOrNull(60_000L) {
            while (latest.pending) {
                delay(1_000L)
                latest = refresh(latest)
                onUpdate(latest)
            }
            latest
        } ?: latest.copy(status = FleetQueueStatus.Unknown, result = "result_wait_timeout").also(onUpdate)
    }

    /** 이미 받은 ID로 결과를 다시 확인할 수 있으나 명령 재접수는 하지 않는다. */
    suspend fun refresh(receipt: FleetReceipt): FleetReceipt {
        val id = receipt.id ?: return receipt.copy(status = FleetQueueStatus.Unknown)
        require(Regex("[A-Za-z0-9_-]{1,128}").matches(id)) { "잘못된 Fleet 명령 ID예요" }
        coroutineContext.ensureActive()
        return try {
            val response = transport.request("GET", "/v1/commands/$id", tokenProvider(), null, null)
            if (response.code == 200) parseReceipt(response.body, receipt)
            else receipt.copy(status = FleetQueueStatus.Unknown, result = "result_unavailable")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { receipt.copy(status = FleetQueueStatus.Unknown, result = "result_unavailable") }
    }

    /** 완료 효과음은 접수/queued가 아니라 최종 성공 응답에 한 번만 연결한다. */
    suspend fun execute(vin: String, command: VehicleCommand, beforeSubmit: () -> Unit,
                        onUpdate: (FleetReceipt) -> Unit = {}): FleetReceipt {
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
            "tesla_transport_or_response_error", "server_restarted", "deadline_exceeded") }
        return expected.copy(id = id, status = status, result = result)
    }
}

data class FleetHttpResponse(val code: Int, val body: String)

/** 토큰을 데이터 클래스/로그에 보관하지 않고 실제 HTTPS와 오프라인 시험을 분리한다. */
fun interface FleetHttpTransport {
    /** POST의 key는 요청마다 고정하며 구현체가 새 POST를 재시도해서는 안 된다. */
    suspend fun request(method: String, path: String, token: String, body: String?, key: String?): FleetHttpResponse
}
