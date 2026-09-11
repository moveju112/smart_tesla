package com.wemade.teslamacro.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.wemade.teslamacro.TeslaMacroApplication
import com.wemade.teslable.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 스마트싱스 명령 알림을 기존 프렁크 바로가기 실행 경로로 넘긴다. */
class SmartThingsNotificationListener : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val receiptGuard = NotificationReceiptGuard()

    /** 스마트싱스 알림의 출처·문구·중복을 확인한 뒤 프렁크 직접 명령으로 넘긴다. */
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != SMARTTHINGS_PACKAGE_NAME) return

        val texts = notificationTexts(sbn.notification)
        serviceScope.launch {
            val app = application as TeslaMacroApplication
            app.ready.first { it }
            val settings = app.container.settingsStore.settings.first()
            if (!settings.smartThingsFrunkEnabled) return@launch
            if (!matchesSmartThingsFrunkNotification(sbn.packageName, texts, settings.smartThingsFrunkText)) {
                return@launch
            }
            if (!receiptGuard.accept(sbn.key, android.os.SystemClock.elapsedRealtime())) {
                DiagLog.add("스마트싱스 프렁크 알림 중복 무시")
                return@launch
            }

            // 기존 바로가기와 같은 서비스 진입점을 써서 BLE·P단·2분 제한을 그대로 적용한다.
            runCatching { MacroService.runQuickAction(this@SmartThingsNotificationListener, "open_frunk", null) }
                .onSuccess {
                    cancelNotification(sbn.key)
                    DiagLog.add("스마트싱스 프렁크 알림 수신 — 명령 전달 후 알림 삭제")
                }
                .onFailure { error ->
                    DiagLog.add("스마트싱스 프렁크 명령 전달 실패 — ${error.message}")
                }
        }
    }

    /** 시스템이 리스너 연결을 끊으면 대기 중인 설정 조회도 함께 정리한다. */
    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    /** 알림 양식이 바뀌어도 제목·본문·확장 본문 중 한 칸의 정확한 일치를 찾는다. */
    private fun notificationTexts(notification: Notification): List<String> = buildList {
        val extras = notification.extras
        listOf(
            Notification.EXTRA_TITLE,
            Notification.EXTRA_TITLE_BIG,
            Notification.EXTRA_TEXT,
            Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_SUMMARY_TEXT,
        ).forEach { key ->
            extras.getCharSequence(key)?.toString()?.let(::add)
        }
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapTo(this) { it.toString() }
    }
}
