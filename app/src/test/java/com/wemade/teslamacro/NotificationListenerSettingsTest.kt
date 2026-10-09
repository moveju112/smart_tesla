package com.wemade.teslamacro

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import app.cash.paparazzi.Paparazzi
import com.wemade.teslamacro.service.SmartThingsNotificationListener
import com.wemade.teslamacro.ui.component.openNotificationListenerSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 설정 앱이 서비스 이름을 정상적으로 읽고 상세 화면 미지원 시 목록으로 이동하는지 확인한다. */
class NotificationListenerSettingsTest {
    @get:Rule val paparazzi = Paparazzi()

    /** 기본 Android 설정의 문자열 계약을 지켜 상세 화면이 즉시 닫히는 회귀를 막는다. */
    @Test fun `상세 화면에는 복원 가능한 서비스 이름 문자열을 전달한다`() {
        val started = mutableListOf<Intent>()
        val context = object : ContextWrapper(paparazzi.context) {
            override fun startActivity(intent: Intent) { started += intent }
        }
        openNotificationListenerSettings(context)
        assertEquals(1, started.size)
        val intent = started.single()
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, intent.action)
        assertEquals(ComponentName(context, SmartThingsNotificationListener::class.java),
            ComponentName.unflattenFromString(intent.getStringExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME)!!))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    /** 상세 화면이 없는 제조사에서도 알림 접근 목록에서 직접 허용할 수 있다. */
    @Test fun `상세 화면 미지원이면 알림 접근 목록으로 이동한다`() {
        val actions = mutableListOf<String?>()
        val context = object : ContextWrapper(paparazzi.context) {
            override fun startActivity(intent: Intent) {
                actions += intent.action
                if (intent.action == Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS) throw ActivityNotFoundException()
            }
        }
        openNotificationListenerSettings(context)
        assertEquals(listOf(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS,
            Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), actions)
    }
}
