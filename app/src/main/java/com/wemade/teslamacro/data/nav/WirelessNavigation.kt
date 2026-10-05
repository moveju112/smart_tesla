package com.wemade.teslamacro.data.nav

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.wemade.teslable.DiagLog
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import java.io.File
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 실험 설정은 일반 백업과 분리해 다른 기기에서 자동 실행되지 않게 한다. */
data class WirelessNavigationState(
    val enabled: Boolean = false,
    val port: String = "",
    val busy: Boolean = false,
    val running: Boolean = false,
    val message: String = "무선 페어링 필요",
)

/** 네이버지도만 같은 휴대폰의 ADB로 실행하며 연결 종료는 자신이 시작한 세션에만 보낸다. */
class WirelessNavigation(private val context: Context) {
    private val preferences = context.getSharedPreferences("wireless_navigation", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(WirelessNavigationState(
        enabled = preferences.getBoolean("enabled", false), port = preferences.getString("port", "").orEmpty()))
    val state = mutableState.asStateFlow()
    private val identity by lazy { LocalAdbIdentity(File(context.noBackupFilesDir, "local-adb.p12")).load() }
    private var operation: Job? = null
    private var disconnect: Job? = null
    private var connected = false

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
        if (!enabled) stop() else if (connected) start(delayed = false)
    }

    /** 포트는 숫자만 허용하며 실행 중 연결 대상을 바꾸지 않는다. */
    fun setPort(value: String) {
        if (state.value.busy || state.value.running) return
        val port = value.filter(Char::isDigit).take(5)
        preferences.edit().putString("port", port).apply()
        mutableState.value = state.value.copy(port = port)
    }

    /** 페어링 코드는 이 요청에서만 사용하고 설정이나 진단 로그에는 저장하지 않는다. */
    fun pair(port: String, code: String) {
        if (operation?.isCompleted == false) return
        if (validPort(port) == null || !code.matches(Regex("[0-9]{6}"))) {
            report("페어링 포트와 6자리 코드를 확인해 주세요")
            return
        }
        operation = scope.launch {
            mutableState.value = state.value.copy(busy = true)
            try {
                val paired = withContext(Dispatchers.IO) {
                    manager().use { LocalAdbPairing.pair(it, port.toInt(), code) }
                }
                report(if (paired) "페어링 완료 · 연결 포트를 입력해 주세요" else "페어링 실패 · 새 코드를 확인해 주세요")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                report("페어링 실패 · 무선 디버깅과 코드를 확인해 주세요")
            } finally { mutableState.value = state.value.copy(busy = false) }
        }
    }

    /** 선택된 차량 오디오가 끊겨도 짧은 재연결에는 실행 세션을 유지한다. */
    fun vehicleChanged(value: Boolean) {
        if (connected == value) return
        connected = value
        disconnect?.cancel()
        if (value) {
            if (state.value.enabled) start(delayed = false)
        } else if (state.value.enabled) {
            disconnect = scope.launch { delay(30_000); stop() }
        }
    }

    /** 잠금 테스트 예약부터 세션 종료까지 하나의 작업으로 묶어 중복 실행을 막는다. */
    fun start(delayed: Boolean = true) {
        if (operation?.isCompleted == false) return
        val port = validPort(state.value.port)
        if (Build.VERSION.SDK_INT < 31 || port == null) {
            report(if (port == null) "무선 디버깅의 연결 포트를 입력해 주세요" else "Android 12 이상에서 사용할 수 있어요")
            return
        }
        operation = scope.launch {
            val wake = context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartTesla:WirelessNavigation")
            mutableState.value = state.value.copy(busy = true)
            try {
                wake.acquire(12 * 60 * 60 * 1000L)
                report(if (delayed) "10초 뒤 실행 · 화면을 잠가 주세요" else "네이버지도 실행 준비")
                if (delayed) delay(10_000)
                withContext(Dispatchers.IO) {
                    manager().use { connection ->
                        check(runInterruptible { connection.connect("127.0.0.1", port) })
                        val uri = requireNotNull(NavigatorApp.NAVER.safeDriveUri(context.packageName)).toString()
                        val command = "trap '' HUP; CLASSPATH=${quote(context.applicationInfo.sourceDir)} exec app_process / " +
                            "com.wemade.teslamacro.data.nav.NaverDisplaySession ${android.os.Process.myUid() / 100000} ${quote(uri)}"
                        runInterruptible { connection.openStream("shell:$command") }.use { active ->
                            var needsStop = true
                            try { needsStop = runSession(active) }
                            finally { if (needsStop) finishSession(active) }
                        }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                report("실행 실패 · 무선 디버깅 연결 포트와 네이버지도 상태를 확인해 주세요")
                DiagLog.add("네이버 안심주행 · 실행 예외 ${error.javaClass.simpleName}")
            } finally {
                if (wake.isHeld) wake.release()
                mutableState.value = state.value.copy(busy = false, running = false)
            }
        }
    }

    /** 셸의 준비 신호만 실행 요청 완료로 표시하고 음성 출력 성공으로 간주하지 않는다. */
    private suspend fun runSession(active: AdbStream): Boolean {
        val output = active.openOutputStream()
        val input = active.openInputStream()
        val buffer = ByteArray(1024)
        var text = ""
        var ready = false
        val started = System.nanoTime()
        var lastPing = 0L
        while (currentCoroutineContext().isActive && (!active.isClosed || input.available() > 0)) {
            val now = System.nanoTime()
            if (now - lastPing > TimeUnit.SECONDS.toNanos(3)) {
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

    /** 채널을 먼저 닫으면 셸이 먼저 죽을 수 있어 종료 응답까지 제한된 시간 동안 기다린다. */
    private suspend fun finishSession(active: AdbStream) = withContext(NonCancellable + Dispatchers.IO) {
        if (active.isClosed) return@withContext
        val acknowledged = runCatching {
            withTimeout(12_000) {
                runInterruptible { active.openOutputStream().apply { write("STOP\n".toByteArray()); flush() } }
                val input = active.openInputStream()
                val bytes = ByteArray(1024)
                var response = ""
                while (!active.isClosed || input.available() > 0) {
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
        disconnect?.cancel()
        if (operation?.isCompleted != false) return
        operation?.cancel()
        report("종료 요청 전송")
    }

    /** 서비스가 다시 시작되면 현재 오디오 상태를 새 탑승 근거로 받을 수 있게 초기화한다. */
    fun serviceStopped() {
        connected = false
        stop()
    }

    /** 상태 로그에는 포트·코드·키를 제외하고 사용자에게 필요한 진행 단계만 남긴다. */
    private fun report(message: String) {
        mutableState.value = state.value.copy(message = message)
        DiagLog.add("네이버 안심주행 · $message")
    }

    companion object {
        /** 포트 범위와 숫자 형식을 검증해 명령이나 원격 주소 입력을 차단한다. */
        internal fun validPort(value: String): Int? = value.takeIf { it.matches(Regex("[0-9]{1,5}")) }
            ?.toIntOrNull()?.takeIf { it in 1..65535 }
        /** APK 경로와 고정 URI를 셸 단일 인자로 넘긴다. */
        internal fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
