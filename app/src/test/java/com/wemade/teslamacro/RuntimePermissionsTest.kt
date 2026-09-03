package com.wemade.teslamacro

import android.Manifest
import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePermissionsTest {

    /** Android 12+ BLE 스캔은 테파일럿과 같은 네 권한을 함께 요청해야 한다 */
    @Test
    fun `android 12는 BLE와 위치 권한을 함께 요청한다`() {
        val permissions = runtimePermissionsFor(Build.VERSION_CODES.S)

        assertTrue(Manifest.permission.BLUETOOTH_SCAN in permissions)
        assertTrue(Manifest.permission.BLUETOOTH_CONNECT in permissions)
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in permissions)
        assertTrue(Manifest.permission.ACCESS_COARSE_LOCATION in permissions)
    }

    /** Android 11 이하는 새 BLE 권한 없이 위치 권한으로 스캔한다 */
    @Test
    fun `android 11은 위치 권한만 요청한다`() {
        val permissions = runtimePermissionsFor(Build.VERSION_CODES.R)

        assertFalse(Manifest.permission.BLUETOOTH_SCAN in permissions)
        assertFalse(Manifest.permission.BLUETOOTH_CONNECT in permissions)
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in permissions)
        assertTrue(Manifest.permission.ACCESS_COARSE_LOCATION in permissions)
    }
}
