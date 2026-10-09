package com.wemade.teslamacro.service

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.wemade.teslable.DiagLog
import com.wemade.teslamacro.data.nav.TeslaDestinationSelection
import com.wemade.teslamacro.data.nav.TeslaNavigationShare
import com.wemade.teslamacro.data.settings.SettingsStore
import com.wemade.teslamacro.data.settings.ThemeMode
import com.wemade.teslamacro.feature.settings.TeslaDestinationChooser
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.TeslaMacroTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 앱 화면을 열지 않고 내비 위에 터치 가능한 선택창을 올린다. */
internal class TeslaDestinationOverlay(
    private val context: Context,
    private val sharing: TeslaNavigationShare,
    settingsStore: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val refreshes = MutableStateFlow(0)
    private val request = mutableStateOf<TeslaDestinationSelection?>(null)
    private val theme = mutableStateOf(ThemeMode.AUTO)
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var unavailableId: Long? = null
    private var screenOn = true

    init {
        val receiver = object : BroadcastReceiver() {
            /** 화면이 꺼지거나 잠기면 선택창을 내리고 잠금 해제 때만 다시 판정한다. */
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_CONFIGURATION_CHANGED) hide()
                screenOn = intent?.action != Intent.ACTION_SCREEN_OFF
                refresh()
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch(Dispatchers.Main.immediate) {
            combine(sharing.selection, settingsStore.settings, refreshes) { pending, settings, _ ->
                theme.value = settings.themeMode
                pending.takeIf { settings.teslaNavigationShareEnabled }
            }.collect { pending -> present(pending) }
        }
    }

    /** 시스템 권한 설정에서 돌아와도 대기 중인 요청으로 창 표시를 다시 시도한다. */
    fun refresh() { refreshes.update { it + 1 } }

    /** 권한·화면 잠금을 직접 확인하고 실패를 화면 표시 성공으로 기록하지 않는다. */
    private fun present(pending: TeslaDestinationSelection?) {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        if (pending == null || !screenOn || !power.isInteractive || keyguard.isKeyguardLocked || keyguard.isDeviceLocked) {
            hide()
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            hide()
            if (unavailableId != pending.id) {
                unavailableId = pending.id
                DiagLog.add("테슬라 내비 연동 — 목적지 선택 오버레이 권한 필요")
                Toast.makeText(context, "설정 → 주행 → 테슬라 내비 연동에서 다른 앱 위에 표시를 허용해 주세요", Toast.LENGTH_LONG).show()
            }
            return
        }
        request.value = pending
        if (view != null) return
        val manager = context.getSystemService(WindowManager::class.java)
        val density = context.resources.displayMetrics.density
        val bounds = if (android.os.Build.VERSION.SDK_INT >= 30) manager.currentWindowMetrics.bounds
            else android.graphics.Rect(0, 0, context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)
        val width = minOf(bounds.width() - (Space.md.value * 2 * density).toInt(), (Space.xxl.value * 8 * density).toInt())
        val maxHeight = (bounds.height() * 0.7f / density).dp
        val lifecycleOwner = OverlayOwner()
        val fresh = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                TeslaMacroTheme(mode = theme.value) {
                    request.value?.let { TeslaDestinationChooser(it, sharing, maxHeight) }
                }
            }
        }
        try {
            manager.addView(fresh, WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.CENTER
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                title = "Tesla destination selection"
            })
            view = fresh
            owner = lifecycleOwner
            lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
            DiagLog.add("테슬라 내비 연동 — 목적지 선택 오버레이 표시 · 후보=${pending.candidates.size}")
        } catch (error: Exception) {
            runCatching { manager.removeView(fresh) }
            view = null
            owner = null
            fresh.disposeComposition()
            lifecycleOwner.registry.currentState = Lifecycle.State.DESTROYED
            DiagLog.add("테슬라 내비 연동 — 목적지 선택 오버레이 실패 ${error.javaClass.simpleName}")
            Toast.makeText(context, "목적지 선택창을 띄우지 못했어요 · 다른 앱 위에 표시 권한을 확인해 주세요", Toast.LENGTH_LONG).show()
        }
    }

    /** OFF·안내 종료·공유·잠금은 Compose 상태와 실제 창을 함께 정리한다. */
    private fun hide() {
        request.value = null
        val attached = view ?: return
        view = null
        attached.disposeComposition()
        owner?.registry?.currentState = Lifecycle.State.DESTROYED
        owner = null
        runCatching { context.getSystemService(WindowManager::class.java).removeView(attached) }
    }

    /** Activity가 없는 오버레이에도 Compose의 수명·입력 상태 소유자를 제공한다. */
    private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
        val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
        init {
            savedState.performAttach()
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.CREATED
        }
    }
}
