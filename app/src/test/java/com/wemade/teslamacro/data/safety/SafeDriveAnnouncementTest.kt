package com.wemade.teslamacro.data.safety

import com.wemade.teslamacro.domain.safety.SafetyKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeDriveAnnouncementTest {
    /** 일반 카메라가 여러 개 이어져도 실제 구간단속으로 오해할 표현을 쓰지 않는다. */
    @Test fun repeatedCamerasAreNotCalledSectionEnforcement() {
        val text = cameraAnnouncement(SafetyKind.SPEED_CAMERA, 500, 80, CameraSequence.CONTINUOUS)
        assertTrue(text.startsWith("단속카메라가 연이어 있습니다."))
        assertTrue(text.contains("단속카메라입니다."))
        assertFalse(text.contains("연속 단속 구간"))
    }

    /** 구간단속 자료는 시작·종점 짝이 없으므로 평균속도 구간 전체가 아니라 지점으로 읽는다. */
    @Test fun sectionCameraIsAnnouncedAsPoint() {
        val text = cameraAnnouncement(SafetyKind.SECTION_CAMERA, 500, 80, CameraSequence.SINGLE)
        assertTrue(text.contains("구간단속 지점입니다."))
        assertFalse(text.contains("구간단속 구간"))
    }
}
