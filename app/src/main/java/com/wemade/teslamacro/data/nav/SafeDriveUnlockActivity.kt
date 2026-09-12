package com.wemade.teslamacro.data.nav

import android.app.Activity
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.wemade.teslamacro.ui.component.ButtonTone
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T
import com.wemade.teslamacro.ui.theme.TeslaMacroTheme
import com.wemade.teslable.DiagLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 키가드 또는 보안 잠금이 남아 있으면 화면을 켜고 시스템 해제를 요청한다. */
class SafeDriveUnlockActivity : ComponentActivity() {
    private var requestId = ""
    private var dismissalRequested = false

    /** 살아 있는 요청만 표시해 프로세스 재시작 후 예전 알림이 지도를 열지 못하게 한다. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            // 뒤로 가기도 인증 취소와 똑같이 처리한다.
            override fun handleOnBackPressed() = cancelRequest()
        })
        bindRequest(intent)
    }

    /** 알림을 다시 눌러도 같은 화면에서 인증 한 번만 요청한다. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val id = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        // 늦게 전달된 이전 알림 때문에 현재 인증 화면까지 닫히지 않게 한다.
        if (!gate.isCurrent(id)) return
        setIntent(intent)
        if (id != requestId) bindRequest(intent)
    }

    /** 요청 ID만 전달받고 외부 앱·URI 선택은 원래 Navigator 호출이 소유한다. */
    private fun bindRequest(intent: Intent) {
        val id = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        if (!gate.isCurrent(id)) {
            finish()
            return
        }
        requestId = id
        dismissalRequested = false
        promptActivity = this
        DiagLog.add("안심운전 인증 화면 표시 — 요청=$id")
        setContent {
            val app = application as com.wemade.teslamacro.TeslaMacroApplication
            val settings by app.container.settingsStore.settings.collectAsState(initial = app.container.initialSettings)
            TeslaMacroTheme(mode = settings.themeMode) {
                SafeDriveUnlockScreen(onCancel = ::cancelRequest)
            }
        }
    }

    /** 화면이 실제로 보이는 시점에 시스템 인증을 요청한다. */
    override fun onResume() {
        super.onResume()
        if (isFinishing || dismissalRequested || !gate.isPending(requestId)) return
        dismissalRequested = true
        val id = requestId
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (isSafeDriveUnlocked(keyguard.isKeyguardLocked, keyguard.isDeviceLocked)) {
            gate.complete(id, true)
            return
        }
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            // 성공 콜백 뒤에도 실제 잠금 상태를 검사해 인증 전 실행을 막는다.
            override fun onDismissSucceeded() {
                if (promptActivity !== this@SafeDriveUnlockActivity) return
                val unlocked = isSafeDriveUnlocked(keyguard.isKeyguardLocked, keyguard.isDeviceLocked)
                if (gate.complete(id, unlocked)) {
                    DiagLog.add(
                        "안심운전 잠금 해제 결과 — 인증완료=$unlocked · " +
                            "키가드잠금=${keyguard.isKeyguardLocked} · 기기잠금=${keyguard.isDeviceLocked}",
                    )
                }
            }

            // 사용자가 취소하면 이번 탑승에서 인증을 반복 요구하지 않는다.
            override fun onDismissCancelled() {
                if (promptActivity !== this@SafeDriveUnlockActivity) return
                if (gate.complete(id, false)) DiagLog.add("안심운전 잠금 해제 취소")
            }

            // 시스템 거부를 지도 실행 성공으로 기록하지 않는다.
            override fun onDismissError() {
                if (promptActivity !== this@SafeDriveUnlockActivity) return
                if (gate.complete(id, false)) DiagLog.add("안심운전 잠금 해제 요청 거부")
            }
        })
    }

    /** 명시적 취소는 대기 요청도 끝내 이후 콜백을 무시한다. */
    private fun cancelRequest() {
        gate.complete(requestId, false)
        finish()
    }

    /** 진단의 두 번째 전달도 인증 대기에서 정한 만료 시각을 넘기지 않는다. */
    internal fun canContinue(): Boolean = gate.isCurrent(requestId)

    /** 화면이 사라지면 요청도 정리하되 회전 재생성은 새 화면이 이어받는다. */
    override fun onDestroy() {
        if (promptActivity === this) {
            promptActivity = null
            if (!isChangingConfigurations) gate.complete(requestId, false)
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_REQUEST_ID = "safe_drive_unlock_request"
        private const val CHANNEL_ID = "safe_drive_unlock"
        private const val NOTIFICATION_ID = 7004
        private val gate = SafeDriveUnlockGate(SystemClock::elapsedRealtime)
        private var promptActivity: SafeDriveUnlockActivity? = null

        /** 보이는 인증 화면에서만 이어서 전달하고, 만료·취소 때 화면과 알림을 함께 닫는다. */
        internal suspend fun runWhenUnlocked(
            context: Context,
            appLabel: String,
            openActivity: suspend (Intent) -> Unit,
            launch: suspend (Activity) -> Unit,
        ): Boolean = withContext(Dispatchers.Main) {
            var notificationIntent: PendingIntent? = null
            gate.run(
                showPrompt = { id ->
                    val intent = Intent(context, SafeDriveUnlockActivity::class.java)
                        .setAction("${context.packageName}.SAFE_DRIVE_UNLOCK.$id")
                        .putExtra(EXTRA_REQUEST_ID, id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    notificationIntent = showNotification(context, intent, appLabel)
                    DiagLog.add("$appLabel 안심운전 인증 대기 — 최대 60초 · 신뢰 기기 등록 불필요")
                    try {
                        openActivity(intent)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        DiagLog.add("안심운전 인증 화면 전달 실패 — 알림에서 이어가기: ${error.message}")
                    }
                },
                closePrompt = { id ->
                    context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                    notificationIntent?.cancel()
                    promptActivity?.takeIf { it.requestId == id }?.finish()
                    DiagLog.add("안심운전 인증 대기 종료 — 요청=$id")
                },
                launch = {
                    val activity = promptActivity
                    check(activity != null && !activity.isFinishing && !activity.isDestroyed) {
                        "잠금 해제 화면이 닫혀 요청을 취소했어요"
                    }
                    val keyguard = context.getSystemService(KeyguardManager::class.java)
                    check(isSafeDriveUnlocked(keyguard.isKeyguardLocked, keyguard.isDeviceLocked)) {
                        "다시 잠겨 안심운전 요청을 취소했어요"
                    }
                    DiagLog.add("$appLabel 안심운전 인증 후 실행 이어가기")
                    launch(activity)
                },
            )
        }

        /** 배경 화면 전환이 지연돼도 사용자가 같은 요청으로 이어갈 수 있는 알림을 둔다. */
        private fun showNotification(context: Context, intent: Intent, appLabel: String): PendingIntent? =
            runCatching {
                val manager = context.getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "안심운전 잠금 해제", NotificationManager.IMPORTANCE_DEFAULT),
                )
                if (!manager.areNotificationsEnabled() ||
                    manager.getNotificationChannel(CHANNEL_ID).importance == NotificationManager.IMPORTANCE_NONE
                ) {
                    DiagLog.add("안심운전 인증 알림 꺼짐 — 인증 화면 자동 표시만 시도")
                    return@runCatching null
                }
                val pendingIntent = PendingIntent.getActivity(
                    context, NOTIFICATION_ID, intent,
                    PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                manager.notify(
                    NOTIFICATION_ID,
                    Notification.Builder(context, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                        .setContentTitle("$appLabel 안심운전 준비")
                        .setContentText("잠금을 해제하면 이어서 실행해요 · 1분 동안 유효")
                        .setContentIntent(pendingIntent)
                        .setAutoCancel(true)
                        .setTimeoutAfter(SAFE_DRIVE_UNLOCK_TIMEOUT_MILLIS)
                        .build(),
                )
                pendingIntent
            }.onFailure { DiagLog.add("안심운전 인증 알림 표시 실패 — ${it.message}") }.getOrNull()
    }
}

/** 시스템 인증 창 뒤에도 실행 목적과 취소 방법을 읽을 수 있게 한다. */
@Composable
internal fun SafeDriveUnlockScreen(onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(T.Void).safeDrawingPadding().padding(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.md, androidx.compose.ui.Alignment.CenterVertically),
    ) {
        Text("안심운전 준비", style = MaterialTheme.typography.headlineSmall, color = T.Ink)
        Text("잠금을 해제하면 안심운전을 이어서 실행해요.", style = MaterialTheme.typography.bodyLarge, color = T.InkMuted)
        TButton(text = "이번에는 취소", onClick = onCancel, tone = ButtonTone.Secondary)
    }
}
