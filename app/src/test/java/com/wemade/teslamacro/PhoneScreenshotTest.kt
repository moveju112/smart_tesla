package com.wemade.teslamacro

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.wemade.teslamacro.data.macro.MacroPresets
import com.wemade.teslamacro.data.settings.AppSettings
import com.wemade.teslamacro.domain.gateway.LinkState
import com.wemade.teslamacro.domain.model.Level
import com.wemade.teslamacro.domain.model.SeatPosition
import com.wemade.teslamacro.feature.dashboard.DashboardScreen
import com.wemade.teslamacro.feature.dashboard.DashboardUiState
import com.wemade.teslamacro.feature.macro.MacroListScreen
import com.wemade.teslamacro.feature.macro.edit.MacroDraft
import com.wemade.teslamacro.feature.macro.edit.MacroEditScreen
import com.wemade.teslamacro.feature.pairing.PairingScreen
import com.wemade.teslamacro.feature.pairing.PairingStep
import com.wemade.teslamacro.feature.pairing.PairingUiState
import com.wemade.teslamacro.feature.settings.SettingsScreen
import com.wemade.teslamacro.ui.nav.Destination
import org.junit.Rule
import org.junit.Test

/**
 * **폰 세로** 화면 검증.
 *
 * 태블릿 가로만 찍다가 폰에서 글자가 세로로 쪼개지는 걸 놓쳤다.
 * 두 폭을 항상 같이 찍어서 한쪽만 고치는 일이 없게 한다.
 */
class PhoneScreenshotTest {
    /** 음성 명령 유효시간 입력이 기기 폭 안에서 표시되는지 확인한다. */
    @Test
    fun `P9 음성 명령 유효시간`() {
        paparazzi.snapshot("P9-smartthings-validity") {
            AppFrame(Destination.Settings) {
                androidx.compose.runtime.CompositionLocalProvider(com.wemade.teslamacro.feature.settings.LocalExpandSettingsDetails provides true) {
                    com.wemade.teslamacro.feature.settings.SmartThingsPanel(
                        settings = com.wemade.teslamacro.data.settings.AppSettings(smartThingsEnabled = true),
                        controls = com.wemade.teslamacro.feature.settings.SmartThingsControls(
                            notificationAccessGranted = true,
                            onEnabledChange = {},
                            onCommandTextChange = { _, _ -> },
                            onRequestNotificationAccess = {},
                        ),
                    )
                }
            }
        }
    }


    /** 음성 명령 목록과 편집 패널의 터치 영역·문구를 확인한다. */
    @Test
    fun `P13 음성 명령 편집`() {
        paparazzi.snapshot("P13-smartthings-command-sheet") {
            AppFrame(Destination.Settings) {
                com.wemade.teslamacro.feature.settings.SmartThingsCommandSheet(
                    settings = AppSettings(smartThingsEnabled = true),
                    selectedAction = null,
                    draftText = "",
                    onSelect = {}, onDraftChange = {}, onSave = { _, _ -> }, onDismiss = {},
                )
            }
        }
    }

    /** 음성 명령 목록과 편집 패널의 터치 영역·문구를 확인한다. */
    @Test
    fun `P14 음성 명령 편집`() {
        paparazzi.snapshot("P14-smartthings-command-sheet") {
            AppFrame(Destination.Settings) {
                com.wemade.teslamacro.feature.settings.SmartThingsCommandSheet(
                    settings = AppSettings(smartThingsEnabled = true),
                    selectedAction = "open_frunk",
                    draftText = "프렁크 열기",
                    onSelect = {}, onDraftChange = {}, onSave = { _, _ -> }, onDismiss = {},
                )
            }
        }
    }

    @get:Rule
    val paparazzi = Paparazzi(
        // 일반적인 폰 세로 (411dp 폭) — Compact 분기점 아래
        deviceConfig = DeviceConfig.PIXEL_6.copy(nightMode = NightMode.NOTNIGHT),
        showSystemUi = false,
    )

    @Test
    fun `P1 차량 등록`() {
        paparazzi.snapshot("P1-pairing") {
            FullScreenFrame {
                PairingScreen(
                    state = PairingUiState(
                        step = PairingStep.EnterVin,
                        detectedName = "Tesla Model Y",
                    ),
                    onVinChange = {},
                    onFindVehicle = {},
                    onRequestEnrollment = {},
                    onSkip = {},
                )
            }
        }
    }

    @Test
    fun `P2 차량 등록 - 카드키 단계`() {
        paparazzi.snapshot("P2-pairing-card") {
            FullScreenFrame {
                PairingScreen(
                    state = PairingUiState(
                        step = PairingStep.TapCard,
                        vin = "5YJS0000000000000",
                        message = "차량을 찾았어요.\n이제 앱 키를 등록할게요",
                    ),
                    onVinChange = {},
                    onFindVehicle = {},
                    onRequestEnrollment = {},
                    onSkip = {},
                )
            }
        }
    }

    @Test
    fun `P3 제어 화면`() {
        paparazzi.snapshot("P3-dashboard") {
            AppFrame(Destination.Dashboard) {
                DashboardScreen(
                    state = dashboardState(),
                    onCommand = {},
                    onRetryConnect = {},
                    onDismissError = {},
                )
            }
        }
    }

    @Test
    fun `P4 매크로 목록`() {
        paparazzi.snapshot("P4-macro-list") {
            AppFrame(Destination.Macros) {
                MacroListScreen(
                    rules = MacroPresets.defaults(),
                    runningIds = emptySet(),
                    progress = emptyMap(),
                    onToggle = { _, _ -> },
                    onStopAll = {},
                    onEdit = {},
                    onDuplicate = {},
                    onDelete = {},
                    onCreate = {},
                )
            }
        }
    }

    @Test
    fun `P5 매크로 편집`() {
        paparazzi.snapshot("P5-macro-edit") {
            AppFrame(Destination.Macros) {
                MacroEditScreen(
                    draft = MacroDraft.from(MacroPresets.summerBoarding()),
                    onChange = {},
                    onSave = {},
                    onDelete = {},
                    onCancel = {},
                )
            }
        }
    }

    @Test
    fun `P6 설정`() {
        settingsAutomationSnapshot("P6-settings", dark = false)
    }

    @Test
    fun `P6N 설정 - 밤`() {
        settingsAutomationSnapshot("P6N-settings-night", dark = true)
    }

    /** 네 탭을 같은 휴대 화면 조건에서 찍어 설정의 묶음과 간격을 대조한다. */
    @Test
    fun `P19 설정 - 주행`() = settingsAutomationSnapshot("P19-settings-driving", dark = false,
        group = com.wemade.teslamacro.feature.settings.SettingsGroup.DRIVING)

    @Test
    fun `P20 설정 - 차량`() = settingsAutomationSnapshot("P20-settings-vehicle", dark = false,
        group = com.wemade.teslamacro.feature.settings.SettingsGroup.VEHICLE)

    @Test
    fun `P21 설정 - 기기`() = settingsAutomationSnapshot("P21-settings-device", dark = false,
        group = com.wemade.teslamacro.feature.settings.SettingsGroup.DEVICE)

    /** 등록 전에도 시뮬레이터 온도와 재현 버튼을 같은 흐름에서 조작할 수 있어야 한다. */
    @Test
    fun `P22 설정 - 시뮬레이터`() = settingsAutomationSnapshot("P22-settings-simulator", dark = false,
        group = com.wemade.teslamacro.feature.settings.SettingsGroup.VEHICLE, simulatorVisible = true)

    /** 스텔스 전류 설정과 남은 시간이 휴대 화면의 낮·밤 팔레트에서 읽히는지 렌더링한다. */
    private fun settingsAutomationSnapshot(name: String, dark: Boolean,
        group: com.wemade.teslamacro.feature.settings.SettingsGroup =
            com.wemade.teslamacro.feature.settings.SettingsGroup.AUTOMATION,
        simulatorVisible: Boolean = false) {
        paparazzi.snapshot(name) {
            AppFrame(Destination.Settings, dark = dark) {
                SettingsScreen(
                    chargeHistory = sampleChargeHistory(),
                    chargeHistoryNowMillis = SNAPSHOT_NOW_MILLIS,
                    settings = AppSettings(
                        vin = if (simulatorVisible) "" else "5YJS0000000000000",
                        vehicleName = if (group == com.wemade.teslamacro.feature.settings.SettingsGroup.VEHICLE && !simulatorVisible) "내 테슬라" else "",
                        hudOverlay = group == com.wemade.teslamacro.feature.settings.SettingsGroup.DRIVING,
                        safeDrive = group == com.wemade.teslamacro.feature.settings.SettingsGroup.DRIVING,
                        autoStartNavigatorSafeDrive = group == com.wemade.teslamacro.feature.settings.SettingsGroup.DRIVING,
                        smartThingsEnabled = true,
                        stealthCharging = true,
                        stealthMaxAmps = 13,
                        stealthScheduleEnabled = true,
                    ),
                    stealthSecondsUntilNextChange = 134,
                    onUnpair = {},
                    onStartPairing = {},
                    battery = com.wemade.teslamacro.feature.settings.BatteryControls(
                        unrestricted = false,
                        onOpenSettings = {},
                    ),
                    smartThings = com.wemade.teslamacro.feature.settings.SmartThingsControls(
                        notificationAccessGranted = false,
                        onEnabledChange = {},
                        onCommandTextChange = { _, _ -> },
                        onRequestNotificationAccess = {},
                    ),
                    navigation = com.wemade.teslamacro.feature.settings.NavigationControls(
                        onAppChange = {}, onHudOverlayChange = {},
                        installed = setOf("NAVER", "KAKAO", "TMAP"),
                    ),
                    backup = com.wemade.teslamacro.feature.settings.BackupControls(
                        onExport = {}, onImport = {},
                    ),
                    simulator = if (simulatorVisible) com.wemade.teslamacro.feature.settings.SimulatorControls(
                        insideTemp = 31.0, outsideTemp = 29.0,
                        onInsideTempChange = {}, onOutsideTempChange = {},
                        onBoard = {}, onLeave = {},
                    ) else null,
                    initialGroup = group,
                )
            }
        }
    }

    /** 경고음 종류 모달이 휴대폰 세로에서 목록·선택 표시·닫기를 한 화면에 담는지 낮·밤으로 확인한다. */
    @Test
    fun `P23 경고음 종류 선택`() {
        for (dark in listOf(false, true)) {
            paparazzi.snapshot("P23-warning-sound-${if (dark) "dark" else "light"}") {
                AppFrame(Destination.Settings, dark = dark) {
                    androidx.compose.material3.MaterialTheme(typography = com.wemade.teslamacro.ui.theme.SettingsTypography) {
                        com.wemade.teslamacro.feature.settings.WarningSoundSheet(
                            selected = com.wemade.teslamacro.data.safety.WarningSound.DING_DONG,
                            onSelect = {}, onDismiss = {},
                        )
                    }
                }
            }
        }
    }

    /** 인증 안내와 취소 버튼이 휴대폰 세로 화면 안에 들어오는지 확인한다. */
    @Test
    fun `P8 안심운전 인증`() {
        paparazzi.snapshot("P8-safe-drive-unlock") {
            FullScreenFrame {
                com.wemade.teslamacro.data.nav.SafeDriveUnlockScreen(onCancel = {})
            }
        }
    }

    /** 밤 팔레트에서도 편집 선택 상태와 저장 버튼의 대비를 유지한다. */
    @Test
    fun `P15 매크로 편집 밤`() {
        paparazzi.snapshot("P15-macro-edit-night") {
            AppFrame(Destination.Macros, dark = true) {
                MacroEditScreen(
                    draft = MacroDraft.from(MacroPresets.summerBoarding()),
                    onChange = {}, onSave = {}, onDelete = {}, onCancel = {},
                )
            }
        }
    }

    /** 처음 만드는 사용자가 첫 단계에서 다음 행동을 찾을 수 있어야 한다. */
    @Test
    fun `P16 새 매크로`() {
        paparazzi.snapshot("P16-macro-new") {
            AppFrame(Destination.Macros) {
                MacroEditScreen(
                    draft = MacroDraft.blank(),
                    onChange = {}, onSave = {}, onDelete = {}, onCancel = {},
                )
            }
        }
    }

    /** 기기 설정에서 라이트·다크 선택이 작은 화면에도 들어오는지 확인한다. */
    @Test
    fun `P17 화면 모드 설정`() {
        for (dark in listOf(false, true)) {
            paparazzi.snapshot("P17-theme-settings-${if (dark) "dark" else "light"}") {
                AppFrame(Destination.Settings, dark = dark) {
                    SettingsScreen(
                        chargeHistory = sampleChargeHistory(),
                        chargeHistoryNowMillis = SNAPSHOT_NOW_MILLIS,
                        settings = AppSettings(themeMode = if (dark) com.wemade.teslamacro.data.settings.ThemeMode.DARK else com.wemade.teslamacro.data.settings.ThemeMode.LIGHT),
                        onUnpair = {}, onStartPairing = {},
                        initialGroup = com.wemade.teslamacro.feature.settings.SettingsGroup.DEVICE,
                    )
                }
            }
        }
    }

    /** 신뢰 기기 안내와 확인·취소가 휴대폰 화면에 들어오는지 확인한다. */
    @Test
    fun `P18 신뢰 기기 안내`() {
        paparazzi.snapshot("P18-trusted-device-prompt") {
            FullScreenFrame {
                com.wemade.teslamacro.feature.settings.TrustedDevicePrompt(onDismiss = {}, onConfirm = {})
            }
        }
    }

    private fun dashboardState() = DashboardUiState(
        link = LinkState.Ready,
        vehicleName = "내 테슬라",
        insideTemp = "31.4",
        outsideTemp = "29.0",
        targetTemp = "22.0",
        targetTempValue = 22.0,
        isClimateOn = true,
        isLocked = false,
        seatClimate = mapOf(
            SeatPosition.FRONT_LEFT to com.wemade.teslamacro.domain.model.SeatClimate(com.wemade.teslamacro.domain.model.SeatMode.COOL, Level.MEDIUM),
            SeatPosition.FRONT_RIGHT to com.wemade.teslamacro.domain.model.SeatClimate(com.wemade.teslamacro.domain.model.SeatMode.HEAT, Level.OFF),
        ),
        isSimulated = false,
        hasReading = true,
        hasBodyReading = true,
        hasClimateReading = true,
        pendingCommand = null,
        errorMessage = null,
        secondsSinceReading = 3,
        batteryPercent = 72,
    )
}
