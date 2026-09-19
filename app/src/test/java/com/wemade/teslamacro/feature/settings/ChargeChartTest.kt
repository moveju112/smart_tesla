package com.wemade.teslamacro.feature.settings

import com.wemade.teslamacro.data.charge.ChargeBucket
import com.wemade.teslamacro.data.charge.ChargeHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 그래프가 칸을 제자리에 놓고 눈금 꼭대기를 값에서 뽑는지 검증한다. */
class ChargeChartTest {

    private val now = ChargeHistory.bucketStart(1_800_000_000_000L)

    @Test
    fun `가장 최근 칸이 맨 오른쪽이다`() {
        val bars = planChargeBars(listOf(bucket(now, amps = 10.0)), now, maxAmps = 10.0)

        assertEquals(SLOT_COUNT - 1, bars.single().slot)
        assertEquals(1.0, bars.single().heightRatio, 0.001)
    }

    @Test
    fun `24시간보다 오래된 칸은 그리지 않는다`() {
        val old = bucket(now - 24 * 60 * 60_000L, amps = 10.0)

        assertTrue(planChargeBars(listOf(old), now, maxAmps = 10.0).isEmpty())
    }

    @Test
    fun `관측이 절반이 안 되는 칸은 흐리게 표시한다`() {
        val thin = ChargeBucket(now, ampsMillis = 10 * 60_000L, coveredMillis = 60_000L)
        val full = bucket(now - ChargeHistory.BUCKET_MILLIS, amps = 10.0)

        assertTrue(planChargeBars(listOf(thin), now, 10.0).single().faded)
        assertFalse(planChargeBars(listOf(full), now, 10.0).single().faded)
    }

    @Test
    fun `눈금 꼭대기는 관측 최댓값을 올림하되 최소 5A다`() {
        assertEquals(5.0, chartTopAmps(emptyList()), 0.001)
        assertEquals(5.0, chartTopAmps(listOf(bucket(now, amps = 3.2))), 0.001)
        assertEquals(13.0, chartTopAmps(listOf(bucket(now, amps = 12.4))), 0.001)
    }

    @Test
    fun `눈금 글자는 6시간 간격 네 개다`() {
        val labels = hourLabels(now, java.util.TimeZone.getTimeZone("Asia/Seoul"))

        assertEquals(4, labels.size)
        assertTrue(labels.all { it.endsWith("시") })
    }

    /** 한 칸을 온전히 채운 기록. */
    private fun bucket(start: Long, amps: Double) = ChargeBucket(
        startMillis = start,
        ampsMillis = (amps * ChargeHistory.BUCKET_MILLIS).toLong(),
        coveredMillis = ChargeHistory.BUCKET_MILLIS,
    )
}
