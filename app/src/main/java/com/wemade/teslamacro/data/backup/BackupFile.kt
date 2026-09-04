package com.wemade.teslamacro.data.backup

import com.wemade.teslamacro.data.settings.AppSettings
import com.wemade.teslamacro.domain.macro.MacroRule
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 내보내기 파일의 내용.
 *
 * **차를 특정하거나 여는 것은 담지 않는다.** VIN·BLE 주소·키 등록 상태는 빠진다 —
 * 이 파일은 메일로도 클라우드로도 나갈 수 있고, 그 안에 차 식별자가 있으면
 * 백업이 아니라 유출이다. 키는 애초에 이 앱의 파일이 아니다(AndroidKeyStore 밖 저장소).
 *
 * 복원하면 매크로와 취향 설정만 돌아오고, 차량 등록은 다시 해야 한다.
 * 기기를 새로 사는 상황에서는 어차피 카드키 등록을 다시 해야 하므로 손해가 없다.
 */
@Serializable
data class BackupFile(
    /** 읽는 쪽이 형식을 가릴 수 있게. 필드가 늘면 올린다 */
    val version: Int = CURRENT_VERSION,
    /** 만든 시각(epoch millis). 어느 백업이 최신인지 사람이 가리는 데 쓴다 */
    val createdAtMillis: Long = 0L,
    /** 만든 앱 버전. 옛 파일을 열었을 때 원인을 짚는 단서 */
    val appVersion: String = "",
    val macros: List<MacroRule> = emptyList(),
    val settings: BackupSettings = BackupSettings(),
) {
    companion object {
        /** 2 — 내비 앱·HUD·과속 안내와 그 소리 설정이 늘었다 */
        const val CURRENT_VERSION = 2

        /** 파일 이름. 날짜를 붙이는 건 저장 다이얼로그에서 사람이 한다 */
        const val DEFAULT_FILE_NAME = "smart-tesla-backup.json"

        val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true   // 새 앱이 만든 파일을 옛 앱이 열어도 죽지 않는다
            encodeDefaults = true
        }
    }
}

/**
 * 백업에 담는 설정. [AppSettings] 전체가 아니라 **취향만** 담는다.
 * 차량 식별·등록 상태는 의도적으로 빠져 있다.
 */
@Serializable
data class BackupSettings(
    val automationEnabled: Boolean = true,
    val stealthCharging: Boolean = false,
    // 내비 앱은 담지 않는다 — 새 기기에 그 앱이 없으면 선택값이 화면에서 사라지고
    // 매크로의 지도 안내가 실행 순간에 실패한다. 다시 고르는 건 한 번의 탭이다
    val hudOverlay: Boolean = false,
    val safeDrive: Boolean = false,
    val safeDriveSound: Boolean = true,
    val safeDriveVolume: Int = 2,
)

/** 지금 설정에서 백업에 담을 부분만 뽑는다 */
fun AppSettings.toBackup(): BackupSettings = BackupSettings(
    automationEnabled = automationEnabled,
    stealthCharging = stealthCharging,
    hudOverlay = hudOverlay,
    safeDrive = safeDrive,
    safeDriveSound = safeDriveSound,
    safeDriveVolume = safeDriveVolume,
)
