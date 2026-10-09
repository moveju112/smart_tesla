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
                    sharing.notification("com.skt.tmap.ku", "route", "경로주행", "현재 위치 > 37.56,126.97")
                    waitForCount(5)
                    sharing.notification("com.skt.tmap.ku", "route", "경로주행", "현재 위치 > 37.56,126.97")
                    check(count() == 5)
                    check(store.settings.first().teslaNavigationLaunchMode == TeslaNavigationLaunchMode.STANDARD)
                    store.setTeslaNavigationShareEnabled(false)
                    sharing.screen("com.nhn.android.nmap", "Late screen")
                    sharing.notification("com.nhn.android.nmap", "naver", "네이버 지도", "내비게이션 - 안내 중")
                    check(count() == 5)
                    sendStatus(0, Bundle().apply { putString("phase", "PASS OFF, persisted mode, coordinator duplicate protection") })
                    val north = TeslaDestinationCandidate("서울 종로구 북지길 13", com.wemade.teslamacro.domain.macro.GeoPoint(37.56, 126.97))
                    val south = TeslaDestinationCandidate("부산 중구 북지길 13", com.wemade.teslamacro.domain.macro.GeoPoint(35.1, 129.0))
                    val entered = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    val resolving = TeslaNavigationShare(targetContext, store, navigator, app.container.appScope,
                        currentPoint = { north.point }, lookup = { query, _ ->
                            when (query) {
                                "북지길 13" -> listOf(south, north)
                                "Slow" -> withContext(NonCancellable) { entered.complete(Unit); release.await(); listOf(south) }
                                else -> listOf(north)
                            }
                        })
                    store.setTeslaNavigationShareEnabled(true)
                    resolving.notification("com.skt.tmap.ku", "partial", "경로주행", "현재 위치 > 북지길 13")
                    val partial = withTimeout(3_000) { resolving.selection.first { it != null }!! }
                    check(partial.candidates == listOf(north, south) && count() == 5)
                    resolving.confirm(partial.id, south)
                    waitForCount(6)
                    check(receipts().contains("q=35.1%2C129.0"))
                    resolving.confirm(partial.id, north)
                    check(count() == 6)

                    resolving.notification("com.skt.tmap.ku", "single", "경로주행", "현재 위치 > 북지길 14")
                    val single = withTimeout(3_000) { resolving.selection.first { it != null }!! }
                    check(single.candidates.size == 1 && count() == 6)
                    store.setTeslaNavigationShareEnabled(false)
                    resolving.confirm(single.id, north)
                    check(resolving.selection.value == null && count() == 6)
                    store.setTeslaNavigationShareEnabled(true)
                    resolving.notification("com.skt.tmap.ku", "slow", "경로주행", "현재 위치 > Slow")
                    withTimeout(3_000) { entered.await() }
                    resolving.removed("com.skt.tmap.ku", "slow")
                    resolving.notification("com.skt.tmap.ku", "new", "경로주행", "현재 위치 > 북지길 15")
                    val latest = withTimeout(3_000) { resolving.selection.first { it != null }!! }
                    release.complete(Unit)
                    resolving.confirm(single.id, north)
                    check(resolving.selection.value?.id == latest.id && count() == 6)
                    resolving.search(latest.id, north.address)
                    val refined = withTimeout(3_000) { resolving.selection.first { it != null && !it.searching }!! }
                    check(count() == 6 && refined.candidates == listOf(north))
                    resolving.confirm(refined.id, north)
                    waitForCount(7)
                    resolving.notification("com.skt.tmap.ku", "exact", "경로주행", "현재 위치 > 서울특별시 종로구 북지길 13")
                    waitForCount(8)
                    check(resolving.selection.value == null)
                    resolving.notification("com.skt.tmap.ku", "remove", "경로주행", "현재 위치 > 북지길 16")
                    val removed = withTimeout(3_000) { resolving.selection.first { it != null }!! }
                    resolving.removed("com.skt.tmap.ku", "remove")
                    resolving.confirm(removed.id, north)
                    check(count() == 8 && resolving.selection.value == null)
                    resolving.clear()
                    sendStatus(0, Bundle().apply { putString("phase", "PASS partial road, single-result confirmation, coordinate receipt, OFF, stale lookup, refined address, exact automatic, removed selection") })
                    result.putString("result", "PASS: ADB/normal/failure paths, coordinate sharing, region selection, single result, OFF, stale response, duplicate protection; receipts=8")
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
