package com.wemade.teslamacro.nav

import android.app.Activity
import android.app.Instrumentation
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import com.wemade.teslamacro.TeslaMacroApplication
import com.wemade.teslamacro.data.nav.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** 공식 앱 대역과 실제 셸 서버로 공유 전달·미준비·응답 유실을 관측한다. */
class TeslaNavigationShareSmokeInstrumentation : Instrumentation() {
    private var sourceSmoke = false
    /** 명시한 에뮬레이터 계측에서만 테스트를 실행한다. */
    override fun onCreate(arguments: Bundle?) { sourceSmoke = arguments?.getString("sourceSmoke") == "true"; super.onCreate(arguments); start() }

    /** 실제 전달 횟수로 대체 실행과 설정 OFF가 중복 공유를 만들지 않는지 확인한다. */
    override fun onStart() {
        val result = Bundle()
        var status = Activity.RESULT_OK
        try {
            check(android.os.Build.MODEL.contains("sdk"))
            runBlocking {
                val app = targetContext.applicationContext as TeslaMacroApplication
                app.ready.first { it }
                withTimeout(15_000) { while (NavigationBridgeProvider.bridge?.pingBinder() != true) delay(100) }
                val real = NavigationBridgeProvider.bridge
                val controller = withContext(Dispatchers.Main) { WirelessNavigation(targetContext) }
                val navigator = NaverNavigator(targetContext, controller)
                var claims = 0
                try {
                    shell("input keyevent KEYCODE_WAKEUP")
                    shell("wm dismiss-keyguard")
                    shell("logcat -c")
                    shell("appops set com.wemade.teslamacro SYSTEM_ALERT_WINDOW deny")
                    navigator.shareTeslaDestination("Smoke & destination", true) { claims++ }.getOrThrow()
                    waitForCount(1)
                    check(claims == 1)
                    check(receipts().contains("https://maps.google.com/maps?q=Smoke+%26+destination"))
                    sendStatus(0, Bundle().apply { putString("phase", "PASS real ADB share without overlay") })

                    NavigationBridgeProvider.bridge = null
                    check(navigator.shareTeslaDestination("Missing permission", true) { claims++ }.isFailure)
                    check(claims == 1 && count() == 1)
                    shell("appops set com.wemade.teslamacro SYSTEM_ALERT_WINDOW allow")
                    navigator.shareTeslaDestination("No ADB fallback", true) { claims++ }.getOrThrow()
                    waitForCount(2)
                    NavigationBridgeProvider.bridge = object : Binder() {}
                    navigator.shareTeslaDestination("Old ADB fallback", true) { claims++ }.getOrThrow()
                    waitForCount(3)
                    sendStatus(0, Bundle().apply { putString("phase", "PASS unavailable ADB, old server, missing overlay") })

                    var sends = 0
                    var preflights = 0
                    NavigationBridgeProvider.bridge = object : Binder() {
                        /** 전달 후 응답만 유실되는 서버를 재현하며 실행 횟수를 따로 센다. */
                        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                            data.enforceInterface(NavigationBridgeProvider.DESCRIPTOR)
                            requireNotNull(reply).writeNoException()
                            when (code) {
                                7 -> { preflights++; data.readString(); reply.writeInt(1) }
                                8 -> { sends++; data.readString(); reply.writeString("UNKNOWN") }
                                else -> return false
                            }
                            return true
                        }
                    }
                    val uncertain = navigator.shareTeslaDestination("Uncertain response", true) { claims++ }
                    check((uncertain.exceptionOrNull() as? DestinationLaunchException)?.uncertain == true)
                    check(sends == 1 && count() == 3)
                    check(navigator.shareTeslaDestination("Disabled before dispatch", true) { error("OFF") }.isFailure)
                    check(sends == 1 && count() == 3)
                    navigator.shareTeslaDestination("Forced standard", false) { claims++ }.getOrThrow()
                    waitForCount(4)
                    check(preflights == 2 && sends == 1)
                    check(runCatching { controller.tryShareTeslaDestination("https://example.com/", {}) }.isFailure)
                    check(!TeslaShareIntent.accepts("https://maps.google.com/maps?q=a&q=b"))
                    sendStatus(0, Bundle().apply { putString("phase", "PASS uncertain response no fallback, pre-dispatch gate, forced standard") })

                    val store = app.container.settingsStore
                    val sharing = app.container.teslaNavigationShare
                    store.setTeslaNavigationShareEnabled(false)
                    sharing.notification("com.skt.tmap.ku", "route", "경로주행", "현재 위치 > Disabled")
                    check(count() == 4)
                    store.setTeslaNavigationLaunchMode(TeslaNavigationLaunchMode.STANDARD)
                    store.setTeslaNavigationShareEnabled(true)
                    sharing.notification("com.skt.tmap.ku", "route", "경로주행", "현재 위치 > Coordinator smoke")
                    waitForCount(5)
                    sharing.notification("com.skt.tmap.ku", "route", "경로주행", "현재 위치 > Coordinator smoke")
                    check(count() == 5)
                    check(store.settings.first().teslaNavigationLaunchMode == TeslaNavigationLaunchMode.STANDARD)
                    store.setTeslaNavigationShareEnabled(false)
                    sharing.screen("com.nhn.android.nmap", "Late screen")
                    sharing.notification("com.nhn.android.nmap", "naver", "네이버 지도", "내비게이션 - 안내 중")
                    check(count() == 5)
                    sendStatus(0, Bundle().apply { putString("phase", "PASS OFF, persisted mode, coordinator duplicate protection") })
                    result.putString("result", "PASS: real ADB, normal fallback, unavailable permissions, old server, uncertain result, OFF, duplicate protection; receipts=5")
                } finally {
                    NavigationBridgeProvider.bridge = real
                    if (sourceSmoke) app.container.settingsStore.setTeslaNavigationLaunchMode(TeslaNavigationLaunchMode.ADB_FIRST)
                    app.container.settingsStore.setTeslaNavigationShareEnabled(sourceSmoke)
                }
            }
        } catch (error: Throwable) {
            status = Activity.RESULT_CANCELED
            result.putString("error", error.stackTraceToString())
        }
        finish(status, result)
    }

    /** 대역 Activity의 로그만 읽어 실제 Android 실행 요청 도착을 센다. */
    private fun receipts(): String = shell("logcat -d -s TeslaShareFixture:I '*:S'")

    /** 자동 공유 요청 하나가 Activity 하나를 실행했는지 센다. */
    private fun count(): Int = receipts().lineSequence().count { it.contains("RECEIVED=") }

    /** 고정 대기 대신 수신 로그가 바뀔 때까지 제한 시간 안에서 관측한다. */
    private suspend fun waitForCount(expected: Int) = withTimeout(5_000) { while (count() < expected) delay(100); check(count() == expected) }

    /** 일회용 에뮬레이터에서만 계측의 셸 권한을 사용한다. */
    private fun shell(command: String): String = uiAutomation.executeShellCommand(command)
        .let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() } }
}
