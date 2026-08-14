package com.wemade.teslamacro.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wemade.teslamacro.data.gateway.SimulatedVehicleGateway
import com.wemade.teslamacro.data.settings.AppSettings
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
        initialValue = AppSettings(),
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

    /** null = 아직 확인 안 함 */
    val update = MutableStateFlow<UpdateState?>(null)

    /**
     * GitHub 최신 릴리스와 현재 버전을 비교한다.
     * 실패해도 릴리스 페이지 링크로 안내할 수 있게 상태만 남긴다.
     */
    fun checkUpdate() {
        update.value = UpdateState.Checking
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            update.value = runCatching {
                // 응답이 안 오면 "확인 중"에 영원히 매달린다 — 연결·읽기 5초씩에 끊는다
                val connection = java.net.URL(RELEASE_API).openConnection()
                    as java.net.HttpURLConnection
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = kotlinx.serialization.json.Json.parseToJsonElement(body)
                    .let { it as kotlinx.serialization.json.JsonObject }
                val tag = json["tag_name"]
                    ?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
                    .orEmpty()
                val apkUrl = (json["assets"] as? kotlinx.serialization.json.JsonArray)
                    ?.firstNotNullOfOrNull { asset ->
                        val obj = asset as kotlinx.serialization.json.JsonObject
                        (obj["browser_download_url"] as? kotlinx.serialization.json.JsonPrimitive)
                            ?.content?.takeIf { it.endsWith(".apk") }
                    }
                val latest = tag.removePrefix("v")
                if (latest.isNotBlank() && latest != com.wemade.teslamacro.BuildConfig.VERSION_NAME) {
                    UpdateState.Available(latest, apkUrl ?: RELEASE_PAGE)
                } else {
                    UpdateState.UpToDate
                }
            }.getOrElse { UpdateState.Failed }
        }
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

    private companion object {
        const val RELEASE_API =
            "https://api.github.com/repos/moveju112/smart_tesla/releases/latest"
        const val RELEASE_PAGE =
            "https://github.com/moveju112/smart_tesla/releases/latest"
    }
}

/** 업데이트 확인 결과 */
sealed interface UpdateState {
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data object Failed : UpdateState

    /** 새 버전이 있다. [apkUrl]을 브라우저로 열면 바로 내려받는다 */
    data class Available(val version: String, val apkUrl: String) : UpdateState
}
