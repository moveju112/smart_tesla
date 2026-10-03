package com.wemade.teslamacro

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.wemade.teslamacro.data.nav.DestinationPlace
import com.wemade.teslamacro.data.nav.DestinationRequest
import com.wemade.teslamacro.feature.destination.DestinationScreen
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

    /** 테스트 요청과 일반 전송을 혼동하지 않는 상태 문구를 확인한다. */
    @Test fun pending() = snapshot(DestinationUiState(query = place.name, overlayAllowed = true,
        request = DestinationRequest("test", place, 1, 600001, "pending", true)))

    /** 장소 선택 없이 검색어와 연결 대상만으로 전송할 수 있는 화면을 확인한다. */
    @Test fun readyToSend() = snapshot(DestinationUiState(query = place.name, receiverName = "차량 태블릿"))

    /** 수신 설정과 오류·코드 표시가 태블릿과 폰에서 읽히는지 확인한다. */
    @Test fun receiving() = snapshot(DestinationUiState(mounted = true, receiving = true, receiverCode = "ABCD234567",
        error = "인터넷 연결 후 다시 확인해 주세요"), true)

    /** 연결 실패 상태에서도 재확인 버튼과 설정 진입이 보이는지 확인한다. */
    @Test fun testButtons() = snapshot(DestinationUiState(query = place.name,
        connectionError = "연결을 확인하지 못했어요"))

    /** 설정 아래의 테스트 항목이 큰 글자에서도 잘리지 않는지 확인한다. */
    @Test fun settingsBottom() = snapshot(DestinationUiState(mounted = true, receiving = true,
        overlayAllowed = true, minutes = 120), setup = true, bottom = true)

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
                AppFrame(if (setup) Destination.Settings else Destination.Features, dark = dark) { DestinationScreen(state, initialSetup = setup,
                    scrollState = androidx.compose.foundation.rememberScrollState(if (bottom) Int.MAX_VALUE else 0)) }
            }
        }
    }
    companion object {
        /** 휴대폰·태블릿과 낮·밤을 교차해 큰 글자 줄바꿈을 점검한다. */
        @JvmStatic @Parameterized.Parameters(name = "dark={0},wide={1}")
        fun configurations(): List<Array<Any>> = listOf(arrayOf(false, false), arrayOf(true, false), arrayOf(false, true), arrayOf(true, true))
    }
}
