package com.wemade.teslamacro.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wemade.teslamacro.data.backup.BackupFile
import com.wemade.teslamacro.data.backup.toBackup
import com.wemade.teslamacro.data.gateway.SimulatedVehicleGateway
import com.wemade.teslamacro.data.settings.AppSettings
import com.wemade.teslamacro.data.update.AppUpdater
import com.wemade.teslamacro.data.update.UpdateState
import com.wemade.teslamacro.data.voice.VoiceModelState
import com.wemade.teslamacro.di.AppContainer
import com.wemade.teslamacro.domain.model.VehicleSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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

    /** 설치 권한 설정에서 돌아오면 멈췄던 업데이트를 이어간다 */
    fun resumeUpdateAfterInstallPermission() {
        viewModelScope.launch { AppUpdater.resumeAfterInstallPermission(container.appContext) }
    }

    // ---- 절전 제외 ----

    /**
     * 이 앱이 배터리 최적화에서 빠져 있는지.
     *
     * Doze는 프로세스를 죽이는 게 아니라 늦춘다 — 매크로의 고정 대기가 늘어지고,
     * 탑승 순간의 측위가 밀려 위치 조건이 통째로 빠진다.
     */
    private val _batteryUnrestricted = MutableStateFlow(readBatteryUnrestricted())
    val batteryUnrestricted: StateFlow<Boolean> = _batteryUnrestricted

    /** 시스템 다이얼로그에서 돌아왔을 때 부른다 — 안 부르면 "절전 걸림"이 계속 남는다 */
    fun refreshBatteryUnrestricted() {
        _batteryUnrestricted.value = readBatteryUnrestricted()
    }

    private fun readBatteryUnrestricted(): Boolean {
        val power = container.appContext.getSystemService(android.os.PowerManager::class.java)
        return power?.isIgnoringBatteryOptimizations(container.appContext.packageName) == true
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

    fun setNavigatorApp(name: String) {
        viewModelScope.launch { container.settingsStore.setNavigatorApp(name) }
    }

    fun setHudOverlay(enabled: Boolean) {
        viewModelScope.launch { container.settingsStore.setHudOverlay(enabled) }
    }

    fun setSafeDrive(enabled: Boolean) {
        viewModelScope.launch { container.settingsStore.setSafeDrive(enabled) }
    }

    /** 경보를 소리로도 알릴지 */
    fun setSafeDriveSound(enabled: Boolean) {
        viewModelScope.launch { container.settingsStore.setSafeDriveSound(enabled) }
    }

    /** 경보 음량 1~3 */
    fun setSafeDriveVolume(level: Int) {
        viewModelScope.launch { container.settingsStore.setSafeDriveVolume(level) }
    }

    /** 카카오내비 앱 키가 꽂혀 있는가. 없으면 과속·단속 안내를 켤 수 없다 */
    fun safeDriveAvailable(): Boolean = container.safeDrive.hasKey

    /** 이 기기에 실제로 깔린 내비 앱. 안 깔린 걸 고르면 매크로가 실행 순간에 실패한다 */
    fun installedNavigators(): Set<String> =
        com.wemade.teslamacro.data.nav.NavigatorApp.entries.filterTo(mutableSetOf()) { app ->
            app.packages.any { pkg ->
                runCatching {
                    container.appContext.packageManager.getPackageInfo(pkg, 0)
                }.isSuccess
            }
        }.mapTo(mutableSetOf()) { it.name }

    /** 마지막 백업 결과. 사람이 성공 여부를 알아야 다시 시도할지 판단한다 */
    private val _backupMessage = MutableStateFlow<String?>(null)
    val backupMessage: StateFlow<String?> = _backupMessage.asStateFlow()

    fun clearBackupMessage() {
        _backupMessage.value = null
    }

    /**
     * 매크로와 취향 설정을 사용자가 고른 파일로 내보낸다.
     * 차량 식별자와 등록 상태는 담기지 않는다 — [BackupFile] 참조.
     */
    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                val backup = BackupFile(
                    createdAtMillis = System.currentTimeMillis(),
                    appVersion = com.wemade.teslamacro.BuildConfig.VERSION_NAME,
                    macros = container.ruleStore.rules.value,
                    settings = container.settingsStore.settings.first().toBackup(),
                )
                withContext(Dispatchers.IO) {
                    container.appContext.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        out.write(BackupFile.json.encodeToString(BackupFile.serializer(), backup).toByteArray())
                    } ?: error("파일을 열지 못했어요")
                }
                backup.macros.size
            }
            _backupMessage.value = result.fold(
                onSuccess = { "매크로 ${it}개를 내보냈어요" },
                onFailure = { "내보내지 못했어요 · ${it.message}" },
            )
        }
    }

    /** 백업 파일에서 매크로와 취향 설정을 되돌린다. 차량 등록은 그대로 둔다 */
    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                val text = withContext(Dispatchers.IO) {
                    container.appContext.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().decodeToString()
                    } ?: error("파일을 열지 못했어요")
                }
                val backup = BackupFile.json.decodeFromString(BackupFile.serializer(), text)
                container.ruleStore.restore(backup.macros)
                container.settingsStore.restore(backup.settings)
                backup.macros.size
            }
            _backupMessage.value = result.fold(
                onSuccess = { "매크로 ${it}개를 되돌렸어요" },
                onFailure = { "되돌리지 못했어요 · ${it.message}" },
            )
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
