package com.wemade.teslamacro.data.charge

import android.content.ContextWrapper
import app.cash.paparazzi.Paparazzi
import com.wemade.teslamacro.domain.model.StateCategory
import com.wemade.teslamacro.domain.model.VehicleSnapshot
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 총전력과 충전 미확인 구간을 실제 기록 저장소로 검증한다. */
class ChargeHistoryStoreTest {
    @get:Rule val paparazzi = Paparazzi()
    @get:Rule val temporary = TemporaryFolder()

    // 3상 차량의 보고 전력을 단상 V×A로 바꾸지 않는다.
    @Test fun reportedPowerIsUsedRegardlessOfVoltageAndAmperage() = runTest {
        val store = store()
        store.record(sample(1_000, 11), 1_000)
        store.record(sample(16_000, 11), 16_000)
        val bucket = store.buckets.value.single()
        assertEquals(11.0, bucket.averageKilowatts, 0.001)
        assertEquals(11_000.0 * 15 / 3600, bucket.energyWattHours, 0.001)
    }

    // 전력이 누락되거나 정수 0으로 보고되면 전압·전류로 임의 복원하지 않는다.
    @Test fun missingAndZeroPowerAreNotEstimatedFromCurrent() = runTest {
        val store = store()
        store.record(sample(1_000, null), 1_000)
        store.record(sample(6_000, 0), 6_000)
        store.record(sample(11_000, 0), 11_000)
        assertEquals(0.0, store.buckets.value.single().averageKilowatts, 0.0)
        assertEquals(16.0, store.buckets.value.single().averageAmps, 0.0)
    }

    // CHARGE를 읽었지만 충전 여부를 모르면 그 구간을 이전 전력으로 채우지 않는다.
    @Test fun unknownChargingStateBreaksIntegration() = runTest {
        val store = store()
        store.record(sample(1_000, 11), 1_000)
        store.record(sample(6_000, null).copy(isCharging = null), 6_000)
        store.record(sample(11_000, 11), 11_000)
        store.record(sample(16_000, 11), 16_000)
        assertEquals(5_000L, store.buckets.value.single().coveredMillis)
    }

    // 테스트가 만든 파일만 임시 폴더 안에 남긴다.
    private fun store(): ChargeHistoryStore = ChargeHistoryStore(object : ContextWrapper(paparazzi.context) {
        // 실제 앱의 충전 기록 파일을 사용하지 않는다.
        override fun getFilesDir(): File = temporary.root
    })

    // 전류·전압은 같은 값으로 두고 차량 보고 전력만 바꾼다.
    private fun sample(time: Long, power: Int?) = VehicleSnapshot(
        timestampMillis = time, isCharging = true, chargingAmps = 16, actualChargingAmps = 16,
        chargerVoltage = 230, chargerPowerKw = power, categoryReadAt = mapOf(StateCategory.CHARGE to time))
}
