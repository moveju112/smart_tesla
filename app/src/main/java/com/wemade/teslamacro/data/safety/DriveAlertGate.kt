package com.wemade.teslamacro.data.safety

/** BLE가 의도적으로 끊겨도 신선한 탑승·차량 이동 증거만 짧게 이어받고 보행이면 즉시 버린다. */
internal class DriveAlertGate {
    enum class Activity { IN_VEHICLE, ON_FOOT, OTHER }

    private var lastDrivingEvidenceMillis: Long? = null
    private var lastActivityMillis: Long? = null
    private var walking = false

    // 차량의 신선한 착석 응답만 출발 근거로 받으며 연결 해제(null)는 하차로 보지 않는다.
    fun observePresence(present: Boolean, nowMillis: Long) {
        if (present) {
            walking = false
            lastDrivingEvidenceMillis = nowMillis
        } else {
            clear()
        }
    }

    // 기기 판정이 늦거나 역순으로 도착하면 오래된 '차량 이동'으로 보행 차단을 풀지 않는다.
    fun observeActivity(activity: Activity, confidence: Int, observedAtMillis: Long, nowMillis: Long) {
        if (observedAtMillis !in 1..nowMillis || nowMillis - observedAtMillis > ACTIVITY_VALID_MILLIS ||
            lastActivityMillis?.let { observedAtMillis < it } == true) return
        lastActivityMillis = observedAtMillis
        when {
            activity == Activity.ON_FOOT && confidence >= 60 -> {
                walking = true
                lastDrivingEvidenceMillis = null
            }
            activity == Activity.IN_VEHICLE && confidence >= 70 -> {
                walking = false
                lastDrivingEvidenceMillis = observedAtMillis
            }
        }
    }

    // 활동 결과도 주행 증거도 유효할 때만 자동 음성을 허용한다. 부팅·권한 거부·미판정은 무음이다.
    fun mayAlert(nowMillis: Long): Boolean = !walking &&
        lastActivityMillis?.let { nowMillis >= it && nowMillis - it <= ACTIVITY_VALID_MILLIS } == true &&
        lastDrivingEvidenceMillis?.let { nowMillis >= it && nowMillis - it <= DRIVING_VALID_MILLIS } == true

    // 설정 해제나 권한 상실 뒤 이전 주행을 다시 살리지 않는다.
    fun clear() {
        walking = true
        lastDrivingEvidenceMillis = null
        lastActivityMillis = null
    }

    companion object {
        private const val ACTIVITY_VALID_MILLIS = 120_000L
        private const val DRIVING_VALID_MILLIS = 180_000L
    }
}
