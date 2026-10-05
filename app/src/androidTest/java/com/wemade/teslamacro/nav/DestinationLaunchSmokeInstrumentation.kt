package com.wemade.teslamacro.nav

import android.app.Activity
import android.app.Instrumentation
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.provider.Settings
import com.wemade.teslamacro.data.nav.*
import kotlinx.coroutines.*

/** 일회용 에뮬레이터에서 실제 셸 전달과 권한 부재·응답 유실 분기를 검증한다. */
class DestinationLaunchSmokeInstrumentation : Instrumentation() {
    /** 계측으로 명시 실행할 때만 시작한다. */
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    /** 실제 준비 서버를 먼저 검증하고 대역으로 실패 경계를 확인한다. */
    override fun onStart() {
        val result = Bundle()
        var status = Activity.RESULT_OK
        try {
            check(android.os.Build.MODEL.contains("sdk"))
            runBlocking {
                withTimeout(15_000) { while (NavigationBridgeProvider.bridge?.pingBinder() != true) delay(100) }
                val real = NavigationBridgeProvider.bridge
                val controller = withContext(Dispatchers.Main) { WirelessNavigation(targetContext) }
                val navigator = NaverNavigator(targetContext, controller)
                val place = DestinationPlace("Smoke & destination")
                var claims = 0
                try {
                    shell("input keyevent KEYCODE_WAKEUP")
                    shell("wm dismiss-keyguard")
                    shell("appops set com.wemade.teslamacro SYSTEM_ALERT_WINDOW deny")
                    shell("settings put global adb_wifi_enabled 0")
                    check(!navigator.hasOverlayPermission)
                    navigator.navigateDestination(place) {
                        check(Settings.Global.getInt(targetContext.contentResolver, "adb_wifi_enabled", 0) == 1)
                        claims++
                    }.getOrThrow()
                    check(claims == 1)
                    check(Settings.Global.getInt(targetContext.contentResolver, "adb_wifi_enabled", 0) == 0)
                    check(shell("dumpsys activity activities").contains("com.nhn.android.nmap/.Fixture"))
                    val pid = shell("pidof com.nhn.android.nmap").trim()
                    check(pid.isNotEmpty())
                    controller.closeWirelessDebugging()
                    check(shell("pidof com.nhn.android.nmap").trim() == pid)
                    // 디버깅을 다시 켤 권한이 없으면 인계 전에 기존 방식으로 반환한다.
                    val denied = object : android.content.ContextWrapper(targetContext) {
                        /** 테스트에서 보안 설정 권한만 없는 기기를 재현한다. */
                        override fun checkSelfPermission(permission: String): Int =
                            if (permission == "android.permission.WRITE_SECURE_SETTINGS") android.content.pm.PackageManager.PERMISSION_DENIED
                            else super.checkSelfPermission(permission)
                    }
                    val unprivileged = withContext(Dispatchers.Main) { WirelessNavigation(denied) }
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, place.naverUri(targetContext.packageName))
                        .setPackage("com.nhn.android.nmap")
                    check(!unprivileged.tryLaunchDestination(intent) { error("Must not claim") })
                    sendStatus(0, Bundle().apply { putString("phase", "Real shell destination passed without overlay; navigation survives cleanup") })

                    // 준비 전·구형 서버는 인계하지 않고 기존 권한 안내로 돌아간다.
                    NavigationBridgeProvider.bridge = null
                    check(navigator.navigateDestination(place) { claims++ }.isFailure)
                    check(claims == 1)
                    NavigationBridgeProvider.bridge = object : Binder() {}
                    check(navigator.navigateDestination(place) { claims++ }.isFailure)
                    check(claims == 1)

                    // ADB가 없어도 기존 오버레이 실행이 실제 지도 Activity를 연다.
                    shell("appops set com.wemade.teslamacro SYSTEM_ALERT_WINDOW allow")
                    NavigationBridgeProvider.bridge = null
                    shell("am force-stop com.nhn.android.nmap")
                    navigator.navigateDestination(place) { claims++ }.getOrThrow()
                    check(claims == 2)
                    withTimeout(5_000) { while (shell("pidof com.nhn.android.nmap").isBlank()) delay(100) }

                    // 전송 결과 유실은 URI 후보와 오버레이로 중복 실행하지 않는다.
                    var sends = 0
                    NavigationBridgeProvider.bridge = object : Binder() {
                        /** 실제 Parcel 계약으로 준비 성공과 응답 유실을 재현한다. */
                        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                            data.enforceInterface(NavigationBridgeProvider.DESCRIPTOR)
                            requireNotNull(reply).writeNoException()
                            when (code) {
                                1 -> reply.writeString("AVAILABLE")
                                3 -> reply.writeInt(1)
                                4 -> { sends++; reply.writeString("UNKNOWN") }
                                else -> return false
                            }
                            return true
                        }
                    }
                    val failure = runCatching { navigator.navigateDestination(place) { claims++ } }.exceptionOrNull()
                    check(failure is DestinationLaunchException && failure.uncertain)
                    check(sends == 1 && claims == 3)
                    check(navigator.navigateDestination(place) { error("expired") }.exceptionOrNull()?.message == "expired")
                    check(sends == 1)
                    // 매크로 경로도 두 URI 중 첫 전달 이후에는 재시도하지 않는다.
                    targetContext.getSharedPreferences("geocode_cache", 0).edit().putString("smoke address", "37.5665,126.978").commit()
                    val macro = navigator.navigate("Smoke", "smoke address", NavigatorApp.NAVER)
                    check(macro.exceptionOrNull() is DestinationLaunchException)
                    check(sends == 2)
                    NavigationBridgeProvider.bridge = real
                    shell("appops set com.wemade.teslamacro SYSTEM_ALERT_WINDOW deny")
                    navigator.navigate("Smoke", "smoke address", NavigatorApp.NAVER).getOrThrow()
                    // 가상 안심주행 중에는 목적지로 대체하지 않고 기존 세션 정리 뒤 다시 허용한다.
                    shell("am force-stop com.nhn.android.nmap")
                    withContext(Dispatchers.IO) {
                        NavigationChannel().connect().use { channel ->
                            channel.send("START")
                            val reader = channel.input.bufferedReader()
                            val ready = withTimeout(15_000) { runInterruptible { reader.readLine() } }
                            check(ready == "NAVER_READY") { "Virtual session: $ready" }
                            val busy = navigator.navigateDestination(place) { error("Must not claim while active") }
                            check(busy.exceptionOrNull() is DestinationLaunchException)
                            channel.send("STOP")
                            check(withTimeout(15_000) { runInterruptible { reader.readLine() } } == "NAVER_CLOSED")
                        }
                    }
                    withTimeout(5_000) {
                        while (NavigationChannel().connect().use { it.status() } != "AVAILABLE") delay(50)
                    }
                    // 잠금 상태에서는 셸 직접 실행이 인증을 우회하지 못한다.
                    shell("locksettings set-pin 1234")
                    try {
                        shell("input keyevent KEYCODE_SLEEP")
                        val keyguard = targetContext.getSystemService(android.app.KeyguardManager::class.java)
                        withTimeout(5_000) { while (!keyguard.isDeviceLocked) delay(50) }
                        NavigationChannel().connect().use {
                            check(!it.canLaunchDestination("com.nhn.android.nmap", intent.dataString!!))
                            check(it.launchDestination("com.nhn.android.nmap", intent.dataString!!) == "NOT_STARTED")
                        }
                    } finally {
                        shell("locksettings clear --old 1234")
                        shell("input keyevent KEYCODE_WAKEUP")
                        shell("wm dismiss-keyguard")
                    }
                } finally {
                    NavigationBridgeProvider.bridge = real
                    controller.closeWirelessDebugging()
                    targetContext.getSharedPreferences("geocode_cache", 0).edit().remove("smoke address").commit()
                }
            }
            result.putString("result", "PASS: real shell destination/macro without overlay, debugging before send and cleanup, map survives, absent/old helper fallback, uncertain result not retried")
        } catch (error: Throwable) {
            status = Activity.RESULT_CANCELED
            result.putString("result", "FAIL: $error; ${error.stackTrace.firstOrNull { it.className.contains("DestinationLaunchSmoke") }}")
        }
        finish(status, result)
    }

    /** 테스트 셸은 일회용 에뮬레이터에만 사용한다. */
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }
}
