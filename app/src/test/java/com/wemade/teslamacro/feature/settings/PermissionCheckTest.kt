package com.wemade.teslamacro.feature.settings

import android.Manifest
import org.junit.Assert.*
import org.junit.Test

class PermissionCheckTest {
    /** Android 12부터 블루투스 승인을 요청하고 알림은 13부터만 요청한다. */
    @Test fun `일괄 요청은 OS에서 지원하는 권한만 포함한다`() {
        val old = permissionCheckRequests(26, emptySet())
        assertFalse(Manifest.permission.BLUETOOTH_SCAN in old)
        assertFalse(Manifest.permission.POST_NOTIFICATIONS in old)
        assertFalse(Manifest.permission.ACTIVITY_RECOGNITION in old)
        val current = permissionCheckRequests(33, emptySet())
        assertTrue(Manifest.permission.BLUETOOTH_SCAN in current)
        assertTrue(Manifest.permission.BLUETOOTH_CONNECT in current)
        assertTrue(Manifest.permission.ACTIVITY_RECOGNITION in current)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in current)
    }

    /** 대략 위치만 허용한 상태에서도 정확한 위치를 유효한 묶음으로 재요청한다. */
    @Test fun `위치 정밀도 변경은 이미 허용된 대략 위치도 함께 요청한다`() {
        val requests = permissionCheckRequests(33, setOf(Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in requests)
        assertTrue(Manifest.permission.ACCESS_COARSE_LOCATION in requests)
        assertFalse(Manifest.permission.BLUETOOTH_SCAN in requests)
    }

    /** 전부 허용됐을 때 재요청해 사용자의 설정을 다시 건드리지 않는다. */
    @Test fun `허용 완료 상태에서는 런타임 요청이 비어 있다`() {
        val all = permissionCheckRequests(33, emptySet()).toSet()
        assertTrue(permissionCheckRequests(33, all).isEmpty())
        assertFalse(Manifest.permission.SYSTEM_ALERT_WINDOW in all)
    }
}
