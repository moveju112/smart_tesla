package com.wemade.teslamacro.data.nav

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.wemade.teslamacro.TeslaMacroApplication

/** 사용자가 시작한 짧은 설정 구간에만 시스템 설정의 페어링 화면을 조작한다. */
class NavigationPairingService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastAction = 0L
    private val check = Runnable { inspect() }

    /** 권한 허용 후 진행 중인 최초 설정만 이어서 연다. */
    override fun onServiceConnected() {
        service = this
        if (SystemClock.elapsedRealtime() < deadline) {
            openSettings(this)
            handler.postDelayed(check, 500)
        }
    }

    /** 설정 화면 변화만 받아 사용자 요청의 유효시간 안에서 검사한다. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (SystemClock.elapsedRealtime() < deadline && !handler.hasCallbacks(check)) handler.postDelayed(check, 200)
    }

    /** 서비스 중단 때 남은 자동 클릭 요청을 폐기한다. */
    override fun onInterrupt() { deadline = 0; handler.removeCallbacks(check) }

    /** 서비스가 꺼지면 예약된 설정 조작도 함께 끝낸다. */
    override fun onDestroy() { if (service === this) service = null; onInterrupt(); super.onDestroy() }

    /** 설정과 무선 디버깅 확인 창만 대상으로 하며 요청 시간이 지나면 조작을 끝낸다. */
    private fun inspect() {
        val now = SystemClock.elapsedRealtime()
        if (now >= deadline) return
        val root = rootInActiveWindow ?: run { handler.postDelayed(check, 500); return }
        val packageName = root.packageName?.toString()
        if (packageName !in listOf("com.android.settings", "com.android.systemui")) return
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collect(root, nodes)
        val texts = nodes.mapNotNull { it.text?.toString() }
        if (packageName == "com.android.systemui") {
            // 시스템 UI에서는 현재 요청의 무선 디버깅 네트워크 확인 대화상자만 허용한다.
            if (texts.any { it.contains("무선 디버깅") || it.contains("wireless debugging", true) } &&
                texts.any { it.contains("네트워크") || it.contains("network", true) }) {
                // 같은 Wi-Fi로 복구할 때 확인 창이 다시 막지 않도록 현재 네트워크만 기억한다.
                val remember = nodes.firstOrNull { it.text?.toString()?.let { text ->
                    text.contains("이 네트워크에서 항상 허용") || text.contains("Always allow on this network", true)
                } == true }
                if (remember != null && !remember.isChecked) click(remember)
                nodes.firstOrNull { it.text?.toString() in listOf("허용", "Allow", "ALLOW") }?.let { click(it) }
                handler.postDelayed(check, 1000)
            }
            return
        }
        PairingScreen.read(texts)?.let { (port, code) ->
            deadline = 0
            com.wemade.teslable.DiagLog.add("네이버 안심주행 · 자동 페어링 정보 확인")
            (application as TeslaMacroApplication).container.wirelessNavigation.pair(port, code)
            return
        }
        if (now - lastAction < 1000) { handler.postDelayed(check, 1000); return }
        val pairing = nodes.firstOrNull { it.text?.toString() in listOf("페어링 코드로 기기 페어링", "Pair device with pairing code") && it.isEnabled }
        val wireless = nodes.firstOrNull { it.text?.toString() in listOf("무선 디버깅 사용", "Use wireless debugging") }
            ?: nodes.firstOrNull { it.text?.toString() in listOf("무선 디버깅", "Wireless debugging") }
        val allow = if (texts.any { it.contains("무선 디버깅") || it.contains("wireless debugging", true) })
            nodes.firstOrNull { it.text?.toString() in listOf("허용", "Allow", "ALLOW") } else null
        val target = allow ?: pairing ?: wireless
        if (target != null && click(target)) lastAction = now
        else if (target == null && texts.any { it in listOf("개발자 옵션", "개발자 옵션 사용", "Developer options", "Use developer options") }) {
            // 기기마다 바로가기 강조 위치가 달라 개발자 옵션 목록 안에서만 아래로 찾는다.
            nodes.asReversed().any { it.isScrollable && it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
            lastAction = now
        }
        handler.postDelayed(check, 1000)
    }

    /** 깊이와 개수를 제한해 설정 화면만 짧게 순회한다. */
    private fun collect(node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
        if (depth > 40 || result.size >= 500) return
        result.add(node)
        for (index in 0 until node.childCount) node.getChild(index)?.let { collect(it, result, depth + 1) }
    }

    /** 이미 켜진 스위치는 건드리지 않고 가까운 활성 클릭 영역만 누른다. */
    private fun click(node: AccessibilityNodeInfo): Boolean {
        var candidate: AccessibilityNodeInfo? = node
        repeat(4) {
            val current = candidate ?: return false
            if (current.isCheckable && current.isChecked) return false
            if (current.isClickable && current.isEnabled) return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            candidate = current.parent
        }
        return false
    }

    companion object {
        private var deadline = 0L
        private var service: NavigationPairingService? = null

        /** 최초 권한 허용은 사용자가 하고 그 뒤 설정 화면 조작만 최대 3분간 돕는다. */
        fun begin(context: Context) {
            deadline = SystemClock.elapsedRealtime() + 180_000
            com.wemade.teslable.DiagLog.add("네이버 안심주행 · 자동 설정 시작")
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().split(":").any { android.content.ComponentName.unflattenFromString(it) ==
                    android.content.ComponentName(context, NavigationPairingService::class.java) }
            if (enabled) {
                openSettings(context)
                service?.let { it.handler.removeCallbacks(it.check); it.handler.postDelayed(it.check, 500) }
            }
            else context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        /** 개발자 옵션의 무선 디버깅 항목으로 이동하며 설정 앱만 대상으로 한다. */
        private fun openSettings(context: Context) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .putExtra(":settings:show_fragment_args", android.os.Bundle().apply {
                    putString(":settings:fragment_args_key", "toggle_adb_wireless")
                }).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
