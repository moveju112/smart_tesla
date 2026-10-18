package com.wemade.teslamacro.feature.dashboard

import com.wemade.teslamacro.domain.model.TirePosition
import org.junit.Assert.assertEquals
import org.junit.Test

class CarPlanTirePositionTest {
    // 노즈가 왼쪽인 도면에서 위쪽 두 바퀴는 동승석 쪽이다.
    @Test
    fun screenTopMapsToVehicleRight() {
        assertEquals(TirePosition.FRONT_RIGHT, tirePositionOf(isFront = true, isScreenTop = true))
        assertEquals(TirePosition.REAR_RIGHT, tirePositionOf(isFront = false, isScreenTop = true))
    }

    // 아래쪽 두 바퀴는 운전석 쪽이며 앞뒤 축은 그대로 유지한다.
    @Test
    fun screenBottomMapsToVehicleLeft() {
        assertEquals(TirePosition.FRONT_LEFT, tirePositionOf(isFront = true, isScreenTop = false))
        assertEquals(TirePosition.REAR_LEFT, tirePositionOf(isFront = false, isScreenTop = false))
    }
}
