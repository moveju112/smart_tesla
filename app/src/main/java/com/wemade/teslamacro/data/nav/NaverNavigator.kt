package com.wemade.teslamacro.data.nav

import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 네이버 지도 길안내를 시작한다.
 *
 * 주소 → 좌표는 안드로이드 내장 지오코더로 푼다 — 네이버 API 키가 필요 없다.
 * 백그라운드에서 다른 앱(지도)을 띄우려면 "다른 앱 위에 표시" 권한이 필수다 (안드로이드 제약).
 */
class NaverNavigator(private val context: Context) {

    /** 권한이 이미 있는가. 편집 화면이 안내 문구를 띄울지 판단할 때 쓴다 */
    val hasOverlayPermission: Boolean get() = Settings.canDrawOverlays(context)

    // 1. 권한 확인 → 2. 주소를 좌표로 → 3. 네이버 지도 길안내 인텐트
    suspend fun navigate(name: String, address: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!hasOverlayPermission) {
                    error("'다른 앱 위에 표시' 권한이 없어요. 매크로 편집에서 허용해 주세요")
                }
                if (address.isBlank()) error("주소가 비어 있어요")

                val point = geocode(address) ?: error("주소를 좌표로 못 바꿨어요: $address")
                val label = name.ifBlank { address }
                val uri = Uri.parse(
                    "nmap://navigation?dlat=${point.latitude}&dlng=${point.longitude}" +
                        "&dname=${Uri.encode(label)}&appname=${context.packageName}"
                )
                context.startActivity(
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

    // 최신 API(콜백식)는 33+ 전용이라, 모든 버전에서 도는 동기식을 그대로 쓴다
    @Suppress("DEPRECATION")
    private fun geocode(address: String): android.location.Address? = runCatching {
        Geocoder(context, Locale.KOREA).getFromLocationName(address, 1)?.firstOrNull()
    }.getOrNull()
}
