package com.wemade.teslamacro.data.history

import com.wemade.teslamacro.domain.model.ShiftState
import com.wemade.teslamacro.domain.model.StateCategory
import com.wemade.teslamacro.domain.model.VehicleSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** 미수신은 null로 보존하고 좌표·수치는 반올림하지 않아 나중 지표에도 원본을 쓴다. */
@Serializable
data class HistorySample(
    val time: Long,
    val observed: Map<String, Long> = emptyMap(),
    val latitude: Double? = null,
    val longitude: Double? = null,
    val odometer: Int? = null,
    val speedKph: Float? = null,
    val powerKw: Int? = null,
    val batteryPercent: Int? = null,
    val rangeKm: Float? = null,
    val charging: Boolean? = null,
    val chargeAddedKwh: Float? = null,
    val chargerPowerKw: Int? = null,
    val chargingAmps: Int? = null,
    val chargerVoltage: Int? = null,
    val chargeLimitPercent: Int? = null,
    val insideTempC: Double? = null,
    val outsideTempC: Double? = null,
    val climateOn: Boolean? = null,
    val tirePressuresBar: Map<String, Float> = emptyMap(),
    val shift: String = ShiftState.UNKNOWN.name,
    val present: Boolean? = null,
)

enum class HistoryKind(val label: String) { DRIVE("주행"), CHARGE("충전"), PARK("정차") }

/** 시작·끝 원본과 관측 누적을 분리해 배터리 반올림 오르내림을 소비량으로 더하지 않는다. */
@Serializable
data class HistorySession(
    val id: String,
    val kind: HistoryKind,
    val start: Long,
    val end: Long,
    val samples: Int = 0,
    val firstOdometer: Int? = null,
    val lastOdometer: Int? = null,
    val firstBattery: Int? = null,
    val lastBattery: Int? = null,
    val firstChargeKwh: Float? = null,
    val lastChargeKwh: Float? = null,
    val chargeCounterReset: Boolean = false,
    val estimatedDriveKwh: Double = 0.0,
    val powerCoveredMillis: Long = 0,
    val distanceInvalid: Boolean = false,
    val complete: Boolean = false,
    val hasGaps: Boolean = false,
    val lastStateObservedAt: Long? = null,
) {
    val distanceKm: Double? get() = if (!distanceInvalid && firstOdometer != null && lastOdometer != null)
        ((lastOdometer - firstOdometer) * 0.01609344).takeIf { it >= 0 } else null
    val batteryUsedPercent: Int? get() = firstBattery?.let { first -> lastBattery?.let { first - it } }
    val kilometersPerPercent: Double? get() = batteryUsedPercent?.takeIf { it > 0 }
        ?.let { distanceKm?.div(it) }
    val chargedKwh: Double? get() = if (!chargeCounterReset && firstChargeKwh != null && lastChargeKwh != null)
        (lastChargeKwh.toDouble() - firstChargeKwh).takeIf { it >= 0 } else null
}

/** 수집·압축·지표 계산을 Android와 분리해 누락과 재시작을 재현한다. */
object VehicleHistory {
    const val SAMPLE_MILLIS = 5_000L
    const val BLOCK_MILLIS = 5 * 60_000L
    const val MAX_GAP_MILLIS = 15_000L
    // BLE 순차 조회·재시도 지연은 세션 분리와 경로·전력 적산에서 다르게 취급한다.
    const val SESSION_GAP_MILLIS = 120_000L
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** 병합 스냅샷을 쓰지 않아 오래된 좌표나 배터리가 새 표본으로 복제되지 않게 한다. */
    fun sample(snapshot: VehicleSnapshot, time: Long): HistorySample = HistorySample(
        time = time,
        observed = snapshot.categoryReadAt.mapKeys { it.key.name },
        latitude = snapshot.vehicleLatitude?.takeIf { it.isFinite() && it in -90.0..90.0 },
        longitude = snapshot.vehicleLongitude?.takeIf { it.isFinite() && it in -180.0..180.0 },
        odometer = snapshot.odometerHundredthsMile?.takeIf { it >= 0 },
        speedKph = snapshot.speedKph?.takeIf { it.isFinite() && it >= 0 },
        powerKw = snapshot.drivePowerKw,
        batteryPercent = snapshot.batteryLevelPercent?.takeIf { it in 0..100 },
        rangeKm = snapshot.rangeKm?.takeIf { it.isFinite() && it >= 0 },
        charging = snapshot.isCharging,
        chargeAddedKwh = snapshot.chargeEnergyAddedKwh?.takeIf { it.isFinite() && it >= 0 },
        chargerPowerKw = snapshot.chargerPowerKw,
        chargingAmps = snapshot.actualChargingAmps,
        chargerVoltage = snapshot.chargerVoltage,
        chargeLimitPercent = snapshot.chargeLimitPercent,
        insideTempC = snapshot.insideTempC?.takeIf(Double::isFinite),
        outsideTempC = snapshot.outsideTempC?.takeIf(Double::isFinite),
        climateOn = snapshot.isClimateOn,
        tirePressuresBar = snapshot.tirePressuresBar.filterValues { it.isFinite() && it > 0 }.mapKeys { it.key.name },
        shift = snapshot.shiftState.name,
        present = snapshot.isUserPresent,
    )

    /** 시계 역행과 연결 공백은 새 구간으로 나눠 관측하지 않은 에너지를 적산하지 않는다. */
    fun contiguous(previous: HistorySample, current: HistorySample): Boolean =
        current.time - previous.time in 1..MAX_GAP_MILLIS

    /** 읽지 못한 기어는 짧은 관측 공백에서만 직전 종류를 이어간다. */
    fun kind(sample: HistorySample, previous: HistorySession?): HistoryKind = when {
        sample.shift == ShiftState.DRIVE.name || sample.shift == ShiftState.REVERSE.name -> HistoryKind.DRIVE
        sample.charging == true -> HistoryKind.CHARGE
        sample.shift == ShiftState.PARK.name || sample.charging == false && previous?.kind == HistoryKind.CHARGE -> HistoryKind.PARK
        previous != null && sample.time - (previous.lastStateObservedAt ?: previous.end) in 0..SESSION_GAP_MILLIS -> previous.kind
        else -> HistoryKind.PARK
    }

    /** 주행 단위는 조회 지연을 견디되 긴 단절·시계 역행은 별도 기록으로 남긴다. */
    fun continues(session: HistorySession, sample: HistorySample): Boolean =
        session.kind == kind(sample, session) && sample.time - session.end in 1..SESSION_GAP_MILLIS

    /** 두 신선한 전력 표본 사이만 사다리꼴 적산하며 회생 전력의 부호도 보존한다. */
    fun append(session: HistorySession, previous: HistorySample?, sample: HistorySample): HistorySession {
        val covered = previous != null && contiguous(previous, sample) &&
            previous.powerKw != null && sample.powerKw != null && session.kind == HistoryKind.DRIVE
        val span = if (covered) sample.time - previous!!.time else 0L
        val energy = if (covered) (previous!!.powerKw!!.toDouble() + sample.powerKw!!.toDouble()) / 2 * span / 3_600_000 else 0.0
        return session.copy(
            end = sample.time, samples = session.samples + 1,
            lastStateObservedAt = if (sample.shift != ShiftState.UNKNOWN.name || sample.charging == true ||
                (session.kind == HistoryKind.CHARGE && sample.charging == false)) sample.time else session.lastStateObservedAt ?: session.start,
            hasGaps = session.hasGaps || (previous != null && !contiguous(previous, sample)),
            firstOdometer = session.firstOdometer ?: sample.odometer,
            lastOdometer = sample.odometer ?: session.lastOdometer,
            firstBattery = session.firstBattery ?: sample.batteryPercent,
            lastBattery = sample.batteryPercent ?: session.lastBattery,
            firstChargeKwh = session.firstChargeKwh ?: sample.chargeAddedKwh,
            lastChargeKwh = sample.chargeAddedKwh ?: session.lastChargeKwh,
            chargeCounterReset = session.chargeCounterReset || (sample.chargeAddedKwh != null &&
                session.lastChargeKwh != null && sample.chargeAddedKwh < session.lastChargeKwh),
            distanceInvalid = session.distanceInvalid || (sample.odometer != null &&
                session.lastOdometer != null && sample.odometer < session.lastOdometer),
            estimatedDriveKwh = session.estimatedDriveKwh + energy,
            powerCoveredMillis = session.powerCoveredMillis + span,
        )
    }

    /** 5분 표본을 독립 압축해 기간 조회 때 전체 이력을 풀지 않게 한다. */
    fun encode(samples: List<HistorySample>): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { it.write(json.encodeToString(samples).toByteArray(Charsets.UTF_8)) }
        output.toByteArray()
    }

    /** 깨진 묶음은 호출자에게 알리고 복원 크기를 제한해 메모리 폭주를 막는다. */
    fun decode(bytes: ByteArray): List<HistorySample> = GZIPInputStream(bytes.inputStream()).use { input ->
        val data = input.readBytesLimited(1_048_576)
        json.decodeFromString<List<HistorySample>>(data.toString(Charsets.UTF_8)).also { require(it.size <= 61) }
    }

    /** 압축 원문을 제한 길이까지만 읽어 손상된 로컬 기록도 안전하게 실패시킨다. */
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer)
            if (count < 0) return output.toByteArray()
            require(output.size() + count <= limit)
            output.write(buffer, 0, count)
        }
    }
}
