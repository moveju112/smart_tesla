package com.wemade.teslamacro.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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
        promote()

        val app = application as TeslaMacroApplication
        lifecycleScope.launch {
            // 컨테이너 초기화가 끝난 뒤에만 폴링을 시작한다
            app.ready.first { it }
            app.container.poller.start(lifecycleScope)
            // 스텔스 충전도 같은 서비스 수명에 맞춰 돈다. 안에서 설정·충전 여부를 스스로 게이트한다
            app.container.stealthCharge.start(lifecycleScope)
        }
    }

    /** 위치 권한을 나중에 받아도 start()를 다시 부르면 여기서 타입이 갱신된다 */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promote()
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * 포그라운드로 승격한다.
     *
     * 백그라운드에서 GPS를 읽으려면 서비스 타입에 location이 있어야 하는데,
     * 위치 권한 없이 location 타입으로 시작하면 시스템이 거부한다.
     * 그래서 권한이 있을 때만 붙이고, 그래도 거부되면 위치 없이 감시만 계속한다.
     */
    private fun promote() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification())
            return
        }
        val base = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        // "대략적인 위치"만 허용한 경우 FINE은 거부 상태다 — 둘 중 하나면 충분하다
        val hasLocation = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ).any { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                if (hasLocation) base or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else base,
            )
        } catch (e: SecurityException) {
            // 부팅 직후 등 위치 타입이 막히는 상황 — 매크로 감시가 죽는 것보단 위치를 포기한다
            startForeground(NOTIFICATION_ID, buildNotification(), base)
        }
    }

    override fun onDestroy() {
        (application as TeslaMacroApplication).container.let {
            it.poller.stop()
            it.stealthCharge.stop()
        }
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
