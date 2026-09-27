package com.wemade.teslamacro.data.safety

import com.wemade.teslamacro.domain.safety.SafetyAlert
import com.wemade.teslamacro.domain.safety.SafetyKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraApproachTrackerTest {
    /** 같은 카메라가 두 번 이어져야 한 번 스친 평행도로 후보를 실제 안내로 올린다. */
    @Test fun newCandidateNeedsConsecutiveFixes() {
        val tracker = CameraApproachTracker()
        assertNull(tracker.observe(alert("a", 500), 1_000))
        assertEquals("a", tracker.observe(alert("a", 470), 2_000)?.cameraKey)
    }

    /** 후보가 매 샘플 바뀌면 어느 쪽도 확정하지 않아 교차로 후보 튐을 음성으로 내보내지 않는다. */
    @Test fun alternatingCandidatesStaySilent() {
        val tracker = CameraApproachTracker()
        assertNull(tracker.observe(alert("a", 500), 1_000))
        assertNull(tracker.observe(alert("b", 480), 2_000))
        assertNull(tracker.observe(alert("a", 450), 3_000))
    }

    /** 이미 확정한 카메라는 GPS 한 번 누락 뒤 바로 복구해 경고가 매초 초기화되지 않는다. */
    @Test fun acceptedCameraSurvivesSingleMiss() {
        val tracker = CameraApproachTracker()
        tracker.observe(alert("a", 500), 1_000)
        tracker.observe(alert("a", 470), 2_000)
        assertNull(tracker.observe(null, 2_500))
        assertEquals("a", tracker.observe(alert("a", 430), 3_000)?.cameraKey)
    }

    /** 오래 벗어난 뒤 같은 좌표를 다시 만나면 새 통과로 보고 다시 확인한다. */
    @Test fun staleCameraNeedsConfirmationAgain() {
        val tracker = CameraApproachTracker()
        tracker.observe(alert("a", 500), 1_000)
        tracker.observe(alert("a", 470), 2_000)
        assertNull(tracker.observe(null, 6_001))
        assertNull(tracker.observe(alert("a", 500), 7_000))
        assertEquals("a", tracker.observe(alert("a", 470), 8_000)?.cameraKey)
    }

    /** 거리가 크게 늘어나는 후보는 접근 중으로 확정하지 않고 확인 횟수를 다시 센다. */
    @Test fun implausibleDistanceGrowthRestartsConfirmation() {
        val tracker = CameraApproachTracker()
        assertNull(tracker.observe(alert("a", 400), 1_000))
        assertNull(tracker.observe(alert("a", 450), 2_000))
        assertEquals("a", tracker.observe(alert("a", 420), 3_000)?.cameraKey)
    }

    private fun alert(key: String, distance: Int) = SafetyAlert(
        kind = SafetyKind.SPEED_CAMERA,
        distanceMeters = distance,
        speedLimitKph = 60,
        cameraKey = key,
    )
}
