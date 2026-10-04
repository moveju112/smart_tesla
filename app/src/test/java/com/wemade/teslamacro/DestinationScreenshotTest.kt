package com.wemade.teslamacro

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.runtime.Composable
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.wemade.teslamacro.data.nav.DestinationPlace
import com.wemade.teslamacro.data.nav.DestinationRequest
import com.wemade.teslamacro.feature.destination.DestinationScreen
import com.wemade.teslamacro.feature.destination.DestinationPairingEditor
import com.wemade.teslamacro.feature.destination.DestinationQueryEditorSheet
import com.wemade.teslamacro.ui.component.PickerSheet
import com.wemade.teslamacro.feature.destination.DestinationUiState
import com.wemade.teslamacro.ui.nav.Destination
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class DestinationScreenshotTest(private val dark: Boolean, private val wide: Boolean) {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = if (wide) DeviceConfig.PIXEL_C.copy(
        screenWidth = 1920, screenHeight = 1200, density = Density.XHIGH,
        orientation = ScreenOrientation.LANDSCAPE, fontScale = 1.3f, softButtons = false)
        else DeviceConfig.PIXEL_6.copy(fontScale = 1.3f, softButtons = false), showSystemUi = false)
    private val place = DestinationPlace("서울시청")

    /** 검색 전 안내와 연결 전 비활성 동작을 큰 글자로 확인한다. */
    @Test fun empty() = snapshot(DestinationUiState())

    /** 일반 전송의 대기 상태 문구를 확인한다. */
    @Test fun pending() = snapshot(DestinationUiState(query = place.name, overlayAllowed = true,
        request = DestinationRequest("test", place, 1, 600001, "pending", false)))

    /** 장소 선택 없이 검색어와 연결 대상만으로 전송할 수 있는 화면을 확인한다. */
    @Test fun readyToSend() = snapshot(DestinationUiState(query = place.name, receiverName = "차량 태블릿"))

    /** 전송 완료 정보는 검색어와 상태가 같은 행에서 줄바꿈되어 표시된다. */
    @Test fun delivered() = snapshot(DestinationUiState(query = place.name, receiverName = "차량 태블릿",
        request = DestinationRequest("test", place, 1, 600001, "delivered", false)))

    /** 검색어 편집은 실제 적용·취소 시트를 휴대폰과 태블릿에서 확인한다. */
    @Test fun queryEditor() {
        paparazzi.snapshot {
            FullScreenFrame(dark = dark) {
                AppFrame(Destination.Features, dark = dark) {
                    DestinationQueryEditorSheet(query = place.name)
                }
            }
        }
    }

    /** 휴대 모드에서도 수신 스위치와 연결 코드를 같은 설정에서 표시한다. */
    @Test fun receiving() = snapshot(DestinationUiState(receiving = true, receiverCode = "ABCD234567",
        error = "인터넷 연결 후 다시 확인해 주세요"), true)

    /** 받기 전에도 모드 전환 없이 수신 설정을 찾을 수 있다. */
    @Test fun receiverOff() = snapshot(DestinationUiState(), setup = true)

    /** 연결 실패 상태에서도 재확인 버튼과 설정 진입이 보이는지 확인한다. */
    @Test fun testButtons() = snapshot(DestinationUiState(query = place.name,
        connectionError = "연결을 확인하지 못했어요"))

    /** 설정 아래의 수신 항목이 큰 글자에서도 잘리지 않는지 확인한다. */
    @Test fun settingsBottom() = snapshot(DestinationUiState(receiving = true,
        overlayAllowed = true, minutes = 120), setup = true, bottom = true)

    /** 코드 입력과 연결 버튼이 한 행에 놓이고 연결 해제와 실패 복구가 보이는지 확인한다. */
    @Test fun pairingEditor() = editorSnapshot("보낼 기기") {
        DestinationPairingEditor(DestinationUiState(receiverName = "차량 태블릿", pairingCode = "ABCD234567",
            error = "연결 코드를 확인해 주세요"))
    }

    /** 실제 편집 시트 본문을 공통 모달 안에서 렌더링한다. */
    private fun editorSnapshot(title: String, content: @Composable () -> Unit) {
        paparazzi.snapshot {
            FullScreenFrame(dark = dark) {
                AppFrame(Destination.Settings, dark = dark) {
                    PickerSheet(title, onDismiss = {}, content = content)
                }
            }
        }
    }

    /** 내비 설치 여부와 무관하게 설정의 주행 분류에서 기기 연결·수신 설정을 찾을 수 있다. */
    @Test fun settingsEntry() {
        paparazzi.snapshot {
            FullScreenFrame(dark = dark) {
                AppFrame(Destination.Settings, dark = dark) {
                    com.wemade.teslamacro.feature.settings.SettingsScreen(
                        settings = com.wemade.teslamacro.data.settings.AppSettings(),
                        onUnpair = {}, onStartPairing = {}, onSendDestination = {},
                        initialGroup = com.wemade.teslamacro.feature.settings.SettingsGroup.DRIVING,
                    )
                }
            }
        }
    }

    /** 실제 앱의 여백과 반응형 구성 안에서 새 화면을 렌더링한다. */
    private fun snapshot(state: DestinationUiState, setup: Boolean = false, bottom: Boolean = false) {
        paparazzi.snapshot {
            FullScreenFrame(dark = dark) {
                AppFrame(if (setup) Destination.Settings else Destination.Features, dark = dark) {
                    if (setup) com.wemade.teslamacro.feature.settings.SettingsScreen(
                        settings = com.wemade.teslamacro.data.settings.AppSettings(),
                        onUnpair = {}, onStartPairing = {}, onSendDestination = {},
                        initialGroup = com.wemade.teslamacro.feature.settings.SettingsGroup.DRIVING,
                    )
                    DestinationScreen(state, settingsOnly = setup,
                        scrollState = androidx.compose.foundation.rememberScrollState(if (bottom) Int.MAX_VALUE else 0))
                }
            }
        }
    }
    companion object {
        /** 휴대폰·태블릿과 낮·밤을 교차해 큰 글자 줄바꿈을 점검한다. */
        @JvmStatic @Parameterized.Parameters(name = "dark={0},wide={1}")
        fun configurations(): List<Array<Any>> = listOf(arrayOf(false, false), arrayOf(true, false), arrayOf(false, true), arrayOf(true, true))
    }
}


/** 두 배 글꼴에서도 설정 행과 연결 입력이 서로 겹치지 않는지 확인한다. */
@RunWith(Parameterized::class)
class DestinationSettingsLargeFontScreenshotTest(private val dark: Boolean) {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(fontScale = 2f, softButtons = false),
        showSystemUi = false,
    )

    /** 긴 기기 이름과 최댓값도 행의 줄바꿈으로 유지한다. */
    @Test fun settingsRows() = snapshot {
        DestinationScreen(DestinationUiState(receiverName = "차량 뒷좌석 태블릿", minutes = 120,
            receiving = true, overlayAllowed = true), settingsOnly = true)
    }

    /** 검색어와 전송 결과가 긴 경우에도 공통 행이 서로 겹치지 않는다. */
    @Test fun sendRows() = snapshot {
        DestinationScreen(DestinationUiState(query = "서울시청 인근 공영 주차장", receiverName = "차량 뒷좌석 태블릿",
            request = DestinationRequest("test", DestinationPlace("서울시청 인근 공영 주차장"),
                1, 600001, "delivered", false)))
    }

    /** 두 배 글꼴에서 검색어 입력과 적용·취소 조작을 확인한다. */
    @Test fun queryEditor() = snapshot {
        DestinationQueryEditorSheet(query = "서울시청 인근 공영 주차장")
    }

    /** 큰 글자에서는 코드 입력 폭을 확보하고 연결 버튼을 다음 줄로 보낸다. */
    @Test fun pairingEditor() = snapshot {
        PickerSheet("보낼 기기", onDismiss = {}) {
            DestinationPairingEditor(DestinationUiState(receiverName = "차량 태블릿",
                pairingCode = "ABCD234567", error = "연결 코드를 확인해 주세요"))
        }
    }

    /** 큰 글자 편집 상태를 실제 앱 프레임에 맞춰 렌더링한다. */
    private fun snapshot(content: @Composable () -> Unit) {
        paparazzi.snapshot {
            FullScreenFrame(dark = dark) {
                AppFrame(Destination.Settings, dark = dark, content = content)
            }
        }
    }

    companion object {
        /** 밝은 배경과 어두운 배경의 큰 글자 대비를 확인한다. */
        @JvmStatic @Parameterized.Parameters(name = "dark={0}")
        fun configurations(): List<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
    }
}
