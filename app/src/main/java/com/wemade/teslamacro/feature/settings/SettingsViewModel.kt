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
                // "다르면 새 버전"이 아니라 실제로 높은지 본다 — 릴리스보다 앞선 로컬 빌드에서
                // 다운그레이드 APK를 새 버전으로 안내하는 사고 방지
                if (isNewer(latest, com.wemade.teslamacro.BuildConfig.VERSION_NAME)) {
                    UpdateState.Available(latest, apkUrl ?: RELEASE_PAGE)
                } else {
                    UpdateState.UpToDate
                }
            }.getOrElse { UpdateState.Failed }
        }
    }

    /**
     * 원클릭 업데이트: APK를 앱 캐시로 내려받아 시스템 설치 화면을 바로 띄운다.
     * 브라우저·알림·파일 앱을 거치지 않는다. 남는 조작은 설치 화면의 "설치" 탭 1번뿐.
     */
    fun downloadAndInstall() {
        // 진행 중 재진입 방지 — 버튼이 비활성이긴 하지만 상태 꼬임을 원천 차단한다
        val target = update.value as? UpdateState.Available ?: return
        update.value = UpdateState.Downloading(target.version, percent = 0)
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                // 1. 캐시로 다운로드 (진행률 갱신)
                val file = java.io.File(container.appContext.cacheDir, "update.apk")
                val connection = java.net.URL(target.apkUrl).openConnection()
                    as java.net.HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 30_000
                val total = connection.contentLengthLong
                connection.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (total > 0) {
                                update.value = UpdateState.Downloading(
                                    target.version,
                                    percent = (copied * 100 / total).toInt(),
                                )
                            }
                        }
                    }
                }
                // 2. FileProvider URI로 시스템 설치기 호출
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    container.appContext,
                    "${container.appContext.packageName}.fileprovider",
                    file,
                )
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK,
                    )
                }
                container.appContext.startActivity(intent)
                // 3. 설치를 취소하고 돌아와도 다시 누를 수 있게 "새 버전 있음"으로 되돌린다
                update.value = target
            }.getOrElse {
                // 실패해도 다시 누르면 재시도되도록 상태만 표시한다
                update.value = target.copy(downloadFailed = true)
            }
        }
    }

    // 점 구분 숫자 버전 비교 — 숫자 아닌 접미사("-beta")는 무시하고 자리별 수치로 판정한다
    private fun isNewer(latest: String, current: String): Boolean {
        if (latest.isBlank()) return false
        val a = latest.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val b = current.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        repeat(maxOf(a.size, b.size)) { i ->
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
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

    /** 새 버전이 있다. 설치 버튼이 [apkUrl]을 앱이 직접 내려받는다 */
    data class Available(
        val version: String,
        val apkUrl: String,
        /** 직전 다운로드가 실패했다 — 같은 버튼으로 재시도 */
        val downloadFailed: Boolean = false,
    ) : UpdateState

    /** APK 내려받는 중. [percent]는 0~100 */
    data class Downloading(val version: String, val percent: Int) : UpdateState
}
