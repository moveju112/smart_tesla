package com.wemade.teslamacro.data.safety

import com.wemade.teslamacro.domain.safety.SafetyAlert

/** 순간 GPS 오차로 옆 도로 카메라가 한 번 잡혀도 연속 접근이 확인되기 전에는 안내하지 않는다. */
internal class CameraApproachTracker {
    private var acceptedKey: String? = null
    private var acceptedSeenMillis: Long? = null
    private var pendingKey: String? = null
    private var pendingCount = 0
    private var pendingDistanceMeters: Int? = null
    private var pendingSeenMillis: Long? = null

    /** 한 번 잡힌 카메라는 짧은 GPS 흔들림 뒤 바로 복구하되 새 후보는 연속 관측으로만 확정한다. */
    fun observe(candidate: SafetyAlert?, nowMillis: Long): SafetyAlert? {
        if (nowMillis < 0) return null
        if (candidate?.cameraKey == null) {
            clearPending()
            if (acceptedSeenMillis?.let { nowMillis - it > REACQUIRE_MILLIS } == true) {
                acceptedKey = null
                acceptedSeenMillis = null
            }
            return null
        }
        val key = candidate.cameraKey
        if (key == acceptedKey && acceptedSeenMillis?.let { nowMillis - it <= REACQUIRE_MILLIS } == true) {
            acceptedSeenMillis = nowMillis
            clearPending()
            return candidate
        }
        val pendingFresh = key == pendingKey &&
            pendingSeenMillis?.let { nowMillis - it in 0..CONFIRM_WINDOW_MILLIS } == true
        if (!pendingFresh) {
            pendingKey = key
            pendingCount = 1
            pendingDistanceMeters = candidate.distanceMeters
            pendingSeenMillis = nowMillis
            return null
        }
        val previousDistance = pendingDistanceMeters
        val currentDistance = candidate.distanceMeters
        if (previousDistance != null && currentDistance != null &&
            currentDistance > previousDistance + MAX_DISTANCE_GROWTH_METERS) {
            pendingCount = 1
            pendingDistanceMeters = currentDistance
            pendingSeenMillis = nowMillis
            return null
        }
        pendingCount += 1
        pendingDistanceMeters = currentDistance
        pendingSeenMillis = nowMillis
        if (pendingCount < CONFIRM_SAMPLES) return null
        acceptedKey = key
        acceptedSeenMillis = nowMillis
        clearPending()
        return candidate
    }

    /** GPS 중단·설정 변경 뒤 이전 도로의 후보가 살아나지 않게 상태를 모두 버린다. */
    fun reset() {
        acceptedKey = null
        acceptedSeenMillis = null
        clearPending()
    }

    /** 새 후보 확인 중간값은 외부 상태가 아니므로 한곳에서만 초기화한다. */
    private fun clearPending() {
        pendingKey = null
        pendingCount = 0
        pendingDistanceMeters = null
        pendingSeenMillis = null
    }

    private companion object {
        const val CONFIRM_SAMPLES = 2
        const val CONFIRM_WINDOW_MILLIS = 2_500L
        const val REACQUIRE_MILLIS = 3_000L
        const val MAX_DISTANCE_GROWTH_METERS = 40
    }
}
