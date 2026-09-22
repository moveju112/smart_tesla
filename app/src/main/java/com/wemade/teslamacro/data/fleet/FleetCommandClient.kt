package com.wemade.teslamacro.data.fleet

import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslable.CommandDeadline
import com.wemade.teslable.ensureCommandActive
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay

/** 서버 경로·인증·JSON 계약 확정 전에는 이 경계까지만 구현하고 실제 HTTP 요청은 만들지 않는다. */
interface FleetApi {
    enum class VehicleState { Online, Asleep, Offline }
    enum class CommandResult { Confirmed, Rejected, Unknown }

    /** 온라인 확인은 깨우기 요청의 접수와 구별한다. */
    suspend fun vehicleState(vin: String): VehicleState
    /** 이 호출의 성공은 차량이 깨어났다는 뜻이 아니다. */
    suspend fun wake(vin: String)
    /** 구현체는 자동 재전송 없이 차량 결과를 반환하며, 단순 서버 접수는 Unknown으로 매핑한다. */
    suspend fun execute(requestId: String, vin: String, command: VehicleCommand, remainingMillis: Long): CommandResult
}

/** API 경로와 인증이 제공될 때까지 BLE로 몰래 전환하거나 추정 경로로 전송하지 않는다. */
object UnconfiguredFleetApi : FleetApi {
    const val BASE_URL = "https://tesla.choondoggy.com"
    const val MESSAGE = "Fleet API 연동 준비 중 — 서버 경로·인증 정보가 아직 설정되지 않았어요"

    /** 미설정 상태는 차량 조회 전에 명확히 실패시킨다. */
    override suspend fun vehicleState(vin: String): FleetApi.VehicleState = error(MESSAGE)
    /** 추정 경로로 차량을 깨우지 않는다. */
    override suspend fun wake(vin: String): Unit = error(MESSAGE)
    /** 계약 없는 개폐 요청은 어떤 주소에도 보내지 않는다. */
    override suspend fun execute(requestId: String, vin: String, command: VehicleCommand, remainingMillis: Long): FleetApi.CommandResult = error(MESSAGE)
}

/** 기존 절대 유효시간 안에서 조회→필요 시 깨우기→온라인 확인→단 한 번 전송한다. */
class FleetCommandClient(
    private val api: FleetApi,
    private val onConfirmed: (VehicleCommand) -> Unit = {},
) {
    /** 취소는 전송 진입 전까지만 확정하며 접수·무응답을 차량 실행 성공으로 취급하지 않는다. */
    suspend fun execute(vin: String, command: VehicleCommand, beforeDispatch: () -> Unit, onWaking: () -> Unit): FleetApi.CommandResult {
        require(vin.isNotBlank()) { "차량을 먼저 등록해 주세요" }
        val deadline = checkNotNull(coroutineContext[CommandDeadline]) { "Fleet 명령에는 유효시간이 필요해요" }
        ensureCommandActive()
        val initialState = api.vehicleState(vin)
        ensureCommandActive()
        if (initialState != FleetApi.VehicleState.Online) {
            onWaking()
            ensureCommandActive()
            api.wake(vin)
            do {
                ensureCommandActive()
                delay(1_000L)
                ensureCommandActive()
                val state = api.vehicleState(vin)
                ensureCommandActive()
            } while (state != FleetApi.VehicleState.Online)
        }
        ensureCommandActive()
        beforeDispatch()
        ensureCommandActive()
        // 전송 결과가 불명확해도 재시도하지 않는다. 서버도 이 ID와 남은 수명으로 중복·지연 실행을 막아야 한다.
        val result = api.execute(UUID.randomUUID().toString(), vin, command, deadline.remainingMillis())
        if (result == FleetApi.CommandResult.Confirmed) runCatching { onConfirmed(command) }
        return result
    }
}
