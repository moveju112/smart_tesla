package com.wemade.teslamacro.nav

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
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
    private var panelOnly = false
    private var setupOnly = false
    private var prepareOnly = false
    private var pairOnly = false
    private var recoveryOnly = false

    /** 별도 계측 실행기로만 테스트를 시작하며 일반 앱 실행에는 포함하지 않는다. */
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        panelOnly = arguments?.getString("panelOnly") == "true"
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
                if (panelOnly) { verifyPanel(); return@runBlocking }
                val navigation = withContext(Dispatchers.Main) {
                    (if (pairOnly || setupOnly) (targetContext.applicationContext as com.wemade.teslamacro.TeslaMacroApplication)
                        .container.wirelessNavigation else WirelessNavigation(targetContext)).also { controller = it }
                }
                if (setupOnly) { verifySetup(navigation); return@runBlocking }
                if (pairOnly) {
                    // 이전 계측에서 남은 하위 설정 화면을 재사용하지 않는다.
                    shell("am force-stop com.android.settings")
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
                    var scrollForward = true
                    withTimeout(60_000) {
                        while (code == null) {
                            val nodes = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
                            automation.rootInActiveWindow?.let { collect(it, nodes) }
                            val texts = nodes.mapNotNull { it.text?.toString() }
                            val displayed = texts.map { it.replace(" ", "") }.firstOrNull { it.matches(Regex("[0-9]{6}")) }
                            if (displayed != null && texts.any { it.contains("pairing code", true) }) {
                                code = displayed
                            } else {
                                // 최초 Wi-Fi 신뢰는 테스트 기기에서 명시적으로 허용하며 제품은 이를 자동 선택하지 않는다.
                                val trust = nodes.firstOrNull { it.isCheckable && !it.isChecked && it.text?.toString()?.contains("Always allow", true) == true }
                                val names = listOf("Allow", "ALLOW", "Pair device with pairing code", "Use wireless debugging", "Wireless debugging")
                                val node = trust ?: names.firstNotNullOfOrNull { name -> nodes.firstOrNull { it.text?.toString() == name && it.isEnabled } }
                                if (node != null) click(node)
                                else {
                                    val list = nodes.asReversed().firstOrNull { it.isScrollable }
                                    val direction = if (scrollForward) android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                                        else android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                                    if (list?.performAction(direction) == false) scrollForward = !scrollForward
                                }
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
                    check(android.provider.Settings.Global.getInt(targetContext.contentResolver, "adb_wifi_enabled", 0) == 0)
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
                    // 이미 준비된 Binder를 재사용할 때도 켜져 있던 무선 디버깅을 닫는다.
                    shell("settings put global adb_wifi_enabled 1")
                    withContext(Dispatchers.Main) { navigation.prepare() }?.join()
                    check(android.provider.Settings.Global.getInt(targetContext.contentResolver, "adb_wifi_enabled", 0) == 0)
                    // 이 일회용 에뮬레이터에서 만든 셸 프로세스만 종료해 실제 재준비를 검증한다.
                    val process = shell("ps -A -o PID,ARGS").lineSequence().map { it.trim().split(Regex("\\s+")) }
                        .single { it.size == 5 && it[1] == "app_process" && it[3] == "com.wemade.teslamacro.data.nav.NaverControlServer" && it[4] == android.os.Process.myUid().toString() }
                    check(process[0].all(Char::isDigit))
                    shell("kill " + process[0])
                    sendStatus(0, Bundle().apply { putString("phase", "Waiting for helper termination") })
                    withTimeout(5000) {
                        while (com.wemade.teslamacro.data.nav.NavigationBridgeProvider.bridge?.pingBinder() == true) delay(100)
                    }
                    sendStatus(0, Bundle().apply { putString("phase", "Waiting for on-demand debugging") })
                    val recovery = withContext(Dispatchers.Main) { navigation.prepare() }
                    withTimeout(5000) {
                        while (android.provider.Settings.Global.getInt(targetContext.contentResolver, "adb_wifi_enabled", 0) == 0) delay(20)
                    }
                    sendStatus(0, Bundle().apply { putString("phase", "Waiting for recovery completion") })
                    recovery?.join()
                    check(navigation.state.value.prepared)
                    check(android.provider.Settings.Global.getInt(targetContext.contentResolver, "adb_wifi_enabled", 0) == 0)
                    check(com.wemade.teslamacro.data.nav.NavigationChannel().connect().use { it.status() } == "AVAILABLE")
                    sendStatus(0, Bundle().apply { putString("phase", "Checking failure and cancellation cleanup") })
                    // 실패한 재페어링도 디버깅을 남기지 않는다.
                    shell("settings put global adb_wifi_enabled 1")
                    withContext(Dispatchers.Main) { navigation.pair("65534", "000000") }?.join()
                    check(!navigation.state.value.busy)
                    check(android.provider.Settings.Global.getInt(targetContext.contentResolver, "adb_wifi_enabled", 0) == 0)
                    // 코드 입력 대기를 취소할 때도 권한을 얻은 기기의 무선 디버깅을 닫는다.
                    shell("settings put global adb_wifi_enabled 1")
                    withContext(Dispatchers.Main) {
                        com.wemade.teslamacro.data.nav.NavigationPairingService.begin(targetContext)
                    }
                    sendStatus(0, Bundle().apply { putString("phase", "Waiting for cancellation notification") })
                    withTimeout(5000) {
                        while (manager.activeNotifications.none { !it.notification.actions.isNullOrEmpty() }) delay(100)
                    }
                    sendStatus(0, Bundle().apply { putString("phase", "Stopping pairing service; busy=${navigation.state.value.busy}") })
                    targetContext.stopService(android.content.Intent(targetContext, com.wemade.teslamacro.data.nav.NavigationPairingService::class.java))
                    withTimeout(5000) {
                        while (android.provider.Settings.Global.getInt(targetContext.contentResolver, "adb_wifi_enabled", 0) != 0) delay(100)
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
                // 무선 디버깅을 끈 뒤 불가능한 포트에서도 명령 전 활성화와 Binder 재사용을 확인한다.
                check(android.provider.Settings.Global.getInt(targetContext.contentResolver, "wifi_on", 0) != 0)
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
                check(wirelessDebuggingEnabled())
                // 주행 중 설정이 꺼져도 다음 심박 명령 전에 다시 켠다.
                shell("settings put global adb_wifi_enabled 0")
                withTimeout(10_000) { while (!wirelessDebuggingEnabled()) delay(50) }
                check(navigation.state.value.running)
                withContext(Dispatchers.Main) { navigation.closeWirelessDebugging() }
                check(wirelessDebuggingEnabled())
                withContext(Dispatchers.Main) { navigation.vehicleChanged(false) }
                delay(1_000)
                check(wirelessDebuggingEnabled())
                withContext(Dispatchers.Main) { navigation.vehicleChanged(true) }
                check(navigation.state.value.running)
                // 수동 종료 후에도 탑승 유지 설정은 디버깅을 끄지 않는다.
                withContext(Dispatchers.Main) { navigation.stop() }
                withTimeout(20_000) { navigation.state.first { !it.running && !it.busy } }
                check(wirelessDebuggingEnabled())
                withContext(Dispatchers.Main) { navigation.start(false) }
                withTimeout(20_000) { navigation.state.first { it.running } }
                withContext(Dispatchers.Main) { navigation.vehicleChanged(false) }
                withTimeout(50_000) { navigation.state.first { !it.running && !it.busy } }
                check(navigation.state.value.message == "실험 종료 완료") { navigation.state.value.message }
                check(!wirelessDebuggingEnabled())
                repeat(2) {
                    withContext(Dispatchers.Main) { navigation.start(false) }
                    withTimeout(20_000) { navigation.state.first { it.running } }
                    check(wirelessDebuggingEnabled())
                    // 종료 명령도 디버깅이 꺼져 있으면 복구한 뒤 전송한다.
                    shell("settings put global adb_wifi_enabled 0")
                    withContext(Dispatchers.Main) { navigation.stop() }
                    withTimeout(20_000) { navigation.state.first { !it.running && !it.busy } }
                    check(navigation.state.value.message == "실험 종료 완료") { navigation.state.value.message }
                    check(!wirelessDebuggingEnabled())
                }
                // 실행 작업 없이 탑승만 남은 상태도 자동 실행 해제 시 정리한다.
                withContext(Dispatchers.Main) { navigation.vehicleChanged(true) }
                withTimeout(20_000) { navigation.state.first { it.running } }
                withContext(Dispatchers.Main) { navigation.stop() }
                withTimeout(20_000) { navigation.state.first { !it.running && !it.busy } }
                check(wirelessDebuggingEnabled())
                // 유예 중 수동 종료를 다시 눌러도 하차 정리 타이머는 유지한다.
                withContext(Dispatchers.Main) {
                    navigation.vehicleChanged(false)
                    navigation.stop()
                }
                check(wirelessDebuggingEnabled())
                withTimeout(35_000) { while (wirelessDebuggingEnabled()) delay(100) }
                withContext(Dispatchers.Main) { navigation.vehicleChanged(true) }
                withTimeout(20_000) { navigation.state.first { it.running } }
                withContext(Dispatchers.Main) { navigation.stop() }
                withTimeout(20_000) { navigation.state.first { !it.running && !it.busy } }
                check(wirelessDebuggingEnabled())
                withContext(Dispatchers.Main) { navigation.setEnabled(false) }
                check(!wirelessDebuggingEnabled())
            }
            result.putString("result", if (panelOnly) "PASS: no inline action result, repeated result Toasts from worker thread, silent progress, management/test sheets, active stop, unprepared setup"
                else if (setupOnly) "PASS: real setup button opens blocked notification settings, unchanged back does not loop, permission grant continues to pairing, Wi-Fi settings route and resume" else if (recoveryOnly) "PASS: helper absent, Wi-Fi arrival automatically restores helper through TLS, wireless debugging restored off"
                else if (pairOnly) "PASS: no accessibility, invalid/replayed reply rejected, notification TLS pairing, pairing/connect port discovery, detached helper, saved-auth reuse, debugging on-demand and off after success/failure/cancel"
                else if (prepareOnly) "PASS: detached helper prepared" else "PASS: debugging enabled before commands, retained during ride and reconnect grace, heartbeat recovery, manual stop retention, departure and disable cleanup, repeated start/stop")
        } catch (error: Throwable) {
            status = Activity.RESULT_CANCELED
            result.putString("result", "FAIL: ${error.javaClass.simpleName}: ${error.message}; ${error.stackTrace.firstOrNull { it.className.contains("WirelessNavigationSmoke") }}")
        } finally {
            targetContext.stopService(android.content.Intent(targetContext, com.wemade.teslamacro.data.nav.NavigationPairingService::class.java))
            runOnMainSync { controller?.setEnabled(false) }
            key.delete()
        }
        finish(status, result)
    }
    /** 설정 복구와 종료 시점을 실제 Android 설정값으로 검사한다. */
    private fun wirelessDebuggingEnabled(): Boolean =
        android.provider.Settings.Global.getInt(targetContext.contentResolver, "adb_wifi_enabled", 0) == 1
    /** 연결 단계별 노출과 상세 시트 동작을 실제 패널에서 검사한다. */
    private suspend fun verifyPanel() {
        val panelState = androidx.compose.runtime.mutableStateOf(
            com.wemade.teslamacro.data.nav.WirelessNavigationState(prepared = true, message = "연결 준비 완료"))
        var tests = 0
        var stops = 0
        val activity = startActivitySync(android.content.Intent(targetContext, com.wemade.teslamacro.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as com.wemade.teslamacro.MainActivity
        try {
            runOnMainSync {
                activity.setContent {
                    com.wemade.teslamacro.ui.theme.TeslaMacroTheme(dark = false) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            com.wemade.teslamacro.feature.settings.WirelessNavigationPanel(
                                com.wemade.teslamacro.data.settings.AppSettings(),
                                com.wemade.teslamacro.feature.settings.NavigationControls(
                                    wirelessState = panelState.value, onAppChange = {}, onHudOverlayChange = {},
                                    onWirelessTest = { tests++ }, onWirelessStop = { stops++ }, installed = setOf("NAVER", "TMAP")))
                        }
                    }
                }
            }
            awaitPanelText("연결 관리", true)
            awaitPanelText("연결 준비 완료", false)
            awaitPanelText("차량 오디오 선택", false)
            // 안심운전 앱 선택은 현재 선택 이름을 보여 주고 상태 변경을 그대로 반영한다.
            awaitPanelText("내비 앱", true)
            awaitPanelText("네이버 지도", true)
            runOnMainSync { panelState.value = panelState.value.copy(app = com.wemade.teslamacro.data.nav.NavigatorApp.TMAP) }
            awaitPanelText("티맵", true)
            runOnMainSync { panelState.value = panelState.value.copy(enabled = true) }
            awaitPanelText("설정 → 차량 → 탑승 감지 블루투스에서 기기를 선택해 주세요", true)
            runOnMainSync { panelState.value = panelState.value.copy(enabled = false) }
            for (text in listOf("연결 설정", "연결 준비 / 복구", "수동 페어링 / 연결 포트", "10초 뒤 테스트", "종료")) {
                awaitPanelText(text, false)
            }
            clickSetup("연결 관리")
            awaitPanelText("연결 준비 / 복구", true)
            awaitPanelText("수동 페어링 / 연결 포트", true)
            uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            awaitPanelText("연결 준비 / 복구", false)
            clickSetup("실행 점검")
            clickSetup("10초 뒤 테스트")
            runOnMainSync { check(tests == 1) }
            uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            awaitPanelText("10초 뒤 테스트", false)
            val completed = "실행 요청 완료 · 음성 안내를 확인해 주세요"
            runOnMainSync { panelState.value = panelState.value.copy(running = true, message = completed) }
            awaitPanelText(completed, false)
            clickSetup("종료")
            runOnMainSync { check(stops == 1) }
            awaitPanelText("연결 설정", false)
            runOnMainSync { panelState.value = panelState.value.copy(prepared = false, running = false, message = "무선 페어링 필요") }
            awaitPanelText("연결 설정", true)
            awaitPanelText("종료", false)
            sendStatus(0, Bundle().apply { putString("phase", "Checking result Toasts") })
            verifyFeedback(completed)
            sendStatus(0, Bundle().apply { putString("phase", "Checking shared feedback") })
            verifyActionFeedback(activity)
        } finally { runOnMainSync { activity.finish() } }
    }

    /** 작업 스레드의 동일 결과도 매번 Toast로 표시하고 진행 로그는 알림을 만들지 않는지 검사한다. */
    private fun verifyFeedback(message: String) {
        val navigation = (targetContext.applicationContext as com.wemade.teslamacro.TeslaMacroApplication)
            .container.wirelessNavigation
        val report = WirelessNavigation::class.java.getDeclaredMethod("report", String::class.java, java.lang.Boolean.TYPE)
            .apply { isAccessible = true }
        val filter = android.app.UiAutomation.AccessibilityEventFilter { event ->
            event.eventType == android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                event.packageName?.toString() == targetContext.packageName && event.text.any { it.toString() == message }
        }
        repeat(2) {
            uiAutomation.executeAndWaitForEvent({ report.invoke(navigation, message, true) }, filter, 8000).recycle()
        }
        try {
            uiAutomation.executeAndWaitForEvent({ report.invoke(navigation, message, false) }, filter, 800).recycle()
            error("Progress must not show a Toast")
        } catch (_: java.util.concurrent.TimeoutException) {
            check(navigation.state.value.message == message)
        }
    }

    /** 백업·Fleet·명령 결과가 본문을 밀지 않고 닫기·재시도·자동 종료 뒤 한 번만 소비되는지 검사한다. */
    private suspend fun verifyActionFeedback(activity: com.wemade.teslamacro.MainActivity) {
        val originalInfo = uiAutomation.serviceInfo
        uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val mode = androidx.compose.runtime.mutableIntStateOf(0)
        val message = androidx.compose.runtime.mutableStateOf<String?>(null)
        val requests = androidx.compose.runtime.mutableStateOf(listOf(
            com.wemade.teslamacro.service.QuickActionRequests.Request(1, "대기 명령", com.wemade.teslamacro.service.QuickActionRequests.Status.Waiting)))
        var dismissed = 0
        var retried = 0
        val consume = { message.value = null; dismissed++; Unit }
        try {
            runOnMainSync {
                activity.setContent {
                    com.wemade.teslamacro.ui.theme.TeslaMacroTheme(dark = false) {
                        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                            when (mode.intValue) {
                                0 -> com.wemade.teslamacro.feature.settings.BackupPanel(
                                    com.wemade.teslamacro.feature.settings.BackupControls({}, {}, message.value, consume))
                                1 -> com.wemade.teslamacro.feature.settings.FleetApiPanel(false, {},
                                    com.wemade.teslamacro.feature.settings.FleetCredentialControls(
                                        com.wemade.teslamacro.feature.settings.FleetCredentialState(stored = true, message = message.value),
                                        {}, {}, {}, consume))
                                2 -> com.wemade.teslamacro.ui.component.ActionFeedback(message.value, consume, "다시 저장", { retried++ })
                                3 -> com.wemade.teslamacro.ui.component.QuickActionRequestPanel(requests.value, {},
                                    { id -> requests.value = requests.value.filterNot { it.id == id } })
                                4 -> com.wemade.teslamacro.ui.component.ActionFeedback(message.value, consume)
                            }
                            androidx.compose.material3.Text("레이아웃 기준")
                        }
                    }
                }
            }
            for (index in 0..1) {
                sendStatus(0, Bundle().apply { putString("phase", "Checking feedback panel $index") })
                runOnMainSync { mode.intValue = index }
                awaitFeedbackText(if (index == 0) "내보내기" else "Fleet API", true)
                sendStatus(0, Bundle().apply { putString("phase", "Showing feedback panel $index") })
                val before = android.graphics.Rect().also { feedbackNodes().first { it.text?.toString() == "레이아웃 기준" }.getBoundsInScreen(it) }
                val result = if (index == 0) "백업 결과 점검" else "Fleet 처리 결과 점검"
                runOnMainSync { message.value = result }
                awaitFeedbackText(result, true)
                sendStatus(0, Bundle().apply { putString("phase", "Checking feedback close $index") })
                val after = android.graphics.Rect().also { feedbackNodes().first { it.text?.toString() == "레이아웃 기준" }.getBoundsInScreen(it) }
                check(before == after) { "Result shifted body: $index" }
                dismissFeedback()
                withTimeout(5000) { while (message.value != null) delay(50) }
                awaitFeedbackText(result, false)
                if (index == 1) {
                    sendStatus(0, Bundle().apply { putString("phase", "Checking feedback above Fleet sheet") })
                    click(feedbackNodes().first { it.text?.toString() == "Fleet API" && it.isVisibleToUser })
                    awaitFeedbackText("암호화해 저장됨", true)
                    runOnMainSync { message.value = "Fleet 상세 결과 점검" }
                    awaitFeedbackText("Fleet 상세 결과 점검", true)
                    dismissFeedback()
                    withTimeout(5000) { while (message.value != null) delay(50) }
                    awaitFeedbackText("Fleet 상세 결과 점검", false)
                    uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                    awaitFeedbackText("암호화해 저장됨", false)
                }
            }
            sendStatus(0, Bundle().apply { putString("phase", "Checking retry action") })
            runOnMainSync { mode.intValue = 2; message.value = "저장 실패 점검" }
            awaitFeedbackText("다시 저장", true)
            click(feedbackNodes().first { it.text?.toString() == "다시 저장" })
            withTimeout(5000) { while (retried != 1 || message.value != null) delay(50) }
            sendStatus(0, Bundle().apply { putString("phase", "Checking auto-dismiss") })
            runOnMainSync { message.value = "자동 닫힘 점검" }
            awaitFeedbackText("자동 닫힘 점검", true)
            withTimeout(16000) { while (message.value != null) delay(100) }
            check(dismissed == 5 && retried == 1)
            sendStatus(0, Bundle().apply { putString("phase", "Checking result queue") })
            runOnMainSync { mode.intValue = 3 }
            awaitFeedbackText("대기 명령", true)
            val before = android.graphics.Rect().also { feedbackNodes().first { it.text?.toString() == "레이아웃 기준" }.getBoundsInScreen(it) }
            runOnMainSync { requests.value = requests.value + listOf(
                com.wemade.teslamacro.service.QuickActionRequests.Request(2, "첫 명령", com.wemade.teslamacro.service.QuickActionRequests.Status.Finished),
                com.wemade.teslamacro.service.QuickActionRequests.Request(3, "다음 명령", com.wemade.teslamacro.service.QuickActionRequests.Status.Cancelled)) }
            for ((label, status) in listOf("첫 명령" to com.wemade.teslamacro.service.QuickActionRequests.Status.Finished,
                "다음 명령" to com.wemade.teslamacro.service.QuickActionRequests.Status.Cancelled)) {
                val result = "$label · ${status.message}"
                awaitFeedbackText(result, true)
                val after = android.graphics.Rect().also { feedbackNodes().first { it.text?.toString() == "레이아웃 기준" }.getBoundsInScreen(it) }
                check(before == after) { "Completed request shifted body" }
                dismissFeedback()
                awaitFeedbackText(result, false)
            }
            check(requests.value.size == 1 && requests.value.single().active)
            awaitFeedbackText("취소", true)
            sendStatus(0, Bundle().apply { putString("phase", "Checking repeated shared Toast") })
            runOnMainSync { mode.intValue = 4 }
            repeat(2) {
                uiAutomation.executeAndWaitForEvent({ runOnMainSync { message.value = "동일 결과 Toast 점검" } },
                    { event -> event.eventType == android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                        event.packageName?.toString() == targetContext.packageName && event.text.any { it.toString() == "동일 결과 Toast 점검" } }, 8000).recycle()
                withTimeout(5000) { while (message.value != null) delay(50) }
                waitForIdleSync()
            }
            check(dismissed == 7)
            sendStatus(0, Bundle().apply { putString("phase", "PASS: stable body, backup/Fleet overlays, retry once, auto-dismiss, sequential command results, repeated Toast consumption") })
        } finally { uiAutomation.serviceInfo = originalInfo }
    }

    /** Popup 제거·내용 교체 후 남은 접근성 캐시를 비우고 모든 창의 현재 노드를 읽는다. */
    private fun feedbackNodes(): List<android.view.accessibility.AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
        return mutableListOf<android.view.accessibility.AccessibilityNodeInfo>().also { result ->
            uiAutomation.windows.forEach { it.root?.let { root -> collect(root, result) } }
        }
    }

    /** 알림 표시·제거를 관측하며 고정 대기 대신 화면 상태 변화를 기다린다. */
    private suspend fun awaitFeedbackText(text: String, visible: Boolean) {
        val reached = withTimeoutOrNull(5000) {
            while (feedbackNodes().any { it.text?.toString() == text && it.isVisibleToUser } != visible) delay(50)
            true
        }
        check(reached == true) {
            val matches = feedbackNodes().filter { it.text?.toString() == text }.map { node ->
                val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                "visible=${node.isVisibleToUser}, bounds=$bounds"
            }
            val labels = feedbackNodes().mapNotNull { it.text?.toString() }.filter {
                it in listOf("내보내기", "가져오기", "Fleet API", "레이아웃 기준", "암호화해 저장됨", "토큰 등록 필요")
            }
            "Feedback timeout: expected=$text visible=$visible; matches=$matches; labels=$labels; windows=${uiAutomation.windows.map { it.type to it.layer }}"
        }
    }

    /** Snackbar의 실제 닫기 버튼을 눌러 소비 콜백을 검증한다. */
    private fun dismissFeedback() {
        val button = feedbackNodes().first { it.contentDescription?.toString() in listOf("Dismiss", "닫기") }
        click(button)
    }

    /** 애니메이션과 재구성 이후 사용자가 볼 수 있는 항목만 비교한다. */
    private suspend fun awaitPanelText(text: String, visible: Boolean) {
        val reached = withTimeoutOrNull(5000) {
            while (true) {
                val nodes = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
                uiAutomation.rootInActiveWindow?.let { collect(it, nodes) }
                val present = nodes.any { it.text?.toString() == text && it.isVisibleToUser }
                if (present == visible && nodes.any { it.packageName?.toString() == targetContext.packageName }) return@withTimeoutOrNull true
                delay(100)
            }
        }
        check(reached == true) {
            val labels = feedbackNodes().mapNotNull { it.text?.toString() }.filter {
                it in listOf("내보내기", "가져오기", "레이아웃 기준", "Fleet API", "연결 관리", "내비 앱", "암호화해 저장됨")
            }
            "Panel timeout: expected=$text visible=$visible; labels=$labels; activity=${resumedActivity()}"
        }
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
    private suspend fun clickSetup(text: String = "연결 설정") = withTimeout(10000) {
        while (true) {
            val nodes = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
            uiAutomation.rootInActiveWindow?.let { collect(it, nodes) }
            val button = nodes.firstOrNull { it.text?.toString() == text && it.isEnabled && it.isVisibleToUser }
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
    private fun shell(command: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8) }

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
