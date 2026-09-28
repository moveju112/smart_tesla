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

    /** 같은 측정 시각·너무 짧은 간격의 중복 좌표는 거리만 줄어도 새 안내를 확정하지 않는다. */
    @Test fun newCandidateNeedsElapsedSampleTime() {
        val tracker = CameraApproachTracker()
        assertNull(tracker.observe(alert("a", 500), 1_000))
        assertNull(tracker.observe(alert("a", 480), 1_000))
        assertNull(tracker.observe(alert("a", 480), 1_200))
        assertEquals("a", tracker.observe(alert("a", 470), 1_500)?.cameraKey)
    }

    /** 정체·완만한 거리 증가·되돌림만으로는 접근이라 오인하지 않고 실제 감소가 생길 때 확정한다. */
    @Test fun unchangedOrRecedingCandidateCannotConfirm() {
        val tracker = CameraApproachTracker()
        assertNull(tracker.observe(alert("a", 500), 1_000))
        assertNull(tracker.observe(alert("a", 500), 1_500))
        assertNull(tracker.observe(alert("a", 520), 2_000))
        assertNull(tracker.observe(alert("a", 510), 2_500))
        assertEquals("a", tracker.observe(alert("a", 490), 3_000)?.cameraKey)
    }

    /** 먼 후보의 1~2m GPS 흔들림은 걸러도 연속된 실제 거리 감소는 몇 초 안에 놓치지 않는다. */
    @Test fun smallProgressCanAccumulateAcrossFixes() {
        val tracker = CameraApproachTracker()
        assertNull(tracker.observe(alert("a", 500), 1_000))
        assertNull(tracker.observe(alert("a", 499), 1_500))
        assertNull(tracker.observe(alert("a", 498), 2_000))
        assertEquals("a", tracker.observe(alert("a", 497), 2_500)?.cameraKey)
    }

    /** 가까운 카메라는 가시 범위를 벗어나기 전에 1m의 전방 이동으로도 확인한다. */
    @Test fun closeCameraConfirmsBeforePassing() {
        val tracker = CameraApproachTracker()
        assertNull(tracker.observe(alert("a", 12), 1_000))
        assertEquals("a", tracker.observe(alert("a", 11), 1_500)?.cameraKey)
    }

    /** 다른 카메라로 옮겨갈 땐 다시 접근을 확인하되 기존 승인은 한 번의 GPS 거리 증가 뒤에도 유지한다. */
    @Test fun acceptedCameraAndSwitchAreIndependent() {
        val tracker = CameraApproachTracker()
        assertNull(tracker.observe(alert("a", 400), 1_000))
        assertEquals("a", tracker.observe(alert("a", 390), 1_500)?.cameraKey)
        assertEquals("a", tracker.observe(alert("a", 394), 2_000)?.cameraKey)
        assertNull(tracker.observe(alert("b", 350), 2_500))
        assertEquals("a", tracker.observe(alert("a", 388), 3_000)?.cameraKey)
        assertNull(tracker.observe(alert("b", 340), 3_500))
        assertEquals("b", tracker.observe(alert("b", 330), 4_000)?.cameraKey)
    }

    /** 초기화 후에는 이전 카메라 승인과 확인 중의 거리 관측을 재사용하지 않는다. */
    @Test fun resetDiscardsAcceptedAndPendingEvidence() {
        val tracker = CameraApproachTracker()
        assertNull(tracker.observe(alert("a", 400), 1_000))
        assertEquals("a", tracker.observe(alert("a", 390), 1_500)?.cameraKey)
        assertNull(tracker.observe(alert("b", 300), 2_000))
        tracker.reset()
        assertNull(tracker.observe(alert("a", 380), 2_500))
        assertEquals("a", tracker.observe(alert("a", 370), 3_000)?.cameraKey)
        assertNull(tracker.observe(alert("b", 290), 3_500))
    }

    /** 거리 정보가 없으면 시간만 흘렀다고 카메라 후보를 승격하지 않는다. */
    @Test fun unknownDistanceNeedsMeasuredApproach() {
        val tracker = CameraApproachTracker()
        assertNull(tracker.observe(alert("a", null), 1_000))
        assertNull(tracker.observe(alert("a", null), 2_000))
        assertNull(tracker.observe(alert("a", 500), 3_000))
        assertEquals("a", tracker.observe(alert("a", 490), 4_000)?.cameraKey)
    }

    /** 키·남은 거리만 바꿔 같은 종류의 카메라 관측을 만든다. */
    private fun alert(key: String, distance: Int?) = SafetyAlert(
        kind = SafetyKind.SPEED_CAMERA,
        distanceMeters = distance,
        speedLimitKph = 60,
        cameraKey = key,
    )
}
