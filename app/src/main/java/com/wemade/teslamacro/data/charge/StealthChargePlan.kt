package com.wemade.teslamacro.data.charge

import kotlin.math.roundToInt
import kotlin.math.pow
import kotlin.random.Random

/** 충전 속도가 과도하게 떨어지지 않도록 상한 주변에서 다음 전류를 고른다. */
object StealthChargePlan {

    /** 한 스텝의 결과: 이 전류로 바꾸고, 이만큼 뒤에 다시 정한다. */
    data class Step(val amps: Int, val holdSeconds: Int)

    /**
     * [minAmps]~[maxAmps] 범위 안에서 높은 전류 쪽에 가중치를 둔다.
     *
     * 예전에는 뽑은 값을 현재 전류와 절반씩 섞어 급격한 점프를 줄였는데, 5~13A처럼 좁은
     * 범위에서는 변화폭까지 반으로 깎여 한 값(10A)에 40% 몰렸다. 패턴을 숨기려고 흔드는
     * 기능이 흔들리지 않으면 의미가 없어 섞기를 뺐다.
     */
    fun next(minAmps: Int, maxAmps: Int, random: Random = Random.Default): Step {
        val lo = minAmps.coerceAtMost(maxAmps)
        val hi = maxAmps.coerceAtLeast(minAmps)
        val span = hi - lo

        // 지수가 1보다 작으면 상한 쪽 표본이 많아진다. 0.75는 균등(1.0)과 예전 sqrt(0.5)의 중간
        val target = lo + (span * random.nextDouble().pow(HIGH_BIAS_EXPONENT)).roundToInt()

        return Step(target.coerceIn(lo, hi), holdSeconds = randomInterval(random))
    }

    /** 사용자가 하한을 정하지 않았을 때 쓰는 자동 하한 — 상한의 위쪽 25%로 충전 속도를 지킨다. */
    fun autoMinAmps(minAmps: Int, maxAmps: Int): Int {
        val lo = minAmps.coerceAtMost(maxAmps)
        val hi = maxAmps.coerceAtLeast(minAmps)
        return maxOf(lo, (hi * HIGH_BAND_RATIO).roundToInt()).coerceAtMost(hi)
    }

    /** 고정 주기로 명령이 몰리지 않도록 60~300초 사이에서 다음 변경 시점을 고른다. */
    private fun randomInterval(random: Random): Int =
        MIN_INTERVAL_S + random.nextInt(MAX_INTERVAL_S - MIN_INTERVAL_S + 1)

    private const val HIGH_BAND_RATIO = 0.75
    private const val HIGH_BIAS_EXPONENT = 0.75
    private const val MIN_INTERVAL_S = 60
    private const val MAX_INTERVAL_S = 300
}
