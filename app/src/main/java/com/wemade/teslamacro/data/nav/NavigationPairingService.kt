package com.wemade.teslamacro.data.nav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import com.wemade.teslamacro.TeslaMacroApplication
import kotlinx.coroutines.*
import java.util.UUID

/** 설정 화면을 읽지 않고 사용자가 알림에 입력한 일회용 코드만 로컬 페어링에 전달한다. */
class NavigationPairingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val navigation get() = (application as TeslaMacroApplication).container.wirelessNavigation
    private val notifications get() = getSystemService(NotificationManager::class.java)
    private var session: String? = null
    private var work: Job? = null
    private var navigationWork: Job? = null

    /** 외부 바인딩 대신 앱이 만든 알림 액션만 받는다. */
    override fun onBind(intent: Intent?): IBinder? = null

    /** 재전송된 이전 알림과 중복 제출을 버리고 현재 설정 요청만 진행한다. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == BEGIN) {
            if (session != null) return START_NOT_STICKY
            session = UUID.randomUUID().toString()
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, "네이버 안심주행 연결", NotificationManager.IMPORTANCE_DEFAULT))
            startForeground(ID, notification("무선 디버깅에서 ‘페어링 코드로 기기 페어링’을 열어 주세요", input = true))
            scope.launch { delay(180_000); finish("설정 시간이 지났어요 · 앱에서 연결 설정을 다시 눌러 주세요") }
        } else if (session != null && intent != null && intent.data?.lastPathSegment == session) {
            if (intent.action == CANCEL) finish(null)
            else if (intent.action == REPLY && work?.isActive != true) {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(CODE)?.toString()?.trim().orEmpty()
                if (!code.matches(Regex("[0-9]{6}"))) {
                    update("숫자 6자리를 입력해 주세요", input = true)
                } else work = scope.launch {
                    update("페어링·연결 준비 중", input = false)
                    navigationWork = navigation.pair("", code)
                    if (navigationWork == null) {
                        update("다른 연결 작업이 끝난 뒤 다시 입력해 주세요", input = true)
                        return@launch
                    }
                    navigationWork?.join()
                    if (navigation.state.value.prepared) finish("페어링·연결 준비 완료")
                    else update(navigation.state.value.message + " · 새 코드 화면을 열어 주세요", input = true)
                }
            }
        } else if (session == null) stopSelf()
        return START_NOT_STICKY
    }

    /** 입력 내용은 알림 기록에 넣지 않고 새 상태만 표시한다. */
    private fun update(message: String, input: Boolean) { notifications.notify(ID, notification(message, input)) }

    /** 현재 요청의 일회용 액션만 만들며 코드는 화면 잠금 해제 후 입력한다. */
    private fun notification(message: String, input: Boolean): Notification {
        val settings = PendingIntent.getActivity(this, 0, settingsIntent(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val content = if (session != null) settings else PendingIntent.getActivity(this, 1,
            Intent(this, com.wemade.teslamacro.MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("네이버 안심주행 연결")
            .setContentText(message).setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(content).setOnlyAlertOnce(true).setAutoCancel(session == null)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setOngoing(session != null)
        if (input) {
            builder.addAction(Notification.Action.Builder(null, "코드 입력", action(REPLY, mutable = true))
                .addRemoteInput(RemoteInput.Builder(CODE).setLabel("페어링 코드 6자리").build())
                .setAuthenticationRequired(true).build())
            builder.addAction(Notification.Action.Builder(null, "설정 열기", settings).build())
        }
        if (session != null) builder.addAction(Notification.Action.Builder(null, "취소", action(CANCEL, mutable = false)).build())
        return builder.build()
    }

    /** 세션 주소로 이전 알림과 새 요청을 분리하며 입력 액션만 수정 가능하게 한다. */
    private fun action(name: String, mutable: Boolean): PendingIntent = PendingIntent.getService(this, 0,
        Intent(this, NavigationPairingService::class.java).setAction(name).setData(Uri.parse("nav-pair://request/$session")),
        PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)

    /** 성공·취소·만료 때 진행 중 알림과 이 서비스 소유의 작업을 정리한다. */
    private fun finish(message: String?) {
        session = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (message != null) notifications.notify(ID, notification(message, input = false))
        stopSelf()
    }

    /** 서비스 종료 뒤 포트 탐색이나 새 페어링을 계속하지 않는다. */
    override fun onDestroy() {
        navigationWork?.takeIf { it.isActive }?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        internal const val CHANNEL = "navigation_pairing"
        private const val ID = 1047
        private const val BEGIN = "nav.pair.begin"
        private const val REPLY = "nav.pair.reply"
        private const val CANCEL = "nav.pair.cancel"
        private const val CODE = "pairing_code"

        /** 알림 입력을 사용할 수 있는지 확인해 보이지 않는 설정 대기를 막는다. */
        fun notificationsEnabled(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java)
            return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        }

        /** 필수 설정과 인증 복구를 확인한 화면에서 새 코드 입력을 시작한다. */
        fun begin(context: Context) {
            if (!notificationsEnabled(context)) {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
            context.startForegroundService(Intent(context, NavigationPairingService::class.java).setAction(BEGIN))
            context.startActivity(settingsIntent())
        }

        /** 제조사별 위치는 설정 앱에 맡기고 무선 디버깅 항목을 강조한다. */
        internal fun settingsIntent(key: String = "toggle_adb_wireless") = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .putExtra(":settings:show_fragment_args", android.os.Bundle().apply {
                putString(":settings:fragment_args_key", key)
            }).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
