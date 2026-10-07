package com.wemade.teslamacro.data.nav

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.wemade.teslable.DiagLog
import androidx.core.content.ContextCompat
import com.wemade.teslamacro.data.safety.DeviceApiClient
import com.wemade.teslamacro.data.settings.AppSettings
import com.wemade.teslamacro.data.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 전원·통신·착석은 실행 조건을 다시 확인하는 계기이며, 특정 도착 순서를 요구하지 않는다. */
internal class DestinationCoordinator(
    private val context: Context,
    private val settingsStore: SettingsStore,
    navigator: NaverNavigator,
) {
    val client = DestinationClient(DeviceApiClient(context))
    val message = MutableStateFlow("탑승 대기")
    private val journal = DestinationJournal(context)
    private val events = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var present: Boolean? = null
    @Volatile private var presenceAt = -1L
    @Volatile private var settings = AppSettings()
    @Volatile private var destinationSeen = false
    @Volatile private var fallback: (suspend () -> Unit)? = null
    private val receiver = DestinationReceiver(client::call, journal::read, journal::save, journal::clear,
        SystemClock::elapsedRealtime, navigator::navigateDestination) { message.value = it }

    /** 신선한 차량 응답만 수신 허가로 쓰고 하차 때 남은 자동 실행을 중단한다. */
    fun observePresence(value: Boolean) {
        present = value
        presenceAt = SystemClock.elapsedRealtime()
        if (!value) { fallback = null; destinationSeen = false; receiver.retryDeclined() }
        nudge()
    }

    /** 전원 해제 때 오래된 착석값을 버리며 배터리 사용 자체를 영구 금지하지 않는다. */
    fun powerChanged(connected: Boolean) {
        if (!connected) { present = null; presenceAt = -1; fallback = null; destinationSeen = false; receiver.retryDeclined() }
        nudge()
    }

    /** 이벤트가 몰려도 대기 신호는 하나만 남겨 서버 조회를 겹치지 않는다. */
    fun nudge() { events.trySend(Unit) }

    /** 기존 안심운전은 목적지가 없다는 응답 뒤 같은 수신 루프에서 한 번만 실행한다. */
    fun afterBoardingWhenEmpty(action: suspend () -> Unit) {
        if (destinationSeen) return
        fallback = action
        nudge()
    }

    /** 수신 설정과 신선한 착석값을 실행 직전에도 다시 검사한다. */
    private fun ready(): Boolean = destinationReady(settings.destinationReceiveEnabled,
        present, presenceAt, SystemClock.elapsedRealtime())

    /** 인터넷 없는 절전에서는 요청을 보내지 않고 연결 복구 이벤트가 다시 깨운다. */
    private fun online(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager.getNetworkCapabilities(manager.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    /** 서비스 수명 안에서만 연결 이벤트와 주기 수신을 유지하고 재시작 시 현재 상태부터 읽는다. */
    suspend fun watch() = coroutineScope {
        settings = settingsStore.settings.first()
        launch { settingsStore.settings.collect { settings = it; nudge() } }
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            /** 네트워크가 검증된 순간 절전 복귀 대기를 해제한다. */
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { nudge() }
            /** 통신이 끊기면 다음 인계 전에 연결 조건을 다시 확인한다. */
            override fun onLost(network: Network) { nudge() }
        }
        val unlocked = object : BroadcastReceiver() {
            /** 잠금 해제와 화면 복귀 때 놓친 목적지·처리 결과를 다시 확인한다. */
            override fun onReceive(context: Context, intent: Intent) { receiver.retryDeclined(); nudge() }
        }
        manager.registerDefaultNetworkCallback(callback)
        ContextCompat.registerReceiver(context, unlocked, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_EXPORTED)
        var failures = 0
        var retryAt = 0L
        var lastGate: String? = null
        try {
            while (isActive) {
                val active = ready()
                val gate = when {
                    !settings.destinationReceiveEnabled -> null
                    !active -> "착석 응답 대기"
                    !online() -> "인터넷 연결 대기"
                    else -> "착석 확인 · 수신 확인 중"
                }
                if (gate != lastGate) {
                    if (gate != null) DiagLog.add("목적지 수신 — $gate")
                    lastGate = gate
                }
                if (active && online() && SystemClock.elapsedRealtime() >= retryAt) {
                    try {
                        val handled = receiver.receive { ready() && online() }
                        if (handled) destinationSeen = true
                        val emptyAction = fallback
                        fallback = null
                        if (!handled && !destinationSeen && ready()) emptyAction?.invoke()
                        failures = 0
                        retryAt = 0L
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        DiagLog.add("목적지 수신 확인 실패 — ${error.javaClass.simpleName}")
                        failures = (failures + 1).coerceAtMost(4)
                        retryAt = SystemClock.elapsedRealtime() + (5_000L shl failures)
                        message.value = error.message ?: "연결 후 목적지를 다시 확인해요"
                    }
                } else if (settings.destinationReceiveEnabled) {
                    message.value = if (!active) "탑승 대기" else "인터넷 연결 필요"
                }
                if (active && online()) withTimeoutOrNull(maxOf(5_000L, retryAt - SystemClock.elapsedRealtime())) { events.receive() }
                else events.receive()
            }
        } finally {
            manager.unregisterNetworkCallback(callback)
            context.unregisterReceiver(unlocked)
            present = null
            presenceAt = -1
            fallback = null
        }
    }

}
