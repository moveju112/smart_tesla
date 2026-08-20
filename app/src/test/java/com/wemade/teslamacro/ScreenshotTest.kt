package com.wemade.teslamacro

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.android.resources.ScreenOrientation
import com.wemade.teslamacro.data.macro.MacroPresets
import com.wemade.teslamacro.data.settings.AppSettings
import com.wemade.teslamacro.data.voice.VoiceModelState
import com.wemade.teslamacro.domain.gateway.LinkState
import com.wemade.teslamacro.domain.macro.MacroLogEntry
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
import com.wemade.teslamacro.feature.settings.SimulatorControls
import com.wemade.teslamacro.ui.component.AppSplash
import com.wemade.teslamacro.ui.nav.Destination
import com.wemade.teslamacro.ui.nav.NavRail
import com.wemade.teslamacro.ui.theme.T
import com.wemade.teslamacro.ui.theme.TeslaMacroTheme
import org.junit.Rule
import org.junit.Test

/**
 * 화면을 PNG로 뽑는다.
 *
 * 차에 가야만 확인되는 앱이라 UI를 눈으로 미리 검토할 방법이 필요했다.
 * 에뮬레이터 없이 JVM에서 렌더링하므로 빌드마다 돌려도 부담이 없다.
 *
 * `./gradlew recordPaparazziDebug` → `app/src/test/snapshots/images/`
 */
class ScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        // 차량 거치 태블릿 기준 가로 화면
        deviceConfig = DeviceConfig.PIXEL_C.copy(
            orientation = ScreenOrientation.LANDSCAPE,
            nightMode = NightMode.NOTNIGHT,
            softButtons = false,
        ),
        showSystemUi = false,
    )

    /** 레일까지 포함한 실제 앱 배치로 감싼다 */
    private fun snapshot(
        name: String,
        selected: Destination,
        dark: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        paparazzi.snapshot(name) { AppFrame(selected, dark, content) }
    }

    @Test
    fun `01 제어 화면`() {
        snapshot("01-dashboard", Destination.Dashboard) {
            DashboardScreen(
                state = dashboardState(),
                onCommand = {},
                onRetryConnect = {},
                onDismissError = {},
            )
        }
    }

    // 밤 팔레트는 눈으로만 검증할 수 있다 — 대비가 무너지면 여기서 바로 보인다
    @Test
    fun `01N 제어 화면 - 밤`() {
        snapshot("01N-dashboard-night", Destination.Dashboard, dark = true) {
            DashboardScreen(
                state = dashboardState(),
                onCommand = {},
                onRetryConnect = {},
                onDismissError = {},
            )
        }
    }

    @Test
    fun `02 제어 화면 - 읽기 전 로딩`() {
        snapshot("02-dashboard-loading", Destination.Dashboard) {
            DashboardScreen(
                state = dashboardState(
                    link = LinkState.Scanning,
                    hasReading = false,
                ),
                onCommand = {},
                onRetryConnect = {},
                onDismissError = {},
            )
        }
    }

    @Test
    fun `03 제어 화면 - 명령 실패`() {
        snapshot("03-dashboard-error", Destination.Dashboard) {
            DashboardScreen(
                state = dashboardState(
                    error = "운전석 통풍 2 실패\n차량이 응답하지 않아요",
                ),
                onCommand = {},
                onRetryConnect = {},
                onDismissError = {},
            )
        }
    }

    @Test
    fun `04 매크로 목록`() {
        snapshot("04-macro-list", Destination.Macros) {
            MacroListScreen(
                rules = MacroPresets.defaults(),
                runningIds = setOf("preset-summer-boarding"),
                progress = mapOf(
                    "preset-summer-boarding" to com.wemade.teslamacro.domain.macro.MacroProgress(
                        ruleId = "preset-summer-boarding",
                        stepIndex = 3,
                        totalSteps = 5,
                        waitEndsAtMillis = System.currentTimeMillis() + 252_000,
                    )
                ),
                log = sampleLog(),
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

    @Test
    fun `05 매크로 목록 - 비어 있음`() {
        snapshot("05-macro-empty", Destination.Macros) {
            MacroListScreen(
                rules = emptyList(),
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

    @Test
    fun `06 매크로 편집`() {
        snapshot("06-macro-edit", Destination.Macros) {
            MacroEditScreen(
                draft = MacroDraft.from(MacroPresets.summerBoarding()),
                onChange = {},
                onSave = {},
                onDelete = {},
                onCancel = {},
            )
        }
    }

    @Test
    fun `07 매크로 편집 - 새로 만들기`() {
        snapshot("07-macro-edit-new", Destination.Macros) {
            MacroEditScreen(
                draft = MacroDraft.blank(),
                onChange = {},
                onSave = {},
                onDelete = {},
                onCancel = {},
            )
        }
    }

    // 반경은 직접 입력이라 눈으로 봐야 확인된다 — 조건 카드만 따로 찍는다.
    // 위치 편집기가 권한 런처를 쓰는데 Paparazzi엔 액티비티가 없어 빈 등록기를 끼워 넣는다
    @Test
    fun `11 위치 조건 카드`() {
        snapshot("11-condition-location", Destination.Macros) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.activity.compose.LocalActivityResultRegistryOwner provides NoResultRegistry
            ) {
            androidx.compose.foundation.layout.Box(
                modifier = androidx.compose.ui.Modifier.padding(
                    com.wemade.teslamacro.ui.theme.Space.lg
                )
            ) {
                com.wemade.teslamacro.feature.macro.edit.ConditionCard(
                    condition = com.wemade.teslamacro.domain.macro.Condition.NearLocation(
                        latitude = 37.4020,
                        longitude = 127.1086,
                        radiusMeters = 1800,
                    ),
                    onChange = {},
                    onRemove = {},
                )
            }
            }
        }
    }

    @Test
    fun `08 설정 - 시뮬레이터`() {
        snapshot("08-settings", Destination.Settings) {
            SettingsScreen(
                settings = AppSettings(vin = ""),
                onAutomationChange = {},
                onIdlePollChange = {},
                onActivePollChange = {},
                onActiveWindowChange = {},
                onUnpair = {},
                onStartPairing = {},
                voice = VoiceControls(
                    model = VoiceModelState.NotInstalled,
                    onAlwaysOnChange = {},
                    onInstall = {},
                    onRemove = {},
                ),
                simulator = SimulatorControls(
                    insideTemp = 31.0,
                    outsideTemp = 29.0,
                    onInsideTempChange = {},
                    onOutsideTempChange = {},
                    onBoard = {},
                    onLeave = {},
                ),
            )
        }
    }

    @Test
    fun `09 차량 등록`() {
        paparazzi.snapshot("09-pairing") {
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
    fun `10 초기 로딩`() {
        paparazzi.snapshot("10-splash") { FullScreenFrame { AppSplash() } }
    }

    // ---- 표본 데이터 ----

    private fun dashboardState(
        link: LinkState = LinkState.Ready,
        hasReading: Boolean = true,
        error: String? = null,
    ) = DashboardUiState(
        link = link,
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
        hasReading = hasReading,
        hasBodyReading = hasReading,
        hasClimateReading = hasReading,
        pendingCommand = null,
        errorMessage = error,
        secondsSinceReading = if (hasReading) 3 else null,
        batteryPercent = 72,
        automationEnabled = true,
        runningMacroCount = 1,
    )

    private fun sampleLog() = listOf(
        MacroLogEntry(1_700_000_000_000, "여름 탑승 쿨링", "시작"),
        MacroLogEntry(1_700_000_001_000, "여름 탑승 쿨링", "운전석 통풍 2"),
        MacroLogEntry(1_700_000_002_000, "여름 탑승 쿨링", "공조 켜기"),
        MacroLogEntry(1_700_000_003_000, "여름 탑승 쿨링", "목표 온도 24.0℃"),
    )
}

/** 스냅샷 전용 빈 등록기. 실제로 실행할 액티비티가 없으니 launch는 아무것도 안 한다 */
private val NoResultRegistry = object : androidx.activity.result.ActivityResultRegistryOwner {
    override val activityResultRegistry = object : androidx.activity.result.ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: androidx.activity.result.contract.ActivityResultContract<I, O>,
            input: I,
            options: androidx.core.app.ActivityOptionsCompat?,
        ) = Unit
    }
}
