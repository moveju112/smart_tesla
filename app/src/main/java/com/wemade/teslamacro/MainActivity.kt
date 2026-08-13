package com.wemade.teslamacro

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import com.wemade.teslamacro.feature.settings.VoiceControls
import com.wemade.teslamacro.service.MacroService
import com.wemade.teslamacro.service.VoiceService
import com.wemade.teslamacro.ui.ViewModelFactory
import com.wemade.teslamacro.ui.component.AppSplash
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
            // BLE 권한만 있으면 감시는 올린다 — 마이크(음성)·알림을 거부해도 매크로가 죽으면 안 된다.
            // 콜백 맵 대신 실제 권한 상태를 다시 본다: 이미 허용된 항목은 맵에 안 실릴 수 있다
            if (hasBlePermission()) MacroService.start(this)
        }

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
            if (app.ready.value) app.container.poller.nudge()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestRuntimePermissions()

        val app = application as TeslaMacroApplication

        setContent {
            TeslaMacroTheme {
                val ready by app.ready.collectAsState()
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(T.Void)
                        .windowInsetsPadding(WindowInsets.systemBars),
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
        val required = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                // 안드로이드 11 이하는 위치 권한이 있어야 스캔 결과가 온다
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            // 음성 명령용. 거부해도 앱의 나머지는 정상 동작한다
            add(Manifest.permission.RECORD_AUDIO)
        }
        if (required.isEmpty()) MacroService.start(this)
        else permissionLauncher.launch(required.toTypedArray())
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

    // 상시 대기 스위치를 그대로 서비스 생사에 연결한다.
    // 앱이 떠 있는 동안 켜야 한다 — 마이크 서비스는 백그라운드에서 시작할 수 없다
    val context = LocalContext.current
    LaunchedEffect(settings.voiceAlwaysOn) {
        if (settings.voiceAlwaysOn) VoiceService.start(context) else VoiceService.stop(context)
    }

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
        onSelect = { current = it },
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
                    val voiceModel by settingsViewModel.voiceModel.collectAsState()
                    val update by settingsViewModel.update.collectAsState()

                    // 음성 모델 zip 고르기. 앱이 직접 내려받지 않으므로 파일을 받아 오는 건 사용자 몫이다
                    val pickModel = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument()
                    ) { uri -> uri?.let(settingsViewModel::installVoiceModel) }

                    SettingsScreen(
                        settings = settings,
                        onAutomationChange = settingsViewModel::setAutomationEnabled,
                        onIdlePollChange = settingsViewModel::setIdlePollSeconds,
                        onActivePollChange = settingsViewModel::setActivePollSeconds,
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
                        voice = VoiceControls(
                            model = voiceModel,
                            onAlwaysOnChange = settingsViewModel::setVoiceAlwaysOn,
                            onInstall = { pickModel.launch(arrayOf("*/*")) },
                            onRemove = settingsViewModel::removeVoiceModel,
                        ),
                        update = update,
                        onCheckUpdate = settingsViewModel::checkUpdate,
                    )
                }
            }
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
