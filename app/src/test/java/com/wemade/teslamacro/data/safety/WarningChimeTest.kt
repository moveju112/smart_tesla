package com.wemade.teslamacro.data.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WarningChimeTest {
    /** 모든 경고음은 가장 빠른 반복 간격(0.6초) 전에 끝나고, 잘림 없이 클릭 없는 무음에서 시작한다. */
    @Test fun everySoundFitsFastestIntervalWithoutClipping() {
        for (sound in WarningSound.entries) {
            val pcm = warningChimePcm(sound, 1f)
            val seconds = pcm.size / 44_100.0
            assertTrue("${sound.label} ${seconds}초", seconds <= 0.4)
            assertEquals(0, pcm.first().toInt())
            val peak = pcm.maxOf { abs(it.toInt()) }
            assertTrue("${sound.label} 최대 $peak", peak in 30_000..31_200)
            // 크기 단계를 낮추면 실제 진폭도 줄어야 한다.
            assertTrue(warningChimePcm(sound, 0.45f).maxOf { abs(it.toInt()) } < peak / 2)
        }
    }

    /** 옛 설정·손으로 고친 백업의 알 수 없는 값은 기본 경고음으로 읽는다. */
    @Test fun unknownSettingFallsBackToChime() {
        assertEquals(WarningSound.BEEP, WarningSound.of("beep"))
        assertEquals(WarningSound.CHIME, WarningSound.of("siren"))
        assertEquals(WarningSound.CHIME, WarningSound.of(null))
    }
}
