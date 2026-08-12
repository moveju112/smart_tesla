package com.wemade.teslamacro.domain.macro

import com.wemade.teslamacro.domain.model.ShiftState
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 조건 하나가 지금 참인지 판정한다.
 *
 * [MacroEngine]과 [MacroRunner](대기 해제 판정)가 **같은 규칙**을 써야 하므로
 * 두 곳에 복사하지 않고 여기 한 곳에 둔다.
 */
object ConditionEvaluator {

    /** 값을 못 읽었으면 "불충족"으로 본다 — 모르는 걸 참으로 치면 차가 멋대로 움직인다 */
    fun holds(condition: Condition, reading: Reading): Boolean = when (condition) {

        is Condition.InRange -> {
            val value = condition.signal.numberOf(reading.snapshot)
            value != null &&
                (condition.gte == null || value >= condition.gte) &&
                (condition.lte == null || value <= condition.lte)
        }

        is Condition.SignalIs ->
            condition.signal.booleanOf(reading.snapshot) == condition.value

        is Condition.TimeWindow -> {
            val minutes = reading.time.minutesOfDay
            // 22시~06시처럼 자정을 넘는 구간도 지원한다
            if (condition.fromMinutes <= condition.toMinutes) {
                minutes in condition.fromMinutes..condition.toMinutes
            } else {
                minutes >= condition.fromMinutes || minutes <= condition.toMinutes
            }
        }

        is Condition.OnDays ->
            condition.days.isEmpty() || reading.time.dayOfWeek in condition.days

        is Condition.NearLocation -> {
            val here = reading.location
            val lat = condition.latitude
            val lng = condition.longitude
            // 위치를 못 읽었거나 아직 저장 전이면 불충족 — 엉뚱한 곳에서 안내가 뜨는 것보다 안 뜨는 게 낫다
            here != null && lat != null && lng != null &&
                distanceMeters(here.latitude, here.longitude, lat, lng) <= condition.radiusMeters
        }
    }

    /** 기어를 못 읽은 상태를 "주차 아님"으로 오해하지 않도록 하는 공용 헬퍼 */
    fun shiftKnown(shiftState: ShiftState): Boolean = shiftState != ShiftState.UNKNOWN

    /** 두 좌표 사이 거리(m). 하버사인 공식 — 수백 m 반경 판정엔 충분한 정밀도다 */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a))
    }

    private const val EARTH_RADIUS_M = 6_371_000.0
}
