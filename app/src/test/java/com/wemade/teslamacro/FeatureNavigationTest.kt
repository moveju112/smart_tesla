package com.wemade.teslamacro

import com.wemade.teslamacro.data.settings.AppSettings
import com.wemade.teslamacro.data.settings.DeviceMode
import com.wemade.teslamacro.feature.features.AppFeature
import com.wemade.teslamacro.feature.features.FeatureSettings
import com.wemade.teslamacro.feature.features.requiredFeatureSettings
import com.wemade.teslamacro.ui.nav.Destination
import org.junit.Assert.*
import org.junit.Test

class FeatureNavigationTest {
    /** 미등록 상태에서도 매크로 작성과 목적지의 자체 연결 절차에 접근할 수 있다. */
    @Test fun independentFeaturesDoNotRequireVehicleEnrollment() {
        for (feature in listOf(AppFeature.MACROS, AppFeature.DESTINATION)) {
            assertNull(requiredFeatureSettings(feature, AppSettings(), false, false, false))
        }
        assertEquals(listOf(Destination.Features, Destination.Settings), Destination.visible)
    }

    /** VIN 저장만으로 차량 제어 기능이 준비됐다고 판단하지 않는다. */
    @Test fun vehicleFeaturesRequireCompletedEnrollment() {
        for (feature in listOf(AppFeature.STEALTH_CHARGE, AppFeature.SMARTTHINGS)) {
            assertEquals(FeatureSettings.VEHICLE, requiredFeatureSettings(feature,
                AppSettings(vin = "5YJS0000000000000"), true, true, true))
        }
        assertNull(requiredFeatureSettings(AppFeature.STEALTH_CHARGE,
            AppSettings(vin = "5YJS0000000000000", isEnrolled = true), false, false, false))
    }

    /** 제거된 안내는 기능·설정 목록으로 돌아오지 않는다. */
    @Test fun removedCameraFeatureIsAbsent() {
        assertFalse(AppFeature.entries.any { it.name == "SAFE_DRIVE" })
        assertFalse(FeatureSettings.entries.any { it.name == "SAFE_DRIVE" })
    }

    /** 명령 문구 설정은 유지하고 권한 부족은 ON 모달에서 처리한다. */
    @Test fun notificationPermissionsDoNotRedirectBeforeToggle() {
        val ready = AppSettings(vin = "5YJS0000000000000", isEnrolled = true)
        assertNull(requiredFeatureSettings(AppFeature.SMARTTHINGS, ready, true, true, false))
        assertEquals(FeatureSettings.SMARTTHINGS, requiredFeatureSettings(AppFeature.SMARTTHINGS,
            ready.copy(smartThingsCommandTexts = emptyMap()), true, true, true))
        assertNull(requiredFeatureSettings(AppFeature.SMARTTHINGS, ready, true, true, true))
    }
    /** 미조회·통신 오류를 연결 부족으로 오해하지 않고 발신·수신 준비를 구별한다. */
    @Test fun destinationSetupWaitsForConfirmedConnectionState() {
        val unknown = com.wemade.teslamacro.feature.destination.DestinationUiState()
        val checked = unknown.copy(connectionChecked = true)
        assertFalse(com.wemade.teslamacro.feature.destination.needsDestinationSetup(unknown))
        assertTrue(com.wemade.teslamacro.feature.destination.needsDestinationSetup(checked))
        assertTrue(com.wemade.teslamacro.feature.destination.needsDestinationSetup(checked.copy(connectionError = "연결 실패")))
        assertFalse(com.wemade.teslamacro.feature.destination.needsDestinationSetup(checked.copy(receiverName = "차량 태블릿")))
        assertTrue(com.wemade.teslamacro.feature.destination.needsDestinationSetup(checked.copy(receiving = true)))
        assertTrue(com.wemade.teslamacro.feature.destination.needsDestinationSetup(checked.copy(receiving = false)))
    }

    /** 미연결·통신 실패·처리 중을 차단하고 기기 역할별 설정만 허용한다. */
    @org.junit.Test fun destinationSettingsFollowConfirmedRole() {
        val state = com.wemade.teslamacro.feature.destination.DestinationUiState(connectionChecked = true)
        assertTrue(state.canConfigure)
        assertFalse(state.canSend)
        assertFalse(state.canReceive)
        val sender = state.copy(receiverName = "차량 태블릿")
        assertTrue(sender.connected)
        assertTrue(sender.canSend)
        assertFalse(sender.canReceive)
        val receiver = state.copy(senderCount = 1)
        assertTrue(receiver.connected)
        assertTrue(receiver.canReceive)
        assertFalse(receiver.canSend)
        assertFalse(sender.copy(busy = true).canSend)
        assertFalse(receiver.copy(connectionError = "오프라인").canReceive)
        assertFalse(sender.copy(connectionChecked = false).canConfigure)
    }
    /** 첫 사용과 명시적 해제는 서버 확인 없이 설정을 조작할 수 있다. */
    @Test fun firstDestinationSetupDoesNotWaitForServer() {
        val fresh = com.wemade.teslamacro.feature.destination.DestinationUiState(setupStarted = false)
        assertTrue(com.wemade.teslamacro.feature.destination.needsDestinationSetup(fresh))
        assertTrue(fresh.canConfigure)
        assertFalse(fresh.canSend)
        assertFalse(fresh.canReceive)
        assertFalse(fresh.copy(busy = true).canConfigure)
        val configured = fresh.copy(setupStarted = true)
        assertFalse(com.wemade.teslamacro.feature.destination.needsDestinationSetup(configured))
        assertFalse(configured.canConfigure)
        assertFalse(com.wemade.teslamacro.feature.destination.needsDestinationSetup(configured.copy(connectionError = "오프라인")))
    }

}
