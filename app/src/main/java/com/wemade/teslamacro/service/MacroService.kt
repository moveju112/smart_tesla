package com.wemade.teslamacro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.wemade.teslamacro.MainActivity
import com.wemade.teslamacro.R
import com.wemade.teslamacro.TeslaMacroApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 매크로 감시를 화면 밖에서도 계속 돌리는 포그라운드 서비스.
 *
 * 태블릿은 차에 상시 거치되므로 앱이 백그라운드로 가도 폴링이 끊기면 안 된다.
 * connectedDevice 타입이라 BLE 연결을 유지할 수 있다.
 */
class MacroService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val app = application as TeslaMacroApplication
        lifecycleScope.launch {
            // 컨테이너 초기화가 끝난 뒤에만 폴링을 시작한다
            app.ready.first { it }
            app.container.poller.start(lifecycleScope)
        }
    }

    override fun onDestroy() {
        (application as TeslaMacroApplication).container.poller.stop()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        // 기기는 한 번 만든 채널의 중요도를 기억한다. 낮추려면 새 ID로 만들어야 적용된다
        manager.deleteNotificationChannel("macro_watch")
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_MIN,   // 상태바 아이콘 없이 목록 맨 아래로
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_title))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "macro_watch_min"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MacroService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MacroService::class.java))
        }
    }
}
