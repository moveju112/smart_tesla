package com.wemade.teslamacro

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.wemade.teslamacro.data.settings.AppSettings
import com.wemade.teslamacro.feature.features.AppFeature
import com.wemade.teslamacro.feature.features.FeatureSettings
import com.wemade.teslamacro.feature.features.FeaturesScreen
import com.wemade.teslamacro.feature.macro.MacroListScreen
import com.wemade.teslamacro.feature.settings.NavigationControls
import com.wemade.teslamacro.feature.settings.SettingsGroup
import com.wemade.teslamacro.feature.settings.SettingsScreen
import com.wemade.teslamacro.feature.settings.SmartThingsControls
import com.wemade.teslamacro.ui.nav.Destination
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 기능 목록·실행·설정 연결을 폰과 태블릿의 큰 글씨에서 확인한다. */
@RunWith(Parameterized::class)
class FeaturesScreenshotTest(private val dark: Boolean, private val wide: Boolean, private val scale: Float) {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = if (wide) DeviceConfig.PIXEL_C.copy(
        screenWidth = 1920, screenHeight = 1200, density = Density.XHIGH,
        orientation = ScreenOrientation.LANDSCAPE, fontScale = scale, softButtons = false)
        else DeviceConfig.PIXEL_6.copy(screenWidth = 720, screenHeight = 1560, density = Density.XHIGH,
            fontScale = scale, softButtons = false), showSystemUi = false)
    private val navigation = NavigationControls(onAppChange = {}, onHudOverlayChange = {}, locationPermitted = false,
        automaticSoundStatus = "차량 오디오 Bluetooth 연결 대기 · 자동 소리 보류")
    private val smartThings = SmartThingsControls(false, {}, { _, _ -> }, {})

    /** 미등록·권한 부족 상태를 기능 목록에서 구별한다. */
    @Test fun featureList() = features(null)

    /** 사용 가능한 기능의 요약과 켜짐 상태도 조밀한 행 안에서 구별한다. */
    @Test fun readyFeatureList() = features(null, AppSettings(
        vin = "5YJS0000000000000", isEnrolled = true,
        safeDrive = true, stealthCharging = false, smartThingsEnabled = true,
    ))

    /** 실행 스위치와 옵션 요약의 줄바꿈을 확인한다. */
    @Test fun safetyExecution() = features(AppFeature.SAFE_DRIVE)

    /** 켜진 안내의 실제 소리 보류 상태는 제목 도움말 뒤로 숨기지 않는다. */
    @Test fun activeSafetyExecution() = features(AppFeature.SAFE_DRIVE, AppSettings(
        vin = "5YJS0000000000000", isEnrolled = true, safeDrive = true,
    ))

    /** 충전과 스마트싱스도 제목 왼쪽·스위치 오른쪽의 실행 행을 공유한다. */
    @Test fun chargeExecution() = features(AppFeature.STEALTH_CHARGE)

    /** 알림 권한 부족 안내는 도움말로 숨기지 않고 실행 행 아래에 남긴다. */
    @Test fun smartThingsExecution() = features(AppFeature.SMARTTHINGS)

    /** 제목 도움말은 폰·태블릿과 큰 글씨에서 공통 모달로 읽고 닫는다. */
    @Test fun featureHelp() {
        paparazzi.snapshot {
            FullScreenFrame(dark = dark) {
                com.wemade.teslamacro.ui.component.HelpSheet(
                    AppFeature.SAFE_DRIVE.label,
                    AppFeature.SAFE_DRIVE.description + "\n\n500m 전 안내 · 초과 +0km/h · 소리 띠링",
                    onDismiss = {},
                )
            }
        }
    }

    /** 기능 목록으로 돌아가는 버튼이 매크로의 생성 동작을 가리지 않는다. */
    @Test fun macros() = features(AppFeature.MACROS)

    /** 꺼진 기능도 설정에서 부족한 권한을 허용할 수 있다. */
    @Test fun missingPermissionSettings() {
        paparazzi.snapshot {
            AppFrame(Destination.Settings, dark) {
                SettingsScreen(AppSettings(), onUnpair = {}, onStartPairing = {}, navigation = navigation,
                    focusedFeature = FeatureSettings.SAFE_DRIVE, initialGroup = SettingsGroup.DRIVING,
                    onBackToFeature = {})
            }
        }
    }

    /** 실행 화면은 기존 매크로 및 기능 패널을 실제 앱 프레임 안에 넣는다. */
    private fun features(selected: AppFeature?, settings: AppSettings = AppSettings()) {
        paparazzi.snapshot {
            AppFrame(Destination.Features, dark) {
                FeaturesScreen(settings, selected,
                    navigation.copy(locationPermitted = settings.isReady),
                    smartThings.copy(notificationAccessGranted = settings.isReady), {}, {}, {},
                    macroContent = {
                        MacroListScreen(emptyList(), emptySet(), emptyMap(), { _, _ -> }, {}, {}, {}, {}, {})
                    })
            }
        }
    }
    companion object {
        /** 폰·태블릿과 낮·밤을 교차하고 폰 최대 글씨와 태블릿 확대를 포함한다. */
        @JvmStatic @Parameterized.Parameters(name = "dark={0},wide={1},scale={2}")
        fun configurations(): List<Array<Any>> = listOf(
            arrayOf(false, false, 1f), arrayOf(true, false, 2f),
            arrayOf(false, true, 1.3f), arrayOf(true, true, 1.3f))
    }
}
