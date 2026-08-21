package com.wemade.teslamacro

import com.wemade.teslamacro.feature.dashboard.fitInscribedSp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 기입 치수 크기 계산.
 *
 * 스크린샷으로는 이걸 못 잡는다 — 값 바로 아래 치수선이 칸 전폭을 채우기 때문에
 * "오른쪽 끝"을 재면 크기가 어떻게 바뀌어도 늘 같은 값이 나온다.
 * 실제로 그 함정에 빠져 "배율을 올려도 안 줄어든다"고 잘못 확인했고,
 * 글자 높이를 재 보니 56px → 39px로 줄고 있었다.
 *
 * 그래서 순수 계산을 직접 본다. 측정기는 화면 폭을 픽셀로 주고,
 * 그 값에는 **글자 배율이 이미 반영돼 있다** — 이 사실이 계산의 전제다.
 */
class InscribedSizeTest {

    /** 잰 폭이 칸에 들어가면 기본 크기를 그대로 쓴다 */
    @Test
    fun `칸에 들어가면 기본 크기`() {
        assertEquals(84f, fitInscribedSp(baseSp = 84f, measuredPx = 300f, roomPx = 400f), 0.01f)
    }

    /** 넘치면 넘친 비율만큼 줄인다 */
    @Test
    fun `넘치면 넘친 비율만큼 줄인다`() {
        // 500px를 400px 칸에 → 0.8배
        assertEquals(67.2f, fitInscribedSp(baseSp = 84f, measuredPx = 500f, roomPx = 400f), 0.01f)
    }

    /** 폭을 못 재면(0) 기본 크기로 둔다 — 0으로 나누면 크기가 무한이 된다 */
    @Test
    fun `폭을 못 재면 기본 크기`() {
        assertEquals(84f, fitInscribedSp(baseSp = 84f, measuredPx = 0f, roomPx = 400f), 0.01f)
    }

    /**
     * **핵심** — 글자 배율을 올리면 기입 치수가 작아져선 안 된다.
     *
     * 사용자가 글자를 키웠는데 화면에서 가장 큰 값만 작아지면 접근성 의도가 거꾸로 되고,
     * "계측값이 가장 크다"는 이 화면의 유일한 축이 무너진다.
     *
     * 측정기를 모형으로 세운다: 기본 크기로 잰 폭은 배율에 비례한다(`K × base × scale`).
     * 그 크기로 실제 그려지는 폭은 `잰 폭 × (고른 크기 / 기본 크기)`다.
     */
    @Test
    fun `글자 배율을 올려도 작아지지 않는다`() {
        val base = 84f
        val room = 400f
        // K: 글자 하나가 배율 1.0·기본 크기에서 먹는 픽셀 계수
        val k = 4.2f

        fun renderedWidth(scale: Float): Float {
            val measured = k * base * scale
            val chosen = fitInscribedSp(base, measured, room)
            return measured * (chosen / base)
        }

        val atOne = renderedWidth(1.0f)
        listOf(1.15f, 1.3f, 1.5f, 2.0f).forEach { scale ->
            val grown = renderedWidth(scale)
            assertTrue(
                "배율 $scale 에서 $grown px — 배율 1.0의 $atOne px보다 작아졌다",
                grown >= atOne - 0.01f,
            )
            // 칸을 넘지도 않아야 한다
            assertTrue("배율 $scale 에서 칸($room)을 넘었다", grown <= room + 0.01f)
        }
    }
}
