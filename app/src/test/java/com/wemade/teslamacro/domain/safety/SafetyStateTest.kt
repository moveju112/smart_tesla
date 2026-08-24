package com.wemade.teslamacro.domain.safety

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 과속 판정.
 *
 * 여기서 틀리면 두 방향 다 나쁘다 — 안 울려야 할 때 울리면 운전자가 경고를 무시하게 되고,
 * 울려야 할 때 침묵하면 있으나 마나다. GPS 속도가 계기판보다 흔들리는 것도 감안해야 한다.
 */
class SafetyStateTest {

    private val camera = SafetyAlert(SafetyKind.SPEED_CAMERA, distanceMeters = 300, speedLimitKph = 80)

    @Test
    fun `다가오는 카메라가 없으면 과속이 아니다`() {
        // 모르는 걸 과속으로 치면 고속도로에서 계속 울린다.
        // free-drive 모드엔 "지금 달리는 도로의 제한속도"라는 값이 아예 안 온다
        val state = SafetyState(ready = true, alert = null)
        assertFalse(state.isOverSpeed(120.0))
    }

    @Test
    fun `카메라 제한속도를 넘으면 과속이다`() {
        val state = SafetyState(ready = true, alert = camera)
        assertTrue(state.isOverSpeed(90.0))
        assertFalse(state.isOverSpeed(75.0))
    }

    /** GPS 속도는 계기판보다 1~2km/h 흔들린다 — 딱 맞을 때 울리면 깜빡인다 */
    @Test
    fun `허용치 안이면 과속으로 안 본다`() {
        val state = SafetyState(ready = true, alert = camera)
        assertFalse(state.isOverSpeed(82.0, toleranceKph = 3))
        assertTrue(state.isOverSpeed(84.0, toleranceKph = 3))
    }

    /** 제한속도가 없는 안전물(보호구역·위험 구간)은 과속 판정 대상이 아니다 */
    @Test
    fun `제한속도 없는 안전물은 과속 판정을 안 한다`() {
        val zone = SafetyAlert(SafetyKind.PROTECTION_ZONE, distanceMeters = 120)
        val state = SafetyState(ready = true, alert = zone)
        assertFalse(state.isOverSpeed(120.0))
    }

    @Test
    fun `안내를 못 하는 상태는 ready가 false다`() {
        assertFalse(SafetyState().ready)
    }
}
