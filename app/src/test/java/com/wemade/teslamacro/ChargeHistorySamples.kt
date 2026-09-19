package com.wemade.teslamacro

import com.wemade.teslamacro.data.charge.ChargeBucket
import com.wemade.teslamacro.data.charge.ChargeHistory

/** 스냅샷이 실행 시각에 흔들리지 않도록 고정한 "지금" (2027-01-15 21:00 KST). */
const val SNAPSHOT_NOW_MILLIS = 1_800_000_000_000L

/**
 * 밤 충전 한 번이 담긴 그래프 표본.
 *
 * 마지막 6시간에 5~13A가 흩어지게 넣어 스텔스 충전이 전류를 흔든 모습이 보이게 한다.
 */
fun sampleChargeHistory(nowMillis: Long = SNAPSHOT_NOW_MILLIS): List<ChargeBucket> {
    val last = ChargeHistory.bucketStart(nowMillis)
    val amps = listOf(11, 8, 13, 6, 9, 12, 7, 10, 13, 5, 9, 11, 8, 12, 6, 10, 13, 7, 11, 9, 12, 8, 10, 11)
    return amps.mapIndexed { index, value ->
        val start = last - (amps.size - 1 - index) * ChargeHistory.BUCKET_MILLIS
        ChargeBucket(
            startMillis = start,
            ampsMillis = value * ChargeHistory.BUCKET_MILLIS,
            coveredMillis = ChargeHistory.BUCKET_MILLIS,
            wattMillis = value * 220L * ChargeHistory.BUCKET_MILLIS,
        )
    }
}
