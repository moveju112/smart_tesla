package com.wemade.teslamacro

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.android.resources.ScreenOrientation
import com.wemade.teslamacro.domain.gateway.LinkState
import com.wemade.teslamacro.domain.model.Level
import com.wemade.teslamacro.domain.model.SeatClimate
import com.wemade.teslamacro.domain.model.SeatMode
import com.wemade.teslamacro.domain.model.SeatPosition
import com.wemade.teslamacro.feature.dashboard.DashboardScreen
import com.wemade.teslamacro.feature.dashboard.DashboardUiState
import com.wemade.teslamacro.ui.nav.Destination
import org.junit.Rule
import org.junit.Test

/**
 * 실차 거치 태블릿(ALLDOCUBE iPlay 60 mini Pro, 8.4" 1920x1200)으로 찍는다.
 *
 * 기본 PIXEL_C는 1.42:1인데 이 기기는 1.6:1로 넓고 납작하다 — dp로는 약 960x600이라
 * 세로가 특히 짧다. 이 차이 때문에 기본 기기에서 멀쩡하던 타일이 실기기에서 속이 텅 비었다.
 * 제어 화면은 "스크롤 없이 한 화면"이 전제라 비율이 바뀌면 바로 티가 난다.
 */
class WideScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_C.copy(
            orientation = ScreenOrientation.LANDSCAPE,
            nightMode = NightMode.NOTNIGHT,
            softButtons = false,
            screenWidth = 1920,
            screenHeight = 1200,
            density = com.android.resources.Density.XHIGH,
        ),
        showSystemUi = false,
    )

    @Test
    fun `W1 제어 화면 - 와이드`() {
        paparazzi.snapshot("W1-dashboard-wide") {
            AppFrame(Destination.Dashboard) {
                DashboardScreen(
                    state = wideState(),
                    onCommand = {},
                    onRetryConnect = {},
                    onDismissError = {},
                )
            }
        }
    }

    // 아직 목표에 못 간 상태 — 이때만 온도가 색을 얻어야 한다.
    // 문이 열려 있으면 그 타일 하나만 통째로 물든다
    @Test
    fun `W2 제어 화면 - 냉방 중 + 문 열림`() {
        paparazzi.snapshot("W2-dashboard-cooling") {
            AppFrame(Destination.Dashboard) {
                DashboardScreen(
                    state = wideState().copy(
                        insideTemp = "31.4",
                        isLocked = false,
                        openings = listOf(com.wemade.teslamacro.domain.model.Door.DRIVER_FRONT),
                    ),
                    onCommand = {},
                    onRetryConnect = {},
                    onDismissError = {},
                )
            }
        }
    }

    // 나머지 화면도 실기기 크기로 본다. PIXEL_C(1280dp)로만 보면 320dp나 넓어서
    // 실기기에서 짓눌리는 걸 못 잡는다 — 편집 화면이 그렇게 새어 나갔다
    @Test
    fun `W3 매크로 편집`() {
        paparazzi.snapshot("W3-macro-edit") {
            AppFrame(Destination.Macros) {
                com.wemade.teslamacro.feature.macro.edit.MacroEditScreen(
                    draft = com.wemade.teslamacro.feature.macro.edit.MacroDraft
                        .from(com.wemade.teslamacro.data.macro.MacroPresets.summerBoarding()),
                    onChange = {},
                    onSave = {},
                    onDelete = {},
                    onCancel = {},
                )
            }
        }
    }

    @Test
    fun `W4 매크로 목록`() {
        paparazzi.snapshot("W4-macro-list") {
            AppFrame(Destination.Macros) {
                com.wemade.teslamacro.feature.macro.MacroListScreen(
                    rules = com.wemade.teslamacro.data.macro.MacroPresets.defaults(),
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
    fun `W5 설정`() {
        paparazzi.snapshot("W5-settings") {
            AppFrame(Destination.Settings) {
                com.wemade.teslamacro.feature.settings.SettingsScreen(
                    settings = com.wemade.teslamacro.data.settings.AppSettings(
                        vin = "5YJS0000000000000",
                    ),
                    onAutomationChange = {},
                    onIdlePollChange = {},
                    onActivePollChange = {},
                    onActiveWindowChange = {},
                    onUnpair = {},
                    onStartPairing = {},
                    voice = com.wemade.teslamacro.feature.settings.VoiceControls(
                        model = com.wemade.teslamacro.data.voice.VoiceModelState.NotInstalled,
                        onAlwaysOnChange = {},
                        onInstall = {},
                        onRemove = {},
                    ),
                    battery = com.wemade.teslamacro.feature.settings.BatteryControls(
                        unrestricted = false,
                        onOpenSettings = {},
                    ),
                )
            }
        }
    }

    // 진단 로그 카드는 설정 화면 맨 아래라 W5 화면에 안 잡힌다.
    // 줄을 끈 모습(설정)과 켠 모습(등록)을 나란히 세워 따로 확인한다
    @Test
    fun `W7 진단 로그 카드`() {
        com.wemade.teslable.DiagLog.clear()
        repeat(5) { com.wemade.teslable.DiagLog.add("직행 연결 성공 (표본 $it)") }
        paparazzi.snapshot("W7-diaglog") {
            AppFrame(Destination.Settings) {
                androidx.compose.foundation.layout.Column(
                    modifier = androidx.compose.ui.Modifier.padding(
                        com.wemade.teslamacro.ui.theme.Space.lg
                    )
                ) {
                    com.wemade.teslamacro.ui.component.DiagLogPanel(
                        title = "설정 화면 — 줄 숨김",
                        showLines = false,
                    )
                    androidx.compose.foundation.layout.Spacer(
                        androidx.compose.ui.Modifier.height(
                            com.wemade.teslamacro.ui.theme.Space.lg
                        )
                    )
                    com.wemade.teslamacro.ui.component.DiagLogPanel(
                        title = "등록 화면 — 줄 표시",
                        showLines = true,
                    )
                }
            }
        }
    }

    @Test
    fun `W6 차량 등록`() {
        paparazzi.snapshot("W6-pairing") {
            FullScreenFrame {
                com.wemade.teslamacro.feature.pairing.PairingScreen(
                    state = com.wemade.teslamacro.feature.pairing.PairingUiState(
                        vin = "5YJS0000000000000",
                        step = com.wemade.teslamacro.feature.pairing.PairingStep.TapCard,
                    ),
                    onVinChange = {},
                    onFindVehicle = {},
                    onRequestEnrollment = {},
                    onSkip = {},
                )
            }
        }
    }

    // 릴리스 노트는 새 버전이 있을 때만 뜬다 — W5(기본 상태)에는 안 잡혀 따로 세운다
    @Test
    fun `W8 업데이트 알림`() {
        paparazzi.snapshot("W8-update-notes") {
            AppFrame(Destination.Settings) {
                com.wemade.teslamacro.feature.settings.SettingsScreen(
                    settings = com.wemade.teslamacro.data.settings.AppSettings(
                        vin = "5YJS0000000000000",
                    ),
                    update = com.wemade.teslamacro.data.update.UpdateState.Available(
                        version = "0.9.1",
                        apkUrl = "https://example.invalid/app.apk",
                        notes = "잠든 차를 게이트웨이가 깨워서 다시 보낸다\n" +
                            "차가 거부하면 사유를 그대로 보여준다\n" +
                            "퇴근 전 예열에서 고정 15초 대기를 걷어냈다",
                    ),
                    onAutomationChange = {},
                    onIdlePollChange = {},
                    onActivePollChange = {},
                    onActiveWindowChange = {},
                    onUnpair = {},
                    onStartPairing = {},
                    voice = com.wemade.teslamacro.feature.settings.VoiceControls(
                        model = com.wemade.teslamacro.data.voice.VoiceModelState.NotInstalled,
                        onAlwaysOnChange = {},
                        onInstall = {},
                        onRemove = {},
                    ),
                    battery = com.wemade.teslamacro.feature.settings.BatteryControls(
                        unrestricted = false,
                        onOpenSettings = {},
                    ),
                    backup = com.wemade.teslamacro.feature.settings.BackupControls(
                        onExport = {},
                        onImport = {},
                        message = "매크로 6개를 내보냈어요",
                    ),
                )
            }
        }
    }

    // 공기압이 빠진 바퀴는 도면에서 그 자리만 적색 실선이 되고, 몇 bar인지는 배너가 말한다.
    // 정상일 때 아무것도 안 뜨는지는 W1이 지킨다
    @Test
    fun `W9 타이어 공기압 경보`() {
        paparazzi.snapshot("W9-low-tire") {
            AppFrame(Destination.Dashboard) {
                DashboardScreen(
                    state = wideState().copy(
                        lowTires = setOf(
                            com.wemade.teslamacro.domain.model.TirePosition.FRONT_RIGHT,
                        ),
                        tireWarning = "앞 우 2.1 bar",
                        vehicleSoftware = "설치 예약됨",
                    ),
                    onCommand = {},
                    onRetryConnect = {},
                    onDismissError = {},
                )
            }
        }
    }

}
