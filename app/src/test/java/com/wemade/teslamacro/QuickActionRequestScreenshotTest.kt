package com.wemade.teslamacro

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.wemade.teslamacro.feature.settings.SettingsScreen
import com.wemade.teslamacro.service.QuickActionRequests.Request
import com.wemade.teslamacro.service.QuickActionRequests.Status
import com.wemade.teslamacro.ui.component.QuickActionRequestPanel
import com.wemade.teslamacro.ui.nav.Destination
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 휴대폰 낮·밤과 실기기 가로 큰 글자에서 공통 취소 영역과 본문이 함께 들어가는지 검증한다. */
@RunWith(Parameterized::class)
class QuickActionRequestScreenshotTest(private val dark: Boolean, private val wide: Boolean) {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = if (wide) DeviceConfig.PIXEL_C.copy(
            screenWidth = 1920, screenHeight = 1200, density = Density.XHIGH,
            orientation = ScreenOrientation.LANDSCAPE, fontScale = 1.3f, softButtons = false,
        ) else DeviceConfig.PIXEL_6.copy(softButtons = false),
        showSystemUi = false,
    )

    /** 연결 대기와 전송 처리 상태를 함께 표시해 취소 가능 여부를 구분한다. */
    @Test
    fun `pending and sending requests`() {
        snapshot(listOf(Request(1, "트렁크 열기", Status.Waiting), Request(2, "공조 켜기", Status.Sending)))
    }

    /** 취소 완료는 미전송을 명시하고, 만료는 이미 전송된 명령의 철회를 약속하지 않는다. */
    @Test
    fun `cancelled and expired requests`() {
        snapshot(listOf(Request(1, "트렁크 열기", Status.Cancelled), Request(2, "프렁크 열기", Status.Expired)))
    }

    /** Fleet 준비 중 제한과 깨우기 중 취소 버튼을 휴대폰·큰 글자 가로에서 함께 검증한다. */
    @Test
    fun `fleet settings and waking request`() {
        snapshot(listOf(Request(1, "보닛(프렁크) 열기", Status.Waking)), fleetEnabled = true)
    }

    /** 실제 루트처럼 탐색 영역 위에 표시하고 남은 높이를 본문에 배정한다. */
    private fun snapshot(requests: List<Request>, fleetEnabled: Boolean = false) {
        paparazzi.snapshot {
            FullScreenFrame(dark = dark) {
                Column(Modifier.fillMaxSize()) {
                    QuickActionRequestPanel(requests, onCancel = {}, onDismiss = {})
                    Box(Modifier.weight(1f)) {
                        AppFrame(Destination.Settings, dark = dark) {
                            SettingsScreen(
                                settings = com.wemade.teslamacro.data.settings.AppSettings(fleetApiEnabled = fleetEnabled),
                                onAutomationChange = {}, onUnpair = {}, onStartPairing = {},
                                onFleetApiEnabledChange = if (fleetEnabled) ({ _: Boolean -> }) else null,
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        /** 두 팔레트를 휴대폰과 큰 글자 가로 태블릿에 교차한다. */
        @JvmStatic
        @Parameterized.Parameters(name = "dark={0},wide={1}")
        fun configurations(): List<Array<Any>> = listOf(
            arrayOf(false, false), arrayOf(true, false), arrayOf(false, true), arrayOf(true, true),
        )
    }
}
