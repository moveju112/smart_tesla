package com.wemade.teslamacro.service

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.os.SystemClock
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.wemade.teslamacro.BuildConfig
import com.wemade.teslamacro.MainActivity
import com.wemade.teslamacro.R
import com.wemade.teslamacro.TeslaMacroApplication
import com.wemade.teslamacro.data.update.AppUpdater
import com.wemade.teslamacro.data.safety.DriveAlertGate
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import com.wemade.teslamacro.data.nav.NavigatorApp
import com.wemade.teslamacro.data.nav.SafeDriveLaunchMode
import com.wemade.teslamacro.data.nav.forAutomaticStart
import com.wemade.teslamacro.data.settings.DeviceMode
import com.wemade.teslamacro.domain.command.confirmCategory
import com.wemade.teslable.BondedDevice
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** 주행 안내만 다시 적용해 무관한 설정 변경으로 GPS 감시를 흔들지 않는다. */
private data class SafeDriveOptions(
    val enabled: Boolean,
    val sound: Boolean,
    val volume: Int,
    val toleranceKph: Int,
    val progressiveSound: Boolean,
    val distanceMeters: Int,
    val voice: Boolean,
    val deviceMode: DeviceMode,
)

/**
 * 매크로 감시를 화면 밖에서도 계속 돌리는 포그라운드 서비스.
 *
 * 거치 모드는 백그라운드 자동화를 감시하고, 휴대 모드는 앱·직접 명령과 자동 안심운전
 * 탑승 확인 창 동안만 연결한다. connectedDevice 타입은 두 모드의 명시적 연결에 쓴다.
 */
class MacroService : LifecycleService() {

    private var safeDriveTestJob: Job? = null
    private var stealthChargeWakeLock: PowerManager.WakeLock? = null
    private val driveAlertGate = DriveAlertGate()
    private var activityUpdates: PendingIntent? = null
    private var activityUpdatesReady = false
    private var activityFailure: String? = null
    private var activityCleanupCompleted = false
    private val carAudioConnected = MutableStateFlow(false)
    private val portableGuidanceActive = MutableStateFlow(false)
    private var manualGuideTimeout: Job? = null
    private var manualGuideActive = false
    private var manualGuideStartedAt: Long? = null
    private var carAudioProfile: BluetoothProfile? = null
    private var carAudioAdapter: BluetoothAdapter? = null
    private var carAudioName: String = ""
    private var carAudioSelectedAddress: String = ""
    private var carAudioWatcherActive = false
    private var carAudioProxyRequested = false
    private var currentDeviceMode = DeviceMode.PORTABLE
    private var currentSafeDriveEnabled = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 프로세스가 예고 없이 재시작되면 수동 세션은 복구하지 않고 종료 사실만 알린다.
        val unfinished = getSharedPreferences(MANUAL_SESSION_PREFERENCES, MODE_PRIVATE)
            .getBoolean(MANUAL_SESSION_ACTIVE, false)
        if (unfinished) {
            getSystemService(AlarmManager::class.java).cancel(manualGuideAlarmIntent())
            getSharedPreferences(MANUAL_SESSION_PREFERENCES, MODE_PRIVATE).edit()
                .putBoolean(MANUAL_SESSION_ACTIVE, false).apply()
        }
        promote()
        if (unfinished) showManualGuideEnded("앱이 다시 시작돼 수동 GPS·카메라 안내가 종료됐어요.")

        val app = application as TeslaMacroApplication
        lifecycleScope.launch {
            // 컨테이너 초기화가 끝난 뒤에만 폴링을 시작한다
            app.ready.first { it }
            app.container.poller.setVehiclePowerConnected(isExternalPowerConnected())
            app.container.poller.start(lifecycleScope)
            app.container.poller.enforceConnectionGuard()
            // 스텔스 충전도 같은 서비스 수명에 맞춰 돈다. 안에서 설정·충전 여부를 스스로 게이트한다
            app.container.stealthCharge.start(lifecycleScope)
        }
        keepCpuAwakeForStealthCharge(app)

        // 새 버전 확인은 컨테이너·차량과 무관하니 따로 돈다
        checkForUpdate()
        watchVehiclePower()
        watchCarAudio()
        watchSpeedOverlay()
        watchSafeDrive()
        watchConfirmedPresence()
        watchNavigatorSafeDrive()
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

    // 1. 음악용 Bluetooth 프로필만 감시한다. 인증 GATT를 붙들거나 차량을 스캔하지 않는다.
    private fun watchCarAudio() {
        val app = application as TeslaMacroApplication
        lifecycleScope.launch {
            app.ready.first { it }
            carAudioWatcherActive = true
            carAudioAdapter = getSystemService(BluetoothManager::class.java)?.adapter
            ContextCompat.registerReceiver(this@MacroService, carAudioReceiver,
                IntentFilter().apply {
                    addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                    addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                    addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                }, ContextCompat.RECEIVER_EXPORTED)
            // 연결 방송을 놓친 서비스 재시작·재부팅에도 현재 A2DP 상태를 다시 읽는다.
            requestCarAudioProfile()
            app.container.settingsStore.settings
                .map { it.vehicleName to it.vehicleAudioAddress }.distinctUntilChanged().collect { (name, selected) ->
                carAudioName = name
                carAudioSelectedAddress = selected
                requestCarAudioProfile()
                refreshCarAudioConnection()
            }
        }
    }

    private val carAudioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Bluetooth를 나중에 켠 경우에도 현재 연결 상태를 복구한다.
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED &&
                intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_OFF) {
                carAudioProfile?.let { runCatching { carAudioAdapter?.closeProfileProxy(BluetoothProfile.A2DP, it) } }
                carAudioProfile = null
                carAudioProxyRequested = false
            }
            requestCarAudioProfile()
            // 방송 내용은 연결 증거로 믿지 않고 실제 프로필을 다시 조회한다.
            refreshCarAudioConnection()
        }
    }

    private val carAudioListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (!carAudioWatcherActive) {
                runCatching { carAudioAdapter?.closeProfileProxy(BluetoothProfile.A2DP, proxy) }
                return
            }
            carAudioProfile = proxy
            lifecycleScope.launch { refreshCarAudioConnection() }
        }

        override fun onServiceDisconnected(profile: Int) {
            carAudioProfile = null
            carAudioProxyRequested = false
            lifecycleScope.launch {
                refreshCarAudioConnection()
                // 시스템 프로필 프로세스가 재시작됐어도 방송을 기다리지 않고 한 번 복구한다.
                delay(1_000)
                requestCarAudioProfile()
            }
        }
    }

    // 1. Bluetooth가 꺼졌다 다시 켜져도 중복 요청 없이 프로필을 복원한다.
    @android.annotation.SuppressLint("MissingPermission")
    private fun requestCarAudioProfile() {
        if (!carAudioWatcherActive || carAudioProxyRequested || !hasCarAudioPermission() ||
            carAudioAdapter?.isEnabled != true) return
        carAudioProxyRequested = runCatching {
            carAudioAdapter?.getProfileProxy(this, carAudioListener, BluetoothProfile.A2DP) == true
        }.getOrElse {
            com.wemade.teslable.DiagLog.add("차량 오디오 프로필 준비 실패 (${it.javaClass.simpleName})")
            false
        }
    }

    // 1. 실제 연결된 A2DP와 페어링 주소를 대조하고 실패 사유만 화면·진단에 전달한다.
    @android.annotation.SuppressLint("MissingPermission")
    private fun refreshCarAudioConnection() {
        if (!carAudioWatcherActive) return
        val container = (application as TeslaMacroApplication).container
        val status = runCatching {
            val permitted = hasCarAudioPermission()
            val enabled = permitted && carAudioAdapter?.isEnabled == true
            val bonded = if (enabled) container.scanner.bondedDevices() else emptyList()
            val addresses = if (enabled) carAudioProfile?.connectedDevices
                ?.map { it.address }?.toSet().orEmpty() else emptySet()
            container.connectedAudioDevices.value = bonded.filter { device ->
                addresses.any { it.equals(device.address, ignoreCase = true) }
            }
            vehicleAudioStatus(carAudioName, bonded, carAudioSelectedAddress, addresses,
                permitted, enabled, carAudioProfile != null)
        }.getOrElse {
            container.connectedAudioDevices.value = emptyList()
            com.wemade.teslable.DiagLog.add("차량 오디오 상태 조회 실패 (${it.javaClass.simpleName})")
            VehicleAudioStatus.READ_FAILED
        }
        if (container.vehicleAudioStatus.value != status) {
            container.vehicleAudioStatus.value = status
            com.wemade.teslable.DiagLog.add("차량 오디오 상태 ${status.name}")
        }
        val connected = status == VehicleAudioStatus.CONNECTED
        if (carAudioConnected.value != connected) {
            carAudioConnected.value = connected
            com.wemade.teslable.DiagLog.add("차량 오디오 Bluetooth ${if (connected) "연결" else "해제"}")
        }
        if (connected && container.manualGuideActive.value) finishManualGuide("오디오 연결로 자동 안내 전환")
        syncPortableGuidance()
    }

    // 1. 수동·오디오 어느 쪽이든 같은 상태로 GPS·오프라인 안내·소리를 전환한다.
    private fun syncPortableGuidance() {
        val container = (application as TeslaMacroApplication).container
        portableGuidanceActive.value = carAudioConnected.value || container.manualGuideActive.value
        refreshAutomaticAlerts()
    }

    // 1. 확인되지 않는 날에만 이번 주행을 수동으로 열고, 잊어도 6시간 뒤 닫는다.
    private fun beginManualGuide() {
        lifecycleScope.launch {
            val app = application as TeslaMacroApplication
            app.ready.first { it }
            val settings = app.container.settingsStore.settings.first()
            if (settings.deviceMode != DeviceMode.PORTABLE ||
                (!settings.safeDrive && !settings.hudOverlay) || carAudioConnected.value ||
                !app.container.speedMeter.hasPermission()) return@launch
            if (app.container.manualGuideActive.value) return@launch
            manualGuideStartedAt = SystemClock.elapsedRealtime()
            manualGuideActive = true
            app.container.manualGuideActive.value = true
            getSharedPreferences(MANUAL_SESSION_PREFERENCES, MODE_PRIVATE).edit()
                .putBoolean(MANUAL_SESSION_ACTIVE, true).apply()
            com.wemade.teslable.DiagLog.add("이번 주행 수동 안내 시작 · 최대 6시간")
            syncPortableGuidance()
            promote()
            scheduleManualGuideTimeout()
            manualGuideTimeout = lifecycleScope.launch {
                delay(MANUAL_GUIDE_TIMEOUT_MILLIS)
                expireManualGuideIfNeeded()
            }
        }
    }

    // 1. 수동 중단·자동 복구·설정 해제 시 같은 경로로 GPS와 소리를 즉시 닫는다.
    private fun finishManualGuide(reason: String) {
        val container = (application as TeslaMacroApplication).container
        if (!container.manualGuideActive.value) return
        manualGuideActive = false
        container.manualGuideActive.value = false
        getSharedPreferences(MANUAL_SESSION_PREFERENCES, MODE_PRIVATE).edit()
            .putBoolean(MANUAL_SESSION_ACTIVE, false).apply()
        manualGuideTimeout?.cancel()
        manualGuideTimeout = null
        manualGuideStartedAt = null
        getSystemService(AlarmManager::class.java).cancel(manualGuideAlarmIntent())
        com.wemade.teslable.DiagLog.add(reason)
        syncPortableGuidance()
        promote()
    }

    // 1. 잠든 동안에도 만료를 요청하고, 정확한 알람 권한이 없으면 절전 허용 알람으로 대체한다.
    private fun scheduleManualGuideTimeout() {
        val alarm = getSystemService(AlarmManager::class.java)
        val deadline = (manualGuideStartedAt ?: return) + MANUAL_GUIDE_TIMEOUT_MILLIS
        val intent = manualGuideAlarmIntent()
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()
        val scheduled = if (exactAllowed) runCatching {
            alarm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, intent)
        }.isSuccess else false
        if (!scheduled) alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, intent)
    }

    // 1. 알람과 위치 콜백 모두 같은 시각 판정으로 안내를 중지해 절전 지연을 보완한다.
    private fun expireManualGuideIfNeeded() {
        if (!manualGuideActive || !manualGuideExpired(manualGuideStartedAt, SystemClock.elapsedRealtime())) return
        finishManualGuide("6시간 경과 · 수동 안내 종료")
        showManualGuideEnded("6시간이 지나 GPS·카메라 안내를 멈췄어요. 계속 필요하면 다시 시작하세요.")
    }

    // 1. 서비스가 재시작해도 알람이 새 GPS 세션을 열지 않도록 고정 요청 코드를 사용한다.
    private fun manualGuideAlarmIntent(): PendingIntent = PendingIntent.getService(this, 4,
        Intent(this, MacroService::class.java).setAction(ACTION_MANUAL_GUIDE_TIMEOUT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    // 1. 시간 만료는 주행 중 소리가 꺼진 사실을 알리되 다시 GPS를 켜지 않는다.
    private fun showManualGuideEnded(reason: String) {
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            getSystemService(NotificationManager::class.java).notify(MANUAL_END_NOTIFICATION_ID,
                Notification.Builder(this, MANUAL_END_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle("수동 내부 안내 종료")
                    .setContentText(reason)
                    .setContentIntent(openApp)
                    .setAutoCancel(true)
                    .build())
        }
    }

    // 1. Android 12+에서 연결 권한이 없으면 프로필을 열지 않고 안내를 보류한다.
    private fun hasCarAudioPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

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
            // 휴대 모드는 오디오 연결이 끊기면 위치 구독 자체를 취소한다.
            combine(
                container.settingsStore.settings.map { Triple(it.hudOverlay, it.safeDrive, it.deviceMode) },
                container.locationPermissionRevision,
                portableGuidanceActive,
            ) { toggles, revision, connected ->
                (toggles to revision) to
                    shouldMonitorGuidance(toggles.third, toggles.first || toggles.second, connected)
            }.distinctUntilChanged()
                .collectLatest { (toggles, active) ->
                    val (showOverlay, safeDrive, mode) = toggles.first
                    if (container.manualGuideActive.value && ((!showOverlay && !safeDrive) ||
                            mode != DeviceMode.PORTABLE)) {
                        finishManualGuide("설정 변경 · 수동 안내 종료")
                    }
                    overlay.hide()
                    overSpeedLogged = false
                    if (!active) return@collectLatest

                    // 위성을 못 잡거나 권한이 없으면 스트림이 곧바로 닫힌다 —
                    // 그때 화면에 아무것도 안 뜨는 이유를 여기서 알 수 있어야 한다
                    com.wemade.teslable.DiagLog.add(
                        "속도 감시 시작 (창=$showOverlay · 과속안내=$safeDrive" +
                            " · 위치권한=${container.speedMeter.hasPermission()}" +
                            " · 오버레이권한=${overlay.canDraw})"
                    )
                    coroutineScope {
                        val locations = MutableStateFlow<android.location.Location?>(null)
                        launch {
                            container.speedMeter.locations().collect { location ->
                                refreshAutomaticAlerts()
                                if (mode == DeviceMode.PORTABLE && !portableGuidanceActive.value) return@collect
                                container.safeDrive.onLocation(location)
                                locations.value = location
                            }
                        }
                        // GPS 콜백이 끊겨도 이전 속도를 지우고 안내 상태 변경을 바로 반영한다.
                        combine(locations, container.safeDrive.state, flow {
                            while (true) {
                                emit(Unit)
                                delay(1_000)
                            }
                        }) { location, safety, _ -> location to safety }.collect { (location, safety) ->
                            refreshAutomaticAlerts()
                            val kph = location?.let { com.wemade.teslamacro.data.location.freshSpeedKph(it) }
                            if (kph == null || kph < MOVING_KPH) {
                                overlay.hide()
                                // 정차·위치 만료 뒤 첫 주행에서 거짓 "과속 해제" 로그를 남기지 않는다.
                                overSpeedLogged = false
                                return@collect
                            }
                            val over = safety.isOverSpeed(toleranceKph = container.safeDrive.toleranceKph)
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
                                    over = over || safety.alert?.limitConflict == true,
                                )
                            }
                        }
                    }
                }
        }
    }

    /**
     * 설정이 켜져 있을 때만 안전운전 안내를 돌린다.
     * GPS를 쓰는 기능이라, 꺼져 있으면 오프라인 안내를 시작하지 않는다.
     */
    private fun watchSafeDrive() {
        val app = application as TeslaMacroApplication
        lifecycleScope.launch {
            app.ready.first { it }
            app.container.settingsStore.settings
                .map { SafeDriveOptions(it.safeDrive, it.safeDriveSound, it.safeDriveVolume,
                    it.safeDriveToleranceKph, it.safeDriveProgressiveSound,
                    it.safeDriveAlertDistanceMeters, it.safeDriveVoice, it.deviceMode) }
                .combine(portableGuidanceActive) { options, connected ->
                    options to shouldMonitorGuidance(options.deviceMode, options.enabled, connected)
                }.distinctUntilChanged()
                .collect { (options, active) ->
                    currentDeviceMode = options.deviceMode
                    currentSafeDriveEnabled = options.enabled
                    // 새 설정을 먼저 적용해 GPS 첫 갱신이 이전 거리·음성을 사용하지 않게 한다.
                    app.container.safeDrive.setAlertOptions(options.distanceMeters, options.voice)
                    app.container.safeDrive.setSound(options.sound, options.volume,
                        options.toleranceKph, options.progressiveSound)
                    // 거치 기기의 기존 활동 인식은 유지하되 휴대폰에선 구독하지 않는다.
                    if (shouldSubscribeDrivingActivity(options.deviceMode, active, options.sound)) startActivityUpdates()
                    else if (activityUpdates != null || !activityCleanupCompleted) stopActivityUpdates()
                    app.container.safeDrive.setRoadMatchEnabled(active)
                    if (active) app.container.safeDrive.start()
                    else app.container.safeDrive.stop()
                    refreshAutomaticAlerts()
                }
        }

        // 권한이 방금 생겼다면 이미 돌던 안내는 측위를 못 받는 채 떠 있다 —
        // 상태를 다시 세워 위치를 기다리도록 한다. 첫 값(0)은 흘려보낸다
        lifecycleScope.launch {
            app.ready.first { it }
            app.container.locationPermissionRevision.drop(1).collect {
                val settings = app.container.settingsStore.settings.first()
                if (!shouldMonitorGuidance(settings.deviceMode, settings.safeDrive, portableGuidanceActive.value)) return@collect
                com.wemade.teslable.DiagLog.add("위치 권한이 바뀌어 안전운전 안내를 다시 세웁니다")
                app.container.safeDrive.stop()
                app.container.safeDrive.start()
            }
        }
    }

    /** 폴러의 이번 VCSEC 응답만 수신한다. 휴대 모드의 의도적 BLE 해제는 하차로 보지 않는다. */
    private fun watchConfirmedPresence() {
        val app = application as TeslaMacroApplication
        lifecycleScope.launch {
            app.ready.first { it }
            app.container.poller.freshPresence.collect { present ->
                driveAlertGate.observePresence(present, SystemClock.elapsedRealtime())
                refreshAutomaticAlerts()
            }
        }
    }

    /** 차량 이동 판정은 기본 자동 탑승 감시가 꺼진 휴대폰에서도 출발 근거가 된다. */
    private fun onActivityUpdate(intent: Intent) {
        if (!activityUpdatesReady) return
        val result = ActivityRecognitionResult.extractResult(intent) ?: return
        val activity = when (result.mostProbableActivity.type) {
            DetectedActivity.IN_VEHICLE -> DriveAlertGate.Activity.IN_VEHICLE
            DetectedActivity.ON_FOOT, DetectedActivity.WALKING, DetectedActivity.RUNNING -> DriveAlertGate.Activity.ON_FOOT
            else -> DriveAlertGate.Activity.OTHER
        }
        driveAlertGate.observeActivity(activity, result.mostProbableActivity.confidence,
            result.elapsedRealtimeMillis, SystemClock.elapsedRealtime())
        refreshAutomaticAlerts()
    }

    /** 권한·Play 서비스 미지원 시 무음으로 닫고, 필요할 때만 활동 갱신을 구독한다. */
    private fun startActivityUpdates() {
        if (activityUpdates != null || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED)) return
        val pending = activityPendingIntent(PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        activityFailure = null
        activityUpdates = pending
        runCatching { ActivityRecognition.getClient(this).requestActivityUpdates(10_000L, pending) }
            .onSuccess { task ->
                task.addOnSuccessListener {
                    if (activityUpdates === pending) {
                        activityUpdatesReady = true
                        activityFailure = null
                        refreshAutomaticAlerts()
                    } else runCatching { ActivityRecognition.getClient(this).removeActivityUpdates(pending) }
                }.addOnFailureListener { error ->
                    if (activityUpdates === pending) onActivityRegistrationFailed(error)
                }
            }
            .onFailure(::onActivityRegistrationFailed)
    }

    /** Google Play 서비스가 없거나 등록에 실패해도 자동 소리만 안전하게 멈춘다. */
    private fun onActivityRegistrationFailed(error: Throwable) {
        activityFailure = "활동 인식 사용 불가 (${error.javaClass.simpleName})"
        com.wemade.teslable.DiagLog.add("안전 안내 · $activityFailure")
        stopActivityUpdates()
        refreshAutomaticAlerts()
    }

    /** 설정 해제·권한 상실·서비스 종료 시 등록을 해제하고 저장된 주행 증거를 버린다. */
    private fun stopActivityUpdates() {
        activityCleanupCompleted = true
        activityUpdatesReady = false
        // 프로세스 강제 종료 뒤에도 Play 서비스에 남을 수 있는 등록을 같은 토큰으로 해제한다.
        val pending = activityUpdates ?: activityPendingIntent(PendingIntent.FLAG_NO_CREATE)
        activityUpdates = null
        pending?.let {
            runCatching {
                ActivityRecognition.getClient(this).removeActivityUpdates(it)
                    .addOnCompleteListener { _ -> if (activityUpdates == null) it.cancel() }
            }.onFailure { _ -> if (activityUpdates == null) it.cancel() }
        }
        driveAlertGate.clear()
        (application as TeslaMacroApplication).container.safeDrive.setAutomaticAlertsAllowed(false)
    }

    /** 프로세스 재생성 후에도 이전 등록을 찾아 해제할 수 있도록 수신 토큰을 고정한다. */
    private fun activityPendingIntent(flags: Int): PendingIntent? {
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getForegroundService(this, ACTIVITY_REQUEST_CODE,
            Intent(this, MacroService::class.java).setAction(ACTION_ACTIVITY_UPDATE), flags or mutable)
    }

    /** 휴대폰은 차량 오디오 연결만, 거치 기기는 기존 탑승·활동 근거로 자동 소리를 연다. */
    private fun refreshAutomaticAlerts() {
        if (manualGuideActive) expireManualGuideIfNeeded()
        val guide = (application as TeslaMacroApplication).container.safeDrive
        if (currentDeviceMode == DeviceMode.PORTABLE) {
            val manual = (application as TeslaMacroApplication).container.manualGuideActive.value
            guide.setAutomaticAlertsAllowed(currentSafeDriveEnabled && portableGuidanceActive.value,
                "차량 오디오 Bluetooth 연결 대기 · 자동 소리 보류",
                if (manual) "이번 주행 수동 안내 · 자동 소리 사용" else "차량 오디오 Bluetooth 연결 · 자동 소리 사용")
            return
        }
        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        val reason = when {
            !permitted -> "활동 인식 권한 필요 · 자동 소리 보류"
            activityFailure != null -> "$activityFailure · 자동 소리 보류"
            !activityUpdatesReady -> "활동 인식 준비 중 · 자동 소리 보류"
            else -> "보행/주행 미확인 · 자동 소리 보류"
        }
        guide.setAutomaticAlertsAllowed(permitted && activityUpdatesReady &&
            driveAlertGate.mayAlert(SystemClock.elapsedRealtime()), reason)
    }

    /** 신선한 탑승 엣지마다 사용자가 고른 내비의 목적지 없는 안심운전을 한 번 연다 */
    private fun watchNavigatorSafeDrive() {
        val app = application as TeslaMacroApplication
        lifecycleScope.launch {
            app.ready.first { it }
            app.container.poller.boardingEvents.collect {
                val settings = app.container.settingsStore.settings.first()
                if (!settings.autoStartNavigatorSafeDrive) return@collect

                val navigatorApp = NavigatorApp.of(settings.navigatorApp)
                val configuredLaunchMode = com.wemade.teslamacro.data.nav.SafeDriveLaunchMode
                    .of(settings.navigatorSafeDriveLaunchMode)
                val automaticLaunchMode = configuredLaunchMode.forAutomaticStart()
                if (configuredLaunchMode != automaticLaunchMode) {
                    com.wemade.teslable.DiagLog.add(
                        "${navigatorApp.label} 안심운전 전체 진단 설정 — " +
                            "탑승 자동 실행은 ${automaticLaunchMode.label} 통로 1회만 사용"
                    )
                }
                app.container.navigator.logSafeDriveState("탑승 자동 실행", navigatorApp, automaticLaunchMode)
                app.container.navigator.startSafeDrive(
                    app = navigatorApp,
                    launchMode = automaticLaunchMode,
                ).onFailure { error ->
                    com.wemade.teslable.DiagLog.add(
                        "${navigatorApp.label} 안심운전 자동 실행 실패 — ${error.message}"
                    )
                }
            }
        }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val app = application as? TeslaMacroApplication ?: return
            if (!app.ready.value) return
            lifecycleScope.launch {
                val settings = app.container.settingsStore.settings.first()
                val mode = settings.deviceMode
                when (intent.action) {
                    // 거치 기기는 전원 상승을 시동 신호로 쓰고, 휴대 기기는 자동 안심운전이
                    // 켜진 경우에만 최대 60초 동안 실제 차량 탑승을 확인한다.
                    Intent.ACTION_POWER_CONNECTED -> {
                        com.wemade.teslable.DiagLog.add(
                            if (mode == DeviceMode.MOUNTED) {
                                "차량 전원 연결 — 탑승 상태 즉시 확인"
                            } else if (settings.autoStartNavigatorSafeDrive) {
                                "기기 충전 연결 — 휴대 모드 안심운전 탑승 확인 (최대 60초)"
                            } else {
                                "기기 충전 연결 — 휴대 모드라 차량 연결 사유에서 제외"
                            }
                        )
                        app.container.poller.setVehiclePowerConnected(true)
                    }
                    // 전원이 끊기면 공식 휴대폰 키만 남도록 인증 BLE를 바로 놓는다.
                    Intent.ACTION_POWER_DISCONNECTED -> {
                        com.wemade.teslable.DiagLog.add("기기 전원 끊김 — 차량 BLE 보호 확인")
                        app.container.poller.setVehiclePowerConnected(false, endAppSession = true)
                        app.container.poller.enforceConnectionGuard()
                    }
                }
            }
        }
    }

    /** 서비스 시작 시 현재 외부 전원을 읽되, 차량 신호로 쓸지는 사용 모드가 정한다. */
    private fun isExternalPowerConnected(): Boolean {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
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
        // Play 서비스의 빈번한 활동 결과로 알림 승격·차량 연결을 다시 실행하지 않는다.
        if (intent?.action == ACTION_ACTIVITY_UPDATE) {
            onActivityUpdate(intent)
            return super.onStartCommand(intent, flags, startId)
        }
        promote()
        // 이미 실행 중인 서비스에서 BLE 권한을 다시 허용한 경우 A2DP 감시를 복구한다.
        requestCarAudioProfile()
        when (intent?.action) {
            ACTION_RUN_QUICK_ACTION -> {
                val action = intent.getStringExtra(QuickActionActivity.EXTRA_ACTION)
                val macroId = intent.getStringExtra(QuickActionActivity.EXTRA_MACRO_ID)
                val receivedAt = intent.getLongExtra(EXTRA_RECEIVED_AT, android.os.SystemClock.elapsedRealtime())
                val app = application as TeslaMacroApplication
                val label = app.container.ruleStore.rules.value.firstOrNull { it.id == macroId }?.name
                    ?: QuickActionActivity.ACTIONS[action]?.label ?: "빠른 명령"
                lifecycleScope.launch {
                    app.container.quickActionRequests.trackWithFleetProgress(label) { beforeDispatch, _, onSubmitted ->
                        handleTimedQuickAction(action, macroId, receivedAt,
                            intent.getIntExtra(EXTRA_VALIDITY_SECONDS, 120).coerceIn(10, 600), beforeDispatch, onSubmitted)
                    }
                }
            }

            ACTION_DISCONNECT_VEHICLE -> disconnectVehicleNow()
            ACTION_MANUAL_GUIDE_START -> beginManualGuide()
            ACTION_MANUAL_GUIDE_STOP -> finishManualGuide("사용자가 수동 안내 종료")
            ACTION_MANUAL_GUIDE_TIMEOUT -> expireManualGuideIfNeeded()
            ACTION_TEST_SAFE_DRIVE -> beginSafeDriveTest()
            ACTION_ACTIVITY_PERMISSION_CHANGED -> lifecycleScope.launch {
                val app = application as TeslaMacroApplication
                app.ready.first { it }
                val settings = app.container.settingsStore.settings.first()
                val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                    checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
                if ((!permitted || settings.deviceMode != DeviceMode.MOUNTED) &&
                    (activityUpdates != null || !activityCleanupCompleted)) stopActivityUpdates()
                else if (shouldSubscribeDrivingActivity(settings.deviceMode,
                        settings.safeDrive, settings.safeDriveSound)) startActivityUpdates()
                refreshAutomaticAlerts()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /** 화면을 잠글 시간을 남기고 서비스에서 실행해 설정 화면의 전면 실행과 구분한다. */
    private fun beginSafeDriveTest() {
        if (safeDriveTestJob?.isActive == true) {
            com.wemade.teslable.DiagLog.add("안심운전 잠금 테스트 — 이전 예약 취소 후 교체")
        }
        safeDriveTestJob?.cancel()
        safeDriveTestJob = lifecycleScope.launch {
            var wakeLock: PowerManager.WakeLock? = null
            try {
                // 화면은 켜지 않고 예약 대기·전달 동안만 CPU 절전 지연을 막는다.
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:safeDriveTest")
                    .apply { setReferenceCounted(false) }
                wakeLock.acquire(SAFE_DRIVE_TEST_TIMEOUT_MILLIS)
                withTimeout(SAFE_DRIVE_TEST_TIMEOUT_MILLIS) {
                    val app = application as TeslaMacroApplication
                    app.ready.first { it }
                    val prepared = app.container.settingsStore.settings.first()
                    app.container.navigator.logSafeDriveState(
                        "잠금 테스트 준비 · 10초 뒤 실행 · 홈 전환 끔",
                        NavigatorApp.of(prepared.navigatorApp),
                        SafeDriveLaunchMode.of(prepared.navigatorSafeDriveLaunchMode),
                    )
                    delay(SAFE_DRIVE_TEST_DELAY_MILLIS)
                    // 예약 뒤 바꾼 설정도 실제 실행 직전에 다시 읽어 선택한 단독 통로를 검증한다.
                    val settings = app.container.settingsStore.settings.first()
                    val navigatorApp = NavigatorApp.of(settings.navigatorApp)
                    val launchMode = SafeDriveLaunchMode.of(settings.navigatorSafeDriveLaunchMode)
                    app.container.navigator.logSafeDriveState("잠금 테스트 실행", navigatorApp, launchMode)
                    app.container.navigator.startSafeDrive(
                        app = navigatorApp,
                        launchMode = launchMode,
                    ).onSuccess {
                        com.wemade.teslable.DiagLog.add(
                            "${navigatorApp.label} 잠금 테스트 요청 완료 — 방식=${launchMode.label} · " +
                                "실제 안심운전 시작은 화면·음성으로 확인",
                        )
                    }.onFailure { error ->
                        com.wemade.teslable.DiagLog.add(
                            "${navigatorApp.label} 잠금 테스트 요청 실패 — ${error.message}",
                        )
                    }
                }
            } catch (error: CancellationException) {
                com.wemade.teslable.DiagLog.add("안심운전 잠금 테스트 취소 — ${error.message}")
                throw error
            } catch (error: Exception) {
                com.wemade.teslable.DiagLog.add("안심운전 잠금 테스트 실패 — ${error.message}")
            } finally {
                wakeLock?.let { lock -> if (lock.isHeld) lock.release() }
            }
        }
    }

    /** 수신 당시 유효시간을 연결·깨우기·전송 전체에 적용한다. */
    private suspend fun handleTimedQuickAction(
        action: String?, macroId: String?, receivedAt: Long, validitySeconds: Int,
        beforeDispatch: () -> Unit, onSubmitted: (String) -> Unit,
    ) {
        val label = QuickActionActivity.ACTIONS[action]?.label ?: "매크로"
        val deadline = com.wemade.teslable.CommandDeadline(receivedAt + validitySeconds * 1000L) {
            android.os.SystemClock.elapsedRealtime()
        }
        val wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:frunkRequest")
        try {
            deadline.check()
            wakeLock.acquire(deadline.remainingMillis())
            com.wemade.teslable.DiagLog.add("$label 요청 대기 — 수신부터 최대 ${validitySeconds}초 · 만료 후 전송 취소")
            val completed = kotlinx.coroutines.withTimeoutOrNull(deadline.remainingMillis()) {
                kotlinx.coroutines.withContext(deadline + com.wemade.teslamacro.data.gateway.ExternalQuickActionSound) {
                    handleQuickAction(action, macroId, beforeDispatch, onSubmitted)
                }
                true
            }
            if (completed == null) throw com.wemade.teslable.CommandExpiredException()
        } catch (expired: com.wemade.teslable.CommandExpiredException) {
            quickActionFailed(label, "요청 후 ${validitySeconds}초가 지나 처리를 종료했어요 · 이미 전송한 명령은 취소되지 않아요")
            throw expired
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    /** 빅스비·런처 요청을 서비스 수명 안에서 연결부터 실제 전송까지 처리한다. */
    private suspend fun handleQuickAction(action: String?, macroId: String?, beforeDispatch: () -> Unit, onSubmitted: (String) -> Unit) {
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

        // 확인음은 출처가 실제로 받아들인 요청에만 내고 실패를 완료음으로 오인시키지 않는다.
        app.container.commandFeedback.received(requestLabel)
        com.wemade.teslable.DiagLog.add("빅스비 요청 처리 시작 — $requestLabel")
        val settings = app.container.settingsStore.settings.first()
        val bleConnected = (app.container.gateway.current as?
            com.wemade.teslamacro.data.gateway.BleVehicleGateway)?.isCommandConnected == true
        val useFleet = shouldUseFleetForQuickAction(settings.fleetApiEnabled, bleConnected)
        com.wemade.teslable.DiagLog.add(
            "빠른 명령 경로 — " + if (useFleet) "Fleet · 차량 BLE 미연결"
            else if (bleConnected) "BLE · 기존 차량 연결 우선" else "BLE · 연결 후 전송",
        )
        if (useFleet) {
            // 경로는 전송 전에 한 번만 고른다. 실패 뒤 다른 경로로 재전송하지 않는다.
            if (rule != null || command == null) {
                quickActionFailed(requestLabel, "Fleet는 현재 단일 차량 명령만 지원해요 · 매크로는 BLE를 선택해 주세요")
                return
            }
            val result = app.container.fleetCommands.execute(settings.vin, command, beforeDispatch) { receipt ->
                if (receipt.pending) receipt.id?.let(onSubmitted)
            }
            val fleetLabel = if (command == com.wemade.teslamacro.domain.command.VehicleCommand.OpenTrunk ||
                command == com.wemade.teslamacro.domain.command.VehicleCommand.CloseTrunk) "뒤 트렁크 작동" else requestLabel
            when (result.status) {
                com.wemade.teslamacro.data.fleet.FleetQueueStatus.Succeeded -> {
                    com.wemade.teslable.DiagLog.add("Fleet [$fleetLabel] 차량 성공 응답 확인 · 물리 상태 확인 아님")
                    app.container.commandFeedback.quickActionConfirmed(command)
                    showQuickActionToast("$fleetLabel · 차량 성공 응답")
                }
                com.wemade.teslamacro.data.fleet.FleetQueueStatus.Failed ->
                    quickActionFailed(fleetLabel, if (result.result in listOf("http_401", "http_403", "vehicle_access_denied"))
                        "토큰 또는 차량 접근 권한을 확인해 주세요" else "서버 또는 차량이 명령을 거부했어요 · 자동 재전송하지 않아요")
                com.wemade.teslamacro.data.fleet.FleetQueueStatus.Expired ->
                    quickActionFailed(fleetLabel, "서버에서 전송 전에 유효시간이 만료됐어요")
                else -> quickActionFailed(fleetLabel, "결과 미확인 · 이미 실행됐을 수 있어 재전송하지 않아요")
            }
            return
        }
        if (!settings.isReady) {
            quickActionFailed(requestLabel, "차량 키 등록을 먼저 완료해 주세요")
            return
        }

        app.container.poller.beginCommandConnection()
        try {
            // 유효시간이 있는 요청은 남은 시간 동안 저장 주소로 연결을 재시도한다.
            val deadline = kotlin.coroutines.coroutineContext[com.wemade.teslable.CommandDeadline]
            var connection: Result<Unit>
            do {
                com.wemade.teslable.ensureCommandActive()
                // 유효시간 내 연결에는 검증된 저장 주소를 반복 사용한다.
                connection = app.container.gateway.connect(settings.vin, allowProbe = deadline == null)
                com.wemade.teslable.ensureCommandActive()
                if (connection.isFailure && deadline != null) {
                    com.wemade.teslable.DiagLog.add("$requestLabel 연결 대기 — 남은 ${deadline.remainingMillis() / 1000}초")
                    delay(1_000L)
                }
            } while (connection.isFailure && deadline != null)
            if (connection.isFailure) {
                quickActionFailed(requestLabel, connection.exceptionOrNull()?.message ?: "차량 연결 실패")
                return
            }

            // 취소와 전송 진입을 원자적으로 결정한다. 이후에는 차량 전달 철회를 보장하지 않는다.
            com.wemade.teslable.ensureCommandActive()
            beforeDispatch()
            if (rule != null) {
                // 빅스비 실행은 목록의 "지금 실행"과 같다. 자동 조건은 다시 검사하지 않는다.
                app.container.runner.launch(
                    rule,
                    System.currentTimeMillis(),
                    restartIfRunning = true,
                    onAccepted = { app.container.poller.recordFired(rule.id) },
                    onCompleted = {
                        if (app.container.gateway.current !is com.wemade.teslamacro.data.gateway.SimulatedVehicleGateway) {
                            app.container.commandFeedback.quickActionCompleted(rule.name)
                        }
                    },
                    executionContext = com.wemade.teslamacro.data.gateway.ExternalQuickActionSound,
                )
                // launch는 비동기다. 러너가 연결 사용권을 이어받은 뒤에 단발 사용권을 놓는다
                kotlinx.coroutines.withTimeoutOrNull(2_000L) {
                    app.container.runner.running.first { running -> rule.id in running }
                }
                com.wemade.teslable.DiagLog.add("빅스비 매크로 [${rule.name}] 실행 요청 완료")
                showQuickActionToast("${rule.name} 실행")
                return
            }

            val result = app.container.gateway.send(checkNotNull(command))
            if (result.isSuccess) {
                // 차량 성공 응답을 받은 뒤에만 공유 게이트웨이의 단발음 대신 두 번 알린다.
                if (app.container.gateway.current !is com.wemade.teslamacro.data.gateway.SimulatedVehicleGateway) {
                    app.container.commandFeedback.quickActionConfirmed(command)
                }
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
        } finally {
            // 만료로 코루틴이 취소돼도 연결 사용권과 보호 정책은 반드시 복구한다.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                app.container.poller.endCommandConnection()
            }
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

    /** 설정 버튼과 감시 알림이 같은 경로로 매크로를 멈추고 BLE를 놓는다. */
    private fun disconnectVehicleNow() {
        lifecycleScope.launch {
            val app = application as TeslaMacroApplication
            app.ready.first { it }
            app.container.runner.cancelAll()
            app.container.poller.disconnectUntilNextUse()
        }
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

    /** 화면이 잠겨 CPU가 잘 때도 활성 충전의 1~5분 전류 전환 시각을 지킨다. */
    private fun keepCpuAwakeForStealthCharge(app: TeslaMacroApplication) {
        lifecycleScope.launch {
            try {
                app.ready.first { it }
                app.container.stealthCharge.runtime.collectLatest { runtime ->
                    if (runtime.running) {
                        val lock = stealthChargeWakeLock
                            ?: getSystemService(PowerManager::class.java)
                                .newWakeLock(
                                    PowerManager.PARTIAL_WAKE_LOCK,
                                    "$packageName:stealthCharge",
                                )
                                .apply { setReferenceCounted(false) }
                                .also { stealthChargeWakeLock = it }
                        if (!lock.isHeld) lock.acquire(STEALTH_WAKE_LOCK_TIMEOUT_MILLIS)
                    } else {
                        stealthChargeWakeLock?.let { if (it.isHeld) it.release() }
                    }
                }
            } finally {
                stealthChargeWakeLock?.let { if (it.isHeld) it.release() }
            }
        }
    }

    override fun onDestroy() {
        if (manualGuideActive) {
            showManualGuideEnded("앱 감시가 중단돼 수동 GPS·카메라 안내가 종료됐어요.")
            getSharedPreferences(MANUAL_SESSION_PREFERENCES, MODE_PRIVATE).edit()
                .putBoolean(MANUAL_SESSION_ACTIVE, false).apply()
        }
        carAudioWatcherActive = false
        carAudioProxyRequested = false
        runCatching { unregisterReceiver(carAudioReceiver) }
        carAudioProfile?.let { profile ->
            runCatching { carAudioAdapter?.closeProfileProxy(BluetoothProfile.A2DP, profile) }
        }
        carAudioProfile = null
        manualGuideTimeout?.cancel()
        if (manualGuideActive) getSystemService(AlarmManager::class.java).cancel(manualGuideAlarmIntent())
        manualGuideStartedAt = null
        manualGuideActive = false
        (application as TeslaMacroApplication).container.let {
            it.manualGuideActive.value = false
            it.connectedAudioDevices.value = emptyList()
            it.vehicleAudioStatus.value = VehicleAudioStatus.CHECKING
        }
        stopActivityUpdates()
        safeDriveTestJob?.cancel()
        stealthChargeWakeLock?.let { if (it.isHeld) it.release() }
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
        // 수동 GPS는 기본 감시와 달리 사용자가 종료 버튼을 찾을 수 있어야 한다.
        manager.createNotificationChannel(NotificationChannel(MANUAL_CHANNEL_ID,
            "이번 주행 수동 안내", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(MANUAL_END_CHANNEL_ID,
            "수동 안내 종료", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnectVehicle = PendingIntent.getService(
            this,
            2,
            Intent(this, MacroService::class.java).setAction(ACTION_DISCONNECT_VEHICLE),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val manual = manualGuideActive
        val builder = Notification.Builder(this, if (manual) MANUAL_CHANNEL_ID else CHANNEL_ID)
            .setContentTitle(if (manual) "이번 주행 수동 GPS 안내 중" else getString(R.string.service_title))
            .setContentText(if (manual) "약 6시간 뒤 종료(절전 중 지연 가능) · 차량 오디오 연결 시 자동 전환" else null)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(openApp)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "차량 연결 끊기",
                    disconnectVehicle,
                ).build()
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
        if (manual) builder.addAction(Notification.Action.Builder(null, "수동 안내 종료",
            PendingIntent.getService(this, 3,
                Intent(this, MacroService::class.java).setAction(ACTION_MANUAL_GUIDE_STOP),
                PendingIntent.FLAG_IMMUTABLE)).build())
        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "macro_watch_min"
        private const val NOTIFICATION_ID = 1001
        private const val MANUAL_CHANNEL_ID = "manual_guide_low"
        private const val MANUAL_END_CHANNEL_ID = "manual_guide_ended"
        private const val MANUAL_SESSION_PREFERENCES = "manual_guide_session"
        private const val MANUAL_SESSION_ACTIVE = "active"
        private const val MANUAL_END_NOTIFICATION_ID = 1003
        private const val ACTION_MANUAL_GUIDE_START = "com.wemade.teslamacro.action.MANUAL_GUIDE_START"
        private const val ACTION_MANUAL_GUIDE_STOP = "com.wemade.teslamacro.action.MANUAL_GUIDE_STOP"
        private const val ACTION_MANUAL_GUIDE_TIMEOUT = "com.wemade.teslamacro.action.MANUAL_GUIDE_TIMEOUT"
        private const val ACTION_RUN_QUICK_ACTION =
            "com.wemade.teslamacro.action.RUN_QUICK_ACTION"
        private const val ACTION_DISCONNECT_VEHICLE =
            "com.wemade.teslamacro.action.DISCONNECT_VEHICLE"
        private const val ACTION_TEST_SAFE_DRIVE =
            "com.wemade.teslamacro.action.TEST_SAFE_DRIVE"
        private const val ACTION_ACTIVITY_UPDATE =
            "com.wemade.teslamacro.action.DRIVE_ACTIVITY_UPDATE"
        private const val ACTION_ACTIVITY_PERMISSION_CHANGED =
            "com.wemade.teslamacro.action.ACTIVITY_PERMISSION_CHANGED"
        private const val ACTIVITY_REQUEST_CODE = 81
        private const val SAFE_DRIVE_TEST_DELAY_MILLIS = 10_000L
        // 10초 예약 뒤 시스템 인증을 최대 60초 기다리고 전달할 시간을 남긴다.
        private const val SAFE_DRIVE_TEST_TIMEOUT_MILLIS = 90_000L
        // 랜덤 전환 최대 간격(5분)보다 길고, 상태는 매초 재확인한다.
        private const val STEALTH_WAKE_LOCK_TIMEOUT_MILLIS = 6 * 60_000L

        /** 새 버전 알림 — 감시 알림과 달리 눈에 보여야 해서 채널이 따로다 */
        private const val UPDATE_CHANNEL_ID = "update_available"
        private const val UPDATE_NOTIFICATION_ID = 1002

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MacroService::class.java))
        }

        /** 설정 화면의 승인 뒤 서비스가 활동 결과 구독을 다시 열도록 알린다. */
        fun refreshActivityPermission(context: Context) {
            context.startForegroundService(
                Intent(context, MacroService::class.java).setAction(ACTION_ACTIVITY_PERMISSION_CHANGED),
            )
        }

        /** 사용자 테스트 요청을 화면 수명과 독립적인 기존 포그라운드 서비스로 넘긴다. */
        fun scheduleSafeDriveTest(context: Context) {
            context.startForegroundService(
                Intent(context, MacroService::class.java).setAction(ACTION_TEST_SAFE_DRIVE),
            )
        }

        private const val EXTRA_VALIDITY_SECONDS = "quick_action_validity_seconds"
        private const val EXTRA_RECEIVED_AT = "quick_action_received_at"

        /** 서비스 시작 지연도 요청 유효 시간에 포함한다. */
        fun runQuickAction(context: Context, action: String?, macroId: String?, validitySeconds: Int? = null, receivedAt: Long = android.os.SystemClock.elapsedRealtime()) {
            val intent = Intent(context, MacroService::class.java)
                .setAction(ACTION_RUN_QUICK_ACTION)
                .putExtra(EXTRA_RECEIVED_AT, receivedAt)
                .putExtra(QuickActionActivity.EXTRA_ACTION, action)
                .putExtra(QuickActionActivity.EXTRA_MACRO_ID, macroId)
            validitySeconds?.let { intent.putExtra(EXTRA_VALIDITY_SECONDS, it.coerceIn(10, 600)) }
            context.startForegroundService(intent)
        }

        /** 앱 화면과 알림에서 강제 종료 없이 인증 BLE를 즉시 놓는다. */
        fun disconnectVehicle(context: Context) {
            context.startForegroundService(
                Intent(context, MacroService::class.java).setAction(ACTION_DISCONNECT_VEHICLE),
            )
        }

        /** 화면에서만 이번 주행의 GPS·안내음 예외 세션을 요청한다. */
        fun startManualGuide(context: Context) {
            context.startForegroundService(Intent(context, MacroService::class.java).setAction(ACTION_MANUAL_GUIDE_START))
        }

        /** 설정 화면의 버튼도 알림의 종료 액션과 같은 경로를 사용한다. */
        fun stopManualGuide(context: Context) {
            context.startForegroundService(Intent(context, MacroService::class.java).setAction(ACTION_MANUAL_GUIDE_STOP))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MacroService::class.java))
        }
    }
}

/** 이 속도를 넘으면 달리는 중으로 본다. 보행 속도는 정지로 친다 */
private const val MOVING_KPH = 5.0

/** 오버레이 아래 줄에 넣을 경고 한 마디. 안내할 게 없으면 null */
private fun warningTextOf(state: com.wemade.teslamacro.domain.safety.SafetyState): String? {
    if (state.stalled) return state.unavailableReason ?: "위치 없음"
    if (!state.ready) return null
    val alert = state.alert ?: return state.dataWarning
    val distance = alert.distanceMeters
    val limit = alert.speedLimitKph
    return buildString {
        append(alert.kind.label)
        if (alert.limitConflict) append(" · 제한 확인 필요")
        else if (limit != null) append(" $limit")
        if (distance != null) append(" · ${distance}m")
        (alert.dateWarning ?: state.dataWarning)?.let { append(" · $it") }
    }
}
