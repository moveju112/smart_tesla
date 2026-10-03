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

    /** 위치 권한은 필수이고 활동 인식은 거치 모드의 소리 사용에만 필요하다. */
    @Test fun safetySettingsRespectDeviceAndSoundRequirements() {
        assertEquals(FeatureSettings.SAFE_DRIVE, requiredFeatureSettings(AppFeature.SAFE_DRIVE,
            AppSettings(), false, true, true))
        assertNull(requiredFeatureSettings(AppFeature.SAFE_DRIVE,
            AppSettings(deviceMode = DeviceMode.PORTABLE, safeDriveSound = true), true, false, false))
        assertEquals(FeatureSettings.SAFE_DRIVE, requiredFeatureSettings(AppFeature.SAFE_DRIVE,
            AppSettings(deviceMode = DeviceMode.MOUNTED, safeDriveSound = true), true, false, true))
        assertNull(requiredFeatureSettings(AppFeature.SAFE_DRIVE,
            AppSettings(deviceMode = DeviceMode.MOUNTED, safeDriveSound = false), true, false, true))
    }

    /** 알림 접근 권한과 명령 문구가 준비돼야 스마트싱스를 켤 수 있다. */
    @Test fun notificationFeaturesRequireAccessAndCommands() {
        val ready = AppSettings(vin = "5YJS0000000000000", isEnrolled = true)
        assertEquals(FeatureSettings.SMARTTHINGS, requiredFeatureSettings(AppFeature.SMARTTHINGS,
            ready, true, true, false))
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
        assertFalse(com.wemade.teslamacro.feature.destination.needsDestinationSetup(checked.copy(connectionError = "연결 실패")))
        assertFalse(com.wemade.teslamacro.feature.destination.needsDestinationSetup(checked.copy(receiverName = "차량 태블릿")))
        assertFalse(com.wemade.teslamacro.feature.destination.needsDestinationSetup(checked.copy(receiving = true)))
        assertTrue(com.wemade.teslamacro.feature.destination.needsDestinationSetup(checked.copy(receiving = false)))
    }

}
