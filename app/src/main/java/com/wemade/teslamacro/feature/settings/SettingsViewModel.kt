package com.wemade.teslamacro.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wemade.teslamacro.data.gateway.SimulatedVehicleGateway
import com.wemade.teslamacro.data.settings.AppSettings
import com.wemade.teslamacro.data.update.AppUpdater
import com.wemade.teslamacro.data.update.UpdateState
import com.wemade.teslamacro.data.voice.VoiceModelState
import com.wemade.teslamacro.di.AppContainer
import com.wemade.teslamacro.domain.model.VehicleSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    /** 시뮬레이터일 때만 값이 있다 */
    private val simulator: SimulatedVehicleGateway? =
        container.gateway as? SimulatedVehicleGateway

    val simulatedState: StateFlow<VehicleSnapshot>? = simulator?.current

    fun setSimulatedInsideTemp(celsius: Double) = simulator?.setInsideTemp(celsius)
    fun setSimulatedOutsideTemp(celsius: Double) = simulator?.setOutsideTemp(celsius)
    fun simulateBoarding() = simulator?.simulateBoarding()
    fun simulateLeaving() = simulator?.simulateLeaving()

    val settings: StateFlow<AppSettings> = container.settingsStore.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        // 빈 기본값으로 시작하면 등록이 끝난 차에서도 VIN 입력 화면이 한 번 스친다.
        // 컨테이너가 시작할 때 이미 읽어 둔 값이 있으니 그걸 첫 값으로 쓴다
        initialValue = container.initialSettings,
    )

    fun setAutomationEnabled(enabled: Boolean) {
        viewModelScope.launch { container.settingsStore.setAutomationEnabled(enabled) }
    }

    fun setIdlePollSeconds(seconds: Int) {
        viewModelScope.launch { container.settingsStore.setIdlePollSeconds(seconds) }
    }

    fun setActivePollSeconds(seconds: Int) {
        viewModelScope.launch { container.settingsStore.setActivePollSeconds(seconds) }
    }

    // 집중 폴링을 얼마나 오래 유지할지 — 길수록 반응은 좋지만 차가 늦게 잔다
    fun setActiveWindowSeconds(seconds: Int) {
        viewModelScope.launch { container.settingsStore.setActiveWindowSeconds(seconds) }
    }

    // ---- 업데이트 ----

    /** 확인·설치 진행 상태. 리시버가 갱신하므로 정본은 [AppUpdater]에 있다 */
    val update: MutableStateFlow<UpdateState?> = AppUpdater.state

    /** GitHub 최신 릴리스와 현재 버전을 비교한다 */
    fun checkUpdate() {
        viewModelScope.launch {
            AppUpdater.check(com.wemade.teslamacro.BuildConfig.VERSION_NAME)
        }
    }

    /** 원클릭 업데이트: 내려받아 곧바로 설치까지 넘긴다 */
    fun downloadAndInstall() {
        viewModelScope.launch { AppUpdater.downloadAndInstall(container.appContext) }
    }

    // ---- 음성 ----

    val voiceModel: StateFlow<VoiceModelState> = container.voiceModelStore.state

    fun setVoiceAlwaysOn(enabled: Boolean) {
        viewModelScope.launch { container.settingsStore.setVoiceAlwaysOn(enabled) }
    }

    /** 사용자가 고른 zip에서 음성 모델을 푼다 */
    fun installVoiceModel(uri: Uri) {
        viewModelScope.launch { container.voiceModelStore.installFromZip(uri) }
    }

    /** 모델을 지우면 상시 대기도 같이 끈다. 켜둔 채로 두면 계속 실패한다 */
    fun removeVoiceModel() {
        viewModelScope.launch {
            container.settingsStore.setVoiceAlwaysOn(false)
            container.voiceModelStore.remove()
        }
    }

    /** 앱에서만 등록을 지운다. 차량 키 목록은 차량 화면에서 직접 지워야 한다 */
    fun unpair() {
        viewModelScope.launch {
            container.gateway.disconnect()
            container.settingsStore.setVin("")
            container.settingsStore.setEnrolled(false)
            // 저장된 차 주소도 함께 지운다. 남겨두면 다른 차 등록 때 엉뚱한 데 붙는다
            container.settingsStore.setVehicleAddress("")
        }
    }

}
