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
    val roadName: String? = null,
)

/**
 * 도로명 주소 체계(…로/…길) 이름만 비교 대상으로 삼는다.
 * 괄호 설명·공백은 표기 차이라 지우고, "중앙로"와 "중앙로10번길"처럼 이어지는 이름도 다른 도로로 본다.
 */
internal fun comparableRoadName(name: String?): String? =
    name?.replace(Regex("\\([^)]*\\)"), "")?.replace(Regex("\\s+"), "")
        ?.takeIf { it.length >= 2 && (it.endsWith("로") || it.endsWith("길")) }

/** 주변 격자만 조회한다. 도로 매칭이 없으므로 결과는 확정 단속이 아니라 전방 후보다. */
class CameraIndex(cameras: List<OfflineCamera>) {
    private val validCameras = cameras.filter {
        it.latitude in 33.0..39.0 && it.longitude in 124.0..132.0 && it.speedLimitKph in 10..130
    }
    private val cells = validCameras.groupBy { cell(it.latitude, it.longitude) }
    private val points = validCameras.groupBy { it.latitude to it.longitude }
    // 동일 좌표의 중복 원본도 모두 추적하되 입력 순서로 경보 상태가 달라지지 않게 한 번만 정렬한다.
    private val pointIds = points.mapValues { (_, records) ->
        records.map { it.id }.distinct().sorted().joinToString("|")
    }
    // 같은 좌표의 제한속도·기준일은 파일 순서로 낙관적인 값을 택하지 않는다.
    private val conflictingPoints = points
        .filterValues { records -> records.map { it.speedLimitKph }.distinct().size > 1 }.keys
    private val pointDates = points.mapValues { (_, records) ->
        // 한 기관의 기준일이라도 잘못되면 문자열 정렬로 다른 기관 날짜를 최신 근거로 삼지 않는다.
        val dates = records.map { it.referenceDate?.takeIf { date ->
            (date.length == 10 || (date.length > 10 && date[10] == 'T')) &&
                runCatching { LocalDate.parse(date.take(10)) }.isSuccess
        } }
        if (dates.any { it == null }) null else dates.filterNotNull().minOrNull()
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

    /** 매칭 성공·실패가 번갈아도 넓은 GPS 회랑으로 옆 도로 후보가 되살아나지 않게 같은 진행 회랑을 쓴다. */
    fun nearest(latitude: Double, longitude: Double, bearing: Double, speedKph: Double,
                accuracyMeters: Double, today: LocalDate = LocalDate.now(),
                maxDistanceMeters: Int = 700, matchedRoadName: String? = null): SafetyAlert? {
        if (!latitude.isFinite() || !longitude.isFinite() || !bearing.isFinite() ||
            !speedKph.isFinite() || speedKph < 5 || accuracyMeters !in 0.0..30.0) return null
        val (row, column) = cell(latitude, longitude)
        var nearest: OfflineCamera? = null
        val maxDistance = maxDistanceMeters.coerceIn(10, 700)
        var distance = maxDistance + 1.0
        val travelRoad = comparableRoadName(matchedRoadName)
        for (x in row - 1..row + 1) for (y in column - 1..column + 1) {
            for (camera in cells[x to y].orEmpty()) {
                val meters = ConditionEvaluator.distanceMeters(latitude, longitude, camera.latitude, camera.longitude)
                if (meters < 10 || meters > maxDistance || meters >= distance) continue
                if (!onTravelCorridor(latitude, longitude, bearing, camera, meters)) continue
                if (onOtherRoad(camera, travelRoad)) continue
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
                dateWarning = sourceDateWarning(referenceDate, today, 12, "자료 기준일"), cameraId = pointIds[point],
                cameraRoadName = it.roadName?.takeIf(String::isNotBlank))
        }
    }

    /**
     * 지금 후보 바로 뒤(1km 안)에 같은 진행 회랑의 카메라가 또 있는지 본다.
     * 매칭이 끊겨도 판정을 넓히지 않으며, 첫 안내에서 연속 구간을 알려 속도 재상승을 막는다.
     */
    fun hasFollowing(latitude: Double, longitude: Double, bearing: Double, afterMeters: Int,
                     withinMeters: Int = 1_000, matchedRoadName: String? = null): Boolean {
        if (!latitude.isFinite() || !longitude.isFinite() || !bearing.isFinite()) return false
        val (row, column) = cell(latitude, longitude)
        val travelRoad = comparableRoadName(matchedRoadName)
        for (x in row - 1..row + 1) for (y in column - 1..column + 1) {
            if (cells[x to y].orEmpty().any { camera ->
                    val meters = ConditionEvaluator.distanceMeters(latitude, longitude, camera.latitude, camera.longitude)
                    // 같은 지점의 중복 레코드(30m 이내)는 다음 카메라로 치지 않는다.
                    meters > afterMeters + 30 && meters <= afterMeters + withinMeters &&
                        onTravelCorridor(latitude, longitude, bearing, camera, meters) && !onOtherRoad(camera, travelRoad)
                }) return true
        }
        return false
    }

    /**
     * 매칭된 도로명과 카메라 자료의 도로명이 둘 다 비교 가능하고 다를 때만 다른 도로로 본다.
     * 한쪽이라도 없거나 노선번호 표기이면 실제 카메라를 놓치지 않게 기존 회랑 판정에 맡긴다.
     */
    private fun onOtherRoad(camera: OfflineCamera, travelRoad: String?): Boolean {
        val cameraRoad = comparableRoadName(camera.roadName) ?: return false
        return travelRoad != null && cameraRoad != travelRoad
    }

    /** 짧은 직선 오차는 허용하되 먼 평행도로를 배제한다. 큰 곡선은 접근할 때까지 안내가 늦어진다. */
    private fun onTravelCorridor(latitude: Double, longitude: Double, bearing: Double,
                                 camera: OfflineCamera, meters: Double): Boolean {
        val difference = bearingDifference(latitude, longitude, bearing, camera)
        return difference <= 25.0 && meters * sin(Math.toRadians(difference)) <= 35.0
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
