package com.wemade.teslamacro.data.nav

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder

/** 공식 앱의 공유 Activity만 허용해 ADB 통로를 임의 앱 실행기로 만들지 않는다. */
object TeslaShareIntent {
    const val PACKAGE = "com.teslamotors.tesla"
    const val ACTIVITY = "com.tesla.share.ShareActivity"

    /** 목적지 문구를 지도 검색 URL로 감싸 공식 앱에 텍스트로 공유한다. */
    fun create(destination: String): Intent {
        val text = requireNotNull(TeslaNavigationDestination.normalize(destination))
        return Intent(Intent.ACTION_SEND).setComponent(ComponentName(PACKAGE, ACTIVITY))
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "https://maps.google.com/maps?q=" + URLEncoder.encode(text, "UTF-8"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** 셸 서버도 앱과 같은 URL·길이 제한을 검사하고 다른 외부 입력은 거부한다. */
    @JvmStatic
    fun accepts(text: String?): Boolean {
        if (text == null || text.length !in 1..4096 || text.any { it.code < 32 }) return false
        val uri = Uri.parse(text)
        return uri.scheme == "https" && uri.host == "maps.google.com" && uri.path == "/maps" &&
            uri.userInfo == null && uri.port == -1 && uri.fragment == null &&
            uri.queryParameterNames == setOf("q") && uri.getQueryParameters("q").size == 1 &&
            uri.getQueryParameter("q")?.let(TeslaNavigationDestination::normalize) != null
    }
}
