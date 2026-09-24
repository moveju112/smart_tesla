package com.wemade.teslamacro.domain.safety

import com.wemade.teslamacro.domain.macro.ConditionEvaluator
import kotlinx.serialization.Serializable
import java.time.LocalDate
import kotlin.math.*

@Serializable
data class CameraDataset(
    val cameras: List<OfflineCamera>,
    val schemaVersion: Int = 1,
    val retrievedAt: String? = null,
)

@Serializable
data class OfflineCamera(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val speedLimitKph: Int,
    val section: Boolean = false,
    val referenceDate: String? = null,
)

/** 주변 격자만 조회한다. 도로 매칭이 없으므로 결과는 확정 단속이 아니라 전방 후보다. */
class CameraIndex(cameras: List<OfflineCamera>) {
    private val validCameras = cameras.filter {
        it.latitude in 33.0..39.0 && it.longitude in 124.0..132.0 && it.speedLimitKph in 10..130
    }
    private val cells = validCameras.groupBy { cell(it.latitude, it.longitude) }
    private val points = validCameras.groupBy { it.latitude to it.longitude }
    // 같은 좌표의 제한속도·기준일은 파일 순서로 낙관적인 값을 택하지 않는다.
    private val conflictingPoints = points
        .filterValues { records -> records.map { it.speedLimitKph }.distinct().size > 1 }.keys
    private val pointDates = points.mapValues { (_, records) ->
        if (records.any { it.referenceDate == null }) null
        else records.mapNotNull { it.referenceDate }.minOrNull()
    }

    /** 원본 건수와 별개로 실제 사용할 수 있는 목록이 있는지 확인한다. */
    val isEmpty: Boolean get() = cells.isEmpty()

    /** 경보보다 넓은 전방 후보를 찾되, GPS 오차로 바로 뒤의 카메라를 놓치지 않게 근거리는 허용한다. */
    fun hasNearby(latitude: Double, longitude: Double, bearing: Double): Boolean {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0 || !bearing.isFinite()) return false
        val (row, column) = cell(latitude, longitude)
        for (x in row - 1..row + 1) for (y in column - 1..column + 1) {
            if (cells[x to y].orEmpty().any { camera ->
                    val meters = ConditionEvaluator.distanceMeters(latitude, longitude, camera.latitude, camera.longitude)
                    meters <= 1_000 && (meters <= 100 || bearingDifference(latitude, longitude, bearing, camera) <= 120)
                }) return true
        }
        return false
    }

    /** 후보 누락을 줄이기 위해 전방 800m까지 넓게 보되, 저정밀·정지·반대방향은 거른다. */
    fun nearest(latitude: Double, longitude: Double, bearing: Double, speedKph: Double,
                accuracyMeters: Double, today: LocalDate = LocalDate.now()): SafetyAlert? {
        if (!latitude.isFinite() || !longitude.isFinite() || !bearing.isFinite() ||
            !speedKph.isFinite() || speedKph < 5 || accuracyMeters !in 0.0..30.0) return null
        val (row, column) = cell(latitude, longitude)
        var nearest: OfflineCamera? = null
        var distance = 801.0
        for (x in row - 1..row + 1) for (y in column - 1..column + 1) {
            for (camera in cells[x to y].orEmpty()) {
                val meters = ConditionEvaluator.distanceMeters(latitude, longitude, camera.latitude, camera.longitude)
                if (meters < 10 || meters > 800 || meters >= distance) continue
                val difference = bearingDifference(latitude, longitude, bearing, camera)
                // 굽은 도로의 전방 카메라를 놓치지 않도록 실차 테스트 전에는 넓게 허용한다.
                if (difference > 45 || meters * sin(Math.toRadians(difference)) > 250) continue
                nearest = camera
                distance = meters
            }
        }
        return nearest?.let {
            val point = it.latitude to it.longitude
            val conflict = point in conflictingPoints
            val referenceDate = pointDates[point]
            SafetyAlert(if (it.section) SafetyKind.SECTION_CAMERA else SafetyKind.SPEED_CAMERA,
                distance.roundToInt(), it.speedLimitKph.takeUnless { conflict }, limitConflict = conflict,
                cameraKey = "${it.latitude},${it.longitude}", referenceDate = referenceDate,
                dateWarning = sourceDateWarning(referenceDate, today, 12, "자료 기준일"))
        }
    }

    /** 안내와 요청의 방향 계산을 공유해 경계에서 서로 다른 후보를 고르지 않게 한다. */
    private fun bearingDifference(latitude: Double, longitude: Double, bearing: Double, camera: OfflineCamera): Double {
        val north = camera.latitude - latitude
        val east = (camera.longitude - longitude) * cos(Math.toRadians(latitude))
        val angle = Math.toDegrees(atan2(east, north))
        return abs(((angle - bearing + 540) % 360 + 360) % 360 - 180)
    }

    /** 약 2km 격자로 전국 목록의 매초 전체 순회를 피한다. */
    private fun cell(latitude: Double, longitude: Double): Pair<Int, Int> =
        floor(latitude / 0.02).toInt() to floor(longitude / 0.02).toInt()
}
