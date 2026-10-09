package com.wemade.teslamacro.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import com.wemade.teslamacro.TeslaMacroApplication
import com.wemade.teslamacro.data.nav.NavigationScreenText
import com.wemade.teslamacro.data.nav.teslaDestinationFromScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 두 내비의 목적지 영역만 읽으며 클릭·입력·다른 앱 화면 제어는 하지 않는다. */
class TeslaNavigationAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastReadAt = -1_000L
    private var lastSummary: String? = null

    /** 화면 후보를 보관하되 실제 안내 알림과 결합하기 전에는 차량에 보내지 않는다. */
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (packageName !in setOf("com.nhn.android.nmap", "com.locnall.KimGiSa")) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastReadAt < 250) return
        lastReadAt = now
        serviceScope.launch {
            val app = application as TeslaMacroApplication
            app.ready.first { it }
            val settings = app.container.settingsStore.settings.first()
            if (!settings.teslaNavigationShareEnabled && !settings.teslaNavigationTestMode) return@launch
            val root = rootInActiveWindow ?: return@launch
            try {
                if (root.packageName?.toString() != packageName) return@launch
                val viewId = if (packageName == "com.nhn.android.nmap") "route_search_bar" else "route_result_trip"
                val nodes = root.findAccessibilityNodeInfosByViewId("$packageName:id/$viewId")
                val entries = mutableListOf<NavigationScreenText>()
                val visited = intArrayOf(0)
                try { nodes.forEach { collectTexts(it, entries, visited, 0) } }
                finally { nodes.forEach { it.recycle() } }
                val destination = teslaDestinationFromScreen(packageName, entries)
                // 판독 결과가 바뀔 때만 기록해 실기기 로그로 화면 영역·파서 실패를 구분한다.
                val summary = when {
                    destination != null -> "화면 목적지 읽음 · $destination"
                    nodes.isEmpty() -> "화면 목적지 영역 없음"
                    else -> "화면 목적지 판독 실패 · " + entries.take(8).joinToString(" | ") { "${it.top}:${it.text.take(20)}" }
                }
                if (summary != lastSummary) {
                    lastSummary = summary
                    com.wemade.teslable.DiagLog.add("테슬라 내비 연동 — $summary")
                }
                destination?.let { app.container.teslaNavigationShare.screen(packageName, it) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                com.wemade.teslable.DiagLog.add("테슬라 내비 연동 — 목적지 화면을 읽지 못함")
            } finally { root.recycle() }
        }
    }

    /** 화면 트리가 커도 제한된 영역·깊이만 읽고 자식 객체는 즉시 반환한다. */
    private fun collectTexts(node: AccessibilityNodeInfo, entries: MutableList<NavigationScreenText>, visited: IntArray, depth: Int) {
        if (depth > 16 || visited[0]++ >= 200) return
        val bounds = Rect().also(node::getBoundsInScreen)
        listOfNotNull(node.text, node.contentDescription).map { it.toString() }.distinct().forEach {
            entries.add(NavigationScreenText(it, bounds.top, bounds.left,
                node.className?.toString()?.endsWith("Button") == true))
        }
        for (index in 0 until node.childCount.coerceAtMost(100)) {
            val child = node.getChild(index) ?: continue
            try { collectTexts(child, entries, visited, depth + 1) } finally { child.recycle() }
        }
    }

    /** 접근성 중단은 실행 동작으로 취급하지 않는다. */
    override fun onInterrupt() = Unit

    /** 서비스가 종료되면 늦은 화면 조회도 함께 취소한다. */
    override fun onDestroy() { serviceScope.cancel(); super.onDestroy() }

    companion object {
        /** 설정 복귀 때 실제 활성화된 서비스 목록으로 권한 상태를 갱신한다. */
        fun enabled(context: Context): Boolean {
            val manager = context.getSystemService(AccessibilityManager::class.java)
            val component = ComponentName(context, TeslaNavigationAccessibilityService::class.java)
            return manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { ComponentName(it.resolveInfo.serviceInfo.packageName, it.resolveInfo.serviceInfo.name) == component }
        }
    }
}
