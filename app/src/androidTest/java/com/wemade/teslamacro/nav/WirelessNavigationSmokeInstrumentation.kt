package com.wemade.teslamacro.nav

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
    private var prepareOnly = false
    private var pairOnly = false
    private var recoveryOnly = false

    /** 별도 계측 실행기로만 테스트를 시작하며 일반 앱 실행에는 포함하지 않는다. */
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
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
                    (if (pairOnly) (targetContext.applicationContext as com.wemade.teslamacro.TeslaMacroApplication)
                        .container.wirelessNavigation else WirelessNavigation(targetContext)).also { controller = it }
                }
                if (pairOnly) {
                    // 계측 시작은 앱 서비스를 끊으므로 사용자 요청 전에 테스트 접근성을 다시 연결한다.
                    val automation = getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
                        "settings put secure enabled_accessibility_services ''")).use { it.readBytes() }
                    delay(300)
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
                        "settings put secure enabled_accessibility_services com.wemade.teslamacro/com.wemade.teslamacro.data.nav.NavigationPairingService"))
                        .use { it.readBytes() }
                    delay(1000)
                    withContext(Dispatchers.Main) {
                        navigation.setEnabled(false)
                        com.wemade.teslamacro.data.nav.NavigationPairingService.begin(targetContext)
                    }
                    withTimeout(120_000) {
                        while (!navigation.state.value.message.startsWith("페어링·준비 완료") || navigation.state.value.busy) {
                            delay(5000)
                            val root = automation.rootInActiveWindow
                            val labels = listOf("Wireless debugging", "Use wireless debugging", "Pair device with pairing code", "Allow", "Use developer options")
                                .filter { root?.findAccessibilityNodeInfosByText(it)?.isNotEmpty() == true }
                            sendStatus(0, Bundle().apply { putString("phase", "${navigation.state.value.message}; window=${root?.packageName}; labels=$labels") })
                        }
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
            result.putString("result", if (recoveryOnly) "PASS: helper absent, Wi-Fi arrival automatically restores helper through TLS, wireless debugging restored off"
                else if (pairOnly) "PASS: accessibility setup, TLS pairing, port discovery, detached helper prepared"
                else if (prepareOnly) "PASS: detached helper prepared" else "PASS: Wi-Fi and wireless debugging off, unusable ADB port, key persistence, reconnect grace, automatic stop, repeated start/stop")
        } catch (error: Throwable) {
            status = Activity.RESULT_CANCELED
            result.putString("result", "FAIL: ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            runOnMainSync { controller?.setEnabled(false) }
            key.delete()
        }
        finish(status, result)
    }
}
