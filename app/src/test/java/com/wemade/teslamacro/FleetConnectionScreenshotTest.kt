package com.wemade.teslamacro

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.wemade.teslamacro.ui.theme.Space
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.wemade.teslamacro.feature.settings.*
import com.wemade.teslamacro.service.QuickActionRequests
import com.wemade.teslamacro.ui.component.QuickActionRequestPanel
import com.wemade.teslamacro.ui.nav.Destination
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class FleetConnectionScreenshotTest(private val dark: Boolean, private val wide: Boolean) {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = if (wide) DeviceConfig.PIXEL_C.copy(screenWidth = 1920, screenHeight = 1200,
            density = Density.XHIGH, orientation = ScreenOrientation.LANDSCAPE, fontScale = 1.3f, softButtons = false)
        else DeviceConfig.PIXEL_6.copy(softButtons = false, fontScale = 1.3f), showSystemUi = false,
    )

    /** 입력할 곳과 저장 전 비활성 버튼을 두 팔레트에서 확인한다. */
    @Test fun missingToken() = snapshot(FleetCredentialState())

    /** 저장 상태와 계정 연결 버튼을 확인하며 일회성 Snackbar는 레이아웃 캡처에 섞지 않는다. */
    @Test fun savedToken() = snapshot(FleetCredentialState(stored = true))

    /** 접수된 요청은 취소가 아니라 결과 확인 중단 버튼을 갖는다. */
    @Test fun observingRequest() = snapshot(FleetCredentialState(stored = true, busy = true), observing = true)

    /** 추가 명령의 값 편집과 적용 버튼을 실제 선택 시트로 확인한다. */
    @Test fun availableCommands() {
        paparazzi.snapshot {
            FullScreenFrame(dark = dark) {
                FleetCommandSheet(enabled = true, onRun = {}, onDismiss = {})
            }
        }
    }

    /** 재시작 뒤 미확인 기록은 취소나 재전송 대신 결과 재조회만 제공한다. */
    @Test fun restoredResultHistory() {
        paparazzi.snapshot {
            FullScreenFrame(dark = dark) {
                FleetHistorySheet(FleetCredentialControls(
                    FleetCredentialState(stored = true), {}, {}, {},
                    history = listOf(com.wemade.teslamacro.data.fleet.FleetHistoryEntry(
                        com.wemade.teslamacro.data.fleet.FleetReceipt("dummy-id", "dummy-key", "5YJS0000000000000",
                            "charge_start", com.wemade.teslamacro.data.fleet.FleetQueueStatus.Unknown), 0)),
                ), onDismiss = {})
            }
        }
    }

    /** 실제 설정 카드에 입력 영역을 연결해 스크롤 아래로 숨지 않은 상태에서 캡처한다. */
    private fun snapshot(state: FleetCredentialState, observing: Boolean = false) {
        paparazzi.snapshot {
            FullScreenFrame(dark = dark) {
                Column(Modifier.fillMaxSize()) {
                    if (observing) QuickActionRequestPanel(
                        listOf(QuickActionRequests.Request(1, "뒤 트렁크 작동", QuickActionRequests.Status.Observing, "dummy-id")),
                        onCancel = {}, onDismiss = {}, onStopObserving = {},
                    )
                    AppFrame(Destination.Settings, dark = dark) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState(if (state.stored && !observing) Int.MAX_VALUE else 0))) {
                                androidx.compose.runtime.CompositionLocalProvider(com.wemade.teslamacro.feature.settings.LocalExpandSettingsDetails provides true) {
                                    // 미등록 상태에서는 토글을 켤 수 없어도 최초 토큰 입력이 보여야 한다.
                                    FleetApiPanel(enabled = state.stored, onEnabledChange = {},
                                        credentials = FleetCredentialControls(state, {}, {}, {}, commandsEnabled = state.stored))
                                }
                            }
                            if (wide) Box(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    companion object {
        /** 휴대폰 낮/밤과 태블릿 가로 큰 글자를 교차한다. */
        @JvmStatic @Parameterized.Parameters(name = "dark={0},wide={1}")
        fun configurations(): List<Array<Any>> = listOf(arrayOf(false, false), arrayOf(true, false), arrayOf(false, true), arrayOf(true, true))
    }
}
