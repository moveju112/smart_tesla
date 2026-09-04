package com.wemade.teslamacro

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.toArgb
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wemade.teslamacro.feature.dashboard.DashboardScreen
import com.wemade.teslamacro.feature.dashboard.DashboardViewModel
import com.wemade.teslamacro.feature.macro.MacroListScreen
import com.wemade.teslamacro.feature.macro.MacroViewModel
import com.wemade.teslamacro.feature.macro.edit.MacroEditScreen
import com.wemade.teslamacro.feature.settings.SimulatorControls
import com.wemade.teslamacro.feature.pairing.PairingScreen
import com.wemade.teslamacro.feature.pairing.PairingStep
import com.wemade.teslamacro.feature.pairing.PairingViewModel
import com.wemade.teslamacro.feature.settings.SettingsScreen
import com.wemade.teslamacro.feature.settings.SettingsViewModel
import com.wemade.teslamacro.data.update.AppUpdater
import com.wemade.teslamacro.data.update.UpdateState
import com.wemade.teslamacro.service.MacroService
import com.wemade.teslamacro.ui.ViewModelFactory
import com.wemade.teslamacro.ui.component.AppSplash
import com.wemade.teslamacro.ui.component.openOverlayPermissionSettings
import com.wemade.teslamacro.ui.layout.LocalPane
import com.wemade.teslamacro.ui.layout.Pane
import com.wemade.teslamacro.ui.nav.Destination
import com.wemade.teslamacro.ui.nav.NavBar
import com.wemade.teslamacro.ui.nav.NavRail
import com.wemade.teslamacro.ui.theme.T
import com.wemade.teslamacro.ui.theme.TeslaMacroTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            com.wemade.teslable.DiagLog.add(
                "앱 권한 확인 — BLE스캔=${permissionGranted(Manifest.permission.BLUETOOTH_SCAN)}" +
                    " · BLE연결=${permissionGranted(Manifest.permission.BLUETOOTH_CONNECT)}" +
                    " · 정확한위치=${permissionGranted(Manifest.permission.ACCESS_FINE_LOCATION)}" +
                    " · 대략위치=${permissionGranted(Manifest.permission.ACCESS_COARSE_LOCATION)}"
            )
            // BLE 권한만 있으면 감시는 올린다 — 알림을 거부해도 매크로가 죽으면 안 된다.
            // 콜백 맵 대신 실제 권한 상태를 다시 본다: 이미 허용된 항목은 맵에 안 실릴 수 있다
            if (hasBlePermission()) MacroService.start(this)
        }

    /** 권한 콜백 로그에서 현재 승인 상태를 다시 읽는다 */
    private fun permissionGranted(permission: String): Boolean =
        checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** 감시 서비스의 최소 요건: BLE 스캔·연결 (12 미만은 위치) */
    private fun hasBlePermission(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return required.all {
            checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onStart() {
        super.onStart()
        // 화면에 앱이 나온 순간(대개 탑승 직후)은 사용자가 최신 값을 기대하는 순간이다.
        // 깊은 유휴 120초를 기다리지 않고 폴러를 바로 깨운다
        (application as TeslaMacroApplication).let { app ->
            if (!app.ready.value) return@let
            app.container.poller.nudge()

            // 부팅 직후 시작된 서비스는 앱이 앞에 나오기 전까지 위치를 못 받는다 —
            // 백그라운드에서 시작된 포그라운드 서비스에는 while-in-use 권한이 없다.
            // 백그라운드 위치 권한을 더 받는 대신, 앞에 나온 지금 다시 세운다
            if (app.container.safeDrive.state.value.stalled) {
                com.wemade.teslable.DiagLog.add("앱이 앞으로 나와 안전운전 안내를 다시 세웁니다")
                app.container.notifyLocationPermissionChanged()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Compose가 그리기 전 첫 프레임과 상태바 아이콘도 낮/밤을 따라야 한다.
        // 안 맞추면 밤에 앱을 열 때마다 흰 화면이 한 번 번쩍인다
        val night = com.wemade.teslamacro.ui.theme.isNightNow()
        val palette = if (night) {
            com.wemade.teslamacro.ui.theme.DarkPalette
        } else {
            com.wemade.teslamacro.ui.theme.LightPalette
        }
        window.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(palette.void.toArgb())
        )
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = !night
        requestRuntimePermissions()

        val app = application as TeslaMacroApplication

        setContent {
            TeslaMacroTheme {
                val ready by app.ready.collectAsState()
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(T.Void)
                        .windowInsetsPadding(WindowInsets.systemBars)
                        // 키보드가 올라오면 본문을 밀어 올린다. 가로 태블릿은 세로가 600dp라
                        // 키보드가 화면 절반을 먹어 VIN·주소 입력칸이 그대로 가려졌다
                        .imePadding(),
                ) {
                    // 폭은 여기서 한 번만 재고 CompositionLocal로 내려보낸다
                    CompositionLocalProvider(LocalPane provides Pane.of(maxWidth)) {
                        if (!ready) {
                            AppSplash()
                        } else {
                            val factory = remember { ViewModelFactory(app.container) }
                            AppRoot(factory)
                        }
                    }
                }
            }
        }
    }

    private fun requestRuntimePermissions() {
        val required = runtimePermissionsFor(Build.VERSION.SDK_INT)
        if (required.isEmpty()) MacroService.start(this)
        else permissionLauncher.launch(required.toTypedArray())
    }
}

/** 테파일럿과 같은 BLE·위치 권한 묶음을 OS 버전에 맞춰 요청한다 */
internal fun runtimePermissionsFor(sdkInt: Int): List<String> = buildList {
    if (sdkInt >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    // neverForLocation을 쓰지 않는 스캔은 위치 권한도 런타임 승인이 필요하다.
    // 정확한 위치만 단독 요청하면 Android 12+에서 무시될 수 있어 둘을 함께 요청한다.
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/**
 * 등록 전에는 등록 화면만, 등록 후에는 레일 + 본문.
 * 화면이 3개뿐이라 Navigation 라이브러리 없이 상태 하나로 전환한다.
 */
@Composable
private fun AppRoot(factory: ViewModelFactory) {
    val settingsViewModel: SettingsViewModel = viewModel(factory = factory)
    val settings by settingsViewModel.settings.collectAsState()

    var skippedPairing by rememberSaveable { mutableStateOf(false) }
    var current by rememberSaveable { mutableStateOf(Destination.Dashboard) }

    val context = LocalContext.current

    // 등록을 해제하면 "나중에" 상태를 풀어 등록 화면으로 되돌린다.
    // 안 풀면 본 화면에 갇혀 다시 등록할 방법이 없어진다
    LaunchedEffect(settings.isPaired) {
        if (!settings.isPaired) skippedPairing = false
    }

    // VIN만 저장된 상태는 "등록 중"이다. 키 등록까지 끝나야 본 화면으로 넘긴다
    if (!settings.isReady && !skippedPairing) {
        val pairingViewModel: PairingViewModel = viewModel(factory = factory)
        val pairingState by pairingViewModel.uiState.collectAsState()

        PairingScreen(
            state = pairingState,
            onVinChange = pairingViewModel::onVinChange,
            onFindVehicle = pairingViewModel::findVehicle,
            onRequestEnrollment = pairingViewModel::requestEnrollment,
            onSkip = { skippedPairing = true },
            onScanNearby = pairingViewModel::scanNearby,
            onLoadBonded = pairingViewModel::loadBonded,
            onConnectDirect = pairingViewModel::connectDirect,
        )
        // 등록이 끝나면 본 화면으로 넘긴다. 컴포지션 도중이 아니라 부수효과로 처리한다
        LaunchedEffect(pairingState.step) {
            if (pairingState.step == PairingStep.Done) skippedPairing = true
        }
        return
    }

    // 세로면 하단 탭, 가로면 좌측 레일 — 폭이 아니라 방향으로 가른다.
    // 차내 태블릿은 세로로 세워도 폭이 600dp를 넘어 레일로 잡혔다. 메뉴가 3개뿐이라 세로에선 레일이 본문 폭만 먹는다
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT

    ResponsiveScaffold(
        bottomNav = portrait,
        current = current,
        onSelect = {
            current = it
            // 탭 전환도 "최신 값을 기대하는 순간" — 자는 폴러를 깨운다 (요구 시점 읽기)
            (context.applicationContext as TeslaMacroApplication).let { app ->
                if (app.ready.value) app.container.poller.nudge()
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (current) {
                Destination.Dashboard -> {
                    val vm: DashboardViewModel = viewModel(factory = factory)
                    val state by vm.uiState.collectAsState()
                    DashboardScreen(
                        state = state,
                        onCommand = vm::send,
                        onRetryConnect = vm::retryConnect,
                        onDismissError = vm::dismissError,
                        onSeatClimate = vm::setSeatClimate,
                        onStealthCharging = vm::setStealthCharging,
                    )
                }

                Destination.Macros -> {
                    val vm: MacroViewModel = viewModel(factory = factory)
                    val draft by vm.draft.collectAsState()

                    // 편집 중이면 목록 대신 편집 화면이 자리를 차지한다
                    val editing = draft
                    if (editing != null) {
                        MacroEditScreen(
                            draft = editing,
                            onChange = vm::updateDraft,
                            onSave = vm::saveDraft,
                            onDelete = vm::deleteDraft,
                            onCancel = vm::cancelEdit,
                        )
                    } else {
                        val rules by vm.rules.collectAsState()
                        val running by vm.running.collectAsState()
                        val progress by vm.progress.collectAsState()
                        val log by vm.log.collectAsState()
                        MacroListScreen(
                            rules = rules,
                            runningIds = running,
                            progress = progress,
                            log = log,
                            onToggle = vm::setEnabled,
                            onRunNow = vm::runNow,
                            onStopAll = vm::stopAll,
                            onEdit = vm::editMacro,
                            onDuplicate = vm::duplicate,
                            onDelete = vm::delete,
                            onCreate = vm::createMacro,
                        )
                    }
                }

                Destination.Settings -> {
                    val simulated = settingsViewModel.simulatedState?.collectAsState()?.value
                    val update by settingsViewModel.update.collectAsState()
                    val batteryUnrestricted by settingsViewModel.batteryUnrestricted.collectAsState()

                    // 절전 다이얼로그에서 돌아오면 상태를 다시 읽는다.
                    // 결과 코드는 의미가 없다 — 허용했는지는 시스템에 되물어야 안다
                    val batteryDialog = rememberLauncherForActivityResult(
                        ActivityResultContracts.StartActivityForResult()
                    ) { settingsViewModel.refreshBatteryUnrestricted() }

                    // 제조사 설정 화면에서 돌아온 순간 실제 권한을 다시 보고 업데이트를 잇는다.
                    // 결과 코드만 믿으면 허용했어도 취소로 오는 기기에서 멈춘다
                    val installPermissionDialog = rememberLauncherForActivityResult(
                        ActivityResultContracts.StartActivityForResult()
                    ) { settingsViewModel.resumeUpdateAfterInstallPermission() }

                    // 백업 파일은 사용자가 어디에 둘지 고른다 — 앱이 임의의 위치에 쓰지 않는다
                    val saveBackup = rememberLauncherForActivityResult(
                        ActivityResultContracts.CreateDocument("application/json")
                    ) { uri -> uri?.let(settingsViewModel::exportBackup) }
                    val openBackup = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument()
                    ) { uri -> uri?.let(settingsViewModel::importBackup) }
                    val backupMessage by settingsViewModel.backupMessage.collectAsState()

                    // 시스템 설정에서 허용하고 돌아오면 경고가 바로 사라지도록 복귀 때마다 다시 읽는다
                    val overlayPermitted = com.wemade.teslamacro.ui.component.rememberOnResume {
                        android.provider.Settings.canDrawOverlays(context)
                    }

                    // 설정 화면에서 프로세스가 재생성돼도 저장해 둔 업데이트를 복구한다.
                    // ActivityResult 콜백이 빠지는 제조사 화면도 ON_RESUME 재조회로 보완한다
                    val installPermitted = com.wemade.teslamacro.ui.component.rememberOnResume {
                        AppUpdater.canInstallPackages(context)
                    }
                    LaunchedEffect(update, installPermitted) {
                        if (update == null || update is UpdateState.NeedsInstallPermission) {
                            settingsViewModel.resumeUpdateAfterInstallPermission()
                        }
                    }

                    // 안드로이드 12부터 BLE는 위치 권한 없이도 돌아서, 첫 실행 요청 목록에
                    // 위치가 빠져 있다. 그 결과 HUD·과속 안내는 GPS를 한 번도 못 받았다 —
                    // 주행 기능을 켜는 이 자리에서 따로 받는다
                    // 권한 대화상자는 액티비티를 잠깐 내리므로 돌아올 때 다시 읽힌다
                    val locationPermitted = com.wemade.teslamacro.ui.component.rememberOnResume {
                        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                    }
                    val askLocation = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { granted ->
                        if (granted) {
                            // GPS 스트림은 구독하는 순간 권한을 본다 — 지금 알려주지 않으면
                            // 토글을 껐다 켜기 전까지 HUD도 과속 판정도 계속 멈춘 채다
                            (context.applicationContext as TeslaMacroApplication)
                                .container.notifyLocationPermissionChanged()
                            // 서비스의 포그라운드 타입에 location을 붙이려면 다시 승격해야 한다.
                            // 실패해도 위치 신호는 이미 갔으니 감시는 계속된다
                            runCatching { MacroService.start(context) }
                        }
                    }

                    SettingsScreen(
                        settings = settings,
                        onAutomationChange = settingsViewModel::setAutomationEnabled,
                        onUnpair = settingsViewModel::unpair,
                        onStartPairing = { skippedPairing = false },
                        simulator = simulated?.let {
                            SimulatorControls(
                                insideTemp = it.insideTempC ?: 25.0,
                                outsideTemp = it.outsideTempC ?: 25.0,
                                onInsideTempChange = settingsViewModel::setSimulatedInsideTemp,
                                onOutsideTempChange = settingsViewModel::setSimulatedOutsideTemp,
                                onBoard = { settingsViewModel.simulateBoarding() },
                                onLeave = { settingsViewModel.simulateLeaving() },
                            )
                        },
                        battery = com.wemade.teslamacro.feature.settings.BatteryControls(
                            unrestricted = batteryUnrestricted,
                            onOpenSettings = { requestBatteryUnrestricted(context, batteryDialog) },
                        ),
                        update = update,
                        onCheckUpdate = settingsViewModel::checkUpdate,
                        onDownloadUpdate = settingsViewModel::downloadAndInstall,
                        onRequestInstallPermission = {
                            requestInstallPermission(context, installPermissionDialog)
                        },
                        backup = com.wemade.teslamacro.feature.settings.BackupControls(
                            onExport = {
                                saveBackup.launch(
                                    com.wemade.teslamacro.data.backup.BackupFile.DEFAULT_FILE_NAME
                                )
                            },
                            onImport = { openBackup.launch(arrayOf("application/json", "text/*")) },
                            message = backupMessage,
                            onDismissMessage = settingsViewModel::clearBackupMessage,
                        ),
                        navigation = com.wemade.teslamacro.feature.settings.NavigationControls(
                            onAppChange = settingsViewModel::setNavigatorApp,
                            onAutoStartSafeDriveChange = settingsViewModel::setAutoStartNavigatorSafeDrive,
                            onHudOverlayChange = settingsViewModel::setHudOverlay,
                            onSafeDriveChange = settingsViewModel::setSafeDrive,
                            onSafeDriveSoundChange = settingsViewModel::setSafeDriveSound,
                            onSafeDriveVolumeChange = settingsViewModel::setSafeDriveVolume,
                            safeDriveAvailable = remember { settingsViewModel.safeDriveAvailable() },
                            installed = remember { settingsViewModel.installedNavigators() },
                            // 설정 화면에서 돌아올 때 다시 읽어야 한다 — 사용자가
                            // 시스템 설정에서 허용하고 돌아오는 게 정상 경로다
                            overlayPermitted = overlayPermitted,
                            locationPermitted = locationPermitted,
                            onRequestLocationPermission = {
                                askLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            },
                            onRequestOverlayPermission = {
                                openOverlayPermissionSettings(context)
                            },
                        ),
                    )
                }
            }
        }
    }
}

/**
 * 절전 제외를 요청한다.
 *
 * 바로 뜨는 확인 다이얼로그를 먼저 시도하고, 그 화면이 없는 기기에서는
 * 앱 목록으로 보낸다 — 아무것도 안 열리는 버튼이 되면 안 된다.
 */
private fun requestBatteryUnrestricted(
    context: android.content.Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>,
) {
    val direct = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(android.net.Uri.parse("package:${context.packageName}"))
    runCatching { launcher.launch(direct) }.onFailure {
        runCatching {
            launcher.launch(
                Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
        }.onFailure {
            com.wemade.teslable.DiagLog.add("절전 · 설정 화면을 열지 못함")
            android.widget.Toast.makeText(
                context,
                "절전 설정 화면을 열지 못했어요.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }
}

/**
 * 이 앱의 APK 설치 권한 화면을 연다.
 *
 * 제조사 설정 앱이 앱별 화면을 제공하지 않으면 보안 설정으로 보낸다.
 */
private fun requestInstallPermission(
    context: android.content.Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>,
) {
    val direct = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
        .setData(android.net.Uri.parse("package:${context.packageName}"))
    runCatching { launcher.launch(direct) }.onFailure {
        runCatching { launcher.launch(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)) }
            .onFailure {
                com.wemade.teslable.DiagLog.add("업데이트 · 설치 권한 화면을 열지 못함")
                android.widget.Toast.makeText(
                    context,
                    "설정 → 보안 → 알 수 없는 앱 설치에서 Smart Tesla를 허용해 주세요.",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
    }
}

/**
 * 내비게이션 배치를 화면 폭에 맞춘다.
 *
 * 폰 세로에서 96dp 레일은 본문 폭의 4분의 1을 먹는다.
 * 좁으면 하단 탭, 넓으면 좌측 레일.
 */
@Composable
private fun ResponsiveScaffold(
    bottomNav: Boolean,
    current: Destination,
    onSelect: (Destination) -> Unit,
    content: @Composable () -> Unit,
) {
    if (bottomNav) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) { content() }
            NavBar(current = current, onSelect = onSelect)
        }
    } else {
        Row(modifier = Modifier.fillMaxSize()) {
            NavRail(current = current, onSelect = onSelect)
            Box(modifier = Modifier.weight(1f)) { content() }
        }
    }
}
