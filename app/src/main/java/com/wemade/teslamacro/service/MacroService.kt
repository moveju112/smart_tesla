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
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.wemade.teslamacro.BuildConfig
import com.wemade.teslamacro.MainActivity
import com.wemade.teslamacro.R
import com.wemade.teslamacro.TeslaMacroApplication
import com.wemade.teslamacro.data.update.AppUpdater
import com.wemade.teslamacro.domain.command.confirmCategory
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
        watchSpeedOverlay()
        watchSafeDrive()
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
            // 시스템이 보내는 브로드캐스트라 EXPORTED여야 받는다.
            // NOT_EXPORTED는 "우리 앱이 보낸 것만" 받겠다는 뜻이라 전원 이벤트가 영영 안 온다.
            // 전원 연결/해제는 protected broadcast라 다른 앱이 위조할 수 없다
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    private val overlay by lazy { SpeedOverlay(this) }

    /** 직전에 로그로 남긴 과속 여부. 상태가 바뀔 때만 한 줄 찍기 위한 기준 */
    private var overSpeedLogged = false

    /**
     * 주행 중 속도를 좇는다.
     *
     * **과속 판정과 HUD 창은 별개다.** 전에는 둘이 한 덩어리라 "속도를 다른 앱 위에 표시"를
     * 꺼두면 과속 감지 자체가 안 돌았다 — 과속 안내만 켠 사람에겐 기능이 통째로 없는 것과 같았다.
     * 그래서 GPS는 **둘 중 하나라도 켜져 있으면** 구독하고, 창은 HUD 설정일 때만 올린다.
     * 둘 다 꺼져 있으면 위성을 아예 안 쓴다.
     *
     * 창은 **정지하면 내린다** — 주차된 차 위에 떠 있는 "0"은 정보가 아니라 방해다.
     */
    private fun watchSpeedOverlay() {
        val app = application as TeslaMacroApplication
        lifecycleScope.launch {
            app.ready.first { it }
            val container = app.container
            // 권한 신호를 함께 묶는다 — 허용하고 돌아온 순간 스트림을 다시 연다.
            // 세 값을 함께 distinct 하므로 무관한 설정 변경으로는 GPS를 다시 열지 않는다
            kotlinx.coroutines.flow.combine(
                container.settingsStore.settings.map { it.hudOverlay to it.safeDrive },
                container.locationPermissionRevision,
            ) { toggles, revision -> Triple(toggles.first, toggles.second, revision) }
                .distinctUntilChanged()
                .collectLatest { (showOverlay, safeDrive, _) ->
                    overlay.hide()
                    overSpeedLogged = false
                    if (!showOverlay && !safeDrive) return@collectLatest

                    // 위성을 못 잡거나 권한이 없으면 스트림이 곧바로 닫힌다 —
                    // 그때 화면에 아무것도 안 뜨는 이유를 여기서 알 수 있어야 한다
                    com.wemade.teslable.DiagLog.add(
                        "속도 감시 시작 (창=$showOverlay · 과속안내=$safeDrive" +
                            " · 위치권한=${container.speedMeter.hasPermission()}" +
                            " · 오버레이권한=${overlay.canDraw})"
                    )
                    container.speedMeter.speedKph().collect { kph ->
                        if (kph < MOVING_KPH) {
                            overlay.hide()
                            // 세우면 과속 상태도 함께 내린다. 안 내리면 다음 주행 첫 순간에
                            // "과속 해제"가 거짓으로 한 줄 찍힌다
                            overSpeedLogged = false
                            return@collect
                        }
                        val safety = container.safeDrive.state.value
                        val over = safety.isOverSpeed(kph, OVER_SPEED_TOLERANCE_KPH)
                        // 매 초 찍으면 로그가 이거로만 찬다 — 넘어간 순간과 돌아온 순간만
                        if (over != overSpeedLogged) {
                            overSpeedLogged = over
                            val limit = safety.alert?.speedLimitKph
                            com.wemade.teslable.DiagLog.add(
                                if (over) "과속 ${kph.toInt()}km/h (제한 ${limit ?: "?"})"
                                else "과속 해제 ${kph.toInt()}km/h"
                            )
                        }
                        if (showOverlay) {
                            overlay.show(
                                speedKph = kph,
                                warning = warningTextOf(safety),
                                over = over,
                            )
                        }
                    }
                }
        }
    }

    /**
     * 설정이 켜져 있을 때만 안전운전 안내를 돌린다.
     * GPS와 망을 계속 쓰는 기능이라, 꺼져 있으면 SDK를 아예 안 깨운다.
     */
    private fun watchSafeDrive() {
        val app = application as TeslaMacroApplication
        lifecycleScope.launch {
            app.ready.first { it }
            app.container.settingsStore.settings
                .map { Triple(it.safeDrive, it.safeDriveSound, it.safeDriveVolume) }
                .distinctUntilChanged()
                .collect { (enabled, sound, volume) ->
                    // 소리 설정을 먼저 밀어 넣는다 — start() 직후 첫 경보가
                    // 옛 설정으로 재생되면 껐는데 소리가 나는 것으로 보인다
                    app.container.safeDrive.setSound(sound, volume)
                    if (enabled) app.container.safeDrive.start()
                    else app.container.safeDrive.stop()
                }
        }

        // 권한이 방금 생겼다면 이미 돌던 안내는 측위를 못 받는 채 떠 있다 —
        // 세웠다 다시 세워야 SDK가 GPS를 새로 잡는다. 첫 값(0)은 흘려보낸다
        lifecycleScope.launch {
            app.ready.first { it }
            app.container.locationPermissionRevision.drop(1).collect {
                if (!app.container.settingsStore.settings.first().safeDrive) return@collect
                com.wemade.teslable.DiagLog.add("위치 권한이 바뀌어 안전운전 안내를 다시 세웁니다")
                app.container.safeDrive.stop()
                app.container.safeDrive.start()
            }
        }
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
        if (intent?.action == ACTION_RUN_QUICK_ACTION) {
            val action = intent.getStringExtra(QuickActionActivity.EXTRA_ACTION)
            val macroId = intent.getStringExtra(QuickActionActivity.EXTRA_MACRO_ID)
            lifecycleScope.launch { handleQuickAction(action, macroId) }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /** 빅스비·런처 요청을 서비스 수명 안에서 연결부터 실제 전송까지 처리한다. */
    private suspend fun handleQuickAction(action: String?, macroId: String?) {
        val app = application as TeslaMacroApplication
        app.ready.first { it }

        val command = QuickActionActivity.ACTIONS[action]
        val rule = macroId?.let { id ->
            app.container.ruleStore.rules.value.firstOrNull { it.id == id }
        }
        val requestLabel = rule?.name ?: command?.label
        if (requestLabel == null) {
            quickActionFailed("알 수 없는 동작", "삭제되었거나 지원하지 않는 바로가기예요")
            return
        }

        com.wemade.teslable.DiagLog.add("빅스비 요청 처리 시작 — $requestLabel")
        val settings = app.container.settingsStore.settings.first()
        if (!settings.isReady) {
            quickActionFailed(requestLabel, "차량 키 등록을 먼저 완료해 주세요")
            return
        }

        // 사람이 방금 누른 요청이므로 저장 주소 직행이 한 번 실패하면 주변 검색까지 시도한다.
        // 자동 폴링과 달리 후보 검증을 생략하면 일시적인 GATT 오류 한 번에 명령이 유실된다.
        val connection = app.container.gateway.connect(settings.vin, allowProbe = true)
        if (connection.isFailure) {
            quickActionFailed(requestLabel, connection.exceptionOrNull()?.message ?: "차량 연결 실패")
            return
        }

        if (rule != null) {
            // 빅스비 실행은 목록의 "지금 실행"과 같다. 자동 조건은 다시 검사하지 않는다.
            app.container.runner.launch(
                rule,
                System.currentTimeMillis(),
                restartIfRunning = true,
            )
            app.container.poller.recordFired(rule.id)
            com.wemade.teslable.DiagLog.add("빅스비 매크로 [${rule.name}] 실행 요청 완료")
            showQuickActionToast("${rule.name} 실행")
            return
        }

        val result = app.container.gateway.send(checkNotNull(command))
        if (result.isSuccess) {
            // 결과를 즉시 다시 읽어, 이어서 앱을 열었을 때 실제 값이 바로 보이게 한다.
            app.container.poller.focusOn(command.confirmCategory())
            com.wemade.teslable.DiagLog.add("빅스비 명령 [${command.label}] 완료")
            showQuickActionToast("${command.label} 완료")
        } else {
            quickActionFailed(
                command.label,
                result.exceptionOrNull()?.message ?: "차량이 명령을 거부했어요",
            )
        }
    }

    /** 빅스비 요청 실패를 진단 로그와 짧은 화면 안내에 함께 남긴다. */
    private fun quickActionFailed(label: String, reason: String) {
        com.wemade.teslable.DiagLog.add("빅스비 명령 [$label] 실패 — $reason")
        showQuickActionToast("$label 실패 — $reason")
    }

    /** 숨은 실행 화면이 이미 끝난 뒤에도 결과를 사용자에게 알린다. */
    private fun showQuickActionToast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
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
            // 부팅 직후 등 위치 타입이 막히는 상황 — 매크로 감시가 죽는 것보단 위치를 포기한다.
            // 폴백까지 던질 수 있다(BLE 권한 없이 connectedDevice 타입을 못 붙인다).
            // 그때 그대로 두면 앱이 죽으므로, 타입 없이라도 살려 둔다
            runCatching { startForeground(NOTIFICATION_ID, buildNotification(), base) }
                .onFailure {
                    com.wemade.teslable.DiagLog.add("감시 알림을 타입 없이 올립니다 (${it.message})")
                    runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }
                }
        }
    }

    override fun onDestroy() {
        overlay.hide()
        runCatching { (application as TeslaMacroApplication).container.safeDrive.stop() }
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
        private const val ACTION_RUN_QUICK_ACTION =
            "com.wemade.teslamacro.action.RUN_QUICK_ACTION"

        /** 새 버전 알림 — 감시 알림과 달리 눈에 보여야 해서 채널이 따로다 */
        private const val UPDATE_CHANNEL_ID = "update_available"
        private const val UPDATE_NOTIFICATION_ID = 1002

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MacroService::class.java))
        }

        /** 숨은 바로가기 화면에서 받은 요청을 포그라운드 서비스에 안전하게 넘긴다. */
        fun runQuickAction(context: Context, action: String?, macroId: String?) {
            val intent = Intent(context, MacroService::class.java)
                .setAction(ACTION_RUN_QUICK_ACTION)
                .putExtra(QuickActionActivity.EXTRA_ACTION, action)
                .putExtra(QuickActionActivity.EXTRA_MACRO_ID, macroId)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MacroService::class.java))
        }
    }
}

/** 이 속도를 넘으면 달리는 중으로 본다. 보행 속도는 정지로 친다 */
private const val MOVING_KPH = 5.0

/** 이보다 넘겨야 과속으로 본다. GPS 속도는 계기판보다 1~2km/h 흔들린다 */
private const val OVER_SPEED_TOLERANCE_KPH = 3

/** 오버레이 아래 줄에 넣을 경고 한 마디. 안내할 게 없으면 null */
private fun warningTextOf(state: com.wemade.teslamacro.domain.safety.SafetyState): String? {
    val alert = state.alert ?: return null
    val distance = alert.distanceMeters
    val limit = alert.speedLimitKph
    return buildString {
        append(alert.kind.label)
        if (limit != null) append(" $limit")
        if (distance != null) append(" · ${distance}m")
    }
}
