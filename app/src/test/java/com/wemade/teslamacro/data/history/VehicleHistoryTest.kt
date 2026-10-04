package com.wemade.teslamacro.data.history

import com.wemade.teslamacro.domain.model.VehicleSnapshot
import com.wemade.teslamacro.domain.model.StateCategory
import com.wemade.teslamacro.domain.model.ShiftState
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class VehicleHistoryTest {
    /** 압축은 null·소수 원문·회생 부호를 바꾸지 않아야 한다. */
    @Test fun `five second samples round trip without numeric loss`() {
        val samples = (0 until 60).map { index -> fixture(index) }
        val encoded = VehicleHistory.encode(samples)
        assertEquals(samples, VehicleHistory.decode(encoded))
        val raw = VehicleHistory.json.encodeToString(samples).toByteArray().size
        assertTrue("gzip should reduce repeated fields", encoded.size < raw / 2)
        println("HISTORY_COMPRESSION samples=${samples.size} jsonBytes=$raw gzipBytes=${encoded.size}")
    }

    /** 손상된 데이터로 성공처럼 보이는 빈 기록을 만들지 않는다. */
    @Test(expected = java.io.IOException::class) fun `corrupt compressed block fails`() {
        VehicleHistory.decode(byteArrayOf(1, 2, 3))
    }

    /** 차량이 생략한 값과 정상적인 0을 구분한다. */
    @Test fun `missing fields remain null and nonfinite values are excluded`() {
        val sample = VehicleHistory.sample(VehicleSnapshot(10, batteryLevelPercent = 0,
            vehicleLatitude = Double.NaN, outsideTempC = Double.POSITIVE_INFINITY), 10)
        assertEquals(0, sample.batteryPercent)
        assertNull(sample.powerKw)
        assertNull(sample.latitude)
        assertNull(sample.outsideTempC)
        assertEquals(sample, VehicleHistory.decode(VehicleHistory.encode(listOf(sample))).single())
    }

    /** 배터리가 일시 반등해도 하락분만 누적하지 않고 시작·끝 차이를 쓴다. */
    @Test fun `distance and SOC efficiency use endpoints`() {
        val first = fixture(0).copy(odometer = 10_000, batteryPercent = 80)
        val second = fixture(1).copy(odometer = 10_050, batteryPercent = 79)
        val third = fixture(2).copy(odometer = 10_100, batteryPercent = 80)
        val last = fixture(3).copy(odometer = 10_200, batteryPercent = 78)
        var summary = session()
        var previous: HistorySample? = null
        for (sample in listOf(first, second, third, last)) {
            summary = VehicleHistory.append(summary, previous, sample)
            previous = sample
        }
        assertEquals(2, summary.batteryUsedPercent)
        assertEquals(3.218688, summary.distanceKm!!, 0.0000001)
        assertEquals(1.609344, summary.kilometersPerPercent!!, 0.0000001)
    }

    /** 배터리가 그대로인 짧은 주행에 무한 전비를 표시하지 않는다. */
    @Test fun `zero SOC change has no efficiency`() {
        assertNull(session().copy(firstBattery = 80, lastBattery = 80).kilometersPerPercent)
    }

    /** 연결 공백과 시간 역행 사이에는 에너지를 적산하지 않는다. */
    @Test fun `power integration excludes gaps and preserves regeneration`() {
        val first = fixture(0).copy(powerKw = 36)
        val second = fixture(1).copy(powerKw = -12)
        var summary = VehicleHistory.append(session(), null, first)
        summary = VehicleHistory.append(summary, first, second)
        assertEquals(12.0 * 5 / 3600, summary.estimatedDriveKwh, 0.000001)
        assertEquals(5_000, summary.powerCoveredMillis)
        val gap = second.copy(time = second.time + 20_000)
        val afterGap = VehicleHistory.append(summary, second, gap)
        assertEquals(summary.estimatedDriveKwh, afterGap.estimatedDriveKwh, 0.0)
        assertFalse(VehicleHistory.contiguous(second, first))
    }

    /** 충전 초기화나 누적거리 역행은 잘못된 차분 대신 미확인으로 표시한다. */
    @Test fun `reset counters invalidate totals`() {
        var summary = session().copy(kind = HistoryKind.CHARGE)
        val first = fixture(0).copy(chargeAddedKwh = 8f, odometer = 100)
        summary = VehicleHistory.append(summary, null, first)
        summary = VehicleHistory.append(summary, first, fixture(1).copy(chargeAddedKwh = 1f, odometer = 90))
        assertNull(summary.chargedKwh)
        assertNull(summary.distanceKm)
    }

    /** 충전 세션의 시작 때 이미 들어간 양을 이번 관측 충전량에 포함하지 않는다. */
    @Test fun `charge energy is observed difference`() {
        val first = fixture(0).copy(chargeAddedKwh = 3.5f)
        var summary = VehicleHistory.append(session().copy(kind = HistoryKind.CHARGE), null, first)
        summary = VehicleHistory.append(summary, first, fixture(1).copy(chargeAddedKwh = 9.5f))
        assertEquals(6.0, summary.chargedKwh!!, 0.0)
    }

    /** 주차·충전·주행 전환은 실제 신호를 우선한다. */
    @Test fun `session classification follows gear and charge state`() {
        assertEquals(HistoryKind.DRIVE, VehicleHistory.kind(fixture(0), null))
        assertEquals(HistoryKind.CHARGE, VehicleHistory.kind(fixture(0).copy(shift = "PARK", charging = true), null))
        assertEquals(HistoryKind.PARK, VehicleHistory.kind(fixture(0).copy(shift = "PARK", charging = false), session()))
    }

    /** 관측 시각은 새 저장 시각으로 덮지 않는다. */
    @Test fun `source observation time remains distinct`() {
        val sample = VehicleHistory.sample(VehicleSnapshot(100, categoryReadAt = mapOf(StateCategory.DRIVE to 90)), 105)
        assertEquals(105, sample.time)
        assertEquals(90L, sample.observed["DRIVE"])
    }

    /** 실제 차량과 관계없는 경로·값으로 반복 패턴을 구성한다. */
    private fun fixture(index: Int) = HistorySample(
        time = 1_700_000_000_000 + index * 5_000L,
        observed = mapOf("DRIVE" to 1_700_000_000_000 + index * 5_000L),
        latitude = 37.0 + index * 0.00001, longitude = 127.0 + index * 0.00002,
        odometer = 100_000 + index, speedKph = 35.75f, powerKw = if (index % 7 == 0) -5 else 12,
        batteryPercent = 80, charging = false, insideTempC = 23.125,
        shift = ShiftState.DRIVE.name,
    )

    /** 공통 시작 요약은 원본값 없이 첫 수신으로 채운다. */
    private fun session() = HistorySession("test", HistoryKind.DRIVE, fixture(0).time, fixture(0).time)
}
