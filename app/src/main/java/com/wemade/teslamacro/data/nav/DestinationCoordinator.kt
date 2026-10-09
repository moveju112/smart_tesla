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
    private val confirmPresence: suspend () -> com.wemade.teslamacro.data.poll.VehiclePresenceObservation?,
) {
    val client = DestinationClient(DeviceApiClient(context))
    val message = MutableStateFlow("탑승 대기")
    private val journal = DestinationJournal(context)
    private val events = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var present: Boolean? = null
    @Volatile private var presenceAt = -1L
    @Volatile private var settings = AppSettings()
    private val diagnostics = DestinationDiagnostics.current.apply { attach(java.io.File(context.noBackupFilesDir, "destination-diagnostics.txt")) }
    private val priority = DestinationNavigationPriority(SystemClock::elapsedRealtime)
    private val boarding = DestinationBoardingState()
    private val networkRecovered = java.util.concurrent.atomic.AtomicBoolean(false)
    private val networkValidated = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var presenceRetryAt = 0L
    private val receiver = DestinationReceiver(::callDestination, journal::read, journal::save, journal::clear,
        SystemClock::elapsedRealtime, navigator::navigateDestination) {
            if (message.value != it) DiagLog.add("목적지 수신 — $it")
            message.value = it
        }

    /** 수신·매크로 사전 조회가 같은 우선권 기록을 사용하며 원문 목적지는 보관하지 않는다. */
    private suspend fun callDestination(operation: String, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): DestinationReply {
        val started = SystemClock.elapsedRealtime()
        val generation = priority.checkpoint()
        val reply = if (operation == "inbox") diagnostics.inbox { client.call(operation, fields) } else client.call(operation, fields)
        val request = reply.request
        if (operation == "inbox") {
            if (request == null) priority.empty(generation)
            else if (request.status == "pending" && !request.legacyTestRequest) {
                destinationDeadline(reply.serverNow, request.expiresAt, started)?.let { priority.pending(request.id, it) }
            }
        } else if (operation == "complete" && request != null &&
            request.status in setOf("delivered", "failed", "expired", "cancelled", "replaced")) priority.complete(request.id)
        return reply
    }

    /** 매크로 지도 준비 전에 수신함을 확인하고 지오코딩·화면 대기 뒤에도 같은 우선권을 검사한다. */
    suspend fun macroNavigationGuard(): () -> Unit {
        val token = priority.checkpoint()
        settings = settingsStore.settings.first()
        if (settings.destinationReceiveEnabled && online()) callDestination("inbox") {}
        val guard = { priority.ensureMacroAllowed(token, receiver.receiptState() != null, settings.destinationReceiveEnabled) }
        guard()
        return guard
    }

    /** 신선한 차량 응답만 수신 허가로 쓰고 하차 때 남은 자동 실행을 중단한다. */
    fun observePresence(value: Boolean, observedAt: Long) {
        synchronized(boarding) {
            present = value
            presenceAt = observedAt
            if (!value) { boarding.reset(); receiver.retryDeclined() }
        }
        nudge()
    }

    /** 전원 해제 때 오래된 착석값을 버리며 배터리 사용 자체를 영구 금지하지 않는다. */
    fun powerChanged(connected: Boolean) {
        synchronized(boarding) {
            if (!connected) { present = null; presenceAt = -1; boarding.reset(); receiver.retryDeclined() }
        }
        presenceRetryAt = 0L
        nudge()
    }

    /** 이벤트가 몰려도 대기 신호는 하나만 남겨 서버 조회를 겹치지 않는다. */
    fun nudge() { events.trySend(Unit) }

    /** 결과가 불명확한 수신 기록은 기존 새로고침에서만 사용자가 확인한다. */
    fun unresolvedRequestId(): String? = receiver.unresolvedRequestId()

    /** 이전 전달 결과 확인 뒤 새 목적지만 다시 허용하며 과거 요청은 재실행하지 않는다. */
    suspend fun resolveReceipt(delivered: Boolean) {
        val session = boarding.session
        receiver.resolveReceipt(delivered)
        boarding.resolved(session)
        message.value = "이전 전달 결과를 반영했어요"
        DiagLog.add("목적지 수신 — 이전 전달 결과 사용자 확인")
        nudge()
    }

    /** 기존 안심운전은 목적지가 없다는 응답 뒤 같은 수신 루프에서 한 번만 실행한다. */
    fun afterBoardingWhenEmpty(action: suspend (() -> Unit) -> Result<Unit>) {
        boarding.queue(SystemClock.elapsedRealtime(), action)
        nudge()
    }

    /** 수신 설정과 신선한 착석값을 실행 직전에도 다시 검사한다. */
    private fun ready(): Boolean = settings.destinationReceiveEnabled && presenceReady()

    /** 자동 안심운전도 현재 탑승의 신선한 관측만 실행 근거로 사용한다. */
    private fun presenceReady(): Boolean = destinationReady(true, present, presenceAt, SystemClock.elapsedRealtime())

    /** 요청이 있을 때만 짧게 차체를 재확인하며 미응답 재시도는 30초 간격으로 제한한다. */
    suspend fun prepareBoarding(): Boolean {
        if (presenceReady()) return true
        if (SystemClock.elapsedRealtime() < presenceRetryAt) return false
        presenceRetryAt = SystemClock.elapsedRealtime() + 30_000L
        diagnostics.presenceStarted()
        val observation = try { confirmPresence() } catch (error: Exception) {
            diagnostics.presenceFinished(null, null)
            throw error
        }
        diagnostics.presenceFinished(observation?.present, observation?.observedAt)
        if (observation == null) return false
        observePresence(observation.present, observation.observedAt)
        if (presenceReady()) presenceRetryAt = 0L
        return presenceReady()
    }

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
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val previouslyValidated = networkValidated.getAndSet(validated)
                if (validated && !previouslyValidated) {
                    networkRecovered.set(true)
                    presenceRetryAt = 0L
                    nudge()
                } else if (!validated && previouslyValidated) nudge()
            }
            /** 통신이 끊기면 다음 인계 전에 연결 조건을 다시 확인한다. */
            override fun onLost(network: Network) { networkValidated.set(false); nudge() }
        }
        val unlocked = object : BroadcastReceiver() {
            /** 잠금 해제와 화면 복귀 때 놓친 목적지·처리 결과를 다시 확인한다. */
            override fun onReceive(context: Context, intent: Intent) { receiver.retryDeclined(); nudge() }
        }
        manager.registerDefaultNetworkCallback(callback)
        ContextCompat.registerReceiver(context, unlocked, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_EXPORTED)
        var failures = 0
        var retryAt = 0L
        var nextInboxAt = 0L
        var lastGate: String? = null
        try {
            while (isActive) {
                if (networkRecovered.getAndSet(false)) { failures = 0; retryAt = 0L; nextInboxAt = 0L }
                val enabled = settings.destinationReceiveEnabled
                val connected = online()
                val gate = when {
                    !enabled -> null
                    !connected -> "인터넷 연결 대기"
                    else -> "수신함 확인 중"
                }
                if (gate != lastGate) {
                    if (gate != null) DiagLog.add("목적지 수신 — $gate")
                    lastGate = gate
                }
                if (enabled && connected && SystemClock.elapsedRealtime() >= maxOf(retryAt, nextInboxAt)) {
                    try {
                        val outcome = boarding.receive(receiver, prepare = {
                            settings.destinationReceiveEnabled && online() && prepareBoarding()
                        }, ready = { ready() && online() }, elapsed = SystemClock::elapsedRealtime,
                            onDispatch = { diagnostics.deliveryChecked(settings.destinationReceiveEnabled, online(), present, presenceAt) })
                        diagnostics.deliveryFinished(outcome)
                        nextInboxAt = SystemClock.elapsedRealtime() + 5_000L
                        failures = 0
                        retryAt = 0L
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        DiagLog.add("목적지 수신 확인 실패 — ${error.javaClass.simpleName}")
                        failures = (failures + 1).coerceAtMost(4)
                        retryAt = SystemClock.elapsedRealtime() + (5_000L shl failures)
                        message.value = error.message ?: "연결 후 목적지를 다시 확인해요"
                    }
                }
                val pending = boarding.fallback
                if (pending != null && SystemClock.elapsedRealtime() - pending.startedAt > 90_000L) {
                    boarding.consume(pending)
                    DiagLog.add("탑승 안심운전 — 재시도 시간 종료")
                }
                val request = boarding.fallback
                val now = SystemClock.elapsedRealtime()
                val result = receiver.receiptState() ?: if (!enabled) DestinationReceiveResult.EMPTY
                    else boarding.result(now)

                if (request != null && settings.autoStartNavigatorSafeDrive && request.canExecute(result, boarding.destinationSeen, now) && prepareBoarding() && boarding.fallback === request
                ) {
                    if (request.execute(now)) boarding.consume(request)
                }
                if ((enabled && connected) || boarding.fallback != null) {
                    val delayMillis = if (boarding.fallback != null) 1_000L else maxOf(5_000L, retryAt - SystemClock.elapsedRealtime())
                    withTimeoutOrNull(delayMillis) { events.receive() }
                } else events.receive()
            }
        } finally {
            manager.unregisterNetworkCallback(callback)
            context.unregisterReceiver(unlocked)
            synchronized(boarding) {
                present = null
                presenceAt = -1
                boarding.reset()
            }
        }
    }

}

/** 탑승 상태 변경은 한 잠금 안에서 적용하고 서버 응답 대기 중에는 잠금을 잡지 않는다. */
internal class DestinationBoardingState {
    @Volatile var session = 0L
        private set
    @Volatile var destinationSeen = false
        private set
    @Volatile var fallback: BoardingNavigationRequest? = null
        private set
    private var inboxObservation: DestinationInboxObservation? = null

    /** 하차·전원 해제는 조회·탑승 상태만 초기화하고 영속 전달 기록은 유지한다. */
    @Synchronized fun reset() {
        session++
        destinationSeen = false
        fallback = null
        inboxObservation = null
    }

    /** 안심운전 대기 등록은 현재 탑승에 속하며 새 탑승 세션을 만들지 않는다. */
    @Synchronized fun queue(now: Long, action: suspend (() -> Unit) -> Result<Unit>) {
        if (destinationSeen) return
        val ownerSession = session
        lateinit var request: BoardingNavigationRequest
        val ensureCurrent = {
            synchronized(this) {
                if (session != ownerSession || fallback !== request || destinationSeen) {
                    throw BoardingRequestExpiredException()
                }
            }
        }
        request = BoardingNavigationRequest(now) {
            try {
                ensureCurrent()
                action(ensureCurrent)
            } catch (error: BoardingRequestExpiredException) {
                Result.failure(error)
            }
        }
        fallback = request
    }

    /** 늦은 실행 완료는 그동안 교체된 다른 탑승 요청을 삭제하지 않는다. */
    @Synchronized fun consume(request: BoardingNavigationRequest) {
        if (fallback === request) fallback = null
    }

    /** 사용자 확인 응답도 새 탑승의 대기 상태를 덮어쓰지 않는다. */
    @Synchronized fun resolved(ownerSession: Long) {
        if (ownerSession == session) { destinationSeen = false; fallback = null }
    }

    /** 조회 시작과 지도 전달 직전 탑승을 구분해 완료 응답의 소유자를 정한다. */
    suspend fun receive(receiver: DestinationReceiver, prepare: suspend () -> Boolean, ready: () -> Boolean, elapsed: () -> Long,
        onDispatch: () -> Unit = {},
    ): DestinationReceiveResult {
        val querySession = session
        var dispatchSession: Long? = null
        val result = receiver.receive(prepare = prepare, onDispatch = {
            synchronized(this) {
                check(ready()) { "탑승 실행 조건이 바뀌었어요" }
                dispatchSession = session
                onDispatch()
            }
        }, ready = ready)
        synchronized(this) {
            val ownerSession = dispatchSession ?: querySession
            if (ownerSession == session) {
                inboxObservation = DestinationInboxObservation(ownerSession, result,
                    if (result == DestinationReceiveResult.WAITING_FOR_CONDITIONS)
                        receiver.waitingDeadline ?: elapsed() + 5_000L
                    else elapsed() + 5_000L)
                if (dispatchSession != null &&
                    (result == DestinationReceiveResult.DISPATCHED || result == DestinationReceiveResult.UNKNOWN)) {
                    destinationSeen = true
                    fallback = null
                }
            }
        }
        return result
    }

    /** 같은 탑승의 유효한 조회 결과만 오프라인 실행 판단에 제공한다. */
    @Synchronized fun result(now: Long): DestinationReceiveResult? = inboxObservation?.resultFor(session, now)
}

/** 조회 결과는 관측 당시 탑승과 목적지 유효시간 안에서만 자동 실행 판단에 쓴다. */
internal data class DestinationInboxObservation(val session: Long, val result: DestinationReceiveResult, val expiresAt: Long) {
    /** 이전 탑승의 늦은 응답과 만료된 목적지 대기를 새 탑승으로 넘기지 않는다. */
    fun resultFor(currentSession: Long, now: Long): DestinationReceiveResult? =
        result.takeIf { session == currentSession && now < expiresAt }
}

/** 하차한 탑승 요청은 조건 회복 재시도 대상이 아니며 수신 루프 취소로 전파하지 않는다. */
internal class BoardingRequestExpiredException : IllegalStateException("이전 탑승의 안심운전 요청을 종료했어요")

/** 탑승 요청은 전달 전 실패만 두 번까지 재시도하고 인계·인증 취소 이후에는 소비한다. */
internal class BoardingNavigationRequest(val startedAt: Long, private val action: suspend () -> Result<Unit>) {
    var retryAt = 0L
        private set
    private var attempts = 0

    /** 서버 조회는 8초까지만 우선하며 목적지 대기·전달·불명확 상태는 중복 안심운전을 막는다. */
    fun canExecute(result: DestinationReceiveResult?, destinationSeen: Boolean, now: Long): Boolean =
        !destinationSeen && attempts < 2 && now >= retryAt && now - startedAt in 0..90_000L &&
            (result == DestinationReceiveResult.EMPTY || result == DestinationReceiveResult.FAILED ||
                (result == null && now - startedAt >= 8_000L))

    /** 권한·설치·실행 중 충돌처럼 전달 전임이 명확한 실패에만 복구 기회를 남긴다. */
    suspend fun execute(now: Long): Boolean {
        attempts++
        val result = action()
        if (result.isSuccess || result.exceptionOrNull() !is SafeDrivePreflightException || attempts >= 2) return true
        retryAt = now + 5_000L
        DiagLog.add("탑승 안심운전 — 전달 전 조건 회복 대기")
        return false
    }
}
