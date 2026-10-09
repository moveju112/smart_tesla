package com.wemade.teslamacro.feature.settings

import org.junit.Assert.*
import org.junit.Test

class FeaturePermissionGateTest {
    /** 권한 일부가 빠지면 차단하고 해당 기능의 필수 권한을 모두 갖추면 허용한다. */
    @Test fun `every feature requires every relevant permission`() {
        PermissionFeature.entries.forEach { feature ->
            val required = requiredFeaturePermissions(feature, 34)
            assertTrue(missingFeaturePermissions(feature, 34, required, true).isEmpty())
            required.forEach { permission ->
                assertEquals(listOf(permission.label), missingFeaturePermissions(feature, 34,
                    PermissionCheck.entries.toSet() - permission, true))
            }
        }
    }

    /** 차량 좌표 기록에 휴대폰 GPS를 요구하지 않고 HUD에는 정확한 위치와 오버레이를 요구한다. */
    @Test fun `permissions follow actual feature dependencies`() {
        assertEquals(setOf(PermissionCheck.BLUETOOTH, PermissionCheck.NOTIFICATIONS),
            requiredFeaturePermissions(PermissionFeature.HISTORY, 34))
        assertEquals(setOf(PermissionCheck.LOCATION, PermissionCheck.OVERLAY, PermissionCheck.NOTIFICATIONS),
            requiredFeaturePermissions(PermissionFeature.HUD, 34))
        assertEquals(setOf(PermissionCheck.LISTENER, PermissionCheck.ACCESSIBILITY, PermissionCheck.OVERLAY, PermissionCheck.NOTIFICATIONS),
            requiredFeaturePermissions(PermissionFeature.TESLA_SHARE, 34))
        PermissionFeature.entries.filter { it != PermissionFeature.UPDATE }.forEach { feature ->
            assertFalse(PermissionCheck.INSTALL in requiredFeaturePermissions(feature, 34))
            assertFalse(PermissionCheck.BATTERY in requiredFeaturePermissions(feature, 34))
        }
        assertEquals(setOf(PermissionCheck.INSTALL), requiredFeaturePermissions(PermissionFeature.UPDATE, 34))
    }

    /** Android 12 미만 차량 BLE는 위치 권한도 필요하며 ADB 인증은 일반 권한과 별개다. */
    @Test fun `legacy BLE location and ADB availability remain independent`() {
        assertTrue(PermissionCheck.LOCATION in requiredFeaturePermissions(PermissionFeature.MACROS, 30))
        assertFalse(PermissionCheck.LOCATION in requiredFeaturePermissions(PermissionFeature.MACROS, 31))
        assertEquals(listOf("무선 ADB 연결 준비"), missingFeaturePermissions(PermissionFeature.WIRELESS,
            34, PermissionCheck.entries.toSet(), false))
        assertTrue(missingFeaturePermissions(PermissionFeature.TESLA_SHARE, 34,
            PermissionCheck.entries.toSet(), false).isEmpty())
    }
}
