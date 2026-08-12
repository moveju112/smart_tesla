package com.wemade.teslamacro.data.charge

import kotlin.random.Random

/**
 * 스텔스 충전의 다음 한 수를 정한다 — 순수 함수라 단위 테스트로 검증한다.
 *
 * 목표: 충전 부하가 "몇 시간 이어지는 크고 일정한 대전류"라는 EV 지문을 흐린다.
 * 세 축으로 흔든다.
 *  1. 연속성 깨기 — 낮은 확률로 아주 낮은 전류('쉬는' 구간)로 떨어뜨린다.
 *  2. 양방향 랜덤워크 — 평균으로 되돌아오는 진동이라 한쪽으로 눌러앉지 않는다.
 *  3. 다음 변경까지의 간격도 난수 — 정확한 주기 자체가 지문이 되는 걸 막는다.
 */
object StealthChargePlan {

    /** 한 스텝의 결과: 이 전류로 바꾸고, 이만큼 뒤에 다시 정한다 */
    data class Step(val amps: Int, val holdSeconds: Int)

    /**
     * @param current 지금 걸려 있는(또는 직전에 정한) 전류
     * @param minAmps 차가 받는 하한 (보통 5A)
     * @param maxAmps 사용자가 허용한 상한
     */
    fun next(current: Int, minAmps: Int, maxAmps: Int, random: Random = Random.Default): Step {
        val lo = minAmps.coerceAtMost(maxAmps)
        val hi = maxAmps.coerceAtLeast(minAmps)

        // 1. 가끔은 통째로 쉰다 — 연속 대전류를 끊는 게 가장 큰 위장이다
        if (random.nextDouble() < PAUSE_PROBABILITY) {
            return Step(lo, holdSeconds = randomInterval(random))
        }

        // 2. 평균으로 되돌아오는 랜덤워크. 밴드 한가운데를 중심으로 위아래로 배회한다
        val mid = (lo + hi) / 2.0
        val drift = (mid - current) * MEAN_REVERSION          // 중앙으로 당기는 힘
        val noise = (random.nextDouble() * 2 - 1) * (hi - lo) * STEP_JITTER
        val target = (current + drift + noise).toInt().coerceIn(lo, hi)

        return Step(target, holdSeconds = randomInterval(random))
    }

    /** 30~120초 사이 난수. 고정 주기(예 정확히 60초)는 그 자체로 티가 난다 */
    private fun randomInterval(random: Random): Int =
        MIN_INTERVAL_S + random.nextInt(MAX_INTERVAL_S - MIN_INTERVAL_S + 1)

    private const val PAUSE_PROBABILITY = 0.15   // 15%는 쉬는 구간
    private const val MEAN_REVERSION = 0.35      // 중앙 복원력
    private const val STEP_JITTER = 0.30         // 한 번에 흔들리는 폭(밴드 대비)
    private const val MIN_INTERVAL_S = 30
    private const val MAX_INTERVAL_S = 120
}
