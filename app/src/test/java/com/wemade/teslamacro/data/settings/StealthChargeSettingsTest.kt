package com.wemade.teslamacro.data.settings

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.paparazzi.Paparazzi
import com.wemade.teslamacro.data.backup.BackupSettings
import com.wemade.teslamacro.data.charge.StealthChargeAction
import com.wemade.teslamacro.data.charge.stealthChargeAction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 스텔스 충전 최대 전류의 기본값·저장·안전 범위를 검증한다. */
class StealthChargeSettingsTest {
    @get:Rule val paparazzi = Paparazzi()
    @get:Rule val temporaryFolder = TemporaryFolder()

    /** 저장값을 5~48A로 제한하고 새 Store에서도 그대로 복원한다. */
    @Test
    fun `최대 전류를 안전 범위로 저장한다`() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(temporaryFolder.root, "stealth-charge.preferences_pb")
        }
        val store = SettingsStore(ContextWrapper(paparazzi.context), preferences)

        assertEquals(48, store.settings.first().stealthMaxAmps)
        store.setStealthMaxAmps(3)
        assertEquals(5, store.settings.first().stealthMaxAmps)
        store.setStealthMaxAmps(60)
        assertEquals(
            48,
            SettingsStore(ContextWrapper(paparazzi.context), preferences)
                .settings.first().stealthMaxAmps,
        )
    }

    /** 진행 중인 세션에 매크로가 "켜기"를 또 넣어도 원래 전류를 잊지 않는다. */
    @Test
    fun `진행 중 다시 켜도 원래 전류를 지키고 새 세션은 흔적을 비운다`() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(temporaryFolder.root, "stealth-charge-restart.preferences_pb")
        }
        val store = SettingsStore(ContextWrapper(paparazzi.context), preferences)

        store.setStealthCharging(true)
        store.beginStealthCharge(16)
        store.setStealthChargeModified(true)

        store.setStealthCharging(true)
        assertEquals(16, store.settings.first().stealthChargeOriginalAmps)

        // 종료 뒤 새로 켜는 것은 이전 흔적을 비운다
        store.completeStealthCharge()
        store.setStealthCharging(true)
        assertNull(store.settings.first().stealthChargeOriginalAmps)
    }

    /** 백업 복원도 진행 중인 전류 변경을 원복한 뒤 종료하도록 기준 전류를 보존한다. */
    @Test
    fun `백업 복원은 실행을 끄되 기존 전류 복구를 유지한다`() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(temporaryFolder.root, "stealth-charge-backup.preferences_pb")
        }
        val store = SettingsStore(ContextWrapper(paparazzi.context), preferences)
        store.setStealthCharging(true)
        store.beginStealthCharge(16)
        store.setStealthChargeModified(true)

        store.restore(BackupSettings(stealthCharging = true))

        val restored = store.settings.first()
        assertFalse(restored.stealthCharging)
        assertTrue(restored.stealthChargeStarted)
        assertTrue(restored.stealthChargeModified)
        assertEquals(16, restored.stealthChargeOriginalAmps)
        assertEquals(
            StealthChargeAction.RESTORE_DISABLED,
            stealthChargeAction(restored.stealthCharging, restored.stealthChargeStarted,
                restored.stealthChargeModified, linked = true, isCharging = true),
        )
        store.completeStealthCharge()
        assertNull(store.settings.first().stealthChargeOriginalAmps)
    }

    /** 전류를 바꾸지 않은 상태는 복원 시 지우고 백업의 1회 실행 예약도 시작하지 않는다. */
    @Test
    fun `백업 복원은 전류 변경이 없는 진행 상태를 비운다`() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(temporaryFolder.root, "stealth-charge-backup-unmodified.preferences_pb")
        }
        val store = SettingsStore(ContextWrapper(paparazzi.context), preferences)
        store.setStealthCharging(true)
        store.beginStealthCharge(16)

        store.restore(BackupSettings(stealthCharging = true))

        val restored = store.settings.first()
        assertFalse(restored.stealthCharging)
        assertFalse(restored.stealthChargeStarted)
        assertFalse(restored.stealthChargeModified)
        assertNull(restored.stealthChargeOriginalAmps)
    }

    /** 원복 전 재활성화는 낮아진 관측 전류를 새 기준으로 덮어쓰지 않아야 한다. */
    @Test
    fun `끄고 즉시 다시 켜도 복구 대기 중인 원래 전류를 지킨다`() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(temporaryFolder.root, "stealth-charge-toggle.preferences_pb")
        }
        val store = SettingsStore(ContextWrapper(paparazzi.context), preferences)
        store.setStealthCharging(true)
        store.beginStealthCharge(16)
        store.setStealthChargeModified(true)

        store.setStealthCharging(false)
        store.setStealthCharging(true)
        store.beginStealthCharge(5)

        val resumed = store.settings.first()
        assertTrue(resumed.stealthCharging)
        assertTrue(resumed.stealthChargeStarted)
        assertTrue(resumed.stealthChargeModified)
        assertEquals(16, resumed.stealthChargeOriginalAmps)
    }

    /** 같은 차량 재저장은 진행 상태를 유지하고 등록 해제는 예약과 원복 정보를 함께 비운다. */
    @Test
    fun `동일 차량은 전류 복구를 유지하고 등록 해제는 진행 상태를 비운다`() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(temporaryFolder.root, "stealth-charge-vehicle.preferences_pb")
        }
        val store = SettingsStore(ContextWrapper(paparazzi.context), preferences)
        store.setVin("5YJS0000000000000")
        store.setStealthCharging(true)
        store.beginStealthCharge(16)
        store.setStealthChargeModified(true)

        store.setVin("5YJS0000000000000")

        val sameVehicle = store.settings.first()
        assertTrue(sameVehicle.stealthCharging)
        assertTrue(sameVehicle.stealthChargeStarted)
        assertTrue(sameVehicle.stealthChargeModified)
        assertEquals(16, sameVehicle.stealthChargeOriginalAmps)

        store.setVin("")

        val unpaired = store.settings.first()
        assertFalse(unpaired.stealthCharging)
        assertFalse(unpaired.stealthChargeStarted)
        assertFalse(unpaired.stealthChargeModified)
        assertNull(unpaired.stealthChargeOriginalAmps)
    }

    /** 차량을 새로 연결할 때 미등록 상태의 이전 예약과 복구 전류를 가져가지 않는다. */
    @Test
    fun `새 차량 연결은 이전 전류 복구 상태를 가져가지 않는다`() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(temporaryFolder.root, "stealth-charge-new-vehicle.preferences_pb")
        }
        val store = SettingsStore(ContextWrapper(paparazzi.context), preferences)
        store.setStealthCharging(true)
        store.beginStealthCharge(16)
        store.setStealthChargeModified(true)

        store.setConnectedVehicle("5YJS0000000000000", "AA:BB:CC:11:22:33")

        val connected = store.settings.first()
        assertFalse(connected.stealthCharging)
        assertFalse(connected.stealthChargeStarted)
        assertFalse(connected.stealthChargeModified)
        assertNull(connected.stealthChargeOriginalAmps)
    }

    /** 하한은 기본이 자동(null)이고, 상한을 내리면 하한도 함께 내려간다. */
    @Test
    fun `최소 전류는 자동이 기본이고 상한을 넘지 않는다`() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(temporaryFolder.root, "stealth-charge-min.preferences_pb")
        }
        val store = SettingsStore(ContextWrapper(paparazzi.context), preferences)

        assertNull(store.settings.first().stealthMinAmps)
        store.setStealthMinAmps(3)
        assertEquals(5, store.settings.first().stealthMinAmps)
        store.setStealthMinAmps(40)
        assertEquals(40, store.settings.first().stealthMinAmps)
        store.setStealthMaxAmps(16)
        assertEquals(16, store.settings.first().stealthMinAmps)
        store.setStealthMinAmps(null)
        assertNull(
            SettingsStore(ContextWrapper(paparazzi.context), preferences)
                .settings.first().stealthMinAmps,
        )
    }
}
