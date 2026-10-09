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
    private var dialogSmoke = false
    private var testModeSmoke = false
    /** 명시한 에뮬레이터 계측에서만 테스트를 실행한다. */
    override fun onCreate(arguments: Bundle?) {
        sourceSmoke = arguments?.getString("sourceSmoke") == "true"
        dialogSmoke = arguments?.getString("dialogSmoke") == "true"
        testModeSmoke = arguments?.getString("testModeSmoke") == "true"
        super.onCreate(arguments); start()
    }

    /** 실제 전달 횟수로 대체 실행과 설정 OFF가 중복 공유를 만들지 않는지 확인한다. */
    override fun onStart() {
        val result = Bundle()
        var status = Activity.RESULT_OK
        try {
            check(android.os.Build.MODEL.contains("sdk"))
            runBlocking {
                val app = targetContext.applicationContext as TeslaMacroApplication
                app.ready.first { it }
                if (testModeSmoke) {
                    verifyTestMode(app)
                    result.putString("result", "PASS test destination preview, choice preview, guidance end, dispatch blocked")
                    return@runBlocking
                }
                app.container.teslaNavigationShare.setTestMode(false)
                if (dialogSmoke) {
                    verifyOverlay(app)
                    result.putString("result", "PASS automatic single candidate, overlay over navigation, permission denial/recovery, selection delivery, OFF and guidance removal")
                    return@runBlocking
                }
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
                                "북지길 13", "북지길 15", "북지길 16" -> listOf(south, north)
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
                    waitForCount(7)
                    check(resolving.selection.value == null)
                    resolving.notification("com.skt.tmap.ku", "single", "경로주행", "현재 위치 > 북지길 14")
                    check(count() == 7)
                    resolving.notification("com.skt.tmap.ku", "off", "경로주행", "현재 위치 > 북지길 16")
                    val single = withTimeout(3_000) { resolving.selection.first { it != null }!! }
                    store.setTeslaNavigationShareEnabled(false)
                    resolving.confirm(single.id, north)
                    check(resolving.selection.value == null && count() == 7)
                    store.setTeslaNavigationShareEnabled(true)
                    resolving.notification("com.skt.tmap.ku", "slow", "경로주행", "현재 위치 > Slow")
                    withTimeout(3_000) { entered.await() }
                    resolving.removed("com.skt.tmap.ku", "slow")
                    resolving.notification("com.skt.tmap.ku", "new", "경로주행", "현재 위치 > 북지길 15")
                    val latest = withTimeout(3_000) { resolving.selection.first { it != null }!! }
                    release.complete(Unit)
                    resolving.confirm(single.id, north)
                    check(resolving.selection.value?.id == latest.id && count() == 7)
                    resolving.search(latest.id, north.address)
                    waitForCount(8)
                    check(resolving.selection.value == null)
                    resolving.notification("com.skt.tmap.ku", "exact", "경로주행", "현재 위치 > 서울특별시 종로구 북지길 13")
                    waitForCount(9)
                    check(resolving.selection.value == null)
                    resolving.notification("com.skt.tmap.ku", "remove", "경로주행", "현재 위치 > 북지길 16")
                    val removed = withTimeout(3_000) { resolving.selection.first { it != null }!! }
                    resolving.removed("com.skt.tmap.ku", "remove")
                    resolving.confirm(removed.id, north)
                    check(count() == 9 && resolving.selection.value == null)
                    resolving.clear()
                    sendStatus(0, Bundle().apply { putString("phase", "PASS partial road choice, single-result automatic, coordinate receipt, OFF, stale lookup, refined automatic, removed selection") })
                    result.putString("result", "PASS: ADB/normal/failure paths, coordinate sharing, region selection, single automatic, OFF, stale response, duplicate protection; receipts=9")
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

    /** 실제 Android 창과 공유 실행 횟수로 임시 모드의 안내·차단·종료 경계를 확인한다. */
    private suspend fun verifyTestMode(app: TeslaMacroApplication) {
        val store = app.container.settingsStore
        val realBridge = NavigationBridgeProvider.bridge
        var dispatches = 0
        NavigationBridgeProvider.bridge = object : Binder() {
            /** 테스트에서는 ADB 사전 확인조차 호출되면 안 되므로 모든 요청을 센다. */
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                dispatches++
                return false
            }
        }
        try {
            check(com.wemade.teslamacro.data.settings.AppSettings().teslaNavigationTestMode)
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            shell("appops set com.wemade.teslamacro SYSTEM_ALERT_WINDOW allow")
            shell("logcat -c")
            store.setTeslaNavigationShareEnabled(true)
            store.setTeslaNavigationTestMode(true)
            val correct = TeslaDestinationCandidate("인천 서구 청마로34번길 6", com.wemade.teslamacro.domain.macro.GeoPoint(37.5, 126.6))
            val sharing = TeslaNavigationShare(targetContext, store, app.container.navigator, app.container.appScope,
                lookup = { _, _ -> listOf(correct, correct.copy(address = "인천 서구 청마로34번길 60")) })
            sharing.screen("com.nhn.android.nmap", "청마로 34번길 6")
            sharing.notification("com.nhn.android.nmap", "route", "네이버 지도", "내비게이션 - 안내 중")
            val automatic = withTimeout(4_000) { sharing.selection.first { it != null }!! }
            check(automatic.query == "청마로34번길 6" && automatic.testMode && automatic.preview == correct && dispatches == 0 && count() == 0)
            val overlay = com.wemade.teslamacro.service.TeslaDestinationOverlay(targetContext, sharing, store, app.container.appScope)
            overlay.refresh()
            withTimeout(10_000) { while (!dialogTexts().let { it.contains("테슬라로 보낼 목적지") && it.contains(correct.address) && it.contains("37.5,126.6") }) delay(100) }
            sharing.notification("com.nhn.android.nmap", "route", "네이버 지도", "내비게이션 - 안내 종료")
            sharing.removed("com.nhn.android.nmap", "route")
            check(sharing.selection.value == null)
            withTimeout(10_000) { while (dialogTexts().contains("테슬라로 보낼 목적지")) delay(100) }
            val both = TeslaNavigationShare(targetContext, store, app.container.navigator, app.container.appScope,
                lookup = { _, _ -> listOf(correct, correct.copy(address = "인천 남동구 청마로34번길 6")) })
            both.screen("com.nhn.android.nmap", "청마로 34번길 6")
            both.notification("com.nhn.android.nmap", "choice", "네이버 지도", "내비게이션 - 안내 중")
            val selected = withTimeout(4_000) { both.selection.first { it != null }!! }
            check(selected.preview == null && selected.testMode && selected.candidates.size == 2)
            both.confirm(selected.id, correct)
            val chosen = withTimeout(4_000) { both.selection.first { it?.preview != null }!! }
            check(chosen.preview == correct && dispatches == 0 && count() == 0)
            both.clear()
            sharing.notification("com.skt.tmap.ku", "coordinates", "경로주행", "현재 위치 > 37.5,126.6")
            val coordinate = withTimeout(4_000) { sharing.selection.first { it?.preview?.shareText == "37.5,126.6" }!! }
            check(coordinate.testMode && dispatches == 0 && count() == 0)
            sharing.dismiss(coordinate.id)
            check(sharing.selection.value == null)
            sharing.notification("com.nhn.android.nmap", "unread", "네이버 지도", "내비게이션 - 안내 중")
            check(sharing.selection.value == null)
            val unread = withTimeout(8_000) { sharing.selection.first { it != null }!! }
            check(unread.query.isEmpty() && unread.error != null && unread.testMode && dispatches == 0 && count() == 0)
            sharing.removed("com.nhn.android.nmap", "unread")
            check(sharing.selection.value == null)
            // 안내 중 목적지 변경: 같은 알림이 유지돼도 새 목적지만 다시 표시하고 같은 목적지는 반복하지 않는다.
            sharing.notification("com.nhn.android.nmap", "reroute", "네이버 지도", "내비게이션 - 안내 중")
            sharing.reroute("com.nhn.android.nmap", "37.6,126.7")
            val rerouted = withTimeout(4_000) { sharing.selection.first { it?.preview?.shareText == "37.6,126.7" }!! }
            sharing.dismiss(rerouted.id)
            sharing.reroute("com.nhn.android.nmap", "37.6,126.7")
            check(sharing.selection.value == null && dispatches == 0 && count() == 0)
            sharing.removed("com.nhn.android.nmap", "reroute")
            sharing.reroute("com.nhn.android.nmap", "37.7,126.8")
            check(sharing.selection.value == null)
            sendStatus(0, Bundle().apply { putString("phase", "PASS test preview after choice, automatic coordinate preview, dispatch blocked") })
        } finally {
            app.container.teslaNavigationShare.clear()
            store.setTeslaNavigationShareEnabled(false)
            store.setTeslaNavigationTestMode(true)
            NavigationBridgeProvider.bridge = realBridge
        }
    }

    /** 다른 앱이 앞에 있는 상태에서 실제 오버레이 권한·선택·공유·종료를 확인한다. */
    private suspend fun verifyOverlay(app: TeslaMacroApplication) {
        val store = app.container.settingsStore
        val north = TeslaDestinationCandidate("서울 종로구 북지길 13", com.wemade.teslamacro.domain.macro.GeoPoint(37.56, 126.97))
        val south = TeslaDestinationCandidate("부산 중구 북지길 13", com.wemade.teslamacro.domain.macro.GeoPoint(35.1, 129.0))
        val sharing = TeslaNavigationShare(targetContext, store, app.container.navigator, app.container.appScope,
            lookup = { query, _ -> if (query == "single") listOf(north) else if (query == "empty") emptyList() else listOf(north, south) })
        val overlay = com.wemade.teslamacro.service.TeslaDestinationOverlay(targetContext, sharing, store, app.container.appScope)
        try {
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            shell("logcat -c")
            store.setTeslaNavigationLaunchMode(TeslaNavigationLaunchMode.STANDARD)
            store.setTeslaNavigationShareEnabled(true)
            shell("am start -n com.nhn.android.nmap/.Fixture")
            withTimeout(10_000) { while (!focusedApp().contains("com.nhn.android.nmap")) delay(100) }
            shell("appops set com.wemade.teslamacro SYSTEM_ALERT_WINDOW deny")
            sharing.notification("com.skt.tmap.ku", "dialog", "경로주행", "현재 위치 > 북지길 13")
            val pending = withTimeout(25_000) { sharing.selection.first { it != null }!! }
            check(!dialogTexts().contains("테슬라 목적지 선택") && count() == 0)
            shell("appops set com.wemade.teslamacro SYSTEM_ALERT_WINDOW allow")
            overlay.refresh()
            waitForOverlay(true)
            try {
                withTimeout(5_000) { while (!dialogTexts().contains(north.address) || !dialogTexts().contains(south.address)) delay(100) }
            } catch (error: TimeoutCancellationException) {
                error("candidate texts missing: " + dialogTexts())
            }
            check(shell("dumpsys window").contains("ty=APPLICATION_OVERLAY"))
            check(shell("dumpsys activity activities").lineSequence().any { it.contains("ResumedActivity") && it.contains("com.nhn.android.nmap") })
            check(sharing.selection.value?.id == pending.id && count() == 0)
            clickCandidate(south.address)
            waitForCount(1)
            waitForOverlay(false)
            check(receipts().contains("q=35.1%2C129.0"))
            sharing.confirm(pending.id, north)
            check(count() == 1)
            sendStatus(0, Bundle().apply { putString("phase", "PASS overlay permission recovery, foreign app foreground, candidate tap, one coordinate delivery") })

            sharing.notification("com.skt.tmap.ku", "single", "경로주행", "현재 위치 > single")
            waitForCount(2)
            check(sharing.selection.value == null)
            check(!dialogTexts().contains("테슬라 목적지 선택"))
            sharing.notification("com.skt.tmap.ku", "single", "경로주행", "현재 위치 > single")
            check(count() == 2)

            sharing.notification("com.skt.tmap.ku", "lock", "경로주행", "현재 위치 > 북지길 13")
            waitForOverlay(true)
            shell("input keyevent KEYCODE_SLEEP")
            waitForOverlay(false)
            check(sharing.selection.value != null && count() == 2)
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            overlay.refresh()
            waitForOverlay(true)
            sharing.clear()
            waitForOverlay(false)
            sendStatus(0, Bundle().apply { putString("phase", "PASS single automatic without picker, duplicate prevention, screen-off hides overlay and retains pending") })

            sharing.notification("com.skt.tmap.ku", "ended", "경로주행", "현재 위치 > 북지길 14")
            waitForOverlay(true)
            sharing.removed("com.skt.tmap.ku", "ended", 8)
            waitForOverlay(false)
            sharing.notification("com.skt.tmap.ku", "empty", "경로주행", "현재 위치 > empty")
            waitForOverlay(true)
            check(dialogTexts().contains("시·군·구를 포함한 주소로 다시 검색해 주세요") && count() == 2)
            store.setTeslaNavigationShareEnabled(false)
            sharing.clear()
            waitForOverlay(false)
            check(count() == 2)
        } finally {
            store.setTeslaNavigationShareEnabled(false)
            sharing.clear()
        }
    }

    /** 창 텍스트가 바뀔 때까지 기다려 고정 지연 없이 표시·제거를 관측한다. */
    private suspend fun waitForOverlay(visible: Boolean) = withTimeout(10_000) {
        while (dialogTexts().contains("테슬라 목적지 선택") != visible) delay(100)
    }

    /** 실제 접근성 버튼을 눌러 오버레이 UI에서 기존 확인 경로까지 연결한다. */
    private fun clickCandidate(address: String) {
        var clicked = false
        uiAutomation.windows.forEach { window ->
            val nodes = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
            window.root?.let(nodes::add)
            var visited = 0
            while (nodes.isNotEmpty() && visited++ < 300 && !clicked) {
                val node = nodes.removeFirst()
                if (node.text?.toString() == address) {
                    var parent = node.parent
                    clicked = node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    var ancestors = 0
                    while (!clicked && parent != null && ancestors++ < 4) {
                        clicked = parent.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                        val next = if (clicked) null else parent.parent
                        parent.recycle()
                        parent = next
                    }
                    parent?.recycle()
                }
                if (!clicked) for (index in 0 until node.childCount.coerceAtMost(50)) node.getChild(index)?.let(nodes::add)
                node.recycle()
            }
            nodes.forEach { it.recycle() }
            window.recycle()
        }
        check(clicked) { "candidate button not found" }
    }

    /** 계측 셸은 파이프를 실행하지 않으므로 창 덤프에서 포커스 행만 직접 읽는다. */
    private fun focusedApp(): String = shell("dumpsys window").lineSequence().firstOrNull { it.contains("mCurrentFocus=") }.orEmpty()

    /** 화면 캡처 대신 보이는 Android 창의 한정된 접근성 텍스트만 읽는다. */
    private fun dialogTexts(): String {
        val automation = uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        val texts = StringBuilder()
        automation.windows.forEach { window ->
            val nodes = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
            window.root?.let(nodes::add)
            var visited = 0
            while (nodes.isNotEmpty() && visited++ < 300) {
                val node = nodes.removeFirst()
                node.text?.let { texts.append(it).append('\n') }
                for (index in 0 until node.childCount.coerceAtMost(50)) node.getChild(index)?.let(nodes::add)
                node.recycle()
            }
            nodes.forEach { it.recycle() }
            window.recycle()
        }
        return texts.toString()
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
