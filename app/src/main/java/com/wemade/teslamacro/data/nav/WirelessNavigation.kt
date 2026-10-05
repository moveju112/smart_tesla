package com.wemade.teslamacro.data.nav

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import com.wemade.teslable.DiagLog
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.provider.Settings
import android.content.pm.PackageManager
import java.io.File
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 실험 설정은 일반 백업과 분리해 다른 기기에서 자동 실행되지 않게 한다. */
data class WirelessNavigationState(
    val enabled: Boolean = false,
    val port: String = "",
    val busy: Boolean = false,
    val running: Boolean = false,
    val prepared: Boolean = false,
    val message: String = "무선 페어링 필요",
)

/** 전송 이후의 실패는 다른 실행 통로·URI로 반복하지 않고 사용자에게 돌린다. */
internal class DestinationLaunchException(message: String, cause: Throwable? = null, val uncertain: Boolean = false) : IllegalStateException(message, cause)

/** 사용자가 잠금 해제 화면을 취소했거나 시간 안에 풀지 않았다. 인계 전이라 요청은 그대로 대기한다. */
internal class DestinationUnlockDeclinedException :
    IllegalStateException("잠금을 해제하면 목적지를 열어요")

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
        enabled = preferences.getBoolean("enabled", false), port = preferences.getString("port", "").orEmpty()))
    val state = mutableState.asStateFlow()
    private val identity by lazy { LocalAdbIdentity(File(context.noBackupFilesDir, "local-adb.p12")).load() }
    private var operation: Job? = null
    private val restartRequest = NavigationRestartRequest(scope)
    private var disconnect: Job? = null
    private var connected = false
    @Volatile private var keepWirelessDebugging = false
    @Volatile private var navigationSessionActive = false
    private val destinationCommands = AtomicInteger()

    init {
        val networks = context.getSystemService(ConnectivityManager::class.java)
        networks.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            object : ConnectivityManager.NetworkCallback() {
                /** 재부팅 뒤 Wi-Fi가 생기면 사용자가 켠 자동 실행만 다시 준비한다. */
                override fun onAvailable(network: Network) {
                    scope.launch { if (state.value.enabled && preferences.getBoolean("configured", false)) prepare() }
                }
            })
    }

    /** 지도 실행 없이 권한 프로세스만 준비해 Wi-Fi를 끄기 전에 연결을 확인한다. */
    fun prepare(): Job? {
        if (operation?.isCompleted == false) return null
        return scope.launch {
            mutableState.value = state.value.copy(busy = true)
            report("저장된 인증으로 연결 준비 중")
            try {
                withContext(Dispatchers.IO) { ensurePrepared() }
                report("연결 준비 완료")
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                report(if (error.message in setOf(USB_DEBUGGING_REQUIRED, WIRELESS_DEBUGGING_REQUIRED)) error.message!! else "준비 필요 · Wi-Fi와 무선 디버깅 설정을 확인해 주세요")
            } finally { mutableState.value = state.value.copy(busy = false) }
        }.also { job ->
            operation = job
            job.invokeOnCompletion { cause -> scope.launch { if (cause == null && state.value.prepared && state.value.enabled && connected) start(false) } }
        }
    }

    /** 기존 서버 재사용도 무선 디버깅을 먼저 켜고, 서버가 없을 때만 ADB로 다시 세운다. */
    private suspend fun ensurePrepared() = withTimeout(30_000) {
        mutableState.value = state.value.copy(prepared = false)
        try {
            // 두 디버깅 방식이 모두 꺼지면 Android가 부모 데몬과 준비 프로세스를 종료한다.
            check(Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1) { USB_DEBUGGING_REQUIRED }
            enableWirelessDebugging()
            NavigationBridgeProvider.request()
            if (runCatching { NavigationChannel().connect().use { it.status() in setOf("AVAILABLE", "ACTIVE") } }.getOrDefault(false)) {
                mutableState.value = state.value.copy(prepared = true)
                return@withTimeout
            }
            mutableState.value = state.value.copy(prepared = false)
            manager().use { connection ->
                DiagLog.add("네이버 안심주행 · 연결 포트 탐색")
                var port: Int? = null
                // 재활성화 직후 남아 있는 이전 포트 광고는 짧게 다시 탐색하되 전체 준비 제한은 유지한다.
                for (attempt in 0 until 3) {
                    val candidate = LocalAdbDiscovery.port(context) ?: validPort(state.value.port) ?: break
                    val connected = try {
                        runInterruptible { connection.connect("127.0.0.1", candidate) }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        false
                    }
                    if (connected) { port = candidate; break }
                    connection.disconnect()
                    if (attempt < 2) {
                        DiagLog.add("네이버 안심주행 · 연결 포트 갱신 대기")
                        delay(500)
                    }
                }
                check(port != null) { "No local debugging connection" }
                DiagLog.add("네이버 안심주행 · 자체 ADB 연결")
                preferences.edit().putString("port", port.toString()).putBoolean("configured", true).apply()
                mutableState.value = state.value.copy(port = port.toString())
                DiagLog.add("네이버 안심주행 · 독립 실행 프로세스 준비")
                val uid = android.os.Process.myUid()
                val command = "trap '' HUP; pm grant --user ${uid / 100000} com.wemade.teslamacro android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1; " +
                    "CLASSPATH=${quote(context.applicationInfo.sourceDir)} setsid app_process / " +
                    "com.wemade.teslamacro.data.nav.NaverControlServer $uid </dev/null >/dev/null 2>&1 & wait"
                runInterruptible { connection.openStream("shell:$command") }.use {
                    withTimeout(15_000) {
                        while (!runCatching { NavigationChannel().connect().use { it.status() == "AVAILABLE" } }.getOrDefault(false)) delay(150)
                    }
                }
            }
            mutableState.value = state.value.copy(prepared = true)
        } finally {
            closeWirelessDebugging()
        }
    }

    /** 명령 직전 꺼진 디버깅을 복구하고 권한이 없으면 설정 안내로 돌린다. */
    @Synchronized
    private fun enableWirelessDebugging() {
        if (Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1) return
        check(context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED) { WIRELESS_DEBUGGING_REQUIRED }
        check(Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1) &&
            Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1) { WIRELESS_DEBUGGING_REQUIRED }
    }

    /** 탑승·재연결 유예·실행 중에는 유지하고 하차 후 명령 정리까지 끝나면 닫는다. */
    @Synchronized
    internal fun closeWirelessDebugging() {
        if (keepWirelessDebugging || navigationSessionActive || destinationCommands.get() > 0) return
        if (Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 0) return
        if (context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return
        runCatching { check(Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 0)) }
            .onFailure { DiagLog.add("네이버 안심주행 · 무선 디버깅 종료 실패 ${it.javaClass.simpleName}") }
    }

    /** 준비된 권한 서버가 없으면 즉시 기존 방식으로 돌리고 전송 이후에는 중복 실행하지 않는다. */
    internal suspend fun tryLaunchDestination(intent: Intent, beforeLaunch: suspend () -> Unit): Boolean = withContext(Dispatchers.IO) {
        val packageName = intent.`package` ?: return@withContext false
        val uri = intent.data ?: return@withContext false
        if (!NavigatorApp.acceptsDestination(packageName, uri)) return@withContext false
        val channel = runCatching { NavigationChannel().connect() }.getOrNull() ?: return@withContext false
        channel.use {
            // 안심주행 종료가 새 목적지까지 강제 종료하지 않도록 같은 네이버 세션은 겹치지 않는다.
            if (packageName in NavigatorApp.NAVER.packages && runCatching { channel.status() }.getOrNull() == "ACTIVE") {
                throw DestinationLaunchException("네이버 안심주행 실험을 종료한 뒤 목적지를 다시 실행해 주세요")
            }
            if (!runCatching { channel.canLaunchDestination(packageName, uri.toString()) }.getOrDefault(false)) return@withContext false
            destinationCommands.incrementAndGet()
            try {
                if (runCatching { enableWirelessDebugging() }.isFailure) return@withContext false
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
                report(if (error.message in setOf(USB_DEBUGGING_REQUIRED, WIRELESS_DEBUGGING_REQUIRED)) error.message!! else if (paired) "페어링 완료 · 연결 준비를 다시 눌러 주세요" else "페어링 실패 · 무선 디버깅과 코드를 확인해 주세요")
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
        disconnect?.cancel()
        if (!value) restartRequest.cancel()
        if (value) {
            keepWirelessDebugging = state.value.enabled
            if (state.value.enabled) start(delayed = false)
        } else if (state.value.enabled) {
            disconnect = scope.launch {
                delay(30_000)
                keepWirelessDebugging = false
                stop()
            }
        }
    }

    /** 잠금 테스트 예약부터 세션 종료까지 하나의 작업으로 묶어 중복 실행을 막는다. */
    fun start(delayed: Boolean = true) {
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
        operation = scope.launch {
            val wake = context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartTesla:WirelessNavigation")
            mutableState.value = state.value.copy(busy = true)
            navigationSessionActive = true
            try {
                wake.acquire(12 * 60 * 60 * 1000L)
                report(if (delayed) "10초 뒤 실행 · 화면을 잠가 주세요" else "네이버지도 실행 준비")
                if (delayed) delay(10_000)
                withContext(Dispatchers.IO) {
                    ensurePrepared()
                    NavigationChannel().connect().use { active ->
                        var needsStop = true
                        try {
                            enableWirelessDebugging()
                            active.send("START")
                            needsStop = runSession(active)
                        } finally { if (needsStop) finishSession(active) }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                report(if (error.message in setOf(USB_DEBUGGING_REQUIRED, WIRELESS_DEBUGGING_REQUIRED)) error.message!! else "실행 실패 · Wi-Fi에서 준비를 다시 하거나 네이버지도 상태를 확인해 주세요")
                DiagLog.add("네이버 안심주행 · 실행 예외 ${error.javaClass.simpleName}")
            } finally {
                if (wake.isHeld) wake.release()
                navigationSessionActive = false
                closeWirelessDebugging()
                mutableState.value = state.value.copy(busy = false, running = false)
            }
        }
    }

    /** 셸의 준비 신호만 실행 요청 완료로 표시하고 음성 출력 성공으로 간주하지 않는다. */
    private suspend fun runSession(active: NavigationChannel): Boolean {
        val output = active.output
        val input = active.input
        val buffer = ByteArray(1024)
        var text = ""
        var ready = false
        val started = System.nanoTime()
        var lastPing = 0L
        while (currentCoroutineContext().isActive) {
            val now = System.nanoTime()
            if (now - lastPing > TimeUnit.SECONDS.toNanos(3)) {
                enableWirelessDebugging()
                runInterruptible { output.write("PING\n".toByteArray()); output.flush() }
                lastPing = now
            }
            if (input.available() > 0) {
                val size = input.read(buffer)
                if (size < 0) break
                text = (text + String(buffer, 0, size)).takeLast(4096)
                if (text.contains("NAVER_BUSY")) {
                    report("네이버지도를 먼저 종료해 주세요 · 기존 실행은 유지했어요")
                    return false
                }
                if (text.contains("NAVER_ERROR")) error("Virtual display launch rejected")
                if (!ready && text.contains("NAVER_READY")) {
                    ready = true
                    mutableState.value = state.value.copy(busy = false, running = true)
                    report("실행 요청 완료 · 음성 안내를 확인해 주세요")
                }
                if (text.contains("NAVER_STOP_FAILED")) {
                    report("네이버지도 종료를 확인해 주세요")
                    return false
                }
                if (text.contains("NAVER_CLOSED")) {
                    report("실험 종료 완료")
                    return false
                }
            }
            if (!ready && now - started > TimeUnit.SECONDS.toNanos(20)) error("Launch timed out")
            delay(150)
        }
        report("실행 연결 종료 · 네이버지도 상태를 확인해 주세요")
        return true
    }

    /** 지도 정리를 확인한 뒤 소유 채널을 닫고 준비된 권한 프로세스는 유지한다. */
    private suspend fun finishSession(active: NavigationChannel) = withContext(NonCancellable + Dispatchers.IO) {
        val acknowledged = runCatching {
            withTimeout(12_000) {
                enableWirelessDebugging()
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
        report(if (acknowledged) "실험 종료 완료" else "종료 확인 실패 · 네이버지도 상태를 확인해 주세요")
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
        report("종료 요청 전송")
    }

    /** 서비스가 다시 시작되면 현재 오디오 상태를 새 탑승 근거로 받을 수 있게 초기화한다. */
    fun serviceStopped() {
        connected = false
        keepWirelessDebugging = false
        stop()
    }

    /** 상태 로그에는 포트·코드·키를 제외하고 사용자에게 필요한 진행 단계만 남긴다. */
    private fun report(message: String) {
        mutableState.value = state.value.copy(message = message)
        DiagLog.add("네이버 안심주행 · $message")
    }

    companion object {
        private const val USB_DEBUGGING_REQUIRED = "USB 디버깅을 켜고 연결 준비를 다시 눌러 주세요"
        private const val WIRELESS_DEBUGGING_REQUIRED = "Wi-Fi 연결과 무선 디버깅 권한을 확인한 뒤 연결 설정을 눌러 주세요"
        /** 포트 범위와 숫자 형식을 검증해 명령이나 원격 주소 입력을 차단한다. */
        internal fun validPort(value: String): Int? = value.takeIf { it.matches(Regex("[0-9]{1,5}")) }
            ?.toIntOrNull()?.takeIf { it in 1..65535 }
        /** APK 경로와 고정 URI를 셸 단일 인자로 넘긴다. */
        internal fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
