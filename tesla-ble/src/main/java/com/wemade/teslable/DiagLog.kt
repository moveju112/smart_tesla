package com.wemade.teslable

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 사용자가 그대로 전달할 수 있는 진단 로그.
 *
 * 실차 문제는 개발자가 현장에 없을 때 터진다. 그리고 이 앱이 사는 곳은
 * **개발자 PC에 물려 있지 않은 차내 태블릿**이라 `adb logcat`을 쓸 수 없다 —
 * 기기 혼자서 로그를 모으고, 앱의 공유 시트로 내보낼 수 있어야 한다.
 *
 * 그래서 두 곳에 남긴다:
 * - 화면 버퍼(최근 [MAX_LINES]줄) — 지금 무슨 일이 일어나는지 보여주는 용도
 * - **파일** — 앱이나 서비스가 재시작해도 살아남는 정본. 정작 알고 싶은 건
 *   "죽기 직전에 뭘 했나"인데, 메모리에만 두면 그 부분이 제일 먼저 사라진다
 */
object DiagLog {

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    /** 지금 쓰는 파일과 직전 파일. 붙기 전에는 메모리만 쓴다 */
    private var current: File? = null
    private var previous: File? = null
    private var maxBytes = DEFAULT_MAX_BYTES

    /** 파일 쓰기는 여러 스레드(BLE·SDK·UI)에서 들어온다 */
    private val fileLock = Any()

    /**
     * 로그를 남길 파일을 물린다. 앱이 뜰 때 한 번 부른다.
     *
     * 한 줄마다 열고 닫는다 — 버퍼에 들고 있으면 정작 앱이 죽는 순간의
     * 마지막 몇 줄이 디스크에 닿지 못한다. 초당 몇 줄 수준이라 값이 싸다.
     */
    fun attachFile(logFile: File, previousFile: File, maxBytes: Long = DEFAULT_MAX_BYTES) {
        synchronized(fileLock) {
            runCatching { logFile.parentFile?.mkdirs() }
            current = logFile
            previous = previousFile
            this.maxBytes = maxBytes
        }
        // 재시작 경계를 파일에서 눈으로 찾을 수 있게 한 줄 긋는다
        add("──────── 앱 시작 ────────")
    }

    fun add(message: String) {
        val time = SimpleDateFormat(TIME_FORMAT, Locale.US).format(Date())
        val line = "$time $message"
        // 최근 것만 화면에 남긴다. 오래 켜둬도 메모리를 먹지 않게
        _lines.update { (it + line).takeLast(MAX_LINES) }
        appendToFile(line)
        // 개발 중에는 adb로도 본다. 실기기에서는 이 통로를 쓸 수 없다
        android.util.Log.i(TAG, message)
    }

    private fun appendToFile(line: String) {
        val file = current ?: return
        synchronized(fileLock) {
            runCatching {
                // 다 차면 직전 파일로 밀고 새로 시작한다. 두 세대면
                // "이번 주행 + 지난 주행"이 남아 원인을 짚기에 충분하다
                if (file.length() >= maxBytes) {
                    previous?.let { old ->
                        old.delete()
                        file.renameTo(old)
                    } ?: file.delete()
                }
                file.appendText(line + "\n")
            }
        }
    }

    /** 화면 버퍼만. 최근 [MAX_LINES]줄이다 */
    fun dump(): String = _lines.value.joinToString("\n")

    /**
     * 파일에 남은 것까지 전부. 공유는 이걸 보낸다.
     *
     * 화면 버퍼만 보내면 재시작 전 기록이 빠지는데, 원인은 대개 그 앞에 있다.
     */
    fun dumpAll(): String = synchronized(fileLock) {
        val file = current ?: return@synchronized dump()
        val parts = listOfNotNull(
            previous?.takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() },
            file.takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() },
        )
        if (parts.isEmpty()) dump() else parts.joinToString("").trimEnd()
    }

    /** 파일에 쌓인 바이트. 사용자에게 "얼마나 모였는지"를 보여주는 용도 */
    fun storedBytes(): Long = synchronized(fileLock) {
        (current?.length() ?: 0L) + (previous?.length() ?: 0L)
    }

    fun clear() {
        _lines.value = emptyList()
        synchronized(fileLock) {
            runCatching { current?.delete() }
            runCatching { previous?.delete() }
        }
    }

    /** 화면에 들고 있는 줄 수 */
    private const val MAX_LINES = 300

    /**
     * 파일 한 세대의 상한. 512KB면 한 줄 80바이트 기준 6천여 줄이라
     * 긴 주행 한 번을 통째로 담고, 두 세대라도 1MB를 안 넘는다.
     */
    private const val DEFAULT_MAX_BYTES = 512L * 1024L

    /** 날짜까지 남긴다 — 며칠 뒤에 받아 보면 시:분만으론 어느 날인지 모른다 */
    private const val TIME_FORMAT = "MM-dd HH:mm:ss.SSS"

    /** logcat 필터용 태그. 개발 PC에 물렸을 때만 쓸 수 있다 */
    private const val TAG = "SmartTesla"
}
