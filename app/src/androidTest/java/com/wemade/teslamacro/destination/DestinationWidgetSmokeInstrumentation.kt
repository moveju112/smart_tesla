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
        try {
            check(targetContext.getSystemService(ConnectivityManager::class.java).activeNetwork == null) {
                "실제 목적지 전송을 막으려면 테스트 에뮬레이터의 네트워크를 먼저 꺼야 합니다"
            }
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
            val store = (targetContext.applicationContext as TeslaMacroApplication).container.settingsStore
            originalAppearance = runBlocking { store.settings.first().destinationWidgetAppearance }
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
            waitForIdleSync()
            if (captureReview) {
                val screen = checkNotNull(uiAutomation.takeScreenshot())
                val reduced = android.graphics.Bitmap.createScaledBitmap(screen, screen.width / 2, screen.height / 2, true)
                java.io.File(targetContext.cacheDir, "destination-input-review.png").outputStream().use {
                    reduced.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                reduced.recycle()
                screen.recycle()
            }
            lateinit var model: DestinationViewModel
            runOnMainSync { model = ViewModelProvider(activity as DestinationQuickSendActivity)[DestinationViewModel::class.java] }
            check(find(uiAutomation.rootInActiveWindow) { it.className == "android.widget.Button" } == null) {
                "간편 입력창에 불필요한 버튼이 남아 있습니다"
            }
            check(field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id))
            waitForIdleSync()
            check(!model.state.value.busy && model.state.value.error == null)
            enter(field, "   ")
            check(awaitNode { it.className == "android.widget.EditText" }
                .performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id))
            waitForIdleSync()
            check(!model.state.value.busy && model.state.value.error == null)
            enter(awaitNode { it.className == "android.widget.EditText" }, "서울시청")
            check(awaitNode { it.className == "android.widget.EditText" }
                .performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id))
            val errorDeadline = SystemClock.uptimeMillis() + 15_000
            while ((model.state.value.error == null || model.state.value.busy) && SystemClock.uptimeMillis() < errorDeadline) SystemClock.sleep(100)
            check(model.state.value.error?.contains("연결을 확인하지 못했어요") == true) { "키보드 전송 실패 복구가 확인되지 않았습니다" }
            check(awaitNode { it.className == "android.widget.EditText" && it.text.toString() == "서울시청" }.isFocused)
            check(!model.state.value.sendCompleted)
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
            result.putString("stream", "PASS: 작은 위젯 아이콘·가로 크기 변경·입력창만 표시·자동 초점·키보드 전송·빈 입력 차단·오프라인 오류·주소 유지·런처 꾸미기 저장/취소")
        } catch (error: Throwable) {
            resultCode = Activity.RESULT_CANCELED
            val controls = if (activity != null) uiAutomation.rootInActiveWindow?.let(::describe) else "입력창 진입 전"
            result.putString("stream", "FAIL: ${error.javaClass.simpleName}: ${error.message}\n${error.stackTrace.take(4).joinToString("\n")}\n$controls")
        } finally {
            originalAppearance?.let { appearance ->
                runBlocking {
                    (targetContext.applicationContext as TeslaMacroApplication).container.settingsStore.setDestinationWidgetAppearance(appearance)
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
