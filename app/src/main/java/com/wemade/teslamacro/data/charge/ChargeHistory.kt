package com.wemade.teslamacro.data.charge

import kotlinx.serialization.Serializable

/**
 * 15분 한 칸의 충전 전류 기록.
 *
 * 표본 수가 아니라 **시간**으로 적산한다. 폴링 간격은 충전 중 15초, 대기 중 120초로
 * 들쭉날쭉해서 표본 평균을 내면 10분 10A + 5분 5A가 7.5A로 나온다. 실제 평균은
 * (10×10 + 5×5) / 15 = 8.3A다.
 *
 * @param ampsMillis 전류 × 유지 시간(ms)의 합
 * @param coveredMillis 실제로 관측된 시간(ms). 연결이 끊긴 구간은 여기에 들어가지 않는다
 */
@Serializable
data class ChargeBucket(
    val startMillis: Long,
    val ampsMillis: Long,
    val coveredMillis: Long,
) {
    /** 이 칸에서 관측된 시간에 대한 가중 평균 전류. */
    val averageAmps: Double
        get() = if (coveredMillis <= 0) 0.0 else ampsMillis.toDouble() / coveredMillis

    /** 15분 중 얼마나 관측됐는가. 0.5면 절반은 모르는 구간이다. */
    val coverage: Double
        get() = (coveredMillis.toDouble() / ChargeHistory.BUCKET_MILLIS).coerceIn(0.0, 1.0)
}

/** 충전 전류를 15분 칸으로 시간 가중 적산하고 최근 24시간만 남긴다. */
object ChargeHistory {

    const val BUCKET_MILLIS = 15 * 60_000L
    const val WINDOW_MILLIS = 24 * 60 * 60_000L

    /**
     * 관측이 끊겼다고 보는 간격.
     *
     * 이보다 오래 못 읽었으면 그 사이 전류가 유지됐다고 단정하지 않는다 — 연결이 끊긴
     * 동안 충전이 끝났을 수도, 사용자가 직접 바꿨을 수도 있다.
     */
    const val MAX_GAP_MILLIS = 10 * 60_000L

    /** 한 칸의 시작 시각. 표준시 오프셋은 모두 15분 배수라 UTC로 잘라도 현지 시각과 어긋나지 않는다. */
    fun bucketStart(millis: Long): Long = millis - millis.mod(BUCKET_MILLIS)

    /**
     * [fromMillis]부터 [toMillis]까지 [amps]가 유지됐다고 보고 칸별로 나눠 담는다.
     *
     * 구간이 15분 경계를 넘으면 걸친 만큼씩 쪼개 담는다. 10:10~10:20을 12A로 채우면
     * 10:00 칸에 5분, 10:15 칸에 5분이 들어간다.
     */
    fun accumulate(
        buckets: List<ChargeBucket>,
        fromMillis: Long,
        toMillis: Long,
        amps: Int,
    ): List<ChargeBucket> {
        if (toMillis <= fromMillis) return buckets
        if (toMillis - fromMillis > MAX_GAP_MILLIS) return prune(buckets, toMillis)

        val byStart = buckets.associateByTo(LinkedHashMap()) { it.startMillis }
        var cursor = fromMillis
        while (cursor < toMillis) {
            val start = bucketStart(cursor)
            val end = minOf(start + BUCKET_MILLIS, toMillis)
            val span = end - cursor
            val previous = byStart[start]
            byStart[start] = ChargeBucket(
                startMillis = start,
                ampsMillis = (previous?.ampsMillis ?: 0L) + amps * span,
                coveredMillis = (previous?.coveredMillis ?: 0L) + span,
            )
            cursor = end
        }
        return prune(byStart.values.sortedBy { it.startMillis }, toMillis)
    }

    /** 24시간이 지난 칸을 버린다. */
    fun prune(buckets: List<ChargeBucket>, nowMillis: Long): List<ChargeBucket> =
        buckets.filter { nowMillis - it.startMillis < WINDOW_MILLIS }
}
