package com.wemade.teslamacro.data.nav

import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.net.Uri
import android.provider.Settings
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.delay
import com.wemade.teslamacro.domain.macro.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 네이버 지도 길안내를 시작한다.
 *
 * 주소 → 좌표는 안드로이드 내장 지오코더로 푼다 — 네이버 API 키가 필요 없다.
 * 백그라운드에서 다른 앱(지도)을 띄우려면 "다른 앱 위에 표시" 권한이 필수다 (안드로이드 제약).
 */
/** 창이 실제로 붙는 데 걸리는 시간. 너무 짧으면 예외가 안 열린다 */
private const val WINDOW_ATTACH_MILLIS = 120L

class NaverNavigator(private val context: Context) {

    /** 권한이 이미 있는가. 편집 화면이 안내 문구를 띄울지 판단할 때 쓴다 */
    val hasOverlayPermission: Boolean get() = Settings.canDrawOverlays(context)

    // 1. 권한 확인 → 2. 주소를 좌표로 → 3. 네이버 지도 길안내 인텐트
    suspend fun navigate(name: String, address: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!hasOverlayPermission) {
                    error("'다른 앱 위에 표시' 권한이 없어요.\n매크로 편집에서 허용해 주세요")
                }
                if (address.isBlank()) error("주소가 비어 있어요")

                val point = geocode(address) ?: error("주소를 좌표로 못 바꿨어요: $address")
                val label = name.ifBlank { address }
                val uri = Uri.parse(
                    "nmap://navigation?dlat=${point.latitude}&dlng=${point.longitude}" +
                        "&dname=${Uri.encode(label)}&appname=${context.packageName}"
                )
                launchFromBackground(
                    Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                com.wemade.teslable.DiagLog.add("네이버 지도 안내 시작 → $label")
            }.recoverCatching { throwable ->
                // 네이버 지도가 없을 때의 안내를 사람이 읽을 말로 바꾼다
                if (throwable is android.content.ActivityNotFoundException) {
                    error("네이버 지도 앱이 설치되어 있지 않아요")
                }
                throw throwable
            }.map { }
        }

    /**
     * 배경에서 다른 앱 화면을 띄운다.
     *
     * 안드로이드 14부터 "다른 앱 위에 표시" 권한을 **가지고 있는 것만으로는** 부족하다.
     * 실제로 떠 있는 창이 있어야 배경 실행 예외가 열린다 —
     * 권한만 믿고 startActivity를 부르면 예외도 안 나고 그냥 무시된다(로그엔 성공으로 남는다).
     *
     * 그래서 1×1 투명 창을 잠깐 올렸다가 인텐트를 던지고 곧바로 내린다.
     * 창 조작은 메인 스레드에서만 되고, 붙는 데 한 프레임이 걸려 짧게 기다린다.
     *
     * 실차 증거(0.8.31): 앱을 최근에 켠 뒤에는 떴고, 배경에 55분 있었을 땐 안 떴다 —
     * 포그라운드 유예 시간에만 통하고 있었다는 뜻이다.
     */
    private suspend fun launchFromBackground(intent: Intent) = withContext(Dispatchers.Main) {
        val manager = context.getSystemService(WindowManager::class.java)
        val anchor = View(context)
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        )
        val attached = runCatching { manager.addView(anchor, params) }.isSuccess
        // 창을 못 올렸으면 배경 실행이 막힐 공산이 크다. 나중에 원인을 찾을 수 있게 남긴다
        if (!attached) {
            com.wemade.teslable.DiagLog.add("지도 안내 · 오버레이 창 실패 — 배경 실행이 막힐 수 있음")
        }
        try {
            if (attached) delay(WINDOW_ATTACH_MILLIS)
            context.startActivity(intent)
        } finally {
            if (attached) runCatching { manager.removeView(anchor) }
        }
    }

    /** 주소를 좌표로. "출발지 근처" 조건이 주소 입력으로 위치를 찍을 때도 쓴다 */
    suspend fun geocodePoint(address: String): GeoPoint? = withContext(Dispatchers.IO) {
        geocode(address)?.let { GeoPoint(it.latitude, it.longitude) }
    }

    /** 좌표를 사람이 읽는 주소로. 저장한 출발지가 어디인지 확인시켜줄 때 쓴다 */
    suspend fun addressOf(point: GeoPoint): String? = withContext(Dispatchers.IO) {
        runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.KOREA)
                .getFromLocation(point.latitude, point.longitude, 1)
                ?.firstOrNull()
                ?.getAddressLine(0)
        }.getOrNull()
    }

    // 최신 API(콜백식)는 33+ 전용이라, 모든 버전에서 도는 동기식을 그대로 쓴다
    @Suppress("DEPRECATION")
    private fun geocode(address: String): android.location.Address? = runCatching {
        Geocoder(context, Locale.KOREA).getFromLocationName(address, 1)?.firstOrNull()
    }.getOrNull()
}
