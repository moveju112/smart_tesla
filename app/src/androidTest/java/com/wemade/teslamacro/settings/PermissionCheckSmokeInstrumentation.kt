package com.wemade.teslamacro.settings

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.data.settings.AppSettings
import com.wemade.teslamacro.MainActivity
import com.wemade.teslamacro.TeslaMacroApplication
import com.wemade.teslamacro.data.nav.NavigationPairingService
import com.wemade.teslamacro.feature.settings.*
import com.wemade.teslamacro.ui.theme.TeslaMacroTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** 임시 에뮬레이터에서 권한 목록·묶음 요청·거부·설정 복귀를 실제 UI로 확인한다. */
class PermissionCheckSmokeInstrumentation : Instrumentation() {
    /** 명시한 로컬 계측에서만 권한 메뉴를 실행한다. */
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    /** 실기기를 거부하고 허용 요청이 기능 자동 실행을 켜지 않는지 확인한다. */
    override fun onStart() {
        val result = Bundle()
        var code = Activity.RESULT_OK
        var activity: MainActivity? = null
        try {
            check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
            val app = targetContext.applicationContext as TeslaMacroApplication
            runBlocking { withTimeout(10_000) { app.ready.first { it } } }
            val before = runBlocking { app.container.settingsStore.settings.first() }
            check(before.vin.isBlank())
            shell("appops set com.wemade.teslamacro SYSTEM_ALERT_WINDOW deny")
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val screen = activity
            runOnMainSync { screen.setContent {
                TeslaMacroTheme(dark = false) {
                    SettingsScreen(before, onUnpair = {}, onStartPairing = {}, initialGroup = SettingsGroup.DEVICE,
                        navigation = NavigationControls(onAppChange = {}, onHudOverlayChange = {}))
                }
            } }
            waitText("권한 점검")
            clickText("권한 점검")
            waitText("부족한 권한 준비")
            check(PermissionCheck.ACTIVITY !in grantedPermissionChecks(targetContext))
            clickText("부족한 권한 준비")
            clickPermission("permission_deny_button")
            waitText("부족한 권한 준비")
            check(PermissionCheck.ACTIVITY !in grantedPermissionChecks(targetContext))
            clickText("부족한 권한 준비")
            clickPermission("permission_allow_button")
            runBlocking { withTimeout(5_000) {
                while (PermissionCheck.ACTIVITY !in grantedPermissionChecks(targetContext)) delay(100)
            } }
            waitText("부족한 권한 준비")
            clickText("부족한 권한 준비")
            runBlocking { withTimeout(5_000) { while (!shell("dumpsys activity activities").lineSequence()
                .any { it.contains("ResumedActivity") && it.contains("com.android.settings") }) delay(100) } }
            shell("appops set com.wemade.teslamacro SYSTEM_ALERT_WINDOW allow")
            shell("input keyevent KEYCODE_BACK")
            waitText("권한 허용")
            check(PermissionCheck.OVERLAY in grantedPermissionChecks(targetContext))
            waitText("USB 디버깅")
            waitText("무선 ADB 연결")
            clickText("무선 ADB 연결")
            runBlocking { withTimeout(5_000) { while (!shell("dumpsys activity activities").lineSequence()
                .any { it.contains("ResumedActivity") && it.contains("com.android.settings") }) delay(100) } }
            check(!app.container.wirelessNavigation.state.value.prepared)
            val after = runBlocking { app.container.settingsStore.settings.first() }
            check(before.historyEnabled == after.historyEnabled && before.teslaNavigationShareEnabled == after.teslaNavigationShareEnabled)
            check(!app.container.wirelessNavigation.state.value.enabled)
            shell("input keyevent KEYCODE_BACK")
            checkFeatureGates(screen, before)
            result.putString("result", "PASS permission gates and centralized settings; permission menu, runtime denial/grant, overlay settings return, ADB settings without implicit authorization, feature settings unchanged")
        } catch (error: Throwable) {
            code = Activity.RESULT_CANCELED
            result.putString("error", "${error.javaClass.simpleName}: ${error.message}")
        } finally {
            targetContext.stopService(Intent(targetContext, NavigationPairingService::class.java))
            activity?.let { runOnMainSync { it.finish() } }
        }
        finish(code, result)
    }

    /** 실제 Android 권한 회수·허용과 모달 이동에서 ON 저장·OFF 허용 계약을 확인한다. */
    private fun checkFeatureGates(screen: MainActivity, before: AppSettings) {
        val changes = java.util.concurrent.atomic.AtomicIntegerArray(PermissionFeature.entries.size)
        val enabled = java.util.concurrent.atomic.AtomicIntegerArray(PermissionFeature.entries.size)
        shell("appops set com.wemade.teslamacro SYSTEM_ALERT_WINDOW deny")
        runOnMainSync { screen.setContent {
            var permissions by remember { mutableStateOf(false) }
            TeslaMacroTheme(dark = false) {
                CompositionLocalProvider(LocalOpenPermissionCheck provides { permissions = true }) {
                    if (permissions) SettingsScreen(before, onUnpair = {}, onStartPairing = {},
                        initialGroup = SettingsGroup.DEVICE, openPermissionCheck = true,
                        onBackToFeature = { permissions = false },
                        navigation = NavigationControls(onAppChange = {}, onHudOverlayChange = {}))
                    else Column {
                        PermissionFeature.entries.forEach { feature ->
                            val toggle = rememberPermissionToggle(feature) { value ->
                                enabled.set(feature.ordinal, if (value) 1 else 0)
                                changes.incrementAndGet(feature.ordinal)
                            }
                            Row {
                                TButton("ON ${feature.name}", fillWidth = false, onClick = { toggle(true) })
                                TButton("OFF ${feature.name}", fillWidth = false, onClick = { toggle(false) })
                            }
                        }
                    }
                }
            }
        } }
        clickText("ON HUD")
        waitText("필수 권한 확인")
        check(hasText("다른 앱 위에 표시"))
        check(changes.get(PermissionFeature.HUD.ordinal) == 0)
        clickText("취소")
        clickText("ON HUD")
        clickText("권한 점검")
        waitText("부족한 권한 준비")
        check(enabled.get(PermissionFeature.HUD.ordinal) == 0)
        shell("input keyevent KEYCODE_BACK")
        clickText("기능으로 돌아가기")
        shell("appops set com.wemade.teslamacro SYSTEM_ALERT_WINDOW allow")
        clickText("ON HUD")
        waitForIdleSync()
        check(enabled.get(PermissionFeature.HUD.ordinal) == 1)
        shell("appops set com.wemade.teslamacro SYSTEM_ALERT_WINDOW deny")
        clickText("OFF HUD")
        waitForIdleSync()
        check(enabled.get(PermissionFeature.HUD.ordinal) == 0)
        check(changes.get(PermissionFeature.HUD.ordinal) == 2)
        clickText("ON TESLA_SHARE")
        waitText("필수 권한 확인")
        check(hasText("알림 접근") && hasText("내비 목적지 읽기") && hasText("다른 앱 위에 표시"))
        check(changes.get(PermissionFeature.TESLA_SHARE.ordinal) == 0)
        clickText("취소")
        clickText("ON WIRELESS")
        waitText("무선 ADB 연결 준비")
        check(changes.get(PermissionFeature.WIRELESS.ordinal) == 0)
        clickText("취소")
        clickText("ON UPDATE")
        waitText("필수 권한 확인")
        check(hasText("앱 업데이트 설치"))
        check(changes.get(PermissionFeature.UPDATE.ordinal) == 0)
        clickText("취소")
        // 오버레이 거부는 필요 없는 차량 기록의 ON까지 막지 않는다.
        clickText("ON HISTORY")
        waitForIdleSync()
        check(enabled.get(PermissionFeature.HISTORY.ordinal) == 1)
        val shareChanges = java.util.concurrent.atomic.AtomicInteger(0)
        runOnMainSync { screen.setContent { TeslaMacroTheme(dark = false) {
            SettingsScreen(before.copy(teslaNavigationShareEnabled = true), onUnpair = {}, onStartPairing = {},
                initialGroup = SettingsGroup.DRIVING,
                navigation = NavigationControls(onAppChange = {}, onHudOverlayChange = {},
                    onTeslaNavigationShareEnabled = { check(!it); shareChanges.incrementAndGet() }))
        } } }
        waitText("테슬라 내비 연동")
        check(!hasText("목적지 화면 읽기") && !hasText("목적지 선택창 권한") && !hasText("알림 접근"))
        clickText("자동 목적지 공유")
        waitForIdleSync()
        check(shareChanges.get() == 1)
    }

    /** 화면 텍스트를 기다려 임의 지연 없이 모달 표시를 관측한다. */
    private fun waitText(text: String) = runBlocking {
        try { withTimeout(10_000) { while (!hasText(text)) delay(100) } }
        catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            error("화면 텍스트 대기 실패: $text · ${activeTexts()}")
        }
    }

    /** 대기 실패 때 현재 창의 한정된 텍스트만 남겨 제품·계측 오류를 구분한다. */
    private fun activeTexts(): String {
        val pending = java.util.ArrayDeque<AccessibilityNodeInfo>()
        uiAutomation.rootInActiveWindow?.let { pending.add(it) }
        val texts = mutableListOf<String>()
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 150) {
            val node = pending.removeFirst()
            node.text?.let { texts.add(it.toString()) }
            for (index in 0 until node.childCount) node.getChild(index)?.let { pending.add(it) }
            node.recycle()
        }
        pending.forEach { it.recycle() }
        return texts.take(35).joinToString(" | ")
    }

    /** 활성 창의 한정된 텍스트 검색으로 현재 화면만 검사한다. */
    private fun hasText(text: String): Boolean {
        val nodes = matchingNodes(text)
        val found = nodes.isNotEmpty()
        nodes.forEach { it.recycle() }
        return found
    }

    /** Compose 가상 노드의 텍스트 검색 누락을 피하고 활성 창의 실제 노드를 읽는다. */
    private fun matchingNodes(text: String): List<AccessibilityNodeInfo> {
        val pending = java.util.ArrayDeque<AccessibilityNodeInfo>()
        uiAutomation.rootInActiveWindow?.let { pending.add(it) }
        val matched = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 200) {
            val node = pending.removeFirst()
            for (index in 0 until node.childCount) node.getChild(index)?.let { pending.add(it) }
            if (node.text?.toString()?.contains(text) == true || node.contentDescription?.toString()?.contains(text) == true) matched.add(node) else node.recycle()
        }
        pending.forEach { it.recycle() }
        return matched
    }

    /** 실제 설정 행을 눌러 버튼 계약과 Android 설정 전환을 함께 검증한다. */
    private fun clickText(text: String) {
        waitText(text)
        val nodes = matchingNodes(text)
        var clicked = false
        nodes.forEach { node ->
            var target: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
            var count = 0
            while (!clicked && target != null && count++ < 4) {
                clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                val parent = if (clicked) null else target.parent
                target.recycle(); target = parent
            }
            target?.recycle(); node.recycle()
        }
        check(clicked) { "행 클릭 실패: $text" }
    }

    /** OS 권한 팝업의 실제 허용·거부 버튼으로 결과 콜백을 실행한다. */
    private fun clickPermission(button: String) = runBlocking { withTimeout(5_000) {
        uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        var clicked = false
        while (!clicked) {
            val root = uiAutomation.rootInActiveWindow
            if (root != null) {
                val nodes = listOf("com.google.android.permissioncontroller", "com.android.permissioncontroller")
                    .flatMap { root.findAccessibilityNodeInfosByViewId("$it:id/$button") }
                nodes.forEach { if (!clicked) clicked = it.performAction(AccessibilityNodeInfo.ACTION_CLICK); it.recycle() }
                root.recycle()
            }
            if (!clicked) delay(100)
        }
    } }

    /** 테스트 전용 Android 셸에서 상태만 바꾸며 실기기에서는 시작 전에 차단한다. */
    private fun shell(command: String): String = uiAutomation.executeShellCommand(command)
        .let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() } }
}
