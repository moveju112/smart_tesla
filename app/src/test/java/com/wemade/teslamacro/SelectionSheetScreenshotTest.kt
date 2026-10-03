package com.wemade.teslamacro

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.layout.onGloballyPositioned
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.wemade.teslamacro.data.settings.AppSettings
import com.wemade.teslamacro.data.settings.DeviceMode
import com.wemade.teslamacro.data.settings.ThemeMode
import com.wemade.teslamacro.feature.settings.LocalExpandSettingsDetails
import com.wemade.teslamacro.feature.settings.SettingsGroup
import com.wemade.teslamacro.feature.settings.SettingsScreen
import com.wemade.teslamacro.ui.component.ChoiceGrid
import com.wemade.teslamacro.ui.component.PickerSheet
import com.wemade.teslamacro.ui.nav.Destination
import com.wemade.teslamacro.ui.theme.SettingsTypography
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 짧은 선택창이 화면 밖으로 늘어나던 문제를 실제 설정과 스크롤 본문에서 막는다. */
@RunWith(Parameterized::class)
class SelectionSheetScreenshotTest(private val dark: Boolean, private val wide: Boolean, private val scale: Float) {
    // 본문과 화면 경계를 함께 검사하고, 실제 시스템 바·IME는 Android 15 에뮬레이터에서 확인한다.
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = if (wide) DeviceConfig.PIXEL_C.copy(
            screenWidth = 1920, screenHeight = 1200, density = Density.XHIGH,
            orientation = ScreenOrientation.LANDSCAPE, fontScale = scale, softButtons = false,
        ) else DeviceConfig.PIXEL_6.copy(
            screenWidth = 720, screenHeight = 1560, density = Density.XHIGH,
            fontScale = scale, softButtons = false,
        ),
        showSystemUi = false,
    )

    /** 기기 사용 방식은 실제 차량 설정 진입점과 시트·선택 컨트롤로 렌더링한다. */
    @Test fun deviceModeSheet() {
        paparazzi.snapshot {
            AppFrame(Destination.Settings, dark = dark) {
                CompositionLocalProvider(LocalExpandSettingsDetails provides true) {
                    SettingsScreen(
                        settings = AppSettings(deviceMode = DeviceMode.PORTABLE),
                        initialGroup = SettingsGroup.VEHICLE,
                        onUnpair = {}, onStartPairing = {},
                    )
                }
            }
        }
    }

    /** 스크롤 안의 두 선택지가 남은 창 높이를 차지하거나 아래에서 잘리지 않아야 한다. */
    @Test fun shortChoicesRemainInsideSheet() {
        var viewport = Rect.Zero
        var choices = Rect.Zero
        paparazzi.snapshot {
            FullScreenFrame(dark = dark) {
                MaterialTheme(typography = SettingsTypography) {
                    PickerSheet("기기 사용 방식", onDismiss = {}) {
                        Column(Modifier.fillMaxWidth().onGloballyPositioned { viewport = Rect(it.positionInWindow(), it.size.toSize()) }
                            .verticalScroll(rememberScrollState())) {
                            ChoiceGrid(
                                options = DeviceMode.entries,
                                selected = DeviceMode.PORTABLE,
                                label = { it.label }, onSelect = {},
                                modifier = Modifier.onGloballyPositioned { choices = Rect(it.positionInWindow(), it.size.toSize()) },
                            )
                        }
                    }
                }
            }
        }
        assertTrue("선택지 전체가 스크롤 없이 보여야 한다", choices.height > 0f && choices.height <= viewport.height + 1f)
        assertTrue("짧은 선택지가 화면 높이까지 늘어나면 안 된다", choices.height < (if (wide) 1200f else 1560f) / 3f)
        assertTrue("선택지 아래가 본문에서 잘리면 안 된다", choices.bottom <= viewport.bottom + 1f)
        assertTrue("본문 자체가 화면 밖으로 밀려나면 안 된다", viewport.bottom <= (if (wide) 1200f else 1560f))
    }

    /** 세 선택지와 큰 글씨 줄바꿈도 실제 화면 안에서 모두 보여야 한다. */
    @Test fun themeChoicesRemainInsideWindow() {
        var choices = Rect.Zero
        paparazzi.snapshot {
            FullScreenFrame(dark = dark) {
                MaterialTheme(typography = SettingsTypography) {
                    PickerSheet("화면 모드", onDismiss = {}) {
                        ChoiceGrid(
                            options = ThemeMode.entries,
                            selected = ThemeMode.LIGHT,
                            label = { it.label }, onSelect = {}, columns = 3,
                            modifier = Modifier.onGloballyPositioned {
                                choices = Rect(it.positionInWindow(), it.size.toSize())
                            },
                        )
                    }
                }
            }
        }
        assertTrue("선택지는 실제 화면 안에 있어야 한다", choices.height > 0f && choices.top >= 0f &&
            choices.bottom <= (if (wide) 1200f else 1560f))
    }

    companion object {
        /** 좁은 휴대폰·낮/밤·2배 글씨와 가로 태블릿을 교차한다. */
        @JvmStatic @Parameterized.Parameters(name = "dark={0},wide={1},scale={2}")
        fun configurations(): List<Array<Any>> = listOf(
            arrayOf(false, false, 1f), arrayOf(true, false, 1.3f),
            arrayOf(false, false, 2f), arrayOf(true, true, 1.3f),
        )
    }
}
