package com.wemade.teslamacro.data.safety

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveAlertGateTest {
    // 아무 근거 없는 보행 속도·정지/미판정으로 주행 안내가 열리지 않는다.
    @Test fun unknownAndWalkingAreSilent() {
        val gate = DriveAlertGate()
        assertFalse(gate.mayAlert(1_000))
        gate.observeActivity(DriveAlertGate.Activity.OTHER, 90, 1_000, 1_000)
        assertFalse(gate.mayAlert(1_000))
        gate.observeActivity(DriveAlertGate.Activity.ON_FOOT, 85, 2_000, 2_000)
        assertFalse(gate.mayAlert(2_000))
    }

    // 기본 탑승 자동 감시가 꺼져도 신선한 차량 이동 인식은 주행을 열고 보행은 즉시 닫는다.
    @Test fun vehicleMotionSurvivesBleDisconnectUntilWalking() {
        val gate = DriveAlertGate()
        gate.observeActivity(DriveAlertGate.Activity.IN_VEHICLE, 80, 10_000, 10_000)
        assertTrue(gate.mayAlert(10_000))
        gate.observeActivity(DriveAlertGate.Activity.OTHER, 85, 30_000, 30_000)
        assertTrue(gate.mayAlert(30_000))
        gate.observeActivity(DriveAlertGate.Activity.ON_FOOT, 85, 31_000, 31_000)
        assertFalse(gate.mayAlert(31_000))
        gate.observeActivity(DriveAlertGate.Activity.IN_VEHICLE, 90, 30_000, 31_001)
        assertFalse(gate.mayAlert(31_001))
        gate.observeActivity(DriveAlertGate.Activity.IN_VEHICLE, 90, 32_000, 32_000)
        assertTrue(gate.mayAlert(32_000))
    }

    // 실제 차의 탑승 응답을 잠시 이어받지만 음성은 최신 활동 결과가 있을 때만 낸다.
    @Test fun freshPresenceRequiresActivityAndExpires() {
        val gate = DriveAlertGate()
        gate.observePresence(true, 1_000)
        assertFalse(gate.mayAlert(1_000))
        gate.observeActivity(DriveAlertGate.Activity.OTHER, 50, 2_000, 2_000)
        assertTrue(gate.mayAlert(2_000))
        assertTrue(gate.mayAlert(122_000))
        assertFalse(gate.mayAlert(122_001))
        gate.observeActivity(DriveAlertGate.Activity.OTHER, 50, 130_000, 130_000)
        assertTrue(gate.mayAlert(130_000))
        assertFalse(gate.mayAlert(181_001))
        gate.observePresence(false, 181_002)
        assertFalse(gate.mayAlert(181_002))
    }

    // 지연·저신뢰·권한 재설정의 과거 결과는 소리 허가를 복구하지 않는다.
    @Test fun staleUncertainAndClearedEvidenceAreSilent() {
        val gate = DriveAlertGate()
        gate.observeActivity(DriveAlertGate.Activity.IN_VEHICLE, 69, 1_000, 1_000)
        assertFalse(gate.mayAlert(1_000))
        gate.observeActivity(DriveAlertGate.Activity.IN_VEHICLE, 90, 2_000, 122_001)
        assertFalse(gate.mayAlert(122_001))
        gate.observeActivity(DriveAlertGate.Activity.IN_VEHICLE, 90, 123_000, 123_000)
        assertTrue(gate.mayAlert(123_000))
        gate.clear()
        assertFalse(gate.mayAlert(123_000))
    }
}
