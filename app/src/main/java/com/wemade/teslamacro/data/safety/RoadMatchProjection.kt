package com.wemade.teslamacro.data.safety

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** 매칭된 좌표가 어느 GPS 측정값의 결과인지 함께 보관한다. */
internal data class RoadMatchAnchor(val source: RoadPoint, val road: MatchedRoad)

/**
 * 2~5초 늦게 도착한 매칭 좌표를 현재 위치로 그대로 쓰지 않고 같은 도로축 위 현재 지점으로 전진시킨다.
 * 방향이 크게 바뀌거나 GPS가 도로축에서 30m 넘게 벗어나면 보정 대신 원래 GPS를 사용한다.
 */
internal fun projectRoadMatch(
    anchor: RoadMatchAnchor,
    latitude: Double,
    longitude: Double,
    timestamp: Long,
    bearingDegrees: Double?,
    speedKph: Double,
    accuracyMeters: Double,
): MatchedRoad? {
    val roadBearing = anchor.road.bearingDegrees ?: return null
    val bearing = bearingDegrees ?: return null
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0 ||
        !bearing.isFinite() || !roadBearing.isFinite() || !speedKph.isFinite() ||
        speedKph < 0 || accuracyMeters !in 0.0..30.0) return null
    val ageSeconds = timestamp - anchor.source.timestamp
    if (ageSeconds !in 0L..5L || angleDifference(bearing, roadBearing) > 45.0) return null

    val meanLatitude = Math.toRadians((latitude + anchor.road.latitude) / 2.0)
    val northMeters = (latitude - anchor.road.latitude) * 111_195.0
    val eastMeters = (longitude - anchor.road.longitude) * 111_195.0 * cos(meanLatitude)
    val radians = Math.toRadians(roadBearing)
    val alongMeters = northMeters * cos(radians) + eastMeters * sin(radians)
    val lateralMeters = -northMeters * sin(radians) + eastMeters * cos(radians)
    if (abs(lateralMeters) > 30.0) return null

    // 현재 속도가 급감했어도 이전 5초의 진행량을 버리지 않도록 60m 여유를 둔다.
    val maximumProgress = max(60.0, speedKph / 3.6 * ageSeconds + 60.0)
    if (alongMeters < -30.0 || alongMeters > maximumProgress) return null

    val projectedLatitude = anchor.road.latitude + alongMeters * cos(radians) / 111_195.0
    val longitudeScale = 111_195.0 * cos(Math.toRadians(projectedLatitude))
    if (abs(longitudeScale) < 1.0) return null
    val projectedLongitude = anchor.road.longitude + alongMeters * sin(radians) / longitudeScale
    if (projectedLatitude !in -90.0..90.0 || projectedLongitude !in -180.0..180.0) return null
    return MatchedRoad(projectedLatitude, projectedLongitude, roadBearing)
}

/** 359도와 1도를 358도 차이로 보지 않는다. */
private fun angleDifference(first: Double, second: Double): Double =
    abs(((first - second + 540.0) % 360.0 + 360.0) % 360.0 - 180.0)
