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
import com.wemade.teslamacro.data.nav.naverDistanceMeters
import com.wemade.teslamacro.data.nav.naverGuidanceMode
import com.wemade.teslamacro.data.nav.naverGuidanceRouteDestination
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
    private var lastSummaryAt = 0L
    private var lastGuidingLog: String? = null
    private var pendingRoute: String? = null
    private var routeStartConfirmed = false

    /** 화면 후보를 보관하되 실제 안내 알림과 결합하기 전에는 차량에 보내지 않는다. */
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (packageName !in setOf("com.nhn.android.nmap", "com.locnall.KimGiSa")) return
        val now = SystemClock.elapsedRealtime()
        val windowChanged = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        // 안내 중 목적지 변경 화면 구조를 모르므로 누른 글자를 기록해 다음 판독 근거로 쓴다.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val clicked = event.text.joinToString(" ").ifBlank { event.contentDescription?.toString().orEmpty() }.take(30)
            // 미리보기·확인창을 닫거나 나중에 출발하면 기존 안내가 이어지므로 바꾼 목적지를 버린다.
            if (clicked.trim() in setOf("닫기", "나중에 출발")) {
                pendingRoute = null
                routeStartConfirmed = false
            }
            if (clicked.trim() == "안내시작" && pendingRoute != null) routeStartConfirmed = true
            if (clicked.isNotBlank()) serviceScope.launch {
                logWhileGuiding(packageName, "안내 중 버튼 누름")
            }
        }
        if (!windowChanged && now - lastReadAt < 250) return
        lastReadAt = now
        serviceScope.launch {
            val app = application as TeslaMacroApplication
            app.ready.first { it }
            val settings = app.container.settingsStore.settings.first()
            if (!settings.teslaNavigationShareEnabled && !settings.teslaNavigationTestMode) return@launch
            val root = rootInActiveWindow ?: return@launch
            try {
                if (root.packageName?.toString() != packageName) return@launch
                val routeMeters = if (packageName == "com.nhn.android.nmap") {
                    // 새 경로 미리보기 거리를 우선하며 이전 안내의 남은 거리와 최댓값을 섞지 않는다.
                    listOf("v_route_card_distance", "distance").firstNotNullOfOrNull { id ->
                        root.findAccessibilityNodeInfosByViewId("$packageName:id/$id").mapNotNull { node ->
                            try { node.text?.toString()?.let(::naverDistanceMeters) } finally { node.recycle() }
                        }.maxOrNull()
                    }
                } else null
                val viewId = if (packageName == "com.nhn.android.nmap") "route_search_bar" else "route_result_trip"
                val nodes = root.findAccessibilityNodeInfosByViewId("$packageName:id/$viewId")
                val entries = mutableListOf<NavigationScreenText>()
                val visited = intArrayOf(0)
                try { nodes.forEach { collectTexts(it, entries, visited, 0) } }
                finally { nodes.forEach { it.recycle() } }
                val destination = teslaDestinationFromScreen(packageName, entries)
                // 판독 결과가 바뀔 때만 기록해 실기기 로그로 화면 영역·파서 실패를 구분한다.
                val summary = when {
                    destination != null -> "화면 목적지 읽음"
                    nodes.isEmpty() -> "화면 목적지 영역 없음"
                    else -> "화면 목적지 판독 실패 · 항목 ${entries.size}개"
                }
                // 같은 결과도 30초가 지나면 다시 기록해 재시도 때 화면을 다시 읽었는지 구분한다.
                if (summary != lastSummary || now - lastSummaryAt > 30_000) {
                    lastSummary = summary
                    lastSummaryAt = now
                    com.wemade.teslable.DiagLog.add("테슬라 내비 연동 — $summary")
                }
                destination?.let {
                    if (packageName == "com.nhn.android.nmap") app.container.teslaNavigationShare.routeDistance(routeMeters, it)
                    app.container.teslaNavigationShare.screen(packageName, it)
                }
                if (nodes.isEmpty() && packageName == "com.nhn.android.nmap" && app.container.teslaNavigationShare.guiding(packageName)) {
                    val screen = screenTexts(root)
                    if (windowChanged) logWhileGuiding(packageName, "안내 중 화면 전환")
                    // 안내 중 목적지 변경 (경로 미리보기 판독 -> 미리보기·확인창이 사라지고 안내 재개 -> 공유)
                    val preview = naverGuidanceRouteDestination(screen)
                    if (preview != null) {
                        if (preview != pendingRoute) {
                            routeStartConfirmed = false
                            com.wemade.teslable.DiagLog.add("테슬라 내비 연동 — 안내 중 경로 미리보기")
                        }
                        app.container.teslaNavigationShare.routeDistance(routeMeters, preview)
                        pendingRoute = preview
                    } else if (screen.none { it.text.trim() == "안내시작" }) {
                        if (routeStartConfirmed && naverGuidanceMode(screen) == com.wemade.teslamacro.data.nav.NaverGuidanceMode.ROUTE) {
                            pendingRoute?.let { app.container.teslaNavigationShare.reroute(packageName, it) }
                        }
                        pendingRoute = null
                        routeStartConfirmed = false
                    }
                    // 미리보기 목적지 변경을 먼저 처리해 안심주행 전환 판정이 빈 입력창을 띄우지 않게 한다.
                    naverGuidanceMode(screen)?.let { app.container.teslaNavigationShare.naverScreen(it) }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                com.wemade.teslable.DiagLog.add("테슬라 내비 연동 — 목적지 화면을 읽지 못함")
            } finally { root.recycle() }
        }
    }

    // 안내 중 진단 기록 (안내 활성 -> 직전과 다른 내용만 기록)
    /** 안내 중일 때만 화면 전환·누름을 남겨 일반 지도 탐색으로 로그가 넘치지 않게 한다. */
    private suspend fun logWhileGuiding(packageName: String, message: String) {
        val app = application as TeslaMacroApplication
        app.ready.first { it }
        if (message == lastGuidingLog || !app.container.teslaNavigationShare.guiding(packageName)) return
        lastGuidingLog = message
        com.wemade.teslable.DiagLog.add("테슬라 내비 연동 — $message")
    }

    // 전체 창 글자 수집 (넓이 우선 -> 최대 400칸 -> 자식 즉시 반환)
    /** 경로 판독에 필요한 글자만 메모리에서 사용하고 원문 진단 덤프는 만들지 않는다. */
    private fun screenTexts(root: AccessibilityNodeInfo): List<NavigationScreenText> {
        val items = mutableListOf<NavigationScreenText>()
        val nodes = java.util.ArrayDeque<Pair<AccessibilityNodeInfo, Int>>().apply { add(root to 0) }
        var visited = 0
        while (nodes.isNotEmpty() && visited++ < 400) {
            val (node, depth) = nodes.removeFirst()
            val texts = listOfNotNull(node.text, node.contentDescription).map { it.toString().trim() }.filter { it.isNotEmpty() }.distinct()
            if (texts.isNotEmpty()) {
                val bounds = Rect().also(node::getBoundsInScreen)
                val button = node.className?.toString()?.endsWith("Button") == true
                texts.forEach { items += NavigationScreenText(it, bounds.top, bounds.left, button) }
            }
            if (depth < 24) for (index in 0 until node.childCount.coerceAtMost(60)) node.getChild(index)?.let { nodes.add(it to depth + 1) }
            if (node !== root) node.recycle()
        }
        nodes.forEach { if (it.first !== root) it.first.recycle() }
        return items
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
