package com.wemade.teslamacro.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import com.wemade.teslamacro.domain.macro.GeoPoint
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 태블릿 자체의 현재 위치를 한 번 읽는다.
 *
 * 매크로의 "출발지 근처" 조건용이다.
 * Play Services 없이 LocationManager만 쓴다 — 타깃 태블릿에 GMS가 없을 수 있다.
 */
class TabletLocation(private val context: Context) {

    /** 권한이 없거나 측위에 실패하면 null — 조건 평가는 null을 불충족으로 본다 */
    suspend fun read(): GeoPoint? {
        if (!hasPermission()) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null

        return runCatching {
            // 1. 최근 위치가 신선하면 그대로 쓴다 — 지하주차장은 새 측위가 안 된다
            val cached = lastKnown(manager)
            if (cached != null && ageMillis(cached) < FRESH_MILLIS) return cached.toPoint()

            // 2. 새로 한 번 측위. 실패하면 오래된 최근 위치라도 쓴다 —
            //    차에 거치된 태블릿의 마지막 위치는 대개 차가 있는 곳이다
            val fresh = withTimeoutOrNull(FIX_TIMEOUT_MILLIS) { requestOnce(manager) }
            (fresh ?: cached)?.toPoint()
        }.getOrNull()
    }

    /**
     * 위치 권한이 있는지. 편집 화면의 저장 버튼도 이걸로 먼저 확인한다.
     * 안드로이드 12+에서 "대략적인 위치"만 허용하면 FINE은 거부되고 COARSE만 온다 —
     * 둘 중 하나만 있어도 동작해야 한다.
     */
    fun hasPermission(): Boolean = hasFine() || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun hasFine(): Boolean = granted(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** 켜져 있는 프로바이더들의 마지막 위치 중 가장 최신 것 */
    @SuppressLint("MissingPermission")
    private fun lastKnown(manager: LocationManager): Location? =
        manager.getProviders(true)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }

    /** 한 번만 새로 측위한다. 타임아웃은 호출부의 withTimeoutOrNull이 건다 */
    @SuppressLint("MissingPermission")
    private suspend fun requestOnce(manager: LocationManager): Location? =
        suspendCancellableCoroutine { cont ->
            val provider = pickProvider(manager)
            if (provider == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                manager.getCurrentLocation(provider, signal, context.mainExecutor) {
                    if (cont.isActive) cont.resume(it)
                }
            } else {
                @Suppress("DEPRECATION")
                val listener = android.location.LocationListener { location ->
                    if (cont.isActive) cont.resume(location)
                }
                cont.invokeOnCancellation { manager.removeUpdates(listener) }
                @Suppress("DEPRECATION")
                manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
        }

    /** GPS 우선, 없으면 기지국/와이파이 측위. 대략 권한(COARSE)만 있으면 GPS는 못 쓴다 */
    private fun pickProvider(manager: LocationManager): String? =
        buildList {
            if (hasFine()) add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

    private fun ageMillis(location: Location): Long =
        System.currentTimeMillis() - location.time

    private fun Location.toPoint() = GeoPoint(latitude, longitude)

    private companion object {
        /** 이 안쪽이면 굳이 새로 측위하지 않는다 */
        const val FRESH_MILLIS = 10 * 60 * 1000L
        /** 새 측위 대기 상한. 폴링 한 바퀴를 너무 오래 잡아먹으면 안 된다 */
        const val FIX_TIMEOUT_MILLIS = 8_000L
    }
}
