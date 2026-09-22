package com.wemade.teslamacro.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.SystemClock
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map

/**
 * 지금 속도(km/h)를 흘려보낸다.
 *
 * **왜 차량 값을 안 쓰나** — 차의 speedKph는 BLE 왕복을 타고 폴링 주기(주행 중 2초)로만
 * 온다. HUD로 쓰기엔 늦고, 읽을 때마다 인포테인먼트를 깨운다.
 * GPS는 1초마다 오고 차를 안 건드린다.
 *
 * 값을 못 얻으면 흘리지 않는다 — 0으로 채우면 정지해 있는 것처럼 보인다.
 */
class SpeedMeter(private val context: Context) {

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 속도 스트림. 구독하는 동안만 GPS를 켠다 —
     * 상시 켜두면 주차된 차에서 밤새 위성을 잡는다.
     */
    fun speedKph(): Flow<Double?> = locations().map { freshSpeedKph(it) }

    /** 안전안내와 HUD가 같은 GPS 좌표를 공유한다. */
    fun locations(): Flow<Location> = callbackFlow {
        val manager = context.getSystemService(LocationManager::class.java)
        if (manager == null || !hasPermission()) {
            close()
            return@callbackFlow
        }

        val listener = LocationListener { location -> trySend(location) }
        val started = runCatching {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                UPDATE_INTERVAL_MS,
                0f,
                listener,
            )
        }.isSuccess
        if (!started) {
            com.wemade.teslable.DiagLog.add("HUD 속도 · GPS를 열지 못했어요")
            close()
            return@callbackFlow
        }

        awaitClose { runCatching { manager.removeUpdates(listener) } }
    }

    private companion object {
        /** 1초. 더 자주 받아도 화면이 못 따라가고 위성만 더 쓴다 */
        const val UPDATE_INTERVAL_MS = 1_000L
    }
}

/** 속도 누락·음수·비정상 수치는 정차(0)가 아니라 확인 불가(null)다. */
internal fun kphOf(location: Location): Double? =
    if (location.hasSpeed() && location.speed.isFinite() && location.speed >= 0f) location.speed * 3.6 else null

/** 수신 시점이 아니라 실제 측정 시점부터 5초 미만인 위치만 사용한다. */
internal fun isFreshLocation(measuredNanos: Long, nowNanos: Long): Boolean =
    measuredNanos >= 0 && measuredNanos <= nowNanos && nowNanos - measuredNanos < 5_000_000_000L

/** HUD와 안전 안내가 같은 품질·유효기간의 GPS 속도를 쓰게 한다. */
internal fun freshSpeedKph(location: Location, nowNanos: Long = SystemClock.elapsedRealtimeNanos()): Double? {
    if (!isFreshLocation(location.elapsedRealtimeNanos, nowNanos) ||
        !location.hasAccuracy() || location.accuracy !in 0f..30f ||
        location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return null
    return kphOf(location)
}
