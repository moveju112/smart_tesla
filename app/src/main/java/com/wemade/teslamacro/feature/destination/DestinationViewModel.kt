package com.wemade.teslamacro.feature.destination

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wemade.teslamacro.data.nav.DestinationPlace
import com.wemade.teslamacro.di.AppContainer
import com.wemade.teslamacro.ui.component.openOverlayPermissionSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
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
                mutableState.update { it.copy(receiving = settings.destinationReceiveEnabled, minutes = settings.destinationValidityMinutes,
                    setupStarted = settings.destinationSetupStarted,
                    connectionChecked = !settings.destinationSetupStarted || it.connectionChecked,
                    receiverName = if (settings.destinationSetupStarted) it.receiverName else null,
                    senderCount = if (settings.destinationSetupStarted) it.senderCount else 0,
                    connectionError = if (settings.destinationSetupStarted) it.connectionError else null) }
            }
        }
        viewModelScope.launch { coordinator.message.collect { message -> mutableState.update { it.copy(receiveMessage = message) } } }
    }

    /** 서버와 같은 길이 제한을 적용하고 검색은 받는 기기의 네이버지도에 맡긴다. */
    fun queryChanged(value: String) { mutableState.update { it.copy(query = value.take(120)) } }

    /** 설정 변경 즉시 저장해 전송하지 않고 나가도 다음 전송에 같은 시간을 적용한다. */
    fun minutesChanged(value: Int) {
        if (state.value.canSend) act { container.settingsStore.setDestinationValidityMinutes(value) }
    }

    /** 연결 코드는 대문자로 정리하되 장소 입력과 섞지 않는다. */
    fun pairingCodeChanged(value: String) { mutableState.update { it.copy(pairingCode = value.filter(Char::isLetterOrDigit).uppercase().take(10)) } }

    /** 서버에서 확인된 수신 기기로만 전송해 미연결 요청을 만들지 않는다. */
    fun send() = act {
        val value = state.value
        check(value.connectionChecked && value.connectionError == null && value.receiverName != null) { "받는 기기를 연결해 주세요" }
        val place = DestinationPlace(value.query.trim())
        check(place.valid()) { "검색어를 1~120자로 입력해 주세요. 줄바꿈은 사용할 수 없어요" }
        val reply = client.send(place, value.minutes)
        mutableState.update { it.copy(request = reply.request, notice = "전송했어요", sendCompleted = true) }
    }

    /** 서버가 취소를 확정한 경우에만 화면을 취소 상태로 바꾼다. */
    fun cancel() = act {
        val request = state.value.request ?: return@act
        val reply = client.call("cancel") { put("requestId", request.id) }
        mutableState.update { it.copy(request = reply.request, notice = "대기 목적지를 취소했어요") }
    }

    /** 코드가 일치한 서버 응답 뒤에만 연결된 기기 이름을 표시한다. */
    fun pair() = act {
        check(!state.value.connected) { "기존 연결을 먼저 해제해 주세요" }
        markSetupStarted()
        val reply = client.call("pair") { put("code", state.value.pairingCode) }
        mutableState.update { it.copy(receiverName = reply.receiverName, pairingCode = "", notice = "받는 기기를 연결했어요") }
        refreshState()
    }

    /** 기기 연결을 끊을 때 아직 인계되지 않은 목적지도 함께 취소된다. */
    fun unlink() = act {
        client.call("disconnect")
        container.settingsStore.setDestinationSetupStarted(false)
        mutableState.update { it.copy(receiverName = null, senderCount = 0, receiverCode = null, request = null,
            pairingCode = "", receiving = false, setupStarted = false, connectionChecked = true,
            connectionError = null, notice = "연결을 해제했어요") }
        coordinator.nudge()
        container.poller.nudge()
    }

    /** 새 코드 발급은 이전 코드를 무효화하며 연결할 폰에서만 입력한다. */
    fun createPairCode() = act {
        check(!state.value.connected) { "기존 연결을 먼저 해제해 주세요" }
        markSetupStarted()
        val reply = client.call("pairCode") { put("name", "차량 태블릿") }
        mutableState.update { it.copy(receiverCode = reply.code, notice = "연결 코드 생성됨") }
    }

    /** 수신 설정은 사용 모드를 바꾸지 않고 기존 탑승 확인 경로를 깨운다. */
    fun receivingChanged(enabled: Boolean) = act {
        check(!enabled || state.value.senderCount > 0) { "보내는 기기를 먼저 연결해 주세요" }
        container.settingsStore.setDestinationReceiveEnabled(enabled)
        coordinator.nudge()
        container.poller.nudge()
    }

    /** 권한 화면에서 돌아온 뒤 실제 허용 상태를 다시 읽는다. */
    fun allowOverlay() { openOverlayPermissionSettings(container.appContext) }

    /** 화면 복귀 즉시 상태를 읽고 보이는 동안만 5초마다 결과를 확인한다. */
    fun observe() {
        if (observation?.isActive == true) return
        observation = viewModelScope.launch {
            state.first { it.setupStarted != null }
            while (isActive) {
                mutableState.update { it.copy(overlayAllowed = container.navigator.hasOverlayPermission) }
                if (state.value.setupStarted == true && operation?.isActive != true) {
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
    fun refresh() = act { markSetupStarted(); refreshState() }

    /** 요청 성공 직후 앱이 종료돼도 다음 실행에서 기존 연결을 다시 확인한다. */
    private suspend fun markSetupStarted() {
        if (state.value.setupStarted != true) {
            container.settingsStore.setDestinationSetupStarted(true)
            mutableState.update { it.copy(setupStarted = true) }
        }
    }

    /** 서버 상태가 정본이며 로컬의 낙관적 완료 표시를 만들지 않는다. */
    private suspend fun refreshState() {
        val reply = client.call("status")
        if (reply.senderCount == 0 && state.value.receiving) {
            container.settingsStore.setDestinationReceiveEnabled(false)
            coordinator.nudge()
            container.poller.nudge()
        }
        mutableState.update { it.copy(receiverName = reply.receiverName, senderCount = reply.senderCount, request = reply.request,
            receiverCode = if (reply.receiverName != null || reply.senderCount > 0) null else it.receiverCode,
            connectionError = null, connectionChecked = true) }
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
