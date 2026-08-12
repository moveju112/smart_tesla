package com.wemade.teslamacro.data.poll

/**
 * 탑승 세션 길이를 잰다 — "탑승 시간(분)" 신호의 원천.
 *
 * 차가 주는 값이 아니라서 폴러가 매 폴링 결과를 여기에 먹인다.
 * 핵심 규칙: 하차 판정 시 세션 길이는 "지금까지"가 아니라
 * **탑승을 마지막으로 확인한 시각까지**다 — BLE가 끊긴 사이 하차했으면
 * 끊겨 있던 밤 시간이 통째로 탑승으로 잡히는 걸 막는다.
 */
class RideSessionMeter(private val now: () -> Long = System::currentTimeMillis) {

    private var sinceMillis: Long? = null
    private var lastSeenMillis: Long? = null
    private var lastSessionMinutes: Double? = null
    private var exitedAtMillis: Long? = null

    /**
     * 이번 폴링의 탑승 여부를 반영하고, 스냅샷에 실을 값을 돌려준다.
     * 탑승 중이면 지금까지 흐른 분, 하차 직후엔 직전 세션 길이, 아직 아무것도 모르면 null.
     *
     * 하차 후 값은 [EXIT_GRACE_MILLIS] 동안만 산다 — 하차 트리거의 조건 판정에는 충분하고,
     * 다음 날 문만 열어도 "30분 이상 탔음"이 참이 되는 오염은 막는다.
     */
    fun update(isUserPresent: Boolean?): Double? {
        when (isUserPresent) {
            true -> {
                if (sinceMillis == null) sinceMillis = now()
                lastSeenMillis = now()
                exitedAtMillis = null
            }
            false -> sinceMillis?.let { since ->
                lastSessionMinutes = ((lastSeenMillis ?: since) - since) / 60_000.0
                exitedAtMillis = now()
                sinceMillis = null
            }
            null -> Unit   // 못 읽었으면 판단 보류 — 세션을 끊지 않는다
        }
        return when {
            sinceMillis != null -> (now() - sinceMillis!!) / 60_000.0
            exitedAtMillis != null && now() - exitedAtMillis!! < EXIT_GRACE_MILLIS ->
                lastSessionMinutes
            else -> null
        }
    }

    private companion object {
        /** 하차 후 세션 길이를 조건 판정에 쓸 수 있는 시간 */
        const val EXIT_GRACE_MILLIS = 10 * 60 * 1000L
    }
}
