package com.wemade.teslamacro.feature.destination

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wemade.teslamacro.data.nav.DestinationPlace
import com.wemade.teslamacro.data.settings.DeviceMode
import com.wemade.teslamacro.di.AppContainer
import com.wemade.teslamacro.ui.component.openOverlayPermissionSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.put

/** 전송 화면이 살아 있을 때만 결과를 조회하고, 수신 자동화는 서비스에 맡긴다. */
class DestinationViewModel(private val container: AppContainer) : ViewModel() {
    private val coordinator = container.destinations
    private val client = coordinator.client
    private val mutableState = MutableStateFlow(DestinationUiState())
    val state = mutableState.asStateFlow()
    private val requestMutex = kotlinx.coroutines.sync.Mutex()
    private var operation: Job? = null
    private var observation: Job? = null

    init {
        viewModelScope.launch {
            container.settingsStore.settings.collect { settings ->
                mutableState.update { it.copy(mounted = settings.deviceMode == DeviceMode.MOUNTED,
                    receiving = settings.destinationReceiveEnabled, minutes = settings.destinationValidityMinutes.toString()) }
            }
        }
        viewModelScope.launch { coordinator.message.collect { message -> mutableState.update { it.copy(receiveMessage = message) } } }
    }

    /** 서버와 같은 길이 제한을 적용하고 검색은 받는 기기의 네이버지도에 맡긴다. */
    fun queryChanged(value: String) { mutableState.update { it.copy(query = value.take(120)) } }

    /** 유효시간 입력 중에는 빈 값도 허용하고 전송할 때 범위를 확인한다. */
    fun minutesChanged(value: String) { mutableState.update { it.copy(minutes = value.filter(Char::isDigit).take(3)) } }

    /** 연결 코드는 대문자로 정리하되 장소 입력과 섞지 않는다. */
    fun pairingCodeChanged(value: String) { mutableState.update { it.copy(pairingCode = value.filter(Char::isLetterOrDigit).uppercase().take(10)) } }

    /** 폰 한 대 테스트는 서버의 자기 수신함으로 보내며 실제 연결 대상은 바꾸지 않는다. */
    fun send(selfTest: Boolean) = act {
        val value = state.value
        val place = DestinationPlace(value.query.trim())
        check(place.valid()) { "검색어를 1~120자로 입력해 주세요. 줄바꿈은 사용할 수 없어요" }
        val minutes = value.minutes.toIntOrNull()?.takeIf { it in 1..120 } ?: error("유효시간은 1~120분으로 입력해 주세요")
        container.settingsStore.setDestinationValidityMinutes(minutes)
        val reply = client.send(place, minutes, selfTest)
        mutableState.update { it.copy(request = reply.request, notice = if (selfTest) "이 폰으로 전송했어요. 아래 수신 테스트를 눌러 주세요" else "검색어를 전송했어요. 태블릿이 수신하면 네이버지도에서 검색해요") }
    }

    /** 서버가 취소를 확정한 경우에만 화면을 취소 상태로 바꾼다. */
    fun cancel() = act {
        val request = state.value.request ?: return@act
        val reply = client.call("cancel") { put("requestId", request.id) }
        mutableState.update { it.copy(request = reply.request, notice = "대기 목적지를 취소했어요") }
    }

    /** 수신 테스트도 실제 인계·만료·중복 차단 경로를 통과시킨다. */
    fun receiveTest() = act {
        coordinator.receiveTest()
        refreshState()
    }

    /** 코드가 일치한 서버 응답 뒤에만 연결된 기기 이름을 표시한다. */
    fun pair() = act {
        val reply = client.call("pair") { put("code", state.value.pairingCode) }
        mutableState.update { it.copy(receiverName = reply.receiverName, pairingCode = "", notice = "받는 기기를 연결했어요") }
        refreshState()
    }

    /** 기기 연결을 끊을 때 아직 인계되지 않은 목적지도 함께 취소된다. */
    fun unlink() = act {
        client.call("unlink")
        refreshState()
    }

    /** 새 코드 발급은 이전 코드를 무효화하며 연결할 폰에서만 입력한다. */
    fun createPairCode() = act {
        val reply = client.call("pairCode") { put("name", "차량 태블릿") }
        mutableState.update { it.copy(receiverCode = reply.code, notice = "보내는 폰에 이 코드를 입력해 주세요. 10분 동안 유효해요") }
    }

    /** 거치 모드 전환은 사용자가 받기 설정에서 명시적으로 선택했을 때만 한다. */
    fun enableReceiver() = act {
        container.settingsStore.setDeviceMode(DeviceMode.MOUNTED)
        container.settingsStore.setDestinationReceiveEnabled(true)
        coordinator.nudge()
        container.poller.nudge()
    }

    /** 휴대 모드에는 자동 수신을 켜지 않고 임시 버튼만 허용한다. */
    fun receivingChanged(enabled: Boolean) = act {
        check(!enabled || state.value.mounted) { "자동 수신은 거치 모드에서 켤 수 있어요" }
        container.settingsStore.setDestinationReceiveEnabled(enabled)
        coordinator.nudge()
    }

    /** 권한 화면에서 돌아온 뒤 실제 허용 상태를 다시 읽는다. */
    fun allowOverlay() { openOverlayPermissionSettings(container.appContext) }

    /** 화면 복귀 즉시 상태를 읽고 보이는 동안만 5초마다 결과를 확인한다. */
    fun observe() {
        if (observation?.isActive == true) return
        observation = viewModelScope.launch {
            while (isActive) {
                mutableState.update { it.copy(overlayAllowed = container.navigator.hasOverlayPermission) }
                if (operation?.isActive != true) {
                    try { requestMutex.withLock { refreshState() } }
                    catch (error: Exception) {
                        if (error is CancellationException) throw error
                        mutableState.update { it.copy(connectionError = error.message ?: "연결 후 상태를 다시 확인해 주세요") }
                    }
                }
                delay(5_000)
            }
        }
    }

    /** 앱이 화면에서 사라지면 발신 상태 조회로 태블릿 절전을 방해하지 않는다. */
    fun stopObserving() { observation?.cancel(); observation = null }

    /** 수동 새로고침은 통신 오류 후 전달 여부를 확인하는 복구 동작이다. */
    fun refresh() = act { refreshState() }

    /** 서버 상태가 정본이며 로컬의 낙관적 완료 표시를 만들지 않는다. */
    private suspend fun refreshState() {
        val reply = client.call("status")
        mutableState.update { it.copy(receiverName = reply.receiverName, request = reply.request, connectionError = null, connectionChecked = true) }
    }

    /** 중복 탭을 막고 실패 메시지를 남기되 취소는 일반 오류로 바꾸지 않는다. */
    private fun act(action: suspend () -> Unit) {
        if (operation?.isActive == true) return
        operation = viewModelScope.launch {
            mutableState.update { it.copy(busy = true, error = null, notice = null) }
            try { requestMutex.withLock { action() } }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(error = error.message ?: "처리하지 못했어요. 다시 확인해 주세요") }
            } finally { mutableState.update { it.copy(busy = false) } }
        }
    }
}
