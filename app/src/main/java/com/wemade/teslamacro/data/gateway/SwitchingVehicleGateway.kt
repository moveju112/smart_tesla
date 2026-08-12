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
    override suspend fun send(command: VehicleCommand) = delegate.value.send(command)
    override suspend fun read(category: StateCategory): Result<VehicleSnapshot> =
        delegate.value.read(category)
}
