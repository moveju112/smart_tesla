package com.wemade.teslamacro.data.gateway

import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.domain.gateway.EnrollmentState
import com.wemade.teslamacro.domain.gateway.LinkState
import com.wemade.teslamacro.domain.gateway.VehicleGateway
import com.wemade.teslamacro.domain.model.StateCategory
import com.wemade.teslamacro.domain.model.VehicleSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlin.coroutines.coroutineContext

/**
 * 실제 구현을 도중에 갈아끼울 수 있는 게이트웨이.
 *
 * 앱을 처음 켜면 등록된 차가 없어 시뮬레이터로 시작한다.
 * 사용자가 VIN을 넣는 순간 **실차(BLE)로 바꿔야** 하는데,
 * 화면과 매크로는 이미 이전 게이트웨이의 Flow를 구독하고 있다.
 *
 * 그래서 구독 대상을 이 껍데기로 고정하고 안쪽만 바꾼다.
 * 이게 없으면 등록을 마쳐도 **앱을 재시작하기 전까지 시뮬레이터가 계속 응답한다**
 * — 실차에서 "되는 줄 알았는데 안 되는" 최악의 상황이 된다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwitchingVehicleGateway(
    initial: VehicleGateway,
    scope: CoroutineScope,
    private val onCommandConfirmed: (VehicleCommand) -> Unit = {},
) : VehicleGateway {

    private val delegate = MutableStateFlow(initial)

    /** 지금 붙어 있는 구현. 시뮬레이터인지 판단할 때 쓴다 */
    val current: VehicleGateway get() = delegate.value

    override val linkState: StateFlow<LinkState> =
        delegate.flatMapLatest { it.linkState }
            .stateIn(scope, SharingStarted.Eagerly, initial.linkState.value)

    override val enrollmentState: StateFlow<EnrollmentState> =
        delegate.flatMapLatest { it.enrollmentState }
            .stateIn(scope, SharingStarted.Eagerly, initial.enrollmentState.value)

    /** 구현을 바꾼다. 이전 연결은 정리한다 */
    suspend fun switchTo(next: VehicleGateway) {
        if (next === delegate.value) return
        val previous = delegate.value
        delegate.value = next
        runCatching { previous.disconnect() }
    }

    override suspend fun connect(vin: String, allowProbe: Boolean) = delegate.value.connect(vin, allowProbe)
    override suspend fun connectDirect(vin: String, address: String) = delegate.value.connectDirect(vin, address)
    override suspend fun verifyKeyEnrollment() = delegate.value.verifyKeyEnrollment()
    override suspend fun disconnect() = delegate.value.disconnect()
    override suspend fun requestKeyEnrollment() = delegate.value.requestKeyEnrollment()
    /** 음성·화면·매크로 모두 같은 성공 경로에서 알리고 시뮬레이터는 실차 효과음을 내지 않는다. */
    override suspend fun send(command: VehicleCommand): Result<Unit> {
        val target = delegate.value
        val result = target.send(command)
        // 외부 빠른 명령은 서비스가 성공 응답 뒤 두 번 울리므로 여기서는 기존 단발음을 생략한다.
        if (result.isSuccess && target !is SimulatedVehicleGateway &&
            coroutineContext[ExternalQuickActionSound.Key] == null) {
            runCatching { onCommandConfirmed(command) }
        }
        return result
    }
    override suspend fun read(category: StateCategory): Result<VehicleSnapshot> =
        delegate.value.read(category)

    override suspend fun readBundle(categories: Set<StateCategory>): Result<VehicleSnapshot> =
        delegate.value.readBundle(categories)
}
