package com.wemade.teslamacro.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.wemade.teslamacro.BuildConfig
import com.wemade.teslamacro.MainActivity
import com.wemade.teslamacro.R
import com.wemade.teslamacro.TeslaMacroApplication
import com.wemade.teslamacro.data.update.AppUpdater
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

        // 새 버전 확인은 컨테이너·차량과 무관하니 따로 돈다
        checkForUpdate()
        watchVehiclePower()
    }

    /**
     * 차 시동이 걸리면 즉시 한 사이클 돈다.
     *
     * 이 태블릿은 차 USB에 물려 상시 거치돼 있다. 그래서 **전원이 들어오는 순간이
     * 곧 차가 깬 순간**이다 — 폰이라면 못 쓸 신호지만 이 기기에서는 가장 정확하다.
     * 안 그러면 깊은 유휴(최대 120초)를 다 자고서야 탑승을 알아챈다.
     *
     * 동적 등록이라 서비스가 사는 동안만 듣는다. 매니페스트에 걸면 앱이 죽어도
     * 깨어나지만, 이 앱은 어차피 FGS로 상주하므로 그 복잡도를 살 이유가 없다.
     */
    private fun watchVehiclePower() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(
            this,
            powerReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val app = application as? TeslaMacroApplication ?: return
            if (!app.ready.value) return
            when (intent.action) {
                // 시동 = 차가 깼다. 낡은 화면으로 사람을 맞이하지 않는다
                Intent.ACTION_POWER_CONNECTED -> {
                    com.wemade.teslable.DiagLog.add("차량 전원 연결 — 즉시 상태 확인")
                    app.container.poller.nudge()
                }
                // 시동 꺼짐도 상태가 바뀐 순간이다 — 잠금·탑승을 한 번 확인하고 조용해진다
                Intent.ACTION_POWER_DISCONNECTED -> {
                    com.wemade.teslable.DiagLog.add("차량 전원 끊김 — 마지막 상태 확인")
                    app.container.poller.nudge()
                }
            }
        }
    }

    /** 하루 한 번 새 버전을 확인하고, 이번에 찾았으면 알림으로 알린다 */
    private fun checkForUpdate() {
        lifecycleScope.launch {
            // 확인이 죽어도 감시는 계속돼야 한다
            val version = runCatching {
                AppUpdater.checkAutomatically(this@MacroService, BuildConfig.VERSION_NAME)
            }.getOrNull()
            if (version != null) notifyUpdate(version)
        }
    }

    /**
     * 새 버전을 찾았을 때 한 번 알린다.
     *
     * 감시 알림은 IMPORTANCE_MIN이라 눈에 안 보인다 — 그 채널에 실으면 아무도 못 본다.
     * 그래서 보이는 채널을 따로 둔다.
     */
    private fun notifyUpdate(version: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                UPDATE_CHANNEL_ID,
                getString(R.string.update_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
        val openApp = PendingIntent.getActivity(
            this,
            1,   // 감시 알림(0)과 다른 요청 코드 — 같으면 인텐트가 서로 덮인다
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            UPDATE_NOTIFICATION_ID,
            Notification.Builder(this, UPDATE_CHANNEL_ID)
                .setContentTitle("새 버전 ${version}이 있어요")
                .setContentText("설정 → 업데이트에서 설치할 수 있어요")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .build(),
        )
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
        runCatching { unregisterReceiver(powerReceiver) }
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

        /** 새 버전 알림 — 감시 알림과 달리 눈에 보여야 해서 채널이 따로다 */
        private const val UPDATE_CHANNEL_ID = "update_available"
        private const val UPDATE_NOTIFICATION_ID = 1002

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MacroService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MacroService::class.java))
        }
    }
}
