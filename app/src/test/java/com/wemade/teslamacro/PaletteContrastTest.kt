package com.wemade.teslamacro

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.wemade.teslamacro.ui.theme.DarkPalette
import com.wemade.teslamacro.ui.theme.LightPalette
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteContrastTest {
    /** 낮·밤의 본문과 보조 문구가 어느 기본 면에서도 흐려지지 않게 한다. */
    @Test
    fun textRemainsReadableOnNeutralSurfaces() {
        for (palette in listOf(LightPalette, DarkPalette)) {
            for (surface in listOf(palette.void, palette.carbon, palette.graphite, palette.slate)) {
                for (text in listOf(palette.ink, palette.inkMuted, palette.inkFaint)) {
                    assertContrast(text, surface, 4.5f)
                }
            }
        }
    }

    /** 주요 버튼과 오류·주의·정상 안내는 밤에도 의미를 읽을 수 있어야 한다. */
    @Test
    fun actionsAndStatusTextKeepContrast() {
        for (palette in listOf(LightPalette, DarkPalette)) {
            assertContrast(if (palette == LightPalette) Color.White else palette.void, palette.electric, 4.5f)
            assertContrast(palette.onDanger, palette.danger, 4.5f)
            assertContrast(palette.warnText, palette.warnFaint, 4.5f)
            for (text in listOf(palette.electric, palette.danger, palette.okText)) {
                assertContrast(text, palette.carbon, 4.5f)
            }
        }
    }

    /** 색 이름이나 고정 RGB 대신 실제 상대 휘도 차이를 검증한다. */
    private fun assertContrast(foreground: Color, background: Color, minimum: Float) {
        val first = foreground.luminance()
        val second = background.luminance()
        val ratio = (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
        assertTrue("$foreground on $background contrast $ratio < $minimum", ratio >= minimum)
    }
}
