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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

/** 기기 종류가 아니라 차량에 계속 두는지 들고 다니는지에 따라 연결 수명을 구분한다. */
enum class DeviceMode(val label: String) {
    MOUNTED("거치 모드"),
    PORTABLE("휴대 모드");

    companion object {
        /** 기존 기기명 저장값을 같은 사용 방식으로 이어받고, 알 수 없으면 안전한 휴대 모드로 간다. */
        fun of(name: String?): DeviceMode = when (name) {
            MOUNTED.name, "CAR_TABLET" -> MOUNTED
            PORTABLE.name, "PERSONAL_PHONE" -> PORTABLE
            else -> PORTABLE
        }
    }
}

/** 앱 설정. */
data class AppSettings(
    val vin: String = "",
    /** 매크로 자동 실행 on/off — 정비·세차 때 통째로 끄는 스위치 */
    val automationEnabled: Boolean = true,
    /** 스마트싱스 알림을 차량 직접 명령으로 받을지 */
    val smartThingsEnabled: Boolean = false,
    val smartThingsValiditySeconds: Int = 120,
    /** 빠른 차량 동작별로 정확히 일치해야 하는 스마트싱스 알림 문구 */
    val smartThingsCommandTexts: Map<String, String> = SmartThingsCommands.defaults(),
    /** 빈 차에서는 인증 BLE를 끊어 공식 휴대폰 키와의 간섭 가능성을 줄인다 */
    val protectPhoneKey: Boolean = true,
    /** 기기 종류와 무관하게 처음엔 백그라운드 연결하지 않는 안전한 휴대 모드로 시작한다. */
    val deviceMode: DeviceMode = DeviceMode.PORTABLE,
    /**
     * 키 등록까지 끝났는지.
     *
     * VIN 저장과 반드시 분리해야 한다 — VIN은 등록 절차 **첫 단계**에서 저장되는데,
     * 이걸 완료로 치면 카드키 태그 단계를 건너뛰고 본 화면으로 넘어간다.
     */
    val isEnrolled: Boolean = false,
    /**
     * 검증까지 끝난 차량의 BLE 주소.
     *
     * 신형은 광고 이름으로 못 찾아 접속 검증으로 차를 가려내는데,
     * 그 결과를 저장해 두면 다음부터는 스캔 없이 바로 붙는다.
     */
    val vehicleAddress: String = "",
    /** 차에 지은 별칭. 페어링 목록에서 그대로 읽어 온다 (예 "Tesla Model Y Why") */
    val vehicleName: String = "",
    /** 다음 충전 1회 동안만 전류를 조절한다. 완료하면 컨트롤러가 자동으로 끈다. */
    val stealthCharging: Boolean = false,
    /** 실제 조절을 시작했는지. 대기만 하다 끝난 충전을 1회 사용으로 세지 않는다. */
    val stealthChargeStarted: Boolean = false,
    /** 조절 전에 사용자가 쓰던 전류. 종료·해제 때 이 값으로 되돌린다. */
    val stealthChargeOriginalAmps: Int? = null,
    /** 마지막 전류 명령이 원래 값과 다른지. 원복이 필요한 세션만 연결을 잠시 유지한다. */
    val stealthChargeModified: Boolean = false,
    /** 정한 시간대 안에서만 전류를 조절할지. */
    val stealthScheduleEnabled: Boolean = false,
    /** 스텔스 충전 시작 시각. 자정부터 흐른 분이다. */
    val stealthStartMinutes: Int = 23 * 60,
    /** 스텔스 충전 종료 시각. 시작보다 작으면 자정을 넘긴다. */
    val stealthEndMinutes: Int = 7 * 60,
    /** 길안내를 넘길 내비 앱. 기기에 깔린 것 중 사용자가 고른다 */
    val navigatorApp: String = "NAVER",
    /** 탑승을 감지하면 선택한 내비의 목적지 없는 안심운전을 자동으로 연다 */
    val autoStartNavigatorSafeDrive: Boolean = false,
    /** 안심운전 전체 진단 뒤 통로를 하나씩 고르는 실행 방식 */
    val navigatorSafeDriveLaunchMode: String = "DEFAULT",
    /** HUD 속도를 다른 앱 위에 띄울지. */
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

class SettingsStore(
    context: Context,
    // 테스트에서는 같은 저장소를 가상 시간 scope로 돌리고, 앱은 기존 기기 저장소를 쓴다.
    private val store: DataStore<Preferences> = context.dataStore,
) {

    val settings: Flow<AppSettings> = store.data.map { prefs ->
        AppSettings(
            vin = prefs[KeyVin] ?: "",
            automationEnabled = prefs[KeyAutomation] ?: true,
            smartThingsEnabled = prefs[KeySmartThingsEnabled]
                ?: prefs[KeySmartThingsFrunkEnabled]
                ?: false,
            smartThingsCommandTexts = commandTexts(prefs),
            smartThingsValiditySeconds = (prefs[KeySmartThingsValiditySeconds] ?: 120).coerceIn(10, 600),
            protectPhoneKey = prefs[KeyProtectPhoneKey] ?: true,
            deviceMode = DeviceMode.of(prefs[KeyDeviceMode]),
            isEnrolled = prefs[KeyEnrolled] ?: false,
            vehicleAddress = prefs[KeyVehicleAddress] ?: "",
            vehicleName = prefs[KeyVehicleName] ?: "",
            stealthCharging = prefs[KeyStealthCharging] ?: false,
            stealthChargeStarted = prefs[KeyStealthChargeStarted] ?: false,
            stealthChargeOriginalAmps = prefs[KeyStealthChargeOriginalAmps],
            stealthChargeModified = prefs[KeyStealthChargeModified] ?: false,
            stealthScheduleEnabled = prefs[KeyStealthScheduleEnabled] ?: false,
            stealthStartMinutes = (prefs[KeyStealthStartMinutes] ?: 23 * 60).coerceIn(0, 1439),
            stealthEndMinutes = (prefs[KeyStealthEndMinutes] ?: 7 * 60).coerceIn(0, 1439),
            // 공개 버전은 네이버 지도만 사용한다. 저장된 예전 선택값은 나중 확장 때 다시 쓸 수 있게 둔다.
            navigatorApp = "NAVER",
            autoStartNavigatorSafeDrive = prefs[KeyAutoStartNavigatorSafeDrive] ?: false,
            // 0.9.20의 켜짐값은 전체 진단으로 이어 받아, 업데이트 뒤 시험 흐름이 끊기지 않게 한다.
            navigatorSafeDriveLaunchMode = prefs[KeyNavigatorSafeDriveLaunchMode]
                ?: if (prefs[KeyNavigatorSafeDriveDiagnostics] == true) "ALL" else "DEFAULT",
            hudOverlay = prefs[KeyHudOverlay] ?: false,
            // 카카오 KNSDK 과금 경로는 공개 버전에서 실행하지 않는다.
            safeDrive = false,
            safeDriveSound = prefs[KeySafeDriveSound] ?: true,
            safeDriveVolume = prefs[KeySafeDriveVolume] ?: 2,
        )
    }

    suspend fun setVin(vin: String) = edit { it[KeyVin] = vin }
    suspend fun setEnrolled(enrolled: Boolean) = edit { it[KeyEnrolled] = enrolled }
    suspend fun setAutomationEnabled(enabled: Boolean) = edit { it[KeyAutomation] = enabled }
    /** 알림 접근 권한과 별개로 차량 명령 수신 여부를 저장한다. */
    /** 음성 요청 유효시간은 10초부터 10분까지 저장한다. */
    suspend fun setSmartThingsValiditySeconds(seconds: Int) = edit {
        it[KeySmartThingsValiditySeconds] = seconds.coerceIn(10, 600)
    }

    suspend fun setSmartThingsEnabled(enabled: Boolean) = edit {
        it[KeySmartThingsEnabled] = enabled
    }

    /** 동작별 문구를 한 줄로 정리해 한 설정값에 함께 저장한다. */
    suspend fun setSmartThingsCommandText(action: String, text: String) = edit { prefs ->
        if (SmartThingsCommands.all.none { it.action == action }) return@edit
        val updated = commandTexts(prefs).toMutableMap().apply {
            this[action] = text
                .replace('\n', ' ')
                .replace('\r', ' ')
                .take(MAX_SMARTTHINGS_COMMAND_TEXT_LENGTH)
        }
        prefs[KeySmartThingsCommandTexts] = Json.encodeToString(updated)
    }
    suspend fun setProtectPhoneKey(enabled: Boolean) = edit { it[KeyProtectPhoneKey] = enabled }
    /** 같은 종류의 기기도 사용 방식이 다를 수 있으므로 이 설치본에만 모드를 저장한다. */
    suspend fun setDeviceMode(mode: DeviceMode) = edit { it[KeyDeviceMode] = mode.name }
    suspend fun setVehicleAddress(address: String) = edit { it[KeyVehicleAddress] = address }
    suspend fun setVehicleName(name: String) = edit { it[KeyVehicleName] = name }
    /** 새 1회 세션은 이전 실행 흔적을 지우고, 수동 해제는 원복이 끝날 때까지 흔적을 남긴다. */
    suspend fun setStealthCharging(enabled: Boolean) = edit {
        it[KeyStealthCharging] = enabled
        if (enabled) {
            it.remove(KeyStealthChargeStarted)
            it.remove(KeyStealthChargeOriginalAmps)
            it.remove(KeyStealthChargeModified)
        } else if (it[KeyStealthChargeModified] != true) {
            // 전류를 바꾸지 않았다면 연결해서 되돌릴 것도 없다. 내부 진행 상태를 바로 비운다.
            it.remove(KeyStealthChargeStarted)
            it.remove(KeyStealthChargeOriginalAmps)
            it.remove(KeyStealthChargeModified)
        }
    }

    /** 첫 전류 조절 직전에 원래 전류를 한 번만 저장한다. */
    suspend fun beginStealthCharge(originalAmps: Int) = edit {
        if (it[KeyStealthChargeStarted] != true) {
            it[KeyStealthChargeStarted] = true
            it[KeyStealthChargeOriginalAmps] = originalAmps
            it[KeyStealthChargeModified] = false
        }
    }

    /** 원래 전류와 다른 명령이 성공했는지 남겨 불필요한 원복 명령을 막는다. */
    suspend fun setStealthChargeModified(modified: Boolean) = edit {
        it[KeyStealthChargeModified] = modified
    }

    /** 충전 완료나 수동 해제 뒤 1회 설정과 내부 복구 상태를 함께 비운다. */
    suspend fun completeStealthCharge() = edit {
        it[KeyStealthCharging] = false
        it.remove(KeyStealthChargeStarted)
        it.remove(KeyStealthChargeOriginalAmps)
        it.remove(KeyStealthChargeModified)
    }

    /** 시간대 제한 사용 여부를 저장한다. */
    suspend fun setStealthScheduleEnabled(enabled: Boolean) = edit {
        it[KeyStealthScheduleEnabled] = enabled
    }

    /** 시작 시각은 하루 범위로 가둬 저장한다. */
    suspend fun setStealthStartMinutes(minutes: Int) = edit {
        it[KeyStealthStartMinutes] = minutes.coerceIn(0, 1439)
    }

    /** 종료 시각은 하루 범위로 가둬 저장한다. */
    suspend fun setStealthEndMinutes(minutes: Int) = edit {
        it[KeyStealthEndMinutes] = minutes.coerceIn(0, 1439)
    }
    suspend fun setNavigatorApp(name: String) = edit { it[KeyNavigatorApp] = name }
    suspend fun setAutoStartNavigatorSafeDrive(enabled: Boolean) = edit {
        it[KeyAutoStartNavigatorSafeDrive] = enabled
    }
    suspend fun setNavigatorSafeDriveLaunchMode(mode: String) = edit {
        it[KeyNavigatorSafeDriveLaunchMode] = mode
    }
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
        it[KeyAutomation] = backup.automationEnabled
        it[KeyProtectPhoneKey] = backup.protectPhoneKey
        // 사용 모드는 일부러 복원하지 않는다 — 다른 기기 백업이 이 설치본의 거치 방식을 바꾸면 안 된다.
        // 1회 실행은 다른 기기에 복원하지 않는다. 시간대 취향만 이 설치본에 남는다.
        it[KeyStealthCharging] = false
        it.remove(KeyStealthChargeStarted)
        it.remove(KeyStealthChargeOriginalAmps)
        it.remove(KeyStealthChargeModified)
        // 옛 백업(version 1)엔 아래 값이 없다 — 그때는 BackupSettings의 기본값이 들어온다.
        // 기본값이 곧 "안 쓰던 상태"라 되돌린 기기가 갑자기 GPS를 켜지는 않는다
        it[KeyHudOverlay] = backup.hudOverlay
        it[KeySafeDrive] = backup.safeDrive
        it[KeySafeDriveSound] = backup.safeDriveSound
        it[KeySafeDriveVolume] = backup.safeDriveVolume.coerceIn(1, 3)
    }

    // 제거된 음성·폴링 설정값을 업데이트 뒤에도 DataStore에 남기지 않는다.
    suspend fun removeObsoleteSettings() = edit {
        it.remove(KeyLegacyVoiceAlwaysOn)
        it.remove(KeyLegacyIdlePoll)
        it.remove(KeyLegacyActivePoll)
        it.remove(KeyLegacyActiveWindow)
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
        val prefs = store.data.first()
        val at = prefs[KeyParkedAt] ?: return null
        return at to prefs[KeyParkedBattery]
    }

    /** 마지막으로 본 탑승 상태와 본 시각. 남긴 적 없으면 null */
    suspend fun lastPresence(): Pair<Boolean, Long>? {
        val prefs = store.data.first()
        val present = prefs[KeyLastPresence] ?: return null
        return present to (prefs[KeyLastPresenceAt] ?: 0L)
    }

    /** 저장된 마지막 좌표와 저장 시각. 저장된 적 없으면 null */
    suspend fun lastGeo(): Pair<com.wemade.teslamacro.domain.macro.GeoPoint, Long>? {
        val prefs = store.data.first()
        val lat = prefs[KeyLastGeoLat] ?: return null
        val lng = prefs[KeyLastGeoLng] ?: return null
        return com.wemade.teslamacro.domain.macro.GeoPoint(lat, lng) to (prefs[KeyLastGeoAt] ?: 0L)
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        store.edit(block)
    }

    /** 새 형식을 읽고, 없거나 깨졌으면 기존 프렁크 문구를 새 동작 목록으로 옮긴다. */
    private fun commandTexts(prefs: Preferences): Map<String, String> {
        val stored = prefs[KeySmartThingsCommandTexts]?.let { encoded ->
            runCatching { Json.decodeFromString<Map<String, String>>(encoded) }.getOrNull()
        }
        val legacyFrunkText = prefs[KeySmartThingsFrunkText]
            ?.take(MAX_SMARTTHINGS_COMMAND_TEXT_LENGTH)
            ?: DEFAULT_SMARTTHINGS_FRUNK_TEXT
        return SmartThingsCommands.normalized(stored ?: SmartThingsCommands.defaults(legacyFrunkText))
    }

    private companion object {
        val KeyVin = stringPreferencesKey("vin")
        val KeyLegacyVoiceAlwaysOn = booleanPreferencesKey("voice_always_on")
        val KeyLegacyIdlePoll = intPreferencesKey("idle_poll_seconds")
        val KeyLegacyActivePoll = intPreferencesKey("active_poll_seconds")
        val KeyLegacyActiveWindow = intPreferencesKey("active_window_seconds")
        val KeyAutomation = booleanPreferencesKey("automation_enabled")
        val KeySmartThingsValiditySeconds = intPreferencesKey("smartthings_validity_seconds")
        val KeySmartThingsEnabled = booleanPreferencesKey("smartthings_enabled")
        val KeySmartThingsCommandTexts = stringPreferencesKey("smartthings_command_texts")
        // 0.9.36 설정은 새 다중 명령 설정의 초기값으로만 읽는다.
        val KeySmartThingsFrunkEnabled = booleanPreferencesKey("smartthings_frunk_enabled")
        val KeySmartThingsFrunkText = stringPreferencesKey("smartthings_frunk_text")
        val KeyProtectPhoneKey = booleanPreferencesKey("protect_phone_key")
        // 키 이름은 기존 설치본의 값을 읽기 위해 유지하고, 값만 MOUNTED/PORTABLE로 갱신한다.
        val KeyDeviceMode = stringPreferencesKey("device_role")
        val KeyEnrolled = booleanPreferencesKey("enrolled")
        val KeyVehicleAddress = stringPreferencesKey("vehicle_address")
        val KeyVehicleName = stringPreferencesKey("vehicle_name")
        val KeyStealthCharging = booleanPreferencesKey("stealth_charging")
        val KeyStealthChargeStarted = booleanPreferencesKey("stealth_charge_started")
        val KeyStealthChargeOriginalAmps = intPreferencesKey("stealth_charge_original_amps")
        val KeyStealthChargeModified = booleanPreferencesKey("stealth_charge_modified")
        val KeyStealthScheduleEnabled = booleanPreferencesKey("stealth_schedule_enabled")
        val KeyStealthStartMinutes = intPreferencesKey("stealth_start_minutes")
        val KeyStealthEndMinutes = intPreferencesKey("stealth_end_minutes")
        val KeyLastGeoLat = doublePreferencesKey("last_geo_lat")
        val KeyLastGeoLng = doublePreferencesKey("last_geo_lng")
        val KeyLastGeoAt = longPreferencesKey("last_geo_at")
        val KeyLastPresence = booleanPreferencesKey("last_presence")
        val KeyLastPresenceAt = longPreferencesKey("last_presence_at")
        val KeyNavigatorApp = stringPreferencesKey("navigator_app")
        val KeyAutoStartNavigatorSafeDrive = booleanPreferencesKey("auto_start_navigator_safe_drive")
        val KeyNavigatorSafeDriveLaunchMode = stringPreferencesKey("navigator_safe_drive_launch_mode")
        // 0.9.20 전용 키. 다음 버전에서 전체 진단으로 1회 이관한다.
        val KeyNavigatorSafeDriveDiagnostics = booleanPreferencesKey("navigator_safe_drive_diagnostics")
        val KeyHudOverlay = booleanPreferencesKey("hud_overlay")
        val KeySafeDrive = booleanPreferencesKey("safe_drive")
        val KeySafeDriveSound = booleanPreferencesKey("safe_drive_sound")
        val KeySafeDriveVolume = intPreferencesKey("safe_drive_volume")
        val KeyParkedAt = longPreferencesKey("parked_at")
        val KeyParkedBattery = intPreferencesKey("parked_battery")
    }
}
