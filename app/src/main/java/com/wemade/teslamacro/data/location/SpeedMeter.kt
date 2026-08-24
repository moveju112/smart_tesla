package com.wemade.teslamacro.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

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
    fun speedKph(): Flow<Double> = callbackFlow {
        val manager = context.getSystemService(LocationManager::class.java)
        if (manager == null || !hasPermission()) {
            close()
            return@callbackFlow
        }

        val listener = LocationListener { location -> trySend(kphOf(location)) }
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

/**
 * 위치의 속도를 km/h로. 속도를 안 실어 보내는 기기가 있어 그때는 0으로 본다 —
 * 여기서는 "모름"과 "정지"를 굳이 가르지 않는다. 둘 다 화면에서 0이고, 달리는 중이면 값이 온다.
 */
internal fun kphOf(location: Location): Double =
    if (location.hasSpeed()) (location.speed * 3.6).toDouble().coerceAtLeast(0.0) else 0.0
