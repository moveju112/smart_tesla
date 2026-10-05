package com.wemade.teslamacro.data.nav

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import com.wemade.teslamacro.TeslaMacroApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** 설정 복귀 때 실제로 바뀐 필수 조건만 이어가고 같은 화면을 반복해서 열지 않는다. */
internal object NavigationSetup {
    enum class Step(val message: String) {
        DEVELOPER("휴대전화 정보에서 빌드번호를 7번 눌러 개발자 옵션을 켜 주세요"),
        USB("개발자 옵션에서 USB 디버깅을 켜 주세요"),
        CONNECT("저장된 인증으로 연결 준비 중"),
        WIFI("Wi-Fi에 연결해 주세요"),
        NOTIFICATIONS("앱 알림을 허용해 주세요"),
        CHANNEL("‘네이버 안심주행 연결’ 알림을 허용해 주세요"),
        PAIR("무선 디버깅 → 페어링 코드로 기기 페어링 → 알림에 코드 6자리 입력"),
        READY("연결 준비 완료 · Wi-Fi 없이 실행할 수 있어요"),
    }

    data class State(val developer: Boolean, val usb: Boolean, val wifi: Boolean, val notifications: Boolean, val channel: Boolean) {
        /** 기존 인증은 Wi-Fi·알림을 요구하기 전에 사용해 이미 준비된 연결을 보존한다. */
        fun nextStep(saved: Boolean): Step = when {
            !developer -> Step.DEVELOPER
            !usb -> Step.USB
            saved -> Step.CONNECT
            !wifi -> Step.WIFI
            !notifications -> Step.NOTIFICATIONS
            !channel -> Step.CHANNEL
            else -> Step.PAIR
        }

        /** 사용자가 해당 조건을 해결했을 때만 다음 설정으로 자동 진행한다. */
        fun completed(step: Step?): Boolean = when (step) {
            Step.DEVELOPER -> developer
            Step.USB -> usb
            Step.WIFI -> wifi
            Step.NOTIFICATIONS -> notifications
            Step.CHANNEL -> channel
            else -> false
        }
    }

    /** 인터넷 검증 여부와 무관하게 이 휴대폰의 Wi-Fi 연결과 알림 차단 상태를 읽는다. */
    fun read(context: Context): State {
        val networks = context.getSystemService(ConnectivityManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        return State(
            Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1,
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1,
            networks.allNetworks.any { networks.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true },
            notifications.areNotificationsEnabled(),
            notifications.getNotificationChannel(NavigationPairingService.CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE,
        )
    }

    /** 인증 복구 실패도 사용자 화면으로 돌려 다음 설정을 바로 열 수 있게 한다. */
    suspend fun resolve(context: Context): Step {
        val navigation = (context.applicationContext as TeslaMacroApplication).container.wirelessNavigation
        val step = read(context).nextStep(navigation.hasPairing)
        if (step != Step.CONNECT) return step
        val preparation = navigation.prepare()
        if (preparation != null) preparation.join()
        // Wi-Fi 복귀가 먼저 시작한 복구도 기다려 불필요한 재페어링을 피한다.
        else withTimeoutOrNull(35_000) { navigation.state.first { it.prepared || !it.busy } }
        return if (navigation.state.value.prepared) Step.READY else read(context).nextStep(saved = false)
    }

    /** 차단된 항목의 시스템 화면을 지정하고 디버깅 권한 자체는 사용자가 허용한다. */
    fun intent(context: Context, step: Step): Intent = when (step) {
        Step.DEVELOPER -> Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
        Step.USB -> NavigationPairingService.settingsIntent("enable_adb")
        Step.WIFI -> Intent(Settings.ACTION_WIFI_SETTINGS)
        Step.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        Step.CHANNEL -> Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, NavigationPairingService.CHANNEL)
        else -> NavigationPairingService.settingsIntent()
    }
}
