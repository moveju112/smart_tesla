package com.wemade.teslamacro.feature.history

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.wemade.teslamacro.BuildConfig
import com.wemade.teslamacro.data.history.HistorySample
import com.wemade.teslamacro.ui.theme.T

/** 로컬 숫자만 HTML에 넣고 외부 지도에는 현재 화면의 타일 주소만 요청한다. */
internal fun historyMapHtml(template: String, samples: List<HistorySample>, background: String, ink: String, route: String): String {
    val points = samples.filter { it.latitude?.isFinite() == true && it.longitude?.isFinite() == true }
        .joinToString(",", "[", "]") { "[${it.latitude},${it.longitude},${it.time}]" }
    return template.replace("__POINTS__", points).replace("__BACKGROUND__", background)
        .replace("__INK__", ink).replace("__ROUTE__", route)
}

/** WebView의 HTTP 캐시를 사용하며 파일 접근·네이티브 JS 브리지는 열지 않는다. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun HistoryMap(samples: List<HistorySample>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val template = remember { context.assets.open("history-map.html").bufferedReader().use { it.readText() } }
    val background = "#%06x".format(T.Carbon.toArgb() and 0xffffff)
    val ink = "#%06x".format(T.Ink.toArgb() and 0xffffff)
    val route = "#%06x".format(T.Electric.toArgb() and 0xffffff)
    val html = remember(samples, background, ink, route) { historyMapHtml(template, samples, background, ink, route) }
    AndroidView(
        modifier = modifier,
        factory = { WebView(it).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.userAgentString = "SmartTesla/${BuildConfig.VERSION_NAME} (+https://github.com/moveju112/smart_tesla)"
            webViewClient = object : WebViewClient() {
                /** 출처 링크만 외부 브라우저로 열고 지도 안의 임의 탐색은 막는다. */
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.url.toString() == "https://www.openstreetmap.org/copyright") {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                    }
                    return true
                }
            }
        } },
        update = { view -> if (view.tag != html) {
            view.tag = html
            view.loadDataWithBaseURL("https://github.com/moveju112/smart_tesla/", html, "text/html", "UTF-8", null)
        } },
        onRelease = { it.stopLoading(); it.destroy() },
    )
}
