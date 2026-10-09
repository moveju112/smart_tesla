package com.wemade.teslamacro.service

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ShortcutManager
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.wemade.teslamacro.TeslaMacroApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** 빈 테스트 에뮬레이터에서 인증 경계와 런처 바로가기를 실제 Android로 확인한다. */
class QuickActionSmokeInstrumentation : Instrumentation() {
    /** 프로젝트의 기존 플랫폼 실행기 방식으로 추가 테스트 프레임워크 없이 시작한다. */
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    /** 인증 없는 요청은 확인창만 열고 인증한 요청만 서비스로 전달되는지 검사한다. */
    override fun onStart() {
        val result = Bundle()
        var resultCode = Activity.RESULT_OK
        try {
            val app = targetContext.applicationContext as TeslaMacroApplication
            runBlocking { withTimeout(30_000L) { app.ready.first { it } } }
            val settings = runBlocking { app.container.settingsStore.settings.first() }
            check(settings.vin.isBlank() && !settings.isEnrolled && !settings.fleetApiEnabled) {
                "실차 설정이 없는 테스트 기기에서만 실행해야 합니다"
            }
            val access = app.container.quickActionAccess
            uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            val initialCount = app.container.quickActionRequests.requests.value.size
            checkConfirmation(Intent(targetContext, QuickActionActivity::class.java)
                .putExtra(QuickActionActivity.EXTRA_ACTION, "unlock"))
            check(app.container.quickActionRequests.requests.value.size == initialCount)

            val key = access.automationKey()
            checkConfirmation(Intent(targetContext, QuickActionActivity::class.java)
                .putExtra(QuickActionActivity.EXTRA_ACTION, "unlock")
                .putExtra(QuickActionActivity.EXTRA_AUTOMATION_KEY, "invalid"))
            check(app.container.quickActionRequests.requests.value.size == initialCount)

            launchAuthenticated(app, Intent(targetContext, QuickActionActivity::class.java)
                .putExtra(QuickActionActivity.EXTRA_ACTION, "lock")
                .putExtra(QuickActionActivity.EXTRA_AUTOMATION_KEY, key), initialCount)
            val token = access.shortcutToken("lock", null)
            val afterKey = app.container.quickActionRequests.requests.value.size
            checkConfirmation(Intent(targetContext, QuickActionActivity::class.java)
                .putExtra(QuickActionActivity.EXTRA_ACTION, "unlock")
                .putExtra(QuickActionActivity.EXTRA_SHORTCUT_TOKEN, token))
            check(app.container.quickActionRequests.requests.value.size == afterKey)
            launchAuthenticated(app, Intent(targetContext, QuickActionActivity::class.java)
                .putExtra(QuickActionActivity.EXTRA_ACTION, "lock")
                .putExtra(QuickActionActivity.EXTRA_SHORTCUT_TOKEN, token), afterKey)

            val shortcuts = targetContext.getSystemService(ShortcutManager::class.java).dynamicShortcuts
            check(shortcuts.map { it.id }.containsAll(listOf("open_frunk", "open_trunk", "vent_windows")))
            for (shortcut in shortcuts) {
                val intent = checkNotNull(shortcut.intent)
                check(access.isAuthorized(intent.getStringExtra(QuickActionActivity.EXTRA_ACTION),
                    intent.getStringExtra(QuickActionActivity.EXTRA_MACRO_ID), null,
                    intent.getStringExtra(QuickActionActivity.EXTRA_SHORTCUT_TOKEN)))
            }
            access.revokeAutomationKey()
            check(!access.isAuthorized("lock", null, key, null))
            check(access.isAuthorized("lock", null, null, token))
            result.putString("result", "PASS: missing/invalid credentials, tampered shortcut, authorized dispatch, signed launcher shortcuts, key revocation")
        } catch (error: Throwable) {
            resultCode = Activity.RESULT_CANCELED
            result.putString("error", "${error.javaClass.simpleName}: ${error.message}")
        }
        finish(resultCode, result)
    }

    /** 실제 사용자 확인 화면이 열리며 취소해도 차량 명령이 접수되지 않음을 확인한다. */
    private fun checkConfirmation(intent: Intent) {
        val monitor = addMonitor(QuickActionConfirmationActivity::class.java.name, null, false)
        var activity: Activity? = null
        try {
            // 접근성 연결을 먼저 열고 실제 제목 노출 이벤트를 기다려 첫 프레임을 놓치지 않는다.
            uiAutomation.executeAndWaitForEvent(
                { runOnMainSync { targetContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                { uiAutomation.windows.any { hasConfirmationTitle(it.root) } },
                5_000L,
            )
            activity = checkNotNull(waitForMonitorWithTimeout(monitor, 5_000L)) { "인증 없는 요청의 확인창이 열리지 않았습니다" }
            waitForIdleSync()
            check(uiAutomation.windows.any { hasConfirmationTitle(it.root) }) {
                "외부 요청의 확인 제목이 표시되지 않았습니다"
            }
        } finally {
            runOnMainSync { (activity ?: monitor.lastActivity)?.finish() }
            waitForIdleSync()
            removeMonitor(monitor)
        }
    }

    /** 기존 위젯 테스트처럼 Compose 노드를 직접 순회해 플랫폼 문자열 검색의 누락을 피한다. */
    private fun hasConfirmationTitle(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.text?.toString() == "외부 요청 확인") return true
        return (0 until node.childCount).any { hasConfirmationTitle(node.getChild(it)) }
    }

    /** 인증 성공은 확인창 없이 서비스 접수까지만 검사하며 등록되지 않은 차량에 전송하지 않는다. */
    private fun launchAuthenticated(app: TeslaMacroApplication, intent: Intent, previousCount: Int) {
        val monitor = addMonitor(QuickActionConfirmationActivity::class.java.name, null, false)
        try {
            runOnMainSync { targetContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            runBlocking {
                withTimeout(5_000L) { app.container.quickActionRequests.requests.first { it.size > previousCount } }
            }
            waitForIdleSync()
            check(monitor.hits == 0) { "인증한 요청이 불필요한 확인창을 열었습니다" }
        } finally {
            removeMonitor(monitor)
        }
    }
}
