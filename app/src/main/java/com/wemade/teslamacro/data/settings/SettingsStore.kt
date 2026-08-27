package com.wemade.teslamacro.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

/**
 * 앱 설정. 폴링 주기는 실차에서 계속 조정하게 되므로 코드에 박지 않고 설정으로 뺐다.
 */
data class AppSettings(
    val vin: String = "",
    /** 평상시 VCSEC 감시 주기. 차를 깨우지 않는 저비용 폴링 */
    val idlePollSeconds: Int = 30,
    /** 이벤트 감지 후 집중 폴링 주기 */
    val activePollSeconds: Int = 2,
    /** 집중 폴링 지속 시간. 5분(300)은 인포테인먼트에 과해 3분을 기본으로 */
    val activeWindowSeconds: Int = 180,
    /** 매크로 자동 실행 on/off — 정비·세차 때 통째로 끄는 스위치 */
    val automationEnabled: Boolean = true,
    /**
     * 키 등록까지 끝났는지.
     *
     * VIN 저장과 반드시 분리해야 한다 — VIN은 등록 절차 **첫 단계**에서 저장되는데,
     * 이걸 완료로 치면 카드키 태그 단계를 건너뛰고 본 화면으로 넘어간다.
     */
    val isEnrolled: Boolean = false,
    /**
     * 음성 상시 대기.
     *
     * 기본값은 꺼짐이다 — 마이크를 계속 여는 기능은 사용자가 직접 켜야 한다.
     */
    val voiceAlwaysOn: Boolean = false,
    /**
     * 검증까지 끝난 차량의 BLE 주소.
     *
     * 신형은 광고 이름으로 못 찾아 접속 검증으로 차를 가려내는데,
     * 그 결과를 저장해 두면 다음부터는 스캔 없이 바로 붙는다.
     */
    val vehicleAddress: String = "",
    /** 차에 지은 별칭. 페어링 목록에서 그대로 읽어 온다 (예 "Tesla Model Y Why") */
    val vehicleName: String = "",
    /**
     * 스텔스 충전 — 충전 중 전류를 난수로 흔들어 부하 지문을 흐린다.
     * 흔드는 범위의 상한은 차가 보고하는 충전기 최대 전류다 (사용자 설정이 아니다).
     * 기본 꺼짐. 켜면 충전이 느려지는 대가가 있다 (평균 전류가 내려가고 쉬는 구간이 생김).
     */
    val stealthCharging: Boolean = false,
    /** 길안내를 넘길 내비 앱. 기기에 깔린 것 중 사용자가 고른다 */
    val navigatorApp: String = "NAVER",
    /** HUD 속도를 다른 앱 위에 띄울지. 끄면 제어 화면 안에만 나온다 */
    val hudOverlay: Boolean = false,
    /** 과속·구간단속·보호구역 안내. 켜면 주행 중 GPS와 망을 계속 쓴다 */
    val safeDrive: Boolean = false,
    /**
     * 경보를 소리로도 알릴지. 기본 켜짐 —
     * 주행 중엔 화면을 볼 수 없는 순간이 있고, 그때 침묵하면 경보가 없는 것과 같다.
     */
    val safeDriveSound: Boolean = true,
    /** 경보 음량 1~3. 내비 음성과 겹쳐 들리므로 사람이 균형을 맞출 수 있어야 한다 */
    val safeDriveVolume: Int = 2,
) {
    /** 차량을 특정할 수 있는가 (연결 시도 가능) */
    val isPaired: Boolean get() = vin.isNotBlank()

    /** 등록 절차를 끝냈는가 (본 화면으로 넘어가도 되는가) */
    val isReady: Boolean get() = isPaired && isEnrolled
}

class SettingsStore(private val context: Context) {

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            vin = prefs[KeyVin] ?: "",
            idlePollSeconds = prefs[KeyIdlePoll] ?: 30,
            activePollSeconds = prefs[KeyActivePoll] ?: 2,
            activeWindowSeconds = prefs[KeyActiveWindow] ?: 180,
            automationEnabled = prefs[KeyAutomation] ?: true,
            isEnrolled = prefs[KeyEnrolled] ?: false,
            voiceAlwaysOn = prefs[KeyVoiceAlwaysOn] ?: false,
            vehicleAddress = prefs[KeyVehicleAddress] ?: "",
            vehicleName = prefs[KeyVehicleName] ?: "",
            stealthCharging = prefs[KeyStealthCharging] ?: false,
            navigatorApp = prefs[KeyNavigatorApp] ?: "NAVER",
            hudOverlay = prefs[KeyHudOverlay] ?: false,
            safeDrive = prefs[KeySafeDrive] ?: false,
            safeDriveSound = prefs[KeySafeDriveSound] ?: true,
            safeDriveVolume = prefs[KeySafeDriveVolume] ?: 2,
        )
    }

    suspend fun setVin(vin: String) = edit { it[KeyVin] = vin }
    suspend fun setEnrolled(enrolled: Boolean) = edit { it[KeyEnrolled] = enrolled }
    suspend fun setIdlePollSeconds(seconds: Int) = edit { it[KeyIdlePoll] = seconds }
    suspend fun setActivePollSeconds(seconds: Int) = edit { it[KeyActivePoll] = seconds }
    suspend fun setActiveWindowSeconds(seconds: Int) = edit { it[KeyActiveWindow] = seconds }
    suspend fun setAutomationEnabled(enabled: Boolean) = edit { it[KeyAutomation] = enabled }
    suspend fun setVoiceAlwaysOn(enabled: Boolean) = edit { it[KeyVoiceAlwaysOn] = enabled }
    suspend fun setVehicleAddress(address: String) = edit { it[KeyVehicleAddress] = address }
    suspend fun setVehicleName(name: String) = edit { it[KeyVehicleName] = name }
    suspend fun setStealthCharging(enabled: Boolean) = edit { it[KeyStealthCharging] = enabled }
    suspend fun setNavigatorApp(name: String) = edit { it[KeyNavigatorApp] = name }
    suspend fun setHudOverlay(enabled: Boolean) = edit { it[KeyHudOverlay] = enabled }
    suspend fun setSafeDrive(enabled: Boolean) = edit { it[KeySafeDrive] = enabled }
    suspend fun setSafeDriveSound(enabled: Boolean) = edit { it[KeySafeDriveSound] = enabled }
    // 범위를 저장 직전에 한 번 가둔다 — 백업 파일이 손으로 고쳐져 들어올 수 있다
    suspend fun setSafeDriveVolume(level: Int) = edit { it[KeySafeDriveVolume] = level.coerceIn(1, 3) }

    // 마지막으로 성공한 측위 좌표를 남긴다 — 다음 측위 실패 때 대체값으로 쓴다.
    // 태블릿은 차에 상주하므로 마지막 좌표가 곧 차의 위치다
    suspend fun saveLastGeo(latitude: Double, longitude: Double) = edit {
        it[KeyLastGeoLat] = latitude
        it[KeyLastGeoLng] = longitude
        it[KeyLastGeoAt] = System.currentTimeMillis()
    }

    /**
     * 마지막으로 본 탑승 상태를 남긴다.
     *
     * 앱이 죽었다 살면 "직전 값"이 사라져 탑승 엣지를 못 본다. 그래서 0.8.14가
     * 재시작 직후 1회 강제 발동을 넣었는데, 주행 중 업데이트로 앱이 되살아나면
     * 그게 탑승 매크로를 통째로 다시 터뜨렸다(0.8.22 실차).
     * 값을 남겨두면 재시작 뒤에도 엣지를 정상 판정할 수 있다.
     */
    suspend fun savePresence(present: Boolean) = edit {
        it[KeyLastPresence] = present
        it[KeyLastPresenceAt] = System.currentTimeMillis()
    }

    /**
     * 백업에서 취향 설정만 되돌린다.
     * 차량 식별·등록 상태는 백업에 없으므로 여기서도 건드리지 않는다.
     */
    suspend fun restore(backup: com.wemade.teslamacro.data.backup.BackupSettings) = edit {
        it[KeyIdlePoll] = backup.idlePollSeconds
        it[KeyActivePoll] = backup.activePollSeconds
        it[KeyActiveWindow] = backup.activeWindowSeconds
        it[KeyAutomation] = backup.automationEnabled
        it[KeyVoiceAlwaysOn] = backup.voiceAlwaysOn
        it[KeyStealthCharging] = backup.stealthCharging
        // 옛 백업(version 1)엔 아래 값이 없다 — 그때는 BackupSettings의 기본값이 들어온다.
        // 기본값이 곧 "안 쓰던 상태"라 되돌린 기기가 갑자기 GPS를 켜지는 않는다
        it[KeyHudOverlay] = backup.hudOverlay
        it[KeySafeDrive] = backup.safeDrive
        it[KeySafeDriveSound] = backup.safeDriveSound
        it[KeySafeDriveVolume] = backup.safeDriveVolume.coerceIn(1, 3)
    }

    /**
     * 주차가 시작된 시각과 그때 배터리를 남긴다.
     *
     * 이 앱이 제일 걱정하는 건 방전이다 — "주차 12시간 동안 3% 줄었다"를 알려면
     * 주차 시작 시점의 배터리가 있어야 하고, 그 값은 앱이 죽어도 살아남아야 한다.
     */
    suspend fun saveParkStart(batteryPercent: Int?) = edit {
        it[KeyParkedAt] = System.currentTimeMillis()
        if (batteryPercent != null) it[KeyParkedBattery] = batteryPercent else it.remove(KeyParkedBattery)
    }

    /** 주차 시작 시각과 그때 배터리. 주차 기록이 없으면 null */
    suspend fun parkStart(): Pair<Long, Int?>? {
        val prefs = context.dataStore.data.first()
        val at = prefs[KeyParkedAt] ?: return null
        return at to prefs[KeyParkedBattery]
    }

    /** 마지막으로 본 탑승 상태와 본 시각. 남긴 적 없으면 null */
    suspend fun lastPresence(): Pair<Boolean, Long>? {
        val prefs = context.dataStore.data.first()
        val present = prefs[KeyLastPresence] ?: return null
        return present to (prefs[KeyLastPresenceAt] ?: 0L)
    }

    /** 저장된 마지막 좌표와 저장 시각. 저장된 적 없으면 null */
    suspend fun lastGeo(): Pair<com.wemade.teslamacro.domain.macro.GeoPoint, Long>? {
        val prefs = context.dataStore.data.first()
        val lat = prefs[KeyLastGeoLat] ?: return null
        val lng = prefs[KeyLastGeoLng] ?: return null
        return com.wemade.teslamacro.domain.macro.GeoPoint(lat, lng) to (prefs[KeyLastGeoAt] ?: 0L)
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    private companion object {
        val KeyVin = stringPreferencesKey("vin")
        val KeyIdlePoll = intPreferencesKey("idle_poll_seconds")
        val KeyActivePoll = intPreferencesKey("active_poll_seconds")
        val KeyActiveWindow = intPreferencesKey("active_window_seconds")
        val KeyAutomation = booleanPreferencesKey("automation_enabled")
        val KeyEnrolled = booleanPreferencesKey("enrolled")
        val KeyVoiceAlwaysOn = booleanPreferencesKey("voice_always_on")
        val KeyVehicleAddress = stringPreferencesKey("vehicle_address")
        val KeyVehicleName = stringPreferencesKey("vehicle_name")
        val KeyStealthCharging = booleanPreferencesKey("stealth_charging")
        val KeyLastGeoLat = doublePreferencesKey("last_geo_lat")
        val KeyLastGeoLng = doublePreferencesKey("last_geo_lng")
        val KeyLastGeoAt = longPreferencesKey("last_geo_at")
        val KeyLastPresence = booleanPreferencesKey("last_presence")
        val KeyLastPresenceAt = longPreferencesKey("last_presence_at")
        val KeyNavigatorApp = stringPreferencesKey("navigator_app")
        val KeyHudOverlay = booleanPreferencesKey("hud_overlay")
        val KeySafeDrive = booleanPreferencesKey("safe_drive")
        val KeySafeDriveSound = booleanPreferencesKey("safe_drive_sound")
        val KeySafeDriveVolume = intPreferencesKey("safe_drive_volume")
        val KeyParkedAt = longPreferencesKey("parked_at")
        val KeyParkedBattery = intPreferencesKey("parked_battery")
    }
}
