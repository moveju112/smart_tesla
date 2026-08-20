package com.wemade.teslamacro

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.wemade.teslamacro.data.macro.MacroPresets
import com.wemade.teslamacro.data.settings.AppSettings
import com.wemade.teslamacro.data.voice.VoiceModelState
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
import com.wemade.teslamacro.feature.settings.VoiceControls
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
                    state = PairingUiState(step = PairingStep.EnterVin),
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
                    log = emptyList(),
                    onToggle = { _, _ -> },
                    onRunNow = {},
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
        paparazzi.snapshot("P6-settings") {
            AppFrame(Destination.Settings) {
                SettingsScreen(
                    settings = AppSettings(vin = "5YJS0000000000000", voiceAlwaysOn = true),
                    onAutomationChange = {},
                    onIdlePollChange = {},
                    onActivePollChange = {},
                    onActiveWindowChange = {},
                    onUnpair = {},
                    onStartPairing = {},
                    voice = VoiceControls(
                        model = VoiceModelState.Installed,
                        onAlwaysOnChange = {},
                        onInstall = {},
                        onRemove = {},
                    ),
                )
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
