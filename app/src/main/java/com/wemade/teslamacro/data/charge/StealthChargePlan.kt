package com.wemade.teslamacro.data.charge

import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/** 충전 속도가 과도하게 떨어지지 않도록 상한 주변에서 다음 전류를 고른다. */
object StealthChargePlan {

    /** 한 스텝의 결과: 이 전류로 바꾸고, 이만큼 뒤에 다시 정한다. */
    data class Step(val amps: Int, val holdSeconds: Int)

    /** 16A 이하 충전기도 실제 상한 안에서 높은 전류 쪽에 가중치를 둔다. */
    fun next(current: Int, minAmps: Int, maxAmps: Int, random: Random = Random.Default): Step {
        val lo = minAmps.coerceAtMost(maxAmps)
        val hi = maxAmps.coerceAtLeast(minAmps)
        val highBandStart = highBandStartAmps(lo, hi)
        val span = hi - highBandStart

        // sqrt 난수는 상한 쪽 표본이 더 많다. 현재값과 절반씩 섞어 급격한 점프만 줄인다.
        val sampled = highBandStart + (span * sqrt(random.nextDouble())).roundToInt()
        val target = if (current in highBandStart..hi) {
            ((current + sampled) / 2.0).roundToInt()
        } else {
            sampled
        }.coerceIn(highBandStart, hi)

        return Step(target, holdSeconds = randomInterval(random))
    }

    /** 충전기 상한의 위쪽 25%를 사용하되 차량 명령 하한보다 낮아지지 않게 한다. */
    internal fun highBandStartAmps(minAmps: Int, maxAmps: Int): Int {
        val lo = minAmps.coerceAtMost(maxAmps)
        val hi = maxAmps.coerceAtLeast(minAmps)
        return maxOf(lo, (hi * HIGH_BAND_RATIO).roundToInt()).coerceAtMost(hi)
    }

    /** 고정 주기로 명령이 몰리지 않도록 기존 30~120초 간격을 유지한다. */
    private fun randomInterval(random: Random): Int =
        MIN_INTERVAL_S + random.nextInt(MAX_INTERVAL_S - MIN_INTERVAL_S + 1)

    private const val HIGH_BAND_RATIO = 0.75
    private const val MIN_INTERVAL_S = 30
    private const val MAX_INTERVAL_S = 120
}
