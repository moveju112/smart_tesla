package com.wemade.teslamacro.destination

import android.app.Activity
import android.app.Instrumentation
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.wemade.teslamacro.R
import com.wemade.teslamacro.feature.destination.DestinationQuickSendActivity
import com.wemade.teslamacro.feature.destination.DestinationWidget

/** 실제 서버에 보내지 않고 런처 PendingIntent부터 입력·실패 복구까지 확인한다. */
class DestinationWidgetSmokeInstrumentation : Instrumentation() {
    /** 기존 플랫폼 실행기를 재사용해 테스트 전용 라이브러리를 추가하지 않는다. */
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    /** 에뮬레이터의 네트워크 차단과 위젯 바인딩 허가가 있을 때만 실행한다. */
    override fun onStart() {
        val result = Bundle()
        var resultCode = Activity.RESULT_OK
        var host: AppWidgetHost? = null
        var widgetId: Int? = null
        var activity: Activity? = null
        try {
            check(targetContext.getSystemService(ConnectivityManager::class.java).activeNetwork == null) {
                "실제 목적지 전송을 막으려면 테스트 에뮬레이터의 네트워크를 먼저 꺼야 합니다"
            }
            val manager = targetContext.getSystemService(AppWidgetManager::class.java)
            val provider = ComponentName(targetContext, DestinationWidget::class.java)
            val info = manager.installedProviders.single { it.provider == provider }
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
            runOnMainSync { view.findViewById<android.view.View>(R.id.destination_widget_input).performClick() }
            activity = waitForMonitorWithTimeout(monitor, 10_000)
            removeMonitor(monitor)
            check(activity != null) { "위젯 클릭으로 입력창이 열리지 않았습니다" }
            val field = awaitNode { it.className == "android.widget.EditText" }
            check(field.isFocused) { "주소 입력칸의 자동 초점 누락" }
            awaitButton("전송", enabled = false)
            enter(field, "   ")
            awaitButton("전송", enabled = false)
            enter(awaitNode { it.className == "android.widget.EditText" }, "서울시청")
            val send = awaitButton("전송", enabled = true)
            check(send.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitNode { it.text?.toString()?.contains("연결을 확인하지 못했어요") == true }
            check(awaitNode { it.className == "android.widget.EditText" }.text.toString() == "서울시청")
            awaitButton("전송", enabled = true)
            check(awaitButton("기기 연결·설정", enabled = true).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitNode { it.text?.toString() == "목적지 설정" }
            result.putString("stream", "PASS: 위젯 바인딩·클릭·입력창·자동 초점·빈 입력 차단·전송·오프라인 오류·입력 유지·설정 진입")
        } catch (error: Throwable) {
            resultCode = Activity.RESULT_CANCELED
            result.putString("stream", "FAIL: ${error.javaClass.simpleName}: ${error.message}\n${error.stackTrace.take(4).joinToString("\n")}\n${uiAutomation.rootInActiveWindow?.let(::describe)}")
        } finally {
            runOnMainSync {
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
    }

    /** Compose의 글자 노드가 아닌 상위 버튼에서 활성 상태와 클릭을 검사한다. */
    private fun awaitButton(label: String, enabled: Boolean): AccessibilityNodeInfo = awaitNode {
        it.isClickable && it.isEnabled == enabled &&
            find(it) { child -> child.text?.toString() == label } != null
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
