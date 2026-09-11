package com.wemade.teslable

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
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
 * - **파일** — 앱이나 서비스가 재시작해도 최근 12시간이 살아남는 정본. 정작 알고 싶은 건
 *   "죽기 직전에 뭘 했나"인데, 메모리에만 두면 그 부분이 제일 먼저 사라진다
 */
object DiagLog {

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    /** 지금 쓰는 파일과 직전 파일. 붙기 전에는 메모리만 쓴다 */
    private var current: File? = null
    private var previous: File? = null
    private var fileMaxLines = MAX_LINES
    private var fileMaxAgeMillis = DEFAULT_MAX_AGE_MILLIS
    private var fileLineCount = 0
    private var oldestFileTimestampMillis: Long? = null

    /** 파일 쓰기는 여러 스레드(BLE·SDK·UI)에서 들어온다 */
    private val fileLock = Any()

    /**
     * 로그를 남길 파일을 물린다. 앱이 뜰 때 한 번 부른다.
     *
     * 한 줄마다 열고 닫는다 — 버퍼에 들고 있으면 정작 앱이 죽는 순간의
     * 마지막 몇 줄이 디스크에 닿지 못한다. 초당 몇 줄 수준이라 값이 싸다.
     */
    fun attachFile(
        logFile: File,
        previousFile: File,
        maxLines: Int = MAX_LINES,
        maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        synchronized(fileLock) {
            runCatching { logFile.parentFile?.mkdirs() }
            current = logFile
            previous = previousFile
            fileMaxLines = maxLines
            fileMaxAgeMillis = maxAgeMillis
            compactFilesLocked(nowMillis)
        }
        // 재시작 경계를 파일에서 눈으로 찾을 수 있게 한 줄 긋는다
        addAt("──────── 앱 시작 ────────", nowMillis)
    }

    fun add(message: String) {
        addAt(message, System.currentTimeMillis())
    }

    /** 스냅샷·파일 테스트가 실행 시각과 무관하게 같은 로그를 만들도록 시간을 받는다. */
    fun addAt(message: String, timestampMillis: Long) {
        val time = SimpleDateFormat(TIME_FORMAT, Locale.US).format(Date(timestampMillis))
        val line = "$time $message"
        // 줄 수와 시간을 함께 제한한다. 낮은 빈도로 오래 켜 둔 기기도 묵은 로그를 품지 않는다.
        _lines.update { retainedLines(it + line, timestampMillis, MAX_LINES, DEFAULT_MAX_AGE_MILLIS) }
        appendToFile(line, timestampMillis)
        // 개발 중에는 adb로도 본다. 실기기에서는 이 통로를 쓸 수 없다
        android.util.Log.i(TAG, message)
    }

    private fun appendToFile(line: String, timestampMillis: Long) {
        val file = current ?: return
        synchronized(fileLock) {
            runCatching {
                file.appendText(line + "\n")
                fileLineCount += 1
                if (oldestFileTimestampMillis == null) oldestFileTimestampMillis = timestampMillis
                val oldest = oldestFileTimestampMillis ?: timestampMillis
                if (fileLineCount > fileMaxLines || timestampMillis - oldest >= fileMaxAgeMillis) {
                    compactFilesLocked(timestampMillis)
                }
            }
        }
    }

    /** 두 세대의 기존 로그를 합쳐 시간·줄 상한을 적용하고 현재 파일 하나로 정리한다. */
    private fun compactFilesLocked(nowMillis: Long) {
        val currentFile = current ?: return
        val files = listOfNotNull(previous, current)
        val original = files.flatMap { file ->
            if (!file.exists()) emptyList() else runCatching { file.readLines() }.getOrDefault(emptyList())
        }
        val retained = retainedLines(original, nowMillis, fileMaxLines, fileMaxAgeMillis)
        val persisted = runCatching {
            if (previous?.exists() == true || retained != original) {
                if (retained.isEmpty()) currentFile.delete()
                else currentFile.writeText(retained.joinToString("\n", postfix = "\n"))
                previous?.delete()
            }
            retained
        }.getOrDefault(original)
        fileLineCount = persisted.size
        oldestFileTimestampMillis = persisted.firstOrNull()?.let { timestampOf(it, nowMillis) }
    }

    /** 화면이나 공유를 열 때도 12시간이 지난 메모리·파일 기록을 즉시 정리한다. */
    fun pruneExpired(nowMillis: Long = System.currentTimeMillis()) {
        _lines.update { retainedLines(it, nowMillis, MAX_LINES, DEFAULT_MAX_AGE_MILLIS) }
        synchronized(fileLock) { compactFilesLocked(nowMillis) }
    }

    /** 화면 버퍼만. 최근 [MAX_LINES]줄이다 */
    fun dump(): String {
        pruneExpired()
        return _lines.value.joinToString("\n")
    }

    /**
     * 파일에 남은 것까지 전부. 공유는 이걸 보낸다.
     *
     * 화면 버퍼만 보내면 재시작 전 기록이 빠지는데, 원인은 대개 그 앞에 있다.
     */
    fun dumpAll(): String {
        pruneExpired()
        return synchronized(fileLock) {
            val file = current ?: return@synchronized _lines.value.joinToString("\n")
            file.takeIf { it.exists() }?.let { runCatching { it.readText().trimEnd() }.getOrNull() }
                ?: _lines.value.joinToString("\n")
        }
    }

    /** 파일에 실제로 남아 있는 줄 수를 돌려준다. */
    fun storedLineCount(): Int = synchronized(fileLock) { fileLineCount }

    fun clear() {
        _lines.value = emptyList()
        synchronized(fileLock) {
            runCatching { current?.delete() }
            runCatching { previous?.delete() }
            fileLineCount = 0
            oldestFileTimestampMillis = null
        }
    }

    /** 화면에 들고 있는 줄 수 */
    const val MAX_LINES = 100

    /** 로그 한 줄의 최대 보관 시간 */
    const val MAX_AGE_HOURS = 12L

    private const val DEFAULT_MAX_AGE_MILLIS = MAX_AGE_HOURS * 60L * 60L * 1000L

    /** 연도까지 있어야 새해를 지나도 12시간 보관 시점을 정확히 계산할 수 있다. */
    private const val TIME_FORMAT = "yyyy-MM-dd HH:mm:ss.SSS"
    private const val LEGACY_TIME_FORMAT = "MM-dd HH:mm:ss.SSS"
    private const val DAY_MILLIS = 24L * 60L * 60L * 1000L

    /** logcat 필터용 태그. 개발 PC에 물렸을 때만 쓸 수 있다 */
    private const val TAG = "SmartTesla"

    /** 저장된 줄에서 신·구 날짜 형식을 읽고 기준 시각과 가장 가까운 연도를 고른다. */
    private fun timestampOf(line: String, referenceMillis: Long): Long? {
        parseTime(line.take(TIME_FORMAT.length), TIME_FORMAT)?.let { return it }
        val legacy = line.take(LEGACY_TIME_FORMAT.length)
        val calendar = Calendar.getInstance().apply { timeInMillis = referenceMillis }
        var year = calendar.get(Calendar.YEAR)
        var parsed = parseTime("$year-$legacy", TIME_FORMAT) ?: return null
        if (parsed > referenceMillis + DAY_MILLIS) {
            year -= 1
            parsed = parseTime("$year-$legacy", TIME_FORMAT) ?: return null
        }
        return parsed
    }

    /** 로그 날짜 접두사를 엄격하게 epoch millis로 바꾼다. */
    private fun parseTime(text: String, pattern: String): Long? = runCatching {
        SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(text)?.time
    }.getOrNull()

    /** 파싱 가능한 최근 12시간 로그 가운데 마지막 줄 상한만 남긴다. */
    private fun retainedLines(
        lines: List<String>,
        nowMillis: Long,
        maxLines: Int,
        maxAgeMillis: Long,
    ): List<String> {
        val cutoff = nowMillis - maxAgeMillis
        return lines.filter { line ->
            timestampOf(line, nowMillis)?.let { it > cutoff } == true
        }.takeLast(maxLines)
    }
}
