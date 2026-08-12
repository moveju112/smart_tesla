package com.wemade.teslamacro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wemade.teslamacro.MainActivity
import com.wemade.teslamacro.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 재부팅 후 감시 서비스를 되살린다.
 *
 * 태블릿은 차량 전원에 물려 있어 시동을 끄면 같이 꺼지는 경우가 많다.
 * 사용자가 앱을 다시 열어야만 매크로가 도는 구조면 자동화가 아니다.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        MacroService.start(context)

        // 음성 상시 대기는 마이크 서비스라 부팅 직후엔 못 올린다 (OS가 백그라운드 시작 금지).
        // 켜뒀던 사용자에게 알림을 남기고, 탭해서 앱이 뜨면 MainActivity가 서비스를 되살린다
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. 설정을 읽어 음성 대기를 켜뒀던 사용자인지 확인한다
                val settings = SettingsStore(context).settings.first()
                if (settings.voiceAlwaysOn) notifyVoiceNeedsTap(context)
            } finally {
                pending.finish()
            }
        }
    }

    // 음성 대기가 꺼진 채 부팅됐음을 알린다. 탭하면 앱이 열리며 서비스가 다시 선다
    private fun notifyVoiceNeedsTap(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "재부팅 안내", NotificationManager.IMPORTANCE_DEFAULT)
        )

        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setContentTitle("음성 대기가 꺼져 있어요")
                .setContentText("재부팅 후에는 자동으로 켤 수 없어요. 탭해서 다시 켜 주세요")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .build(),
        )
    }

    private companion object {
        const val CHANNEL_ID = "boot_notice"
        const val NOTIFICATION_ID = 1003
    }
}
