package com.wemade.teslamacro.service

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ShortcutManager
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.wemade.teslamacro.TeslaMacroApplication
import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.domain.macro.ActionStep
import com.wemade.teslamacro.domain.macro.MacroRule
import com.wemade.teslamacro.domain.macro.Trigger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** 빈 테스트 에뮬레이터에서 인증 경계와 런처 바로가기를 실제 Android로 확인한다. */
class QuickActionSmokeInstrumentation : Instrumentation() {
    private var upgradePhase: String? = null
    /** 프로젝트의 기존 플랫폼 실행기 방식으로 추가 테스트 프레임워크 없이 시작한다. */
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        upgradePhase = arguments?.getString("upgradePhase")
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
            uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            val access = app.container.quickActionAccess
            if (upgradePhase == "verifyUpgrade") verifyUpgrade(app)
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
            check(shortcuts.map { it.id }.containsAll(listOf("quick-v2-open_frunk", "quick-v2-open_trunk", "quick-v2-vent_windows")))
            for (shortcut in shortcuts) {
                val intent = checkNotNull(shortcut.intent)
                check(access.isAuthorized(intent.getStringExtra(QuickActionActivity.EXTRA_ACTION),
                    intent.getStringExtra(QuickActionActivity.EXTRA_MACRO_ID), null,
                    intent.getStringExtra(QuickActionActivity.EXTRA_SHORTCUT_TOKEN)))
            }
            val beforeShortcut = app.container.quickActionRequests.requests.value.size
            launchAuthenticated(app, checkNotNull(shortcuts.first { it.id == "quick-v2-open_frunk" }.intent), beforeShortcut)
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

    /** 고정 상태 업데이트에서 데이터 보존과 매크로 추가·수정 후 실제 발행을 확인한다. */
    private fun verifyUpgrade(app: TeslaMacroApplication) {
        val manager = targetContext.getSystemService(ShortcutManager::class.java)
        check(manager.pinnedShortcuts.any { it.id == "open_frunk" && it.isImmutable && !it.isEnabled })
        val rule = upgradeRule()
        check(app.container.ruleStore.rules.value.first { it.id == rule.id } == rule)
        check(targetContext.getSharedPreferences("shortcut-upgrade-fixture", 0).getInt("marker", 0) == 77)
        awaitShortcut("quick-v2-open_frunk")
        awaitShortcut("macro-${rule.id}", rule.name)
        val renamed = rule.copy(name = "! 고정 업데이트 수정")
        runBlocking { app.container.ruleStore.upsert(renamed) }
        awaitShortcut("macro-${rule.id}", renamed.name)
        val added = rule.copy(id = "shortcut-upgrade-added", name = "!! 새 매크로")
        runBlocking { app.container.ruleStore.upsert(added) }
        awaitShortcut("macro-${added.id}", added.name)
        check(app.container.ruleStore.rules.value.first { it.id == rule.id } == renamed)
        runBlocking { app.container.ruleStore.delete(added.id) }
        awaitShortcut("macro-${rule.id}", renamed.name)
    }

    /** 수동·비활성 매크로는 테스트 도중 자동으로 차량 명령을 실행하지 않는다. */
    private fun upgradeRule() = MacroRule(
        id = "shortcut-upgrade-preserved", name = "! 업데이트 보존", enabled = false,
        triggers = listOf(Trigger.Manual), actions = listOf(ActionStep.Run(VehicleCommand.Lock)),
    )

    /** 저장 완료 뒤 실제 Android 바로가기 갱신이 관측될 때까지만 기다린다. */
    private fun awaitShortcut(id: String, label: String? = null) = runBlocking {
        val manager = targetContext.getSystemService(ShortcutManager::class.java)
        withTimeout(5_000L) {
            while (manager.dynamicShortcuts.none { it.id == id && (label == null || it.shortLabel.toString() == label) }) delay(50L)
        }
    }

    /** 기본 Activity 창과 Compose Dialog 창을 같은 접근성 경로로 조회한다. */
    private fun findWindowNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
        uiAutomation.windows.firstNotNullOfOrNull { findNode(it.root, predicate) }

    /** 기존 위젯 테스트 방식으로 Compose 노드를 직접 순회한다. */
    private fun findNode(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        return (0 until node.childCount).firstNotNullOfOrNull { findNode(node.getChild(it), predicate) }
    }

    /** 실제 사용자 확인 화면이 열리며 취소해도 차량 명령이 접수되지 않음을 확인한다. */
    private fun checkConfirmation(intent: Intent) {
        val monitor = addMonitor(QuickActionConfirmationActivity::class.java.name, null, false)
        var activity: Activity? = null
        try {
            // 접근성 연결을 먼저 열고 실제 제목 노출 이벤트를 기다려 첫 프레임을 놓치지 않는다.
            uiAutomation.executeAndWaitForEvent(
                { runOnMainSync { targetContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                { findWindowNode { it.text?.toString() == "외부 요청 확인" } != null },
                5_000L,
            ).recycle()
            activity = checkNotNull(waitForMonitorWithTimeout(monitor, 5_000L)) { "인증 없는 요청의 확인창이 열리지 않았습니다" }
            waitForIdleSync()
            check(findWindowNode { it.text?.toString() == "외부 요청 확인" } != null) {
                "외부 요청의 확인 제목이 표시되지 않았습니다"
            }
        } finally {
            runOnMainSync { (activity ?: monitor.lastActivity)?.finish() }
            waitForIdleSync()
            removeMonitor(monitor)
        }
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
