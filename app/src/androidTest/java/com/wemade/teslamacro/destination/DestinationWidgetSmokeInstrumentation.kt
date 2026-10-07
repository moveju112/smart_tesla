package com.wemade.teslamacro.destination

import android.app.Activity
import android.app.ActivityOptions
import android.app.Instrumentation
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import com.wemade.teslamacro.feature.destination.DestinationViewModel
import kotlinx.coroutines.withTimeoutOrNull
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import com.wemade.teslamacro.feature.destination.DestinationQuickSendContent
import com.wemade.teslamacro.feature.destination.DestinationScreen
import com.wemade.teslamacro.feature.destination.DestinationUiState
import com.wemade.teslamacro.ui.theme.TeslaMacroTheme
import androidx.compose.ui.graphics.toArgb
import com.wemade.teslamacro.TeslaMacroApplication
import com.wemade.teslamacro.data.settings.DestinationWidgetAppearance
import com.wemade.teslamacro.ui.theme.DarkPalette
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.wemade.teslamacro.R
import com.wemade.teslamacro.feature.destination.DestinationQuickSendActivity
import com.wemade.teslamacro.feature.destination.DestinationWidget
import com.wemade.teslamacro.feature.destination.DestinationWidgetConfigurationActivity

/** 실제 서버에 보내지 않고 런처 PendingIntent부터 입력·실패 복구까지 확인한다. */
class DestinationWidgetSmokeInstrumentation : Instrumentation() {
    private var captureReview = false
    /** 기존 플랫폼 실행기를 재사용해 테스트 전용 라이브러리를 추가하지 않는다. */
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        captureReview = arguments?.getString("captureReview") == "true"
        start()
    }

    /** 에뮬레이터의 네트워크 차단과 위젯 바인딩 허가가 있을 때만 실행한다. */
    override fun onStart() {
        val result = Bundle()
        var resultCode = Activity.RESULT_OK
        var host: AppWidgetHost? = null
        var widgetId: Int? = null
        var activity: Activity? = null
        var configuration: Activity? = null
        var originalAppearance: DestinationWidgetAppearance? = null
        var originalSetup: Boolean? = null
        var originalReceiving = false
        try {
            uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            check(targetContext.getSystemService(ConnectivityManager::class.java).activeNetwork == null) {
                "실제 목적지 전송을 막으려면 테스트 에뮬레이터의 네트워크를 먼저 꺼야 합니다"
            }
            val app = targetContext.applicationContext as TeslaMacroApplication
            runBlocking { kotlinx.coroutines.withTimeout(10_000) { app.ready.first { it } } }
            val store = app.container.settingsStore
            val original = runBlocking { store.settings.first() }
            originalAppearance = original.destinationWidgetAppearance
            originalSetup = original.destinationSetupStarted
            originalReceiving = original.destinationReceiveEnabled
            runBlocking { store.setDestinationSetupStarted(false) }
            val manager = targetContext.getSystemService(AppWidgetManager::class.java)
            val provider = ComponentName(targetContext, DestinationWidget::class.java)
            val info = manager.installedProviders.single { it.provider == provider }
            check(info.configure == ComponentName(targetContext, DestinationWidgetConfigurationActivity::class.java))
            check(info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE != 0)
            check(info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0)
            check(info.targetCellHeight == 1)
            lateinit var view: AppWidgetHostView
            runOnMainSync {
                host = AppWidgetHost(targetContext, 146)
                widgetId = host!!.allocateAppWidgetId()
                check(manager.bindAppWidgetIdIfAllowed(widgetId!!, provider)) { "테스트 앱의 위젯 바인딩 허가 필요" }
                host!!.startListening()
                view = host!!.createView(targetContext, widgetId!!, info)
            }
            val monitor = addMonitor(DestinationQuickSendActivity::class.java.name, null, false)
            val deadline = SystemClock.uptimeMillis() + 10_000
            var clickable = false
            while (!clickable && SystemClock.uptimeMillis() < deadline) {
                runOnMainSync { clickable = view.findViewById<android.view.View>(R.id.destination_widget_input)?.isClickable == true }
                if (!clickable) SystemClock.sleep(100)
            }
            check(clickable) { "위젯에 입력 동작이 연결되지 않았습니다" }
            // 작은 한 칸부터 넓은 여덟 칸 폭까지 같은 위젯의 옵션 변경을 검사한다.
            resizeWidget(view, 48, compact = true)
            runOnMainSync { view.findViewById<android.view.View>(R.id.destination_widget_root).performClick() }
            activity = waitForMonitorWithTimeout(monitor, 10_000)
            removeMonitor(monitor)
            check(activity != null) { "위젯 클릭으로 입력창이 열리지 않았습니다" }
            // 설정 이력이 없는 실제 위젯은 오프라인에서도 즉시 설정을 열고 조회 주기가 지나도 오류가 없어야 한다.
            awaitNode { it.text?.toString() == "전송받을 기기" }
            awaitNode { it.text?.toString() == "연결코드생성" }
            check(find(uiAutomation.rootInActiveWindow) { it.text?.toString() == "연결 확인 중…" } == null)
            lateinit var model: DestinationViewModel
            runOnMainSync { model = ViewModelProvider(activity as DestinationQuickSendActivity)[DestinationViewModel::class.java] }
            check(model.state.value.setupStarted == false && model.state.value.canConfigure)
            check(runBlocking { withTimeoutOrNull(5_500) { model.state.first { it.connectionError != null } } } == null)
            // 설정 이력이 있으면 오프라인에서도 즉시 입력하고 전송 요청 때만 연결을 확인한다.
            runOnMainSync { activity!!.finish() }
            runBlocking { store.setDestinationSetupStarted(true) }
            val configuredMonitor = addMonitor(DestinationQuickSendActivity::class.java.name, null, false)
            runOnMainSync { view.findViewById<android.view.View>(R.id.destination_widget_root).performClick() }
            activity = waitForMonitorWithTimeout(configuredMonitor, 10_000)
            removeMonitor(configuredMonitor)
            check(activity != null)
            awaitNode { it.className == "android.widget.EditText" }
            runOnMainSync { model = ViewModelProvider(activity as DestinationQuickSendActivity)[DestinationViewModel::class.java] }
            check(!model.state.value.connectionChecked && !model.state.value.busy)
            enter(awaitNode { it.className == "android.widget.EditText" && it.isFocused }, "서울역")
            check(runBlocking { withTimeoutOrNull(5_500) { model.state.first { it.connectionError != null || it.connectionChecked } } } == null)
            check(awaitNode { it.className == "android.widget.EditText" }
                .performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id))
            runBlocking { kotlinx.coroutines.withTimeout(20_000) { model.state.first { !it.busy && it.error != null } } }
            check(model.state.value.query == "서울역" && !model.state.value.sendCompleted)
            awaitNode { it.className == "android.widget.EditText" }
            // 명시적 해제 기록을 읽으면 오류 화면에서도 설정으로 돌아간다.
            runBlocking { store.setDestinationSetupStarted(false) }
            awaitNode { it.text?.toString() == "전송받을 기기" }
            val fixture = mutableStateOf(DestinationUiState(connectionChecked = true))
            var sends = 0
            var disconnects = 0
            var receiveChanges = 0
            runOnMainSync {
                (activity as DestinationQuickSendActivity).setContent {
                    TeslaMacroTheme(dark = false) {
                        DestinationQuickSendContent(fixture.value,
                            onQuery = { fixture.value = fixture.value.copy(query = it) },
                            onSend = { sends++; fixture.value = fixture.value.copy(error = "오프라인") },
                            setup = {
                                DestinationScreen(fixture.value, settingsOnly = true,
                                    onCreateCode = { fixture.value = fixture.value.copy(receiverCode = "ABCD234567") },
                                    onUnlink = { disconnects++; fixture.value = DestinationUiState(connectionChecked = true) },
                                    onReceiving = { receiveChanges++; fixture.value = fixture.value.copy(receiving = it) })
                            })
                    }
                }
            }
            awaitNode { it.text?.toString() == "연결코드생성" }
            checkDisabled("전송 유효시간")
            checkDisabled("이 기기 자동 수신")
            check(find(uiAutomation.rootInActiveWindow) { it.text?.toString() == "수신 테스트" } == null)
            clickLabel("생성")
            val code = awaitNode { it.text?.toString() == "ABCD234567" }
            waitForIdleSync()
            uiAutomation.waitForIdle(500, 5_000)
            val bounds = android.graphics.Rect().also { awaitNode { node -> node.text?.toString() == "ABCD234567" }.getBoundsInScreen(it) }
            val down = SystemClock.uptimeMillis()
            sendPointerSync(android.view.MotionEvent.obtain(down, down, android.view.MotionEvent.ACTION_DOWN,
                bounds.exactCenterX(), bounds.exactCenterY(), 0))
            SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 150)
            sendPointerSync(android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_UP,
                bounds.exactCenterX(), bounds.exactCenterY(), 0))
            val copy = awaitNode { it.text?.toString() in listOf("Copy", "복사") }
            clickLabel(copy.text.toString())
            runOnMainSync {
                val clipboard = targetContext.getSystemService(android.content.ClipboardManager::class.java)
                check(clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "ABCD234567")
                clipboard.clearPrimaryClip()
            }
            clickLabel("코드 복사")
            runOnMainSync {
                val clipboard = targetContext.getSystemService(android.content.ClipboardManager::class.java)
                check(clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "ABCD234567")
                fixture.value = DestinationUiState(connectionChecked = true, senderCount = 1)
            }
            awaitNode { it.text?.toString() == "보내는 기기 1대" }
            checkDisabled("생성")
            checkDisabled("전송 유효시간")
            check(awaitNode { it.contentDescription?.toString() == "이 기기 자동 수신" }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitForIdleSync()
            check(receiveChanges == 1)
            clickLabel("연결 해제")
            check(disconnects == 1)
            awaitNode { it.text?.toString() == "미연결" }
            checkDisabled("이 기기 자동 수신")
            runOnMainSync { fixture.value = DestinationUiState(setupStarted = true, connectionChecked = true, receiverName = "차량 태블릿") }
            val field = awaitNode { it.className == "android.widget.EditText" && it.isFocused }
            check(field.isFocused) { "주소 입력칸의 자동 초점 누락" }
            val keyboardDeadline = SystemClock.uptimeMillis() + 10_000
            var keyboardVisible = false
            while (!keyboardVisible && SystemClock.uptimeMillis() < keyboardDeadline) {
                runOnMainSync { keyboardVisible = activity!!.window.decorView.rootWindowInsets
                    ?.isVisible(android.view.WindowInsets.Type.ime()) == true }
                if (!keyboardVisible) SystemClock.sleep(100)
            }
            check(keyboardVisible) { "입력창을 열었지만 키보드가 나타나지 않았습니다" }
            // IME의 실제 창 위치를 기다려 입력 카드가 키보드와 충분히 떨어져 있는지 확인한다.
            val layoutDeadline = SystemClock.uptimeMillis() + 10_000
            var separated = false
            while (!separated && SystemClock.uptimeMillis() < layoutDeadline) {
                val keyboardWindow = uiAutomation.windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                if (keyboardWindow != null) {
                    val keyboardBounds = android.graphics.Rect().also { keyboardWindow.getBoundsInScreen(it) }
                    val inputBounds = android.graphics.Rect().also {
                        awaitNode { node -> node.className == "android.widget.EditText" }.getBoundsInScreen(it)
                    }
                    separated = keyboardBounds.top - inputBounds.bottom >= 24 * targetContext.resources.displayMetrics.density
                }
                if (!separated) SystemClock.sleep(100)
            }
            check(separated) { "입력 카드가 키보드에 너무 가깝습니다" }
            runOnMainSync {
                check(activity!!.window.attributes.gravity and android.view.Gravity.VERTICAL_GRAVITY_MASK == android.view.Gravity.CENTER_VERTICAL)
                check(activity!!.window.attributes.dimAmount in 0.1f..0.4f)
            }
            waitForIdleSync()
            if (captureReview) {
                uiAutomation.waitForIdle(500, 5_000)
                val screen = checkNotNull(uiAutomation.takeScreenshot())
                val reduced = android.graphics.Bitmap.createScaledBitmap(screen, screen.width / 2, screen.height / 2, true)
                java.io.File(targetContext.cacheDir, "destination-input-review.png").outputStream().use {
                    reduced.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                reduced.recycle()
                screen.recycle()
            }
            check(find(uiAutomation.rootInActiveWindow) { it.className == "android.widget.Button" } == null)
            check(field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id))
            waitForIdleSync()
            check(sends == 0)
            enter(field, "   ")
            check(awaitNode { it.className == "android.widget.EditText" }
                .performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id))
            waitForIdleSync()
            check(sends == 0)
            enter(awaitNode { it.className == "android.widget.EditText" }, "서울시청")
            check(awaitNode { it.className == "android.widget.EditText" }
                .performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id))
            waitForIdleSync()
            check(sends == 1 && fixture.value.error == "오프라인")
            check(awaitNode { it.className == "android.widget.EditText" && it.text.toString() == "서울시청" }.isFocused)
            resizeWidget(view, 240, compact = false)
            resizeWidget(view, 640, compact = false)
            // 런처가 사용하는 표준 위젯 재설정 API로 꾸미기를 열어 취소도 확인한다.
            // Android 14 이상의 런처 호스트는 시스템 PendingIntent 실행 권한을 명시한다.
            val configurationOptions = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()
            val configurationMonitor = addMonitor(DestinationWidgetConfigurationActivity::class.java.name, null, false)
            runOnMainSync { host!!.startAppWidgetConfigureActivityForResult(activity!!, widgetId!!, 0, 148, configurationOptions) }
            configuration = waitForMonitorWithTimeout(configurationMonitor, 10_000)
            check(configuration != null) { "런처 재설정으로 꾸미기가 열리지 않았습니다" }
            awaitNode { it.text?.toString() == "위젯 꾸미기" }
            clickLabel("완전 투명")
            clickLabel("취소")
            awaitNode { it.className == "android.widget.EditText" }
            check(runBlocking { store.settings.first().destinationWidgetAppearance } == originalAppearance) {
                "꾸미기 취소가 기존 설정을 변경했습니다"
            }
            // waitForMonitorWithTimeout은 관찰자를 제거하므로 재진입 전에 새로 등록한다.
            val saveMonitor = addMonitor(DestinationWidgetConfigurationActivity::class.java.name, null, false)
            runOnMainSync { host!!.startAppWidgetConfigureActivityForResult(activity!!, widgetId!!, 0, 148, configurationOptions) }
            configuration = waitForMonitorWithTimeout(saveMonitor, 10_000)
            check(configuration != null) { "저장 검증을 위한 꾸미기 재진입 실패" }
            awaitNode { it.text?.toString() == "위젯 꾸미기" }
            clickLabel("배경 색상")
            clickLabel("어둡게")
            clickLabel("완전 투명")
            clickLabel("글자 색상")
            clickLabel("밝은색")
            clickLabel("저장")
            awaitNode { it.className == "android.widget.EditText" }
            val appearance = runBlocking { store.settings.first().destinationWidgetAppearance }
            check(appearance.transparency == 100 && appearance.theme.name == "DARK" && appearance.text.name == "LIGHT") {
                "예상한 꾸미기가 저장되지 않았습니다: $appearance"
            }
            val appliedDeadline = SystemClock.uptimeMillis() + 5_000
            var transparent = false
            while (!transparent && SystemClock.uptimeMillis() < appliedDeadline) {
                runOnMainSync {
                    transparent = view.findViewById<ImageView>(R.id.destination_widget_background).imageAlpha == 0 &&
                        view.findViewById<TextView>(R.id.destination_widget_input).currentTextColor == DarkPalette.ink.toArgb()
                }
                if (!transparent) SystemClock.sleep(100)
            }
            check(transparent) { "저장한 투명도·글자색이 홈 위젯에 반영되지 않았습니다" }
            // 입력창은 스타일 저장 뒤에도 작성 중인 주소를 유지해야 한다.
            check(awaitNode { it.className == "android.widget.EditText" }.text.toString() == "서울시청")
            result.putString("stream", "PASS: 미설정 위젯 즉시 설정/조회 생략·설정 이력 재실행/해제 분기·전송받을 기기 문구·실제 위젯 오프라인 분기·합성 연결 상태별 설정/입력 전환·비활성화·코드 복사/롱터치 제공·연결 해제·가운데 입력·IME 전송·빈 입력 차단·위젯 크기/꾸미기")
        } catch (error: Throwable) {
            resultCode = Activity.RESULT_CANCELED
            val controls = if (activity != null) uiAutomation.rootInActiveWindow?.let(::describe) else "입력창 진입 전"
            result.putString("stream", "FAIL: ${error.javaClass.simpleName}: ${error.message}\n${error.stackTrace.take(4).joinToString("\n")}\n$controls")
        } finally {
            originalAppearance?.let { appearance ->
                runBlocking {
                    val store = (targetContext.applicationContext as TeslaMacroApplication).container.settingsStore
                    store.setDestinationWidgetAppearance(appearance)
                    originalSetup?.let { store.setDestinationSetupStarted(it) }
                    store.setDestinationReceiveEnabled(originalReceiving)
                }
                DestinationWidget.updateAll(targetContext, appearance)
            }
            runOnMainSync {
                configuration?.finish()
                activity?.finish()
                widgetId?.let { host?.deleteAppWidgetId(it) }
                host?.stopListening()
            }
        }
        finish(resultCode, result)
    }

    /** 접근성 부모에 합쳐진 비활성 의미까지 검사한다. */
    private fun checkDisabled(label: String) {
        var node: AccessibilityNodeInfo? = if (label == "이 기기 자동 수신") {
            awaitNode { it.contentDescription?.toString() == label }
        } else awaitNode { it.text?.toString() == label }
        while (node != null) {
            if (!node.isEnabled) return
            node = node.parent
        }
        error("비활성화되어야 하는 항목: $label")
    }

    /** 사용자 입력과 같은 접근성 동작으로 주소를 편집한다. */
    private fun enter(node: AccessibilityNodeInfo, value: String) {
        check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        waitForIdleSync()
        awaitNode { it.className == "android.widget.EditText" && it.text.toString() == value }
    }

    /** 작은 화면에서는 스크롤로 가려진 설정을 드러낸 뒤 누른다. */
    private fun clickLabel(label: String) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val root = uiAutomation.rootInActiveWindow
            var control = root?.let { find(it) { node -> node.text?.toString() == label } }
                ?: uiAutomation.windows.firstNotNullOfOrNull { window ->
                    window.root?.let { find(it) { node -> node.text?.toString() == label } }
                }
            // 선택 시트의 바깥 닫기 영역 대신 글자에 가장 가까운 조작 행을 누른다.
            while (control != null && !control.isClickable) control = control.parent
            if (control != null && control.isVisibleToUser) {
                check(control.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                waitForIdleSync()
                return
            }
            root?.let { find(it) { node -> node.isScrollable } }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(100)
        }
        error("설정을 찾지 못했습니다: $label")
    }

    /** 크기 변경 브로드캐스트가 실제 RemoteViews에 반영될 때까지 확인한다. */
    private fun resizeWidget(view: AppWidgetHostView, width: Int, compact: Boolean) {
        runOnMainSync {
            view.updateAppWidgetOptions(Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 56)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 56)
            })
        }
        val deadline = SystemClock.uptimeMillis() + 10_000
        var matched = false
        while (!matched && SystemClock.uptimeMillis() < deadline) {
            runOnMainSync {
                matched = (view.findViewById<TextView>(R.id.destination_widget_input).visibility == android.view.View.GONE) == compact
            }
            if (!matched) SystemClock.sleep(100)
        }
        check(matched) { "위젯 폭 $width 변경 반영 실패" }
    }

    /** 실패한 테스트 화면의 컨트롤 구조만 남겨 글자와 버튼의 구분을 진단한다. */
    private fun describe(node: AccessibilityNodeInfo): String = buildString {
        appendLine("${node.className}: ${node.text} enabled=${node.isEnabled} clickable=${node.isClickable}")
        for (index in 0 until node.childCount) node.getChild(index)?.let { append(describe(it)) }
    }

    /** 고정 대기 대신 창·네트워크 오류의 실제 상태가 나타날 때까지만 기다린다. */
    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < deadline) {
            uiAutomation.rootInActiveWindow?.let { root -> find(root, predicate)?.let { return it } }
            uiAutomation.windows.forEach { window -> window.root?.let { root -> find(root, predicate)?.let { return it } } }
            SystemClock.sleep(100)
        }
        error("30초 안에 기대한 화면 상태가 나타나지 않았습니다")
    }

    /** Compose 접근성 트리에서 실제 표시된 컨트롤을 찾는다. */
    private fun find(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { find(it, predicate)?.let { found -> return found } }
        }
        return null
    }
}
