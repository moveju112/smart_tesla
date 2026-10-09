package com.wemade.teslamacro.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.wemade.teslamacro.TeslaMacroApplication
import com.wemade.teslamacro.data.settings.SmartThingsCommands
import com.wemade.teslable.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 스마트싱스 명령 알림을 문구별 기존 빠른 차량 동작 실행 경로로 넘긴다. */
class SmartThingsNotificationListener : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val receiptGuard = NotificationReceiptGuard()

    /** 스마트싱스 알림의 출처·문구·중복을 확인한 뒤 연결된 빠른 차량 동작으로 넘긴다. */
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (com.wemade.teslamacro.data.nav.TeslaNavigationDestination.supports(sbn.packageName)) {
            serviceScope.launch {
                val app = application as TeslaMacroApplication
                app.ready.first { it }
                val extras = sbn.notification.extras
                app.container.navigationTestOverlay.notification(sbn)
                app.container.teslaNavigationShare.notification(sbn.packageName, sbn.key,
                    extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                    extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty())
            }
            return
        }
        if (sbn.packageName != SMARTTHINGS_PACKAGE_NAME) return

        val receivedAt = android.os.SystemClock.elapsedRealtime()
        val texts = notificationTexts(sbn.notification)
        serviceScope.launch {
            val app = application as TeslaMacroApplication
            app.ready.first { it }
            val settings = app.container.settingsStore.settings.first()
            if (!settings.smartThingsEnabled) return@launch
            val action = matchingSmartThingsAction(
                sbn.packageName,
                texts,
                settings.smartThingsCommandTexts,
            ) ?: return@launch
            val label = SmartThingsCommands.all.firstOrNull { it.action == action }?.label ?: action
            if (!receiptGuard.accept(sbn.key, android.os.SystemClock.elapsedRealtime())) {
                DiagLog.add("스마트싱스 $label 알림 중복 무시")
                return@launch
            }

            // 알림 수신 시각을 전달해 설정 조회와 서비스 시작 지연도 유효시간에 포함한다.
            runCatching {
                MacroService.runQuickAction(this@SmartThingsNotificationListener, action, null,
                    settings.smartThingsValiditySeconds, receivedAt, dismissOnSuccess = true)
            }
                .onSuccess {
                    cancelNotification(sbn.key)
                    DiagLog.add("스마트싱스 $label 알림 수신 — 명령 전달 후 알림 삭제")
                }
                .onFailure { error ->
                    DiagLog.add("스마트싱스 $label 명령 전달 실패 — ${error.message}")
                }
        }
    }

    /** 안내 알림 종료를 전송 예약기에 알려 다음 안내에 과거 목적지가 남지 않게 한다. */
    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        if (!com.wemade.teslamacro.data.nav.TeslaNavigationDestination.supports(sbn.packageName)) return
        serviceScope.launch {
            val app = application as TeslaMacroApplication
            app.ready.first { it }
            app.container.teslaNavigationShare.removed(sbn.packageName, sbn.key, reason)
            app.container.navigationTestOverlay.removed(sbn.key)
        }
    }

    /** 시스템이 리스너 연결을 끊으면 대기 중인 설정 조회도 함께 정리한다. */
    override fun onDestroy() {
        val app = application as TeslaMacroApplication
        if (app.ready.value) app.container.navigationTestOverlay.clear()
        serviceScope.cancel()
        super.onDestroy()
    }

    /** 재연결 때 활성 알림을 다시 읽어 이미 시작된 안내도 같은 테스트창에 표시한다. */
    override fun onListenerConnected() {
        serviceScope.launch {
            val app = application as TeslaMacroApplication
            app.ready.first { it }
            activeNotifications.orEmpty().filter { com.wemade.teslamacro.data.nav.TeslaNavigationDestination.supports(it.packageName) }
                .forEach { app.container.navigationTestOverlay.notification(it) }
        }
    }

    /** 알림 접근이 끊기면 갱신할 수 없는 마지막 안내창을 즉시 닫는다. */
    override fun onListenerDisconnected() {
        val app = application as TeslaMacroApplication
        if (app.ready.value) app.container.navigationTestOverlay.clear()
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
