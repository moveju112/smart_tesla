package com.wemade.teslamacro.domain.macro

import com.wemade.teslamacro.domain.model.VehicleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "출발지 근처" 조건 검증.
 *
 * 핵심 규칙: **위치를 모르면 무조건 불충족(fail-closed)** —
 * 엉뚱한 곳에서 길안내가 뜨는 것보다 안 뜨는 게 낫다.
 */
class NearLocationTest {

    // 판교역 부근을 기준점으로 쓴다 (실주소 아님, 좌표 예시)
    private val base = GeoPoint(37.3948, 127.1112)

    private fun reading(location: GeoPoint?) = Reading(
        snapshot = VehicleSnapshot(timestampMillis = 0L),
        time = TimeContext(0L, minutesOfDay = 9 * 60, dayOfWeek = 1),
        location = location,
    )

    private fun condition(radius: Int = 400) = Condition.NearLocation(
        latitude = base.latitude,
        longitude = base.longitude,
        radiusMeters = radius,
    )

    @Test
    fun `반경 안이면 충족`() {
        // 기준점에서 약 111m 북쪽 (위도 0.001도 ≈ 111m)
        val near = GeoPoint(base.latitude + 0.001, base.longitude)
        assertTrue(ConditionEvaluator.holds(condition(radius = 400), reading(near)))
    }

    @Test
    fun `반경 밖이면 불충족`() {
        // 약 1.1km 북쪽
        val far = GeoPoint(base.latitude + 0.01, base.longitude)
        assertFalse(ConditionEvaluator.holds(condition(radius = 400), reading(far)))
    }

    @Test
    fun `위치를 못 읽었으면 불충족 - fail closed`() {
        assertFalse(ConditionEvaluator.holds(condition(), reading(location = null)))
    }

    @Test
    fun `좌표를 아직 저장 안 한 조건은 불충족`() {
        val unset = Condition.NearLocation()
        assertFalse(ConditionEvaluator.holds(unset, reading(base)))
    }

    @Test
    fun `하버사인 거리 - 위도 1도는 약 111km`() {
        val d = ConditionEvaluator.distanceMeters(37.0, 127.0, 38.0, 127.0)
        assertEquals(111_000.0, d, 500.0)
    }

    @Test
    fun `위치 조건은 차량 신호를 요구하지 않는다`() {
        assertTrue(condition().signals().isEmpty())
    }
}
