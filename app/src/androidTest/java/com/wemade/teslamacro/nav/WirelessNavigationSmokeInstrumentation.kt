package com.wemade.teslamacro.nav

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import com.wemade.teslamacro.data.nav.NavigationSetup
import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import com.wemade.teslamacro.data.nav.LocalAdbIdentity
import com.wemade.teslamacro.data.nav.WirelessNavigation
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** 로컬 에뮬레이터와 지도 대체 앱으로 연결·잠금 실행·오디오 해제 종료를 검사한다. */
class WirelessNavigationSmokeInstrumentation : Instrumentation() {
    private var setupOnly = false
    private var prepareOnly = false
    private var pairOnly = false
    private var recoveryOnly = false

    /** 별도 계측 실행기로만 테스트를 시작하며 일반 앱 실행에는 포함하지 않는다. */
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        setupOnly = arguments?.getString("setupOnly") == "true"
        prepareOnly = arguments?.getString("prepareOnly") == "true"
        pairOnly = arguments?.getString("pairOnly") == "true"
        recoveryOnly = arguments?.getString("recoveryOnly") == "true"
        start()
    }

    /** RSA 저장 복원과 로컬 ADB 세션을 검증하고 자동 실행 선택은 끝날 때 해제한다. */
    override fun onStart() {
        val result = Bundle()
        var status = Activity.RESULT_OK
        var controller: WirelessNavigation? = null
        val key = File(targetContext.cacheDir, "nav-smoke-key.p12")
        try {
            check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Emulator only" }
            val original = LocalAdbIdentity(key).load()
            val restored = LocalAdbIdentity(key).load()
            check(original.first.encoded.contentEquals(restored.first.encoded))
            restored.second.verify(restored.second.publicKey)
            runBlocking {
                val navigation = withContext(Dispatchers.Main) {
                    (if (pairOnly || setupOnly) (targetContext.applicationContext as com.wemade.teslamacro.TeslaMacroApplication)
                        .container.wirelessNavigation else WirelessNavigation(targetContext)).also { controller = it }
                }
                if (setupOnly) { verifySetup(navigation); return@runBlocking }
                if (pairOnly) {
                    val automation = getUiAutomation()
                    shell("settings delete secure enabled_accessibility_services")
                    check(android.provider.Settings.Secure.getString(targetContext.contentResolver, "enabled_accessibility_services").isNullOrEmpty())
                    targetContext.getSharedPreferences("wireless_navigation", 0).edit().putBoolean("configured", false).commit()
                    withContext(Dispatchers.Main) {
                        navigation.setEnabled(false)
                        com.wemade.teslamacro.data.nav.NavigationPairingService.begin(targetContext)
                    }
                    // 테스트에서만 설정 UI를 조작하며 제품에는 화면 읽기 권한이 없다.
                    var code: String? = null
                    withTimeout(60_000) {
                        while (code == null) {
                            val nodes = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
                            automation.rootInActiveWindow?.let { collect(it, nodes) }
                            val texts = nodes.mapNotNull { it.text?.toString() }
                            val displayed = texts.map { it.replace(" ", "") }.firstOrNull { it.matches(Regex("[0-9]{6}")) }
                            if (displayed != null && texts.any { it.contains("pairing code", true) }) {
                                code = displayed
                            } else {
                                val names = listOf("Allow", "ALLOW", "Pair device with pairing code", "Use wireless debugging", "Wireless debugging")
                                val node = names.firstNotNullOfOrNull { name -> nodes.firstOrNull { it.text?.toString() == name && it.isEnabled } }
                                if (node != null) click(node)
                                else nodes.asReversed().firstOrNull { it.isScrollable }?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                            }
                            delay(500)
                        }
                    }
                    sendStatus(0, Bundle().apply { putString("phase", "Pairing dialog ready; sending notification input") })
                    val manager = targetContext.getSystemService(android.app.NotificationManager::class.java)
                    val action = withTimeout(5000) {
                        var action: android.app.Notification.Action? = null
                        while (action == null) {
                            action = manager.activeNotifications.flatMap { it.notification.actions?.toList().orEmpty() }
                                .firstOrNull { !it.remoteInputs.isNullOrEmpty() }
                            if (action == null) delay(100)
                        }
                        action
                    }
                    // 잘못된 입력은 연결 없이 재입력을 허용하고 실제 코드는 로그에 쓰지 않는다.
                    reply(action, "123")
                    withTimeout(5000) {
                        while (manager.activeNotifications.none { it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.contains("숫자 6자리") == true }) delay(100)
                    }
                    check(!navigation.state.value.busy)
                    reply(action, code!!)
                    reply(action, "000000")
                    withTimeout(65_000) { navigation.state.first { it.prepared && !it.busy } }
                    check(navigation.state.value.message.startsWith("페어링·준비 완료")) { navigation.state.value.message }
                    check(navigation.state.value.port.isNotEmpty())
                    withTimeout(5000) {
                        while (manager.activeNotifications.any { it.notification.channelId == "navigation_pairing" && !it.notification.actions.isNullOrEmpty() }) delay(100)
                    }
                    // 종료된 알림을 재전송해도 새 페어링 작업을 시작하지 않는다.
                    reply(action, "000000")
                    delay(500)
                    check(!navigation.state.value.busy && navigation.state.value.prepared)
                    withContext(Dispatchers.Main) {
                        val preparation = navigation.prepare()
                        check(navigation.state.value.busy)
                        check(NavigationSetup.resolve(targetContext) == NavigationSetup.Step.READY)
                        preparation?.join()
                    }
                    return@runBlocking
                }
                withContext(Dispatchers.Main) {
                    navigation.setPort(if (recoveryOnly) "65534" else "5557")
                    navigation.setEnabled(false)
                    if (prepareOnly) navigation.prepare()
                }
                if (recoveryOnly) {
                    check(android.provider.Settings.Global.getInt(targetContext.contentResolver, "wifi_on", 0) == 0)
                    withContext(Dispatchers.Main) { navigation.setEnabled(true) }
                    withTimeout(40_000) { navigation.state.first { !it.busy && !it.prepared } }
                    val automation = getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("svc wifi enable")).use { it.readBytes() }
                    withTimeout(45_000) { navigation.state.first { it.prepared && !it.busy } }
                    check(navigation.state.value.port != "65534")
                    check(android.provider.Settings.Global.getInt(targetContext.contentResolver, "adb_wifi_enabled", 0) == 0)
                    return@runBlocking
                }
                if (prepareOnly) {
                    withTimeout(40_000) { navigation.state.first { it.prepared && !it.busy } }
                    return@runBlocking
                }
                // Wi-Fi·무선 디버깅을 끄고 연결 불가능한 포트로 바꿔 Binder 재사용을 확인한다.
                check(android.provider.Settings.Global.getInt(targetContext.contentResolver, "wifi_on", 0) == 0)
                check(android.provider.Settings.Global.getInt(targetContext.contentResolver, "adb_wifi_enabled", 0) == 0)
                withContext(Dispatchers.Main) {
                    navigation.setPort("65534")
                    navigation.setEnabled(true)
                }
                withTimeout(10_000) { navigation.state.first { it.prepared && !it.busy } }
                withContext(Dispatchers.Main) {
                    navigation.vehicleChanged(true)
                }
                withTimeout(30_000) { navigation.state.first { it.running } }
                withContext(Dispatchers.Main) { navigation.vehicleChanged(false) }
                delay(1_000)
                withContext(Dispatchers.Main) { navigation.vehicleChanged(true) }
                check(navigation.state.value.running)
                withContext(Dispatchers.Main) { navigation.vehicleChanged(false) }
                withTimeout(50_000) { navigation.state.first { !it.running && !it.busy } }
                check(navigation.state.value.message == "실험 종료 완료") { navigation.state.value.message }
                repeat(2) {
                    withContext(Dispatchers.Main) { navigation.start(false) }
                    withTimeout(20_000) { navigation.state.first { it.running } }
                    withContext(Dispatchers.Main) { navigation.stop() }
                    withTimeout(20_000) { navigation.state.first { !it.running && !it.busy } }
                    check(navigation.state.value.message == "실험 종료 완료") { navigation.state.value.message }
                }
            }
            result.putString("result", if (setupOnly) "PASS: real setup button opens blocked notification settings, unchanged back does not loop, permission grant continues to pairing, Wi-Fi settings route and resume" else if (recoveryOnly) "PASS: helper absent, Wi-Fi arrival automatically restores helper through TLS, wireless debugging restored off"
                else if (pairOnly) "PASS: no accessibility, invalid/replayed reply rejected, notification TLS pairing, pairing/connect port discovery, detached helper and saved-auth reuse"
                else if (prepareOnly) "PASS: detached helper prepared" else "PASS: Wi-Fi and wireless debugging off, unusable ADB port, key persistence, reconnect grace, automatic stop, repeated start/stop")
        } catch (error: Throwable) {
            status = Activity.RESULT_CANCELED
            result.putString("result", "FAIL: ${error.javaClass.simpleName}: ${error.message}; ${error.stackTrace.firstOrNull { it.className.contains("WirelessNavigationSmoke") }}")
        } finally {
            runOnMainSync { controller?.setEnabled(false) }
            key.delete()
        }
        finish(status, result)
    }
    /** 실제 설정 버튼과 화면 복귀를 사용해 권한 거부·부족한 설정·다음 단계 이동을 검사한다. */
    private suspend fun verifySetup(navigation: WirelessNavigation) {
        withTimeout(20000) { while (!NavigationSetup.read(targetContext).wifi) delay(100) }
        check(NavigationSetup.read(targetContext).let { it.developer && it.usb && !it.notifications }) { "Setup prerequisites: ${NavigationSetup.read(targetContext)}" }
        targetContext.getSharedPreferences("wireless_navigation", 0).edit().putBoolean("configured", false).commit()
        val activity = startActivitySync(android.content.Intent(targetContext, com.wemade.teslamacro.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as com.wemade.teslamacro.MainActivity
        runOnMainSync {
            activity.setContent {
                com.wemade.teslamacro.ui.theme.TeslaMacroTheme(dark = false) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        com.wemade.teslamacro.feature.settings.WirelessNavigationPanel(
                            com.wemade.teslamacro.data.settings.AppSettings(),
                            com.wemade.teslamacro.feature.settings.NavigationControls(onAppChange = {}, onHudOverlayChange = {}))
                    }
                }
            }
        }
        clickSetup()
        awaitSettings("AppNotificationSettings")
        uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        withTimeout(5000) { while (uiAutomation.rootInActiveWindow?.packageName?.toString() != targetContext.packageName) delay(100) }
        delay(800)
        check(resumedActivity().contains(targetContext.packageName)) { "Unchanged settings reopened: ${resumedActivity()}; notifications=${NavigationSetup.read(targetContext).notifications}" }
        clickSetup()
        awaitSettings("AppNotificationSettings")
        uiAutomation.grantRuntimePermission(targetContext.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        awaitSettings("DevelopmentSettingsDashboardActivity")
        // 알림 입력은 별도 TLS 검사에서 검증하며 여기서는 다음 설정으로의 실제 이동만 확인한다.
        targetContext.stopService(android.content.Intent(targetContext, com.wemade.teslamacro.data.nav.NavigationPairingService::class.java))
        shell("svc wifi disable")
        withTimeout(10000) { while (NavigationSetup.read(targetContext).wifi) delay(100) }
        uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        clickSetup()
        awaitSettings("WifiSettings")
        shell("svc wifi enable")
        withTimeout(20000) { while (!NavigationSetup.read(targetContext).wifi) delay(100) }
        uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        awaitSettings("DevelopmentSettingsDashboardActivity")
        targetContext.stopService(android.content.Intent(targetContext, com.wemade.teslamacro.data.nav.NavigationPairingService::class.java))
        runOnMainSync { activity.finish(); navigation.setEnabled(false) }
    }

    /** 제품 버튼을 실제 접근성 노드로 눌러 클릭 콜백과 설정 이동까지 검사한다. */
    private suspend fun clickSetup() = withTimeout(10000) {
        while (true) {
            val nodes = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
            uiAutomation.rootInActiveWindow?.let { collect(it, nodes) }
            val button = nodes.firstOrNull { it.text?.toString() == "연결 설정" && it.isEnabled && it.isVisibleToUser }
            if (button != null) { click(button); return@withTimeout }
            delay(100)
        }
    }

    /** 설정 화면 종류만 읽고 화면의 코드나 사용자 입력 내용은 출력하지 않는다. */
    private suspend fun awaitSettings(name: String) {
        sendStatus(0, Bundle().apply { putString("phase", "Waiting for $name") })
        val reached = withTimeoutOrNull(12000) {
            while (true) {
                if (resumedActivity().contains(name)) return@withTimeoutOrNull true
                delay(100)
            }
        }
        check(reached == true) { "Settings timeout: $name; actual=${resumedActivity()}" }
    }

    /** 토스트 창과 실제 화면 이동을 혼동하지 않도록 재개된 Activity만 확인한다. */
    private fun resumedActivity(): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand("dumpsys activity activities"))
        .bufferedReader().use { it.readText() }.lineSequence().firstOrNull { it.contains("topResumedActivity=") }.orEmpty()

    /** 테스트 셸은 로컬 에뮬레이터 준비에만 사용한다. */
    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    /** 테스트만 시스템 설정 노드를 제한 깊이로 수집한다. */
    private fun collect(node: android.view.accessibility.AccessibilityNodeInfo, result: MutableList<android.view.accessibility.AccessibilityNodeInfo>, depth: Int = 0) {
        if (depth > 40 || result.size > 500) return
        result.add(node)
        for (index in 0 until node.childCount) node.getChild(index)?.let { collect(it, result, depth + 1) }
    }

    /** 테스트가 설정 버튼의 실제 클릭 가능한 부모를 찾는다. */
    private fun click(node: android.view.accessibility.AccessibilityNodeInfo) {
        var current: android.view.accessibility.AccessibilityNodeInfo? = node
        repeat(5) {
            val item = current ?: return
            if (item.isCheckable && item.isChecked) return
            if (item.isClickable) { item.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK); return }
            current = item.parent
        }
    }

    /** 시스템 알림과 동일한 RemoteInput 경로로만 제품 서비스에 전달한다. */
    private fun reply(action: android.app.Notification.Action, code: String) {
        val intent = android.content.Intent()
        android.app.RemoteInput.addResultsToIntent(action.remoteInputs, intent, Bundle().apply { putCharSequence(action.remoteInputs.first().resultKey, code) })
        action.actionIntent.send(targetContext, 0, intent)
    }

}
