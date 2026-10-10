package com.wemade.teslamacro.data.nav

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.wemade.teslable.DiagLog
import com.wemade.teslamacro.BuildConfig
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.provider.Settings
import android.content.pm.PackageManager
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 셸 출력은 줄 단위로 조립해 한글 분할 수신과 진단 문구 속 제어 신호 오인을 막는다. */
internal class NavigationSessionOutput {
    private val pending = ByteArrayOutputStream()
    private var discarding = false

    /** 완성된 UTF-8 줄만 반환하고 과도하게 긴 기록은 다음 줄부터 정상 수신한다. */
    fun feed(bytes: ByteArray, size: Int): List<String> {
        val messages = mutableListOf<String>()
        for (index in 0 until size) {
            val value = bytes[index].toInt() and 0xff
            if (value == 10) {
                if (!discarding) messages.add(pending.toString(Charsets.UTF_8.name()).trimEnd('\r'))
                pending.reset()
                discarding = false
            } else if (!discarding) {
                if (pending.size() < 4096) pending.write(value)
                else {
                    pending.reset()
                    discarding = true
                }
            }
        }
        return messages
    }
}

/** 실험 설정은 일반 백업과 분리해 다른 기기에서 자동 실행되지 않게 한다. */
data class WirelessNavigationState(
    val enabled: Boolean = false,
    /** 하차 때 USB 디버깅을 끄고 탑승 때 다시 켠다. 디버깅을 막는 은행 앱용 */
    val toggleUsbDebugging: Boolean = false,
    val port: String = "",
    val busy: Boolean = false,
    val running: Boolean = false,
    val prepared: Boolean = false,
    val message: String = "무선 페어링 필요",
    /** 안심운전을 실행할 내비. 네이버·티맵·카카오내비 중 사용자가 고른다 */
    val app: NavigatorApp = NavigatorApp.NAVER,
)

/** 전송 이후의 실패는 다른 실행 통로·URI로 반복하지 않고 사용자에게 돌린다. */
internal class DestinationLaunchException(message: String, cause: Throwable? = null, val uncertain: Boolean = false) : IllegalStateException(message, cause)

/** 사용자가 잠금 해제 화면을 취소했거나 시간 안에 풀지 않았다. 인계 전이라 요청은 그대로 대기한다. */
internal class DestinationUnlockDeclinedException :
    IllegalStateException("잠금을 해제하면 목적지를 열어요")

/** 전달 전 실패만 탑승 요청의 제한 재시도를 허용한다. */
internal class SafeDrivePreflightException(message: String) : IllegalStateException(message)

/** 기존 세션의 종료 응답까지 기다린 뒤 새 안내를 허용한다. */
internal suspend fun stopNavigationForDestination(operation: Job?, hasSession: Boolean, stopped: () -> Boolean) {
    if (!hasSession) return
    operation?.cancel()
    val finished = withTimeoutOrNull(15_000L) { operation?.join(); true } == true
    if (!finished || !stopped()) throw DestinationLaunchException("안심주행 종료를 확인하지 못했어요 · 지도 상태를 확인해 주세요")
}

/** 종료 정리 중 들어온 새 요청만 보관하며 정상 종료·실패를 자동 재시도로 바꾸지 않는다. */
internal class NavigationRestartRequest(private val scope: CoroutineScope) {
    private var pending: Any? = null

    /** 취소 중 작업의 완료 콜백을 재사용해 여러 시작 요청을 한 번으로 합친다. */
    fun afterCancellation(operation: Job, restart: () -> Unit) {
        if (!operation.isCancelled || operation.isCompleted || pending != null) return
        val request = Any()
        pending = request
        operation.invokeOnCompletion {
            scope.launch {
                if (pending === request) {
                    pending = null
                    restart()
                }
            }
        }
    }

    /** 설정 해제·하차·서비스 종료 뒤 늦게 도착하는 완료 콜백은 실행 권한이 없다. */
    fun cancel() { pending = null }
}

/** ADB는 준비 때만 쓰고 지도 제어는 같은 휴대폰의 Binder로 연결한다. */
class WirelessNavigation(private val context: Context) {
    private val preferences = context.getSharedPreferences("wireless_navigation", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(WirelessNavigationState(
        enabled = preferences.getBoolean("enabled", false),
        toggleUsbDebugging = preferences.getBoolean(TOGGLE_USB_DEBUGGING, false), port = preferences.getString("port", "").orEmpty(),
        app = safeDriveApp(preferences.getString("app", null))))
    val state = mutableState.asStateFlow()
    private val identity by lazy { LocalAdbIdentity(File(context.noBackupFilesDir, "local-adb.p12")).load() }
    private var operation: Job? = null
    private val restartRequest = NavigationRestartRequest(scope)
    private var disconnect: Job? = null
    private var connected = false
    @Volatile private var destinationTransition = false
    @Volatile private var destinationOwnsRide = false
    @Volatile private var sessionStopConfirmed = true
    @Volatile private var keepWirelessDebugging = false
    @Volatile private var navigationSessionActive = false
    /** 하차 유예가 끝나 세션·목적지 정리 뒤 USB 디버깅을 꺼야 하는지 */
    @Volatile private var usbDebuggingCloseDue = false
    private val destinationCommands = AtomicInteger()
    /** 실행 중인 가상 화면 세션의 앱. 같은 앱 목적지만 세션 종료와 충돌한다 */
    @Volatile private var sessionApp: NavigatorApp? = null

    init {
        val networks = context.getSystemService(ConnectivityManager::class.java)
        networks.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            object : ConnectivityManager.NetworkCallback() {
                /** 재부팅 뒤 Wi-Fi가 생기면 사용자가 켠 자동 실행만 다시 준비한다. */
                override fun onAvailable(network: Network) {
                    scope.launch {
                        // 하차로 앱이 끈 디버깅이면 집 Wi-Fi에서 켜 달라는 안내를 띄우지 않는다
                        if (state.value.toggleUsbDebugging && !adbEnabled()) return@launch
                        if (state.value.enabled && preferences.getBoolean("configured", false)) prepare()
                    }
                }
            })
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), false,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) { usbDebuggingChanged() }
            })
    }

    /** 지도 실행 없이 권한 프로세스만 준비해 Wi-Fi를 끄기 전에 연결을 확인한다. */
    fun prepare(): Job? {
        if (operation?.isCompleted == false) return null
        return scope.launch {
            mutableState.value = state.value.copy(busy = true)
            report("저장된 인증으로 연결 준비 중", notify = false)
            try {
                withContext(Dispatchers.IO) { ensurePrepared() }
                report("연결 준비 완료")
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                report(if (error.message in setOf(USB_DEBUGGING_REQUIRED, WIRELESS_DEBUGGING_REQUIRED, SERVER_UPDATE_BUSY, LOCAL_CONNECTION_REQUIRED)) error.message!! else "준비 필요 · Wi-Fi와 무선 디버깅 설정을 확인해 주세요")
            } finally { mutableState.value = state.value.copy(busy = false) }
        }.also { job ->
            operation = job
            job.invokeOnCompletion { cause -> scope.launch { if (cause == null && state.value.prepared && state.value.enabled && connected) start(false) } }
        }
    }

    /** 현재 APK와 같은 서버만 재사용하고 구버전은 기존 ADB 준비 경로에서 교체한다. */
    private suspend fun ensurePrepared() = withTimeout(30_000) {
        mutableState.value = state.value.copy(prepared = false)
        try {
            // 두 디버깅 방식이 모두 꺼지면 Android가 부모 데몬과 준비 프로세스를 종료한다.
            awaitUsbDebugging()
            check(adbEnabled()) { USB_DEBUGGING_REQUIRED }
            NavigationBridgeProvider.request()
            val server = runCatching { NavigationChannel().connect().use {
                it.status() to it.serverVersionCode()
            } }.getOrNull()
            if (server != null) DiagLog.add("안심주행 · 준비 프로세스 버전 · 앱=${BuildConfig.VERSION_CODE} · 셸=${server.second ?: "미확인"}")
            if (server?.first in setOf("AVAILABLE", "ACTIVE") && server?.second == BuildConfig.VERSION_CODE) {
                mutableState.value = state.value.copy(prepared = true)
                return@withTimeout
            }
            // 이전 서버에서 주행 중이면 강제 교체로 내비를 종료하지 않고 명시적 종료를 먼저 받는다.
            check(server?.first != "ACTIVE") { SERVER_UPDATE_BUSY }
            if (server != null) DiagLog.add("안심주행 · 이전 준비 프로세스를 현재 앱 버전으로 교체")
            mutableState.value = state.value.copy(prepared = false)
            manager().use { connection ->
                val tcpPort = localTcpPort()
                var port = tcpPort?.takeIf { connectLocalAfterRestart(connection, it) }
                if (port != null) DiagLog.add("안심주행 · 저장된 인증으로 로컬 TCP 재접속 완료")
                if (port == null) {
                    val networks = context.getSystemService(ConnectivityManager::class.java)
                    check(networks.allNetworks.any { networks.getNetworkCapabilities(it)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }) {
                        LOCAL_CONNECTION_REQUIRED
                    }
                    enableWirelessDebugging()
                    DiagLog.add("안심주행 · 무선 연결 포트 탐색")
                    for (attempt in 0 until 3) {
                        val candidate = LocalAdbDiscovery.port(context) ?: validPort(state.value.port) ?: break
                        if (connectLocal(connection, candidate, 8)) { port = candidate; break }
                        if (attempt < 2) delay(500)
                    }
                    check(port != null) { "No local debugging connection" }
                    preferences.edit().putString("port", port.toString()).apply()
                    mutableState.value = state.value.copy(port = port.toString())
                    // 최초 페어링 인증을 유지한 채 TCP로 전환해 Wi-Fi 해제 뒤에도 복구한다.
                    val target = tcpPort ?: java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
                        .use { it.localPort }
                    try {
                        runInterruptible { connection.openStream("tcpip:$target") }.use { stream ->
                            runInterruptible { stream.openInputStream().readBytes() }
                        }
                    } catch (_: java.io.IOException) {
                        // adbd 재시작으로 스트림이 닫혀도 인증 재접속 성공 전에는 준비 완료로 보지 않는다.
                    }
                    connection.disconnect()
                    withTimeout(10_000) {
                        while (!connectLocal(connection, target)) delay(250)
                    }
                    DiagLog.add("안심주행 · LTE 복구용 로컬 TCP 인증 확인 완료")
                }
                preferences.edit().putBoolean("configured", true).apply()
                DiagLog.add("안심주행 · 독립 실행 프로세스 준비")
                val uid = android.os.Process.myUid()
                // 업데이트 전 서버가 잠금 소켓을 쥐고 있으면 새 서버가 뜨지 못하므로 같은 UID 서버만 먼저 정리한다.
                // 대괄호 패턴은 이 정리 명령을 실행하는 셸 자신과는 일치하지 않는다.
                runInterruptible { connection.openStream("shell:pkill -l KILL -f '[N]averControlServer $uid'; sleep 0.5") }
                    .use { stream ->
                        // 원격 셸이 끝나면 라이브러리는 -1 대신 닫힘 예외를 던지므로 정리 완료 신호로 본다.
                        try { runInterruptible { stream.openInputStream().readBytes() } } catch (_: java.io.IOException) { }
                    }
                val command = "trap '' HUP; pm grant --user ${uid / 100000} com.wemade.teslamacro android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1; " +
                    "CLASSPATH=${quote(context.applicationInfo.sourceDir)} setsid app_process / " +
                    "com.wemade.teslamacro.data.nav.NaverControlServer $uid </dev/null >/dev/null 2>&1 & wait"
                runInterruptible { connection.openStream("shell:$command") }.use {
                    withTimeout(15_000) {
                        while (!runCatching { NavigationChannel().connect().use {
                            it.status() == "AVAILABLE" && it.serverVersionCode() == BuildConfig.VERSION_CODE
                        } }.getOrDefault(false)) delay(150)
                    }
                }
            }
            DiagLog.add("안심주행 · 준비 프로세스 확인 완료 · 버전=${BuildConfig.VERSION_CODE}")
            mutableState.value = state.value.copy(prepared = true)
        } finally {
            closeWirelessDebugging()
        }
    }

    /** 디버깅 재시작 직후 adbd가 TCP 포트를 다시 열 때까지 짧게 재시도한다. */
    private suspend fun connectLocalAfterRestart(connection: AbsAdbConnectionManager, port: Int): Boolean {
        repeat(5) { attempt ->
            if (connectLocal(connection, port)) return true
            if (attempt < 4) delay(1_000)
        }
        return false
    }

    private fun adbEnabled() = Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1

    // USB 디버깅 대기 (탑승 중 꺼짐 -> 설정이면 직접 켜고, 아니면 다른 앱이 켜기를 잠시 기다림)
    // Tesor처럼 차량 연결에 맞춰 디버깅을 켜는 앱이 조금 늦게 켜도 준비 실패로 끝내지 않는다
    private suspend fun awaitUsbDebugging() {
        if (adbEnabled() || !connected) return
        if (state.value.toggleUsbDebugging) enableUsbDebugging()
        withTimeoutOrNull(10_000) { while (!adbEnabled()) delay(250) }
    }

    // USB 디버깅 켜기 (탑승 -> 하차 때 끈 디버깅 복구)
    private fun enableUsbDebugging() {
        if (adbEnabled()) return
        if (context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) {
            DiagLog.add("안심주행 · USB 디버깅 켜기 권한 없음")
            return
        }
        runCatching { check(Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 1)) }
            .onSuccess { DiagLog.add("안심주행 · 탑승으로 USB 디버깅 켬") }
            .onFailure { DiagLog.add("안심주행 · USB 디버깅 켜기 실패 ${it.javaClass.simpleName}") }
    }

    // 하차 정리 (유예 종료·세션 종료·목적지 명령 종료 -> USB 디버깅 끄기)
    // 끄면 adbd와 준비 프로세스가 함께 종료되므로 실행 중인 세션이나 명령이 있으면 끝날 때까지 미룬다
    private fun closeUsbDebugging() {
        if (!usbDebuggingCloseDue || connected || navigationSessionActive || destinationCommands.get() > 0) return
        usbDebuggingCloseDue = false
        if (!state.value.toggleUsbDebugging || !adbEnabled()) return
        if (context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) {
            DiagLog.add("안심주행 · USB 디버깅 끄기 권한 없음")
            return
        }
        runCatching { check(Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0)) }
            .onSuccess { DiagLog.add("안심주행 · 하차로 USB 디버깅 끔") }
            .onFailure { DiagLog.add("안심주행 · USB 디버깅 끄기 실패 ${it.javaClass.simpleName}") }
    }

    // USB 디버깅 변경 대응 (다른 앱이 켬 -> 탑승 중이면 안심주행 다시 준비)
    // 꺼지면 준비 프로세스가 종료되므로 준비 상태만 내리고, 켜지면 같은 탑승 세션에서 실행을 이어 간다
    private fun usbDebuggingChanged() {
        if (!adbEnabled()) {
            mutableState.value = state.value.copy(prepared = false)
            return
        }
        if (state.value.enabled && connected && !state.value.running && operation?.isCompleted != false) start(delayed = false)
    }

    /** 공개 시스템 속성의 실제 수신 포트만 사용해 재부팅 뒤 오래된 TLS 포트를 피한다. */
    private suspend fun localTcpPort(): Int? = runInterruptible {
        val process = ProcessBuilder("/system/bin/getprop").start()
        try {
            val properties = process.inputStream.bufferedReader().use { it.readText() }
            localAdbTcpPort(properties)
        } finally { process.destroy() }
    }

    /** 설치별 기존 키로만 인증하고 실패한 소켓은 다음 시도 전에 닫는다. */
    private suspend fun connectLocal(connection: AbsAdbConnectionManager, port: Int, timeoutSeconds: Long = 3): Boolean {
        try {
            connection.setTimeout(timeoutSeconds, TimeUnit.SECONDS)
            if (runInterruptible { connection.connect("127.0.0.1", port) }) return true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            DiagLog.add("안심주행 · 로컬 ADB 연결 실패 ${error.javaClass.simpleName}")
        }
        connection.disconnect()
        return false
    }

    /** 최초 무선 인증·연결에만 디버깅을 켜고 권한이 없으면 설정 안내로 돌린다. */
    @Synchronized
    private fun enableWirelessDebugging() {
        // 사용자가 이미 켠 디버깅(PC 연결·다른 앱)은 앱 소유가 아니다 — 소유 표시 없이 그대로 쓴다
        if (Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1) return
        check(context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED) { WIRELESS_DEBUGGING_REQUIRED }
        check(Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1) &&
            Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1) { WIRELESS_DEBUGGING_REQUIRED }
        preferences.edit().putBoolean(OWNS_WIRELESS_DEBUGGING, true).apply()
    }

    // 연결 설정 시작 (꺼진 디버깅 -> 앱이 켜게 한 것으로 소유 표시)
    // 페어링은 사용자가 설정 화면에서 직접 켜지만 앱의 안내로 켠 것이라 끝나면 앱이 닫는다
    internal fun claimWirelessDebuggingForPairing() {
        if (Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 0) {
            preferences.edit().putBoolean(OWNS_WIRELESS_DEBUGGING, true).apply()
        }
    }

    /** 탑승·재연결 유예·실행 중에는 유지하고 하차 후 명령 정리까지 끝나면 닫는다. */
    @Synchronized
    internal fun closeWirelessDebugging() {
        closeUsbDebugging()
        if (keepWirelessDebugging || navigationSessionActive || destinationCommands.get() > 0) return
        // 앱이 켜지 않은 디버깅은 끄지 않는다 — Shizuku·PC 무선 adb 연결을 매 Wi-Fi 접속마다 끊어 버린다
        if (!preferences.getBoolean(OWNS_WIRELESS_DEBUGGING, false)) return
        if (Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 0) {
            preferences.edit().remove(OWNS_WIRELESS_DEBUGGING).apply()
            return
        }
        if (context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return
        runCatching { check(Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 0)) }
            .onSuccess { preferences.edit().remove(OWNS_WIRELESS_DEBUGGING).apply() }
            .onFailure { DiagLog.add("안심주행 · 무선 디버깅 종료 실패 ${it.javaClass.simpleName}") }
    }

    /** 실제 ADB 준비와 공식 앱 지원을 확인한 뒤 공유하며 전송 이후의 실패는 대체 실행하지 않는다. */
    internal suspend fun tryShareTeslaDestination(text: String, beforeLaunch: suspend () -> Unit): Boolean = withContext(Dispatchers.IO) {
        require(TeslaShareIntent.accepts(text))
        val channel = runCatching { NavigationChannel().connect() }.getOrNull() ?: return@withContext false
        channel.use {
            if (!runCatching { channel.canShareTeslaDestination(text) }.getOrDefault(false)) return@withContext false
            beforeLaunch()
            currentCoroutineContext().ensureActive()
            try {
                val result = runInterruptible { channel.shareTeslaDestination(text) }
                if (result == "NOT_STARTED") throw DestinationLaunchException("실행 조건이 바뀌었어요 · 길안내를 다시 시작해 주세요")
                if (result != "DELIVERED") throw DestinationLaunchException("공유 결과를 확인하지 못했어요 · 테슬라 앱에서 목적지를 확인해 주세요", uncertain = true)
            } catch (error: Exception) {
                if (error is CancellationException || error is DestinationLaunchException) throw error
                throw DestinationLaunchException("공유 결과를 확인하지 못했어요 · 테슬라 앱에서 목적지를 확인해 주세요", error, uncertain = true)
            }
            true
        }
    }

    /** 준비된 권한 서버가 없으면 즉시 기존 방식으로 돌리고 전송 이후에는 중복 실행하지 않는다. */
    internal suspend fun tryLaunchDestination(intent: Intent, beforeLaunch: suspend () -> Unit): Boolean = withContext(Dispatchers.IO) {
        val packageName = intent.`package` ?: return@withContext false
        val uri = intent.data ?: return@withContext false
        if (!NavigatorApp.acceptsDestination(packageName, uri)) return@withContext false
        val channel = runCatching { NavigationChannel().connect() }.getOrNull() ?: return@withContext false
        channel.use {
            // 안심주행 종료가 새 목적지까지 강제 종료하지 않도록 같은 앱 세션은 겹치지 않는다.
            val activeApp = sessionApp ?: state.value.app
            if (packageName in activeApp.packages && runCatching { channel.status() }.getOrNull() == "ACTIVE") {
                throw DestinationLaunchException("${activeApp.label} 안심주행 실험을 종료한 뒤 목적지를 다시 실행해 주세요")
            }
            if (!runCatching { channel.canLaunchDestination(packageName, uri.toString()) }.getOrDefault(false)) return@withContext false
            destinationCommands.incrementAndGet()
            try {
                try { beforeLaunch() } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    throw DestinationLaunchException(error.message ?: "목적지를 다시 확인해 주세요", error)
                }
                currentCoroutineContext().ensureActive()
                try {
                    val result = runInterruptible { channel.launchDestination(packageName, uri.toString()) }
                    if (result == "NOT_STARTED") throw DestinationLaunchException("실행 조건이 바뀌었어요 · 목적지를 다시 확인해 주세요")
                    if (result != "DELIVERED") throw DestinationLaunchException("지도 실행 결과를 확인하지 못했어요 · 지도 상태를 확인해 주세요", uncertain = true)
                } catch (error: Exception) {
                    if (error is CancellationException || error is DestinationLaunchException) throw error
                    throw DestinationLaunchException("지도 실행을 완료하지 못했어요 · 목적지 상태를 확인해 주세요", error, uncertain = true)
                }
                true
            } finally {
                destinationCommands.decrementAndGet()
                closeWirelessDebugging()
            }
        }
    }

    /** ADB 전용 RSA 키는 기존 목적지용 EC 인증 계약과 달라 독립적으로 제공한다. */
    private fun manager(): AbsAdbConnectionManager = object : AbsAdbConnectionManager() {
        /** 설치별 개인키는 통신 구현에만 전달한다. */
        override fun getPrivateKey(): PrivateKey = identity.first
        /** 같은 공개키 인증서를 재사용해 재페어링을 줄인다. */
        override fun getCertificate(): Certificate = identity.second
        /** 시스템 페어링 목록에 앱 이름만 표시한다. */
        override fun getDeviceName(): String = "Smart Tesla"
        /** TLS 제공자가 재사용하는 설치 키는 유지하고 요청별 연결만 닫는다. */
        override fun close() { disconnect() }
    }.apply {
        setApi(Build.VERSION.SDK_INT)
        setHostAddress("127.0.0.1")
        setTimeout(8, TimeUnit.SECONDS)
        setThrowOnUnauthorised(true)
    }

    /** 자동 실행 설정은 명시적으로 켠 기기에만 저장하고 끄면 예약·실행을 함께 취소한다. */
    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("enabled", enabled).apply()
        mutableState.value = mutableState.value.copy(enabled = enabled)
        keepWirelessDebugging = enabled && connected
        if (!enabled) stop() else if (connected) start(delayed = false) else prepare()
    }

    // 하차 시 USB 디버깅 끄기 설정 (권한 확인 -> 저장)
    // 연결 설정 중일 수 있어 켜는 즉시 끄지 않고 다음 하차부터 적용한다
    fun setToggleUsbDebugging(enabled: Boolean) {
        if (enabled && context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) {
            report("연결 준비를 마친 뒤 켜 주세요")
            return
        }
        preferences.edit().putBoolean(TOGGLE_USB_DEBUGGING, enabled).apply()
        mutableState.value = state.value.copy(toggleUsbDebugging = enabled)
    }

    /** 차량 오디오 연결과 현재 버전의 실제 실행을 확인한 때만 탑승 안심운전을 실험에 맡긴다. */
    internal suspend fun ownsBoardingSafeDrive(): Boolean = state.value.enabled && connected && withContext(Dispatchers.IO) {
        val pending = operation
        withTimeoutOrNull(25_000L) {
            while (pending?.isActive == true && !state.value.running) delay(100L)
        }
        if (!connected || !state.value.running) return@withContext false
        NavigationBridgeProvider.request()
        runCatching { NavigationChannel().connect().use {
            it.status() == "ACTIVE" && it.serverVersionCode() == BuildConfig.VERSION_CODE
        } }.getOrDefault(false)
    }

    /** 인증 여부와 무관하게 기존 실험 정리를 끝내며 전환 중 오디오 이벤트의 재시작을 막는다. */
    internal suspend fun prepareDestination() {
        destinationTransition = true
        destinationCommands.incrementAndGet()
        restartRequest.cancel()
        stopNavigationForDestination(operation, navigationSessionActive) { sessionStopConfirmed }
        if (hasPairing) NavigationBridgeProvider.request()
        val status = withContext(Dispatchers.IO) {
            runCatching { NavigationChannel().connect().use { it.status() } }.getOrNull()
        }
        if (status == "ACTIVE" || (!sessionStopConfirmed && status != "AVAILABLE")) {
            throw DestinationLaunchException("기존 안심주행 종료를 확인해 주세요")
        }
        if (status == "AVAILABLE") sessionStopConfirmed = true
    }

    /** 한 번 전달한 목적지는 같은 오디오 연결 세션의 실험 재시작으로 덮어쓰지 않는다. */
    internal fun destinationFinished(dispatched: Boolean) {
        if (dispatched) destinationOwnsRide = connected
        destinationTransition = false
        destinationCommands.decrementAndGet()
        closeWirelessDebugging()
    }

    /** 안심운전 앱은 실행 중 바꾸지 않아 종료 정리가 다른 앱을 강제 종료하지 않게 한다. */
    fun setApp(name: String) {
        val app = safeDriveApp(name)
        if (app == state.value.app) return
        if (state.value.busy || state.value.running) {
            report("실험을 종료한 뒤 내비를 바꿔 주세요")
            return
        }
        preferences.edit().putString("app", app.name).apply()
        mutableState.value = state.value.copy(app = app)
    }

    /** 포트는 숫자만 허용하며 실행 중 연결 대상을 바꾸지 않는다. */
    fun setPort(value: String) {
        if (state.value.busy || state.value.running) return
        val port = value.filter(Char::isDigit).take(5)
        preferences.edit().putString("port", port).apply()
        mutableState.value = state.value.copy(port = port)
    }

    /** 저장된 인증이 있는 경우 설정 화면보다 재연결을 먼저 시도한다. */
    val hasPairing: Boolean get() = preferences.getBoolean("configured", false)

    /** 페어링 코드는 이 요청에서만 사용하고 설정이나 진단 로그에는 저장하지 않는다. */
    fun pair(port: String, code: String): Job? {
        if (operation?.isCompleted == false) return null
        if ((port.isNotBlank() && validPort(port) == null) || !code.matches(Regex("[0-9]{6}"))) {
            report("페어링 포트와 6자리 코드를 확인해 주세요")
            return null
        }
        return scope.launch {
            mutableState.value = state.value.copy(busy = true, prepared = false)
            var paired = false
            try {
                paired = withContext(Dispatchers.IO) {
                    enableWirelessDebugging()
                    val target = validPort(port) ?: LocalAdbDiscovery.port(context, pairing = true)
                        ?: error("No local pairing port")
                    manager().use { LocalAdbPairing.pair(it, target, code) }
                }
                if (paired) {
                    preferences.edit().putBoolean("configured", true).apply()
                    withContext(Dispatchers.IO) { ensurePrepared() }
                }
                report(if (paired) "페어링·준비 완료" else "페어링 실패 · 새 코드를 확인해 주세요")
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                report(if (error.message in setOf(USB_DEBUGGING_REQUIRED, WIRELESS_DEBUGGING_REQUIRED, SERVER_UPDATE_BUSY, LOCAL_CONNECTION_REQUIRED)) error.message!! else if (paired) "페어링 완료 · 연결 준비를 다시 눌러 주세요" else "페어링 실패 · 무선 디버깅과 코드를 확인해 주세요")
            } finally {
                closeWirelessDebugging()
                mutableState.value = state.value.copy(busy = false)
            }
        }.also { job ->
            operation = job
            job.invokeOnCompletion { cause -> scope.launch { if (cause == null && state.value.prepared && state.value.enabled && connected) start(false) } }
        }
    }

    /** 선택된 차량 오디오가 끊겨도 짧은 재연결에는 실행 세션을 유지한다. */
    fun vehicleChanged(value: Boolean) {
        if (connected == value) return
        connected = value
        if (!value && !state.value.enabled) destinationOwnsRide = false
        disconnect?.cancel()
        if (!value) restartRequest.cancel()
        if (value) {
            usbDebuggingCloseDue = false
            if (state.value.toggleUsbDebugging) enableUsbDebugging()
            keepWirelessDebugging = state.value.enabled
            if (state.value.enabled) start(delayed = false)
        } else if (state.value.enabled || state.value.toggleUsbDebugging) {
            disconnect = scope.launch {
                delay(30_000)
                destinationOwnsRide = false
                keepWirelessDebugging = false
                usbDebuggingCloseDue = true
                stop()
            }
        }
    }

    /** 잠금 테스트 예약부터 세션 종료까지 하나의 작업으로 묶어 중복 실행을 막는다. */
    fun start(delayed: Boolean = true) {
        if (destinationTransition || destinationOwnsRide) return
        operation?.takeUnless { it.isCompleted }?.let { previous ->
            restartRequest.afterCancellation(previous) {
                if (delayed || (state.value.enabled && connected)) start(delayed)
            }
            return
        }
        restartRequest.cancel()
        if (Build.VERSION.SDK_INT < 31) {
            report("Android 12 이상에서 사용할 수 있어요")
            return
        }
        val app = state.value.app
        operation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var sessionRequested = false
            val wake = context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartTesla:WirelessNavigation")
            mutableState.value = state.value.copy(busy = true)
            navigationSessionActive = true
            sessionStopConfirmed = false
            try {
                wake.acquire(12 * 60 * 60 * 1000L)
                if (app.packages.none { runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess }) {
                    report("${app.label} 설치 후 다시 시도해 주세요")
                    return@launch
                }
                logLaunchEnvironment("예약", app)
                report(if (delayed) "5초 뒤 실행" else "${app.label} 실행 준비", notify = delayed)
                if (delayed) delay(5_000)
                withContext(Dispatchers.IO) {
                    ensurePrepared()
                    NavigationChannel().connect().use { active ->
                        var needsStop = true
                        try {
                            sessionApp = app
                            logLaunchEnvironment("전달 직전", app)
                            sessionRequested = true
                            active.send(app.safeDriveCommand)
                            needsStop = runSession(active, app)
                        } finally { if (needsStop) finishSession(active, app) }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                // 다른 앱이 하차에 맞춰 디버깅을 끄면 연결이 끊기는 게 정상이라 Wi-Fi 재준비 안내를 띄우지 않는다
                if (!adbEnabled() && error.message != USB_DEBUGGING_REQUIRED) report("USB 디버깅이 꺼져 안심주행을 종료했어요", notify = false)
                else report(if (error.message in setOf(USB_DEBUGGING_REQUIRED, WIRELESS_DEBUGGING_REQUIRED, SERVER_UPDATE_BUSY, LOCAL_CONNECTION_REQUIRED)) error.message!! else "실행 실패 · Wi-Fi에서 준비를 다시 하거나 ${app.label} 상태를 확인해 주세요")
                DiagLog.add("안심주행 · 실행 예외 ${error.javaClass.simpleName}")
            } finally {
                if (wake.isHeld) wake.release()
                if (!sessionRequested) sessionStopConfirmed = true
                sessionApp = null
                navigationSessionActive = false
                closeWirelessDebugging()
                mutableState.value = state.value.copy(busy = false, running = false)
            }
        }
    }

    /** 셸의 준비 신호만 실행 요청 완료로 표시하고 음성 출력 성공으로 간주하지 않는다. */
    private suspend fun runSession(active: NavigationChannel, app: NavigatorApp): Boolean {
        val output = active.output
        val input = active.input
        val buffer = ByteArray(1024)
        val messages = NavigationSessionOutput()
        var ready = false
        val started = System.nanoTime()
        var lastPing = 0L
        while (currentCoroutineContext().isActive) {
            val now = System.nanoTime()
            if (now - lastPing > TimeUnit.SECONDS.toNanos(3)) {
                runInterruptible { output.write("PING\n".toByteArray()); output.flush() }
                lastPing = now
            }
            if (input.available() > 0) {
                val size = input.read(buffer)
                if (size < 0) break
                for (message in messages.feed(buffer, size)) {
                    if (message.startsWith(NaverDisplaySession.DIAGNOSTIC_PREFIX)) {
                        DiagLog.add("안심주행 · ${app.label} · ${message.removePrefix(NaverDisplaySession.DIAGNOSTIC_PREFIX)}")
                    }
                    if (message == "NAVER_BUSY") {
                        report("${app.label} 실행을 먼저 종료해 주세요 · 기존 실행은 유지했어요")
                        return false
                    }
                    if (message.startsWith("NAVER_ERROR ")) error("Virtual display launch rejected")
                    if (!ready && message == "NAVER_READY") {
                        ready = true
                        mutableState.value = state.value.copy(busy = false, running = true)
                        report("실행 요청 완료 · 음성 안내를 확인해 주세요")
                    }
                    if (message == "NAVER_STOP_FAILED") {
                        report("${app.label} 종료를 확인해 주세요")
                        return false
                    }
                    if (message == "NAVER_CLOSED") {
                        sessionStopConfirmed = true
                        report("실험 종료 완료")
                        return false
                    }
                }
            }
            if (!ready && now - started > TimeUnit.SECONDS.toNanos(20)) error("Launch timed out")
            delay(150)
        }
        report("실행 연결 종료 · ${app.label} 상태를 확인해 주세요")
        return true
    }

    /** 조회 실패는 기록만 남기고 실행·인증·종료 흐름에는 영향을 주지 않는다. */
    private fun logLaunchEnvironment(stage: String, app: NavigatorApp) {
        val packageName = app.packages.firstOrNull {
            runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess
        }
        DiagLog.add("안심주행 · ${app.label} $stage — ${navigationLaunchEnvironment(context, packageName)}")
    }

    /** 지도 정리를 확인한 뒤 소유 채널을 닫고 준비된 권한 프로세스는 유지한다. */
    private suspend fun finishSession(active: NavigationChannel, app: NavigatorApp) = withContext(NonCancellable + Dispatchers.IO) {
        val acknowledged = runCatching {
            withTimeout(12_000) {
                runInterruptible { active.output.apply { write("STOP\n".toByteArray()); flush() } }
                val input = active.input
                val bytes = ByteArray(1024)
                var response = ""
                while (true) {
                    if (input.available() > 0) {
                        val count = input.read(bytes)
                        if (count < 0) break
                        response = (response + String(bytes, 0, count)).takeLast(4096)
                        if ("NAVER_STOP_FAILED" in response) return@withTimeout false
                        if ("NAVER_CLOSED" in response) return@withTimeout true
                    }
                    delay(100)
                }
                false
            }
        }.getOrDefault(false)
        sessionStopConfirmed = acknowledged
        // 하차에 맞춰 디버깅이 꺼지면 종료 응답을 못 받는 게 정상이라 매 하차마다 실패 안내를 띄우지 않는다
        report(if (acknowledged) "실험 종료 완료" else "종료 확인 실패 · ${app.label} 상태를 확인해 주세요",
            notify = acknowledged || adbEnabled())
    }

    /** 취소해도 정리 블록에서 지도 종료 응답을 확인한 뒤 통신 연결을 닫는다. */
    fun stop() {
        restartRequest.cancel()
        // 유예 중 수동 종료해도 하차 타이머는 남겨 디버깅 유지가 무기한 이어지지 않게 한다.
        if (connected || !keepWirelessDebugging) disconnect?.cancel()
        if (operation?.isCompleted != false) {
            closeWirelessDebugging()
            return
        }
        operation?.cancel()
        report("종료 요청 전송", notify = false)
    }

    /** 서비스가 다시 시작되면 현재 오디오 상태를 새 탑승 근거로 받을 수 있게 초기화한다. */
    fun serviceStopped() {
        connected = false
        destinationOwnsRide = false
        keepWirelessDebugging = false
        stop()
    }

    /** 진행은 로그에 남기고 결과는 메인 스레드의 일회성 Toast로 알려 설정 레이아웃을 밀지 않는다. */
    private fun report(message: String, notify: Boolean = true) {
        mutableState.value = state.value.copy(message = message)
        DiagLog.add("안심주행 · $message")
        if (notify) scope.launch {
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val LOCAL_CONNECTION_REQUIRED = "로컬 연결 준비가 필요해요 · Wi-Fi에서 연결 준비를 다시 해 주세요"
        private const val SERVER_UPDATE_BUSY = "이전 안심주행 실험을 종료한 뒤 준비를 다시 해 주세요"
        /** 앱이 무선 디버깅을 켰는지. 사용자가 켜 둔 디버깅은 앱이 닫지 않는다 */
        private const val OWNS_WIRELESS_DEBUGGING = "owns_adb_wifi"
        private const val TOGGLE_USB_DEBUGGING = "toggle_usb_debugging"
        private const val USB_DEBUGGING_REQUIRED = "USB 디버깅을 켜고 연결 준비를 다시 눌러 주세요"
        private const val WIRELESS_DEBUGGING_REQUIRED = "Wi-Fi 연결과 무선 디버깅 권한을 확인한 뒤 연결 설정을 눌러 주세요"
        /** 저장값이 없거나 안심운전을 지원하지 않는 앱이면 기존 동작인 네이버로 둔다. */
        internal fun safeDriveApp(name: String?): NavigatorApp =
            NavigatorApp.of(name).takeIf { it.supportsSafeDrive } ?: NavigatorApp.NAVER

        /** 포트 범위와 숫자 형식을 검증해 명령이나 원격 주소 입력을 차단한다. */
        internal fun validPort(value: String): Int? = value.takeIf { it.matches(Regex("[0-9]{1,5}")) }
            ?.toIntOrNull()?.takeIf { it in 1..65535 }
        /** APK 경로와 고정 URI를 셸 단일 인자로 넘긴다. */
        internal fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
