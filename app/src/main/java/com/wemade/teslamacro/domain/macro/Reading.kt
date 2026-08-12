package com.wemade.teslamacro.domain.macro

import com.wemade.teslamacro.domain.model.VehicleSnapshot
import java.util.Calendar
import java.util.TimeZone

/**
 * 매크로 판정 시점의 시각 정보.
 *
 * 시간 조건은 차량 신호가 아니라 로컬 시계에서 온다.
 * 엔진을 순수 함수로 유지하려고 시각도 인자로 받는다 (내부에서 시계를 읽지 않는다).
 */
data class TimeContext(
    val epochMillis: Long,
    /** 자정부터 흐른 분. 0~1439 */
    val minutesOfDay: Int,
    /** 월=1 … 일=7 (ISO 기준) */
    val dayOfWeek: Int,
) {
    companion object {
        fun of(epochMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): TimeContext {
            val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = epochMillis }
            // Calendar는 일요일=1이라 ISO(월=1)로 옮긴다
            val isoDay = ((calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
            return TimeContext(
                epochMillis = epochMillis,
                minutesOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 +
                    calendar.get(Calendar.MINUTE),
                dayOfWeek = isoDay,
            )
        }
    }
}

/** 위도·경도 한 쌍. 태블릿 GPS에서 온다 */
data class GeoPoint(val latitude: Double, val longitude: Double)

/** 한 번의 폴링 결과 = 차량 상태 + 그 시점의 시각 + (위치 조건을 쓸 때만) 태블릿 위치 */
data class Reading(
    val snapshot: VehicleSnapshot,
    val time: TimeContext,
    val location: GeoPoint? = null,
)
