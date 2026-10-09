package com.wemade.teslamacro.service

import android.app.KeyguardManager
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.wemade.teslable.DiagLog
import com.wemade.teslamacro.data.nav.isTeslaNavigationGuidance
import com.wemade.teslamacro.data.settings.SettingsStore
import com.wemade.teslamacro.ui.theme.Space
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 임시 모드에서 지도 알림 원본을 표시해 회전·거리 안내를 추측하거나 차량에 보내지 않는다. */
internal class NavigationTestOverlay(
    private val context: Context,
    settingsStore: SettingsStore,
    scope: CoroutineScope,
) {
    private val pending = MutableStateFlow<StatusBarNotification?>(null)
    private val refreshes = MutableStateFlow(0)
    private var view: FrameLayout? = null
    private var unavailableKey: String? = null

    init {
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            /** 잠금·화면·회전 변경 때 현재 알림을 재판정해 잠긴 화면에는 표시하지 않는다. */
            override fun onReceive(context: Context?, intent: Intent?) { refresh() }
        }, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch(Dispatchers.Main.immediate) {
            combine(pending, settingsStore.settings, refreshes) { notification, settings, _ ->
                notification.takeIf { settings.teslaNavigationTestMode }
            }.collect { present(it) }
        }
    }

    /** 실제 안내 시작 알림만 저장하고 같은 알림의 안내 종료는 즉시 창을 내린다. */
    fun notification(notification: StatusBarNotification) {
        val extras = notification.notification.extras
        if (isTeslaNavigationGuidance(notification.packageName,
                extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty())) {
            pending.value = notification
        } else if (pending.value?.key == notification.key) pending.value = null
    }

    /** 다른 앱의 알림 삭제가 현재 안내창을 닫지 않도록 정확한 키만 소비한다. */
    fun removed(key: String) { if (pending.value?.key == key) pending.value = null }

    /** 리스너 연결이 끊기면 오래된 회전·거리를 남기지 않는다. */
    fun clear() { pending.value = null }

    /** 오버레이 권한 허용 후 복귀하면 이미 진행 중인 안내도 다시 표시한다. */
    fun refresh() { refreshes.update { it + 1 } }

    /** 알림의 RemoteViews를 재사용하고 일반 알림도 Android 원본 템플릿으로 표시한다. */
    private fun present(notification: StatusBarNotification?) {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        if (notification == null || !power.isInteractive || keyguard.isKeyguardLocked || keyguard.isDeviceLocked) {
            hide()
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            hide()
            if (unavailableKey != notification.key) {
                unavailableKey = notification.key
                DiagLog.add("내비 테스트 보조창 — 다른 앱 위에 표시 권한 필요")
                Toast.makeText(context, "설정 → 기기 → 권한 점검에서 다른 앱 위에 표시를 허용해 주세요", Toast.LENGTH_LONG).show()
            }
            return
        }
        unavailableKey = null
        val manager = context.getSystemService(WindowManager::class.java)
        val frame = view ?: FrameLayout(context).apply { contentDescription = "내비 테스트 보조창" }
        runCatching {
            val original = notification.notification
            val remote = original.bigContentView ?: original.contentView
                ?: Notification.Builder.recoverBuilder(context, original).createContentView()
            // 지도 앱이 제공한 원본 회전 아이콘·거리만 사용하며 알림 버튼은 터치 불가로 둔다.
            val content = remote.apply(context, frame)
            frame.removeAllViews()
            frame.addView(content)
            val density = context.resources.displayMetrics.density
            val margin = (Space.md.value * density).toInt()
            val params = WindowManager.LayoutParams(
                context.resources.displayMetrics.widthPixels - margin * 2,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = margin
                y = margin
                title = "Navigation test overlay"
                // Android 12 이상은 불투명한 터치 불가 창 아래 입력도 막으므로 시스템 허용치로 제한한다.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    alpha = context.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch
                }
            }
            if (view == null) {
                manager.addView(frame, params)
                view = frame
                DiagLog.add("내비 테스트 보조창 — 지도 안내 알림 표시 · 차량 전송 차단")
            } else manager.updateViewLayout(frame, params)
        }.onFailure {
            hide()
            DiagLog.add("내비 테스트 보조창 — 지도 알림 표시 실패 ${it.javaClass.simpleName}")
            Toast.makeText(context, "지도 알림을 표시하지 못했어요 · 길안내를 다시 시작해 주세요", Toast.LENGTH_LONG).show()
        }
    }

    /** 안내 종료·테스트 OFF·잠금 때 현재 창만 제거한다. */
    private fun hide() {
        val attached = view ?: return
        view = null
        runCatching { context.getSystemService(WindowManager::class.java).removeView(attached) }
    }
}
