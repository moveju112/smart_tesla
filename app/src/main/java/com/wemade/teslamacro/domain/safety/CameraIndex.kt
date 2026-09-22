package com.wemade.teslamacro.domain.safety

import com.wemade.teslamacro.domain.macro.ConditionEvaluator
import kotlinx.serialization.Serializable
import kotlin.math.*

@Serializable
data class CameraDataset(val cameras: List<OfflineCamera>, val schemaVersion: Int = 1)

@Serializable
data class OfflineCamera(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val speedLimitKph: Int,
    val section: Boolean = false,
)

/** 주변 격자만 조회한다. 도로 매칭이 없으므로 결과는 확정 단속이 아니라 전방 후보다. */
class CameraIndex(cameras: List<OfflineCamera>) {
    private val validCameras = cameras.filter {
        it.latitude in 33.0..39.0 && it.longitude in 124.0..132.0 && it.speedLimitKph in 10..130
    }
    private val cells = validCameras.groupBy { cell(it.latitude, it.longitude) }
    // 동일 좌표의 상충하는 제한속도는 배열 순서로 정하지 않고 확인 필요로 표시한다.
    private val conflictingPoints = validCameras.groupBy { it.latitude to it.longitude }
        .filterValues { records -> records.map { it.speedLimitKph }.distinct().size > 1 }.keys

    /** 원본 건수와 별개로 실제 사용할 수 있는 목록이 있는지 확인한다. */
    val isEmpty: Boolean get() = cells.isEmpty()

    /** 저정밀·정지·방향 미확정 시에는 추측하지 않고 전방 600m 안의 후보만 고른다. */
    fun nearest(latitude: Double, longitude: Double, bearing: Double, speedKph: Double,
                accuracyMeters: Double): SafetyAlert? {
        if (!latitude.isFinite() || !longitude.isFinite() || !bearing.isFinite() ||
            !speedKph.isFinite() || speedKph < 5 || accuracyMeters !in 0.0..30.0) return null
        val (row, column) = cell(latitude, longitude)
        var nearest: OfflineCamera? = null
        var distance = 601.0
        for (x in row - 1..row + 1) for (y in column - 1..column + 1) {
            for (camera in cells[x to y].orEmpty()) {
                val meters = ConditionEvaluator.distanceMeters(latitude, longitude, camera.latitude, camera.longitude)
                if (meters < 10 || meters > 600 || meters >= distance) continue
                val north = camera.latitude - latitude
                val east = (camera.longitude - longitude) * cos(Math.toRadians(latitude))
                val angle = Math.toDegrees(atan2(east, north))
                val difference = abs(((angle - bearing + 540) % 360 + 360) % 360 - 180)
                if (difference > 25 || meters * sin(Math.toRadians(difference)) > 35) continue
                nearest = camera
                distance = meters
            }
        }
        return nearest?.let {
            val conflict = (it.latitude to it.longitude) in conflictingPoints
            SafetyAlert(if (it.section) SafetyKind.SECTION_CAMERA else SafetyKind.SPEED_CAMERA,
                distance.roundToInt(), it.speedLimitKph.takeUnless { conflict }, limitConflict = conflict)
        }
    }

    /** 약 2km 격자로 전국 목록의 매초 전체 순회를 피한다. */
    private fun cell(latitude: Double, longitude: Double): Pair<Int, Int> =
        floor(latitude / 0.02).toInt() to floor(longitude / 0.02).toInt()
}
