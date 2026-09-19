package com.wemade.teslamacro.data.charge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 15분 칸이 표본 수가 아니라 시간으로 가중되는지 검증한다. */
class ChargeHistoryTest {

    private val base = ChargeHistory.bucketStart(1_800_000_000_000L)

    @Test
    fun `10분 10A와 5분 5A는 8_3A다`() {
        var buckets = ChargeHistory.accumulate(emptyList(), base, base + 10 * MINUTE, 10)
        buckets = ChargeHistory.accumulate(buckets, base + 10 * MINUTE, base + 15 * MINUTE, 5)

        assertEquals(1, buckets.size)
        // 표본 평균이면 7.5A가 된다. 시간 가중이면 (10×10 + 5×5) / 15
        assertEquals(8.333, buckets.single().averageAmps, 0.001)
        assertEquals(1.0, buckets.single().coverage, 0.001)
    }

    @Test
    fun `칸 경계를 넘는 구간은 걸친 만큼 나눠 담는다`() {
        // 10분~20분을 12A로 채우면 앞 칸에 5분, 뒤 칸에 5분
        val buckets = ChargeHistory.accumulate(
            emptyList(),
            base + 10 * MINUTE,
            base + 20 * MINUTE,
            12,
        )

        assertEquals(2, buckets.size)
        assertEquals(base, buckets[0].startMillis)
        assertEquals(base + 15 * MINUTE, buckets[1].startMillis)
        buckets.forEach {
            assertEquals(12.0, it.averageAmps, 0.001)
            assertEquals(5 * MINUTE, it.coveredMillis)
        }
    }

    @Test
    fun `관측이 오래 끊긴 구간은 유지됐다고 단정하지 않는다`() {
        val buckets = ChargeHistory.accumulate(
            emptyList(),
            base,
            base + ChargeHistory.MAX_GAP_MILLIS + 1,
            11,
        )

        assertTrue("끊긴 구간을 채웠다 ($buckets)", buckets.isEmpty())
    }

    @Test
    fun `일부만 관측된 칸은 관측 비율이 낮게 남는다`() {
        val buckets = ChargeHistory.accumulate(emptyList(), base, base + 3 * MINUTE, 9)

        assertEquals(9.0, buckets.single().averageAmps, 0.001)
        assertEquals(0.2, buckets.single().coverage, 0.001)
    }

    @Test
    fun `24시간이 지난 칸은 버린다`() {
        val old = ChargeBucket(base - ChargeHistory.WINDOW_MILLIS, 100, 10)
        val buckets = ChargeHistory.accumulate(listOf(old), base, base + MINUTE, 7)

        assertEquals(1, buckets.size)
        assertEquals(base, buckets.single().startMillis)
    }

    @Test
    fun `칸 시작은 정각 15분 단위다`() {
        assertEquals(base, ChargeHistory.bucketStart(base))
        assertEquals(base, ChargeHistory.bucketStart(base + 14 * MINUTE + 59_000))
        assertEquals(base + 15 * MINUTE, ChargeHistory.bucketStart(base + 15 * MINUTE))
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
