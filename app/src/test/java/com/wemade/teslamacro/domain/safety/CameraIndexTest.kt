package com.wemade.teslamacro.domain.safety

import org.junit.Assert.*
import org.junit.Test

class CameraIndexTest {
    private val index = CameraIndex(listOf(OfflineCamera("test", 37.003, 127.0, 50)))

    /** 전방 거리와 후보 제한속도를 함께 반환한다. */
    @Test fun approaching() {
        val alert = index.nearest(37.0, 127.0, 0.0, 60.0, 10.0)
        assertEquals(50, alert?.speedLimitKph)
        assertTrue(alert!!.distanceMeters!! in 330..340)
    }

    /** 반대 방향·평행 도로·저정밀·정지·범위 밖은 안내하지 않는다. */
    @Test fun rejectedFixes() {
        assertNull(index.nearest(37.0, 127.0, 180.0, 60.0, 10.0))
        assertNull(index.nearest(37.0, 127.001, 0.0, 60.0, 10.0))
        assertNull(index.nearest(37.0, 127.0, 0.0, 60.0, 31.0))
        assertNull(index.nearest(37.0, 127.0, 0.0, 0.0, 10.0))
        assertNull(index.nearest(36.99, 127.0, 0.0, 60.0, 10.0))
        assertNull(index.nearest(37.0, 127.0, Double.NaN, 60.0, 10.0))
    }

    /** GPS 단절이나 미준비 상태에 남은 카메라로 경보를 만들지 않는다. */
    @Test fun unavailableState() {
        val alert = SafetyAlert(SafetyKind.SPEED_CAMERA, 300, 50)
        assertFalse(SafetyState(alert = alert).isOverSpeed(100.0))
        assertFalse(SafetyState(ready = true, stalled = true, alert = alert).isOverSpeed(100.0))
        assertFalse(SafetyState(ready = true, alert = alert).isOverSpeed(Double.POSITIVE_INFINITY))
    }
}
