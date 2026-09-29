package com.wemade.teslamacro.data.settings

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import com.wemade.teslamacro.data.backup.toBackup

class VehicleAudioSelectionTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    // 1. 같은 차량에서는 선택을 유지하고 VIN 교체·등록 해제 때 이전 오디오 주소를 지운다.
    @Test fun selectedAudioBelongsOnlyToCurrentVin() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(temporaryFolder.root, "audio.preferences_pb")
        }
        val store = SettingsStore(Application(), preferences)
        store.setVin("5YJS0000000000000")
        store.setVehicleName("Tesla One")
        store.setVehicleAudioAddress("AA:BB:CC:DD:EE:FF")
        assertEquals("AA:BB:CC:DD:EE:FF", SettingsStore(Application(), preferences).settings.first().vehicleAudioAddress)
        store.setVin("5YJS0000000000000")
        assertEquals("AA:BB:CC:DD:EE:FF", store.settings.first().vehicleAudioAddress)
        store.setEnrolled(true)
        store.setVehicleAddress("11:22:33:44:55:66")
        assertFalse(store.settings.first().toBackup().toString().contains("AA:BB:CC:DD:EE:FF"))
        store.setVin("test-vin")
        assertEquals("", store.settings.first().vehicleAudioAddress)
        assertEquals("", store.settings.first().vehicleName)
        assertFalse(store.settings.first().isEnrolled)
        assertEquals("", store.settings.first().vehicleAddress)
        store.setVehicleAudioAddress("AA:BB:CC:DD:EE:FF")
        store.setVin("")
        assertEquals("", store.settings.first().vehicleAudioAddress)
        assertEquals("", store.settings.first().vehicleName)
    }

    /** 같은 차량 재연결은 승인을 유지하고 다른 차량 연결은 주소와 승인 상태를 함께 교체한다. */
    @Test fun directConnectionPersistsVerifiedAddressWithoutOldVehicleApproval() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(temporaryFolder.root, "direct.preferences_pb")
        }
        val store = SettingsStore(Application(), preferences)
        store.setConnectedVehicle("VIN-A", "11:22:33:44:55:66", "Tesla A")
        store.setEnrolled(true)
        store.setVehicleAudioAddress("AA:BB:CC:DD:EE:FF")
        store.setConnectedVehicle("VIN-A", "11:22:33:44:55:77")
        assertTrue(store.settings.first().isEnrolled)
        assertEquals("Tesla A", store.settings.first().vehicleName)
        assertEquals("AA:BB:CC:DD:EE:FF", store.settings.first().vehicleAudioAddress)

        store.setConnectedVehicle("VIN-B", "22:33:44:55:66:77")
        val switched = store.settings.first()
        assertEquals("VIN-B", switched.vin)
        assertEquals("22:33:44:55:66:77", switched.vehicleAddress)
        assertFalse(switched.isEnrolled)
        assertEquals("", switched.vehicleName)
        assertEquals("", switched.vehicleAudioAddress)
    }
}
