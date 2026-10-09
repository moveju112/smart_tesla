package com.wemade.teslamacro.settings

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.wemade.teslamacro.ui.component.openNotificationListenerSettings
import java.io.FileInputStream

/** 실제 기본 Android 설정에서 올바른 서비스 이름 인자로 상세 화면이 유지되는지 확인한다. */
class NotificationListenerSettingsSmokeInstrumentation : Instrumentation() {
    /** 물리 기기가 아닌 명시한 로컬 에뮬레이터 계측에서만 실행한다. */
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    /** 권한을 변경하지 않고 상세 화면의 유지와 서비스 표시를 관측한다. */
    override fun onStart() {
        val result = Bundle()
        var code = Activity.RESULT_OK
        try {
            check(Build.HARDWARE in setOf("ranchu", "goldfish"))
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            runOnMainSync { openNotificationListenerSettings(targetContext) }
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (!notificationDetailsResumed() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            uiAutomation.waitForIdle(600, 10_000)
            check(notificationDetailsResumed()) { "올바른 문자열 인자에서도 상세 화면이 닫힘" }
            val nodes = uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("내비·스마트싱스 알림").orEmpty()
            check(nodes.isNotEmpty()) { "앱의 알림 리스너 표시가 없음" }
            nodes.forEach { it.recycle() }
            result.putString("result", "PASS flattened string keeps notification access details open and shows the app listener")
        } catch (error: Throwable) {
            code = Activity.RESULT_CANCELED
            result.putString("error", "${error.javaClass.simpleName}: ${error.message}")
        } finally {
            uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
        }
        finish(code, result)
    }

    /** 화면 종료 판단은 설정 앱의 실제 최상단 액티비티만 사용한다. */
    private fun notificationDetailsResumed(): Boolean = uiAutomation.executeShellCommand("dumpsys activity activities").use { descriptor ->
        FileInputStream(descriptor.fileDescriptor).bufferedReader().use { reader ->
            reader.lineSequence().any { it.contains("ResumedActivity") && it.contains("NotificationAccessDetails") }
        }
    }
}
