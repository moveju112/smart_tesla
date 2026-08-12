package com.wemade.teslamacro.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
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
    /** 집중 폴링 지속 시간 */
    val activeWindowSeconds: Int = 300,
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
     * 기본 꺼짐. 켜면 충전이 느려지는 대가가 있다 (평균 전류가 내려가고 쉬는 구간이 생김).
     */
    val stealthCharging: Boolean = false,
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
            activeWindowSeconds = prefs[KeyActiveWindow] ?: 300,
            automationEnabled = prefs[KeyAutomation] ?: true,
            isEnrolled = prefs[KeyEnrolled] ?: false,
            voiceAlwaysOn = prefs[KeyVoiceAlwaysOn] ?: false,
            vehicleAddress = prefs[KeyVehicleAddress] ?: "",
            vehicleName = prefs[KeyVehicleName] ?: "",
            stealthCharging = prefs[KeyStealthCharging] ?: false,
        )
    }

    suspend fun setVin(vin: String) = edit { it[KeyVin] = vin }
    suspend fun setEnrolled(enrolled: Boolean) = edit { it[KeyEnrolled] = enrolled }
    suspend fun setIdlePollSeconds(seconds: Int) = edit { it[KeyIdlePoll] = seconds }
    suspend fun setActivePollSeconds(seconds: Int) = edit { it[KeyActivePoll] = seconds }
    suspend fun setAutomationEnabled(enabled: Boolean) = edit { it[KeyAutomation] = enabled }
    suspend fun setVoiceAlwaysOn(enabled: Boolean) = edit { it[KeyVoiceAlwaysOn] = enabled }
    suspend fun setVehicleAddress(address: String) = edit { it[KeyVehicleAddress] = address }
    suspend fun setVehicleName(name: String) = edit { it[KeyVehicleName] = name }
    suspend fun setStealthCharging(enabled: Boolean) = edit { it[KeyStealthCharging] = enabled }

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
    }
}
