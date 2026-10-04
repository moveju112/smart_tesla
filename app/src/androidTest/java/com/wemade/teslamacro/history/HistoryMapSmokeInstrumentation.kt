package com.wemade.teslamacro.history

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.wemade.teslamacro.MainActivity
import com.wemade.teslamacro.TeslaMacroApplication
import com.wemade.teslamacro.domain.model.ShiftState
import com.wemade.teslamacro.domain.model.StateCategory
import com.wemade.teslamacro.domain.model.VehicleSnapshot
import com.wemade.teslamacro.feature.history.HistoryRoute
import com.wemade.teslamacro.feature.history.HistoryViewModel
import com.wemade.teslamacro.ui.ViewModelFactory
import com.wemade.teslamacro.ui.theme.TeslaMacroTheme
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 읽기 전용으로 부팅한 빈 에뮬레이터에서 실제 Compose·WebView·ViewModel 갱신을 확인한다. */
class HistoryMapSmokeInstrumentation : Instrumentation() {
    private val sampleStart = System.currentTimeMillis()

    /** 별도 테스트 프레임워크 없이 기존 플랫폼 실행기를 쓴다. */
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    /** 실기기·등록 차량에서는 실행을 거부하고 더미 주행으로만 지도와 갱신을 검증한다. */
    override fun onStart() {
        val result = Bundle()
        var code = Activity.RESULT_OK
        var activity: MainActivity? = null
        val app = targetContext.applicationContext as TeslaMacroApplication
        var fixtureCreated = false
        try {
            check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
            runBlocking {
                withTimeout(10_000) { app.ready.first { it } }
                check(app.container.settingsStore.settings.first().vin.isBlank()) { "빈 임시 에뮬레이터만 지원" }
                app.container.settingsStore.setVin("5YJS0000000000000")
                app.container.vehicleHistory.finish("5YJS0000000000000")
                fixtureCreated = true
                for (index in 0..1) app.container.vehicleHistory.record(snapshot(index), "5YJS0000000000000", sampleStart + index * 5_000)
            }
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val screen = activity
            lateinit var model: HistoryViewModel
            val trip = runBlocking { app.container.vehicleHistory.overview("5YJS0000000000000").sessions.first { it.start == sampleStart } }
            runOnMainSync {
                model = ViewModelProvider(screen, ViewModelFactory(app.container))[HistoryViewModel::class.java]
                model.select(trip)
                screen.setContent { TeslaMacroTheme(dark = false) { HistoryRoute(model) } }
            }
            runBlocking { withTimeout(10_000) { model.state.first { it.detail.samples.size == 2 && !it.detail.loading } } }
            val webView = awaitMap(screen)
            checkMap(webView)
            runBlocking {
                var loadingSeen = false
                val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                    model.state.collect { if (it.detail.loading) loadingSeen = true }
                }
                app.container.vehicleHistory.record(snapshot(2), "5YJS0000000000000", sampleStart + 10_000)
                withTimeout(10_000) { model.state.first { it.detail.samples.size == 3 && !it.detail.loading } }
                observer.cancel()
                check(!loadingSeen) { "같은 기록 갱신에서 지도를 제거하는 로딩 상태가 발생" }
            }
            waitForIdleSync()
            check(awaitMap(screen) === webView) { "갱신 중 WebView가 다시 생성됨" }
            checkMap(webView)
            result.putString("stream", "PASS: 실제 Android 경로·지도 버튼·WebView 크기·주행 갱신 시 지도 유지")
        } catch (error: Throwable) {
            code = Activity.RESULT_CANCELED
            result.putString("stream", "FAIL: ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            activity?.let { runOnMainSync { it.finish() } }
            if (fixtureCreated) runBlocking { app.container.settingsStore.setVin("") }
        }
        finish(code, result)
    }

    /** 실제 위치를 쓰지 않고 5초 간격의 두 점 이상을 만든다. */
    private fun snapshot(index: Int) = VehicleSnapshot(sampleStart + index * 5_000,
        categoryReadAt = mapOf(StateCategory.DRIVE to sampleStart + index * 5_000),
        shiftState = ShiftState.DRIVE, vehicleLatitude = 37.0 + index * 0.001,
        vehicleLongitude = 127.0 + index * 0.001, odometerHundredthsMile = 1000 + index * 10,
        batteryLevelPercent = 84)

    /** 전역 레이아웃 신호로 Compose 안에 실제 WebView가 붙는 순간만 기다린다. */
    private fun awaitMap(activity: MainActivity): WebView {
        val ready = CountDownLatch(1)
        var found: WebView? = null
        val root = activity.window.decorView
        lateinit var listener: android.view.ViewTreeObserver.OnGlobalLayoutListener
        runOnMainSync {
            listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
                findMap(root)?.let { found = it; ready.countDown() }
            }
            root.viewTreeObserver.addOnGlobalLayoutListener(listener)
            listener.onGlobalLayout()
        }
        try { check(ready.await(10, TimeUnit.SECONDS)) { "WebView가 화면에 없음" } }
        finally { runOnMainSync { root.viewTreeObserver.removeOnGlobalLayoutListener(listener) } }
        return checkNotNull(found)
    }

    /** Compose 호스트의 자식에서 지도 뷰만 찾는다. */
    private fun findMap(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findMap(view.getChildAt(it)) }
        else -> null
    }

    /** 실제 WebView 프레임에서 이동선·조작 버튼·크기를 읽고 이미지 캡처 없이 판정한다. */
    private fun checkMap(view: WebView) {
        val ready = CountDownLatch(1)
        var valid = false
        var detail = "callback pending"
        runOnMainSync {
            view.webChromeClient = object : android.webkit.WebChromeClient() {
                /** 타일 네트워크 완료와 무관하게 문서 진행 이벤트에서 실제 경로 DOM을 기다린다. */
                override fun onProgressChanged(webView: WebView, progress: Int) {
                    webView.evaluateJavascript("JSON.stringify({ready:document.readyState,path:document.getElementById('line')?.getAttribute('d'),controls:document.getElementById('controls')?.offsetWidth})") {
                        detail = "${view.width}x${view.height} progress=$progress url=${view.url} $it"
                        val document = runCatching { org.json.JSONObject(org.json.JSONArray("[$it]").getString(0)) }.getOrNull()
                        valid = document != null && document.optString("path").contains("L") && document.optInt("controls") > 0 &&
                            view.width > 0 && view.height > 0
                        if (valid) ready.countDown()
                    }
                }
            }
            view.webChromeClient?.onProgressChanged(view, view.progress)
        }
        check(ready.await(15, TimeUnit.SECONDS) && valid) { "지도 경로·버튼·크기 확인 실패: $detail" }
    }
}
