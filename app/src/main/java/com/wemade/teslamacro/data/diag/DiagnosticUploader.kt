package com.wemade.teslamacro.data.diag

import android.content.Context
import com.wemade.teslable.DiagLog
import com.wemade.teslamacro.BuildConfig
import com.wemade.teslamacro.data.safety.DeviceApiClient
import com.wemade.teslamacro.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** 정식 오픈 전까지 진단 로그를 기기 인증 서버로 보내 사용자가 공유하지 않아도 원인을 볼 수 있게 한다. */
internal class DiagnosticUploader(
    context: Context,
    private val settingsStore: SettingsStore,
    private val api: DeviceApiClient,
    private val scope: CoroutineScope,
) {
    // 보낸 위치는 사용자 설정이 아닌 내부 상태라 설정 백업·덤프와 분리한다.
    private val preferences = context.getSharedPreferences("diagnostic_upload", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private var loop: Job? = null

    // 주기 전송 시작 (앱 준비 -> 즉시 1회 -> 5분마다)
    /** 앱 프로세스가 사는 동안 5분마다 새 줄만 보낸다. */
    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                upload()
                delay(INTERVAL_MILLIS)
            }
        }
    }

    // 화면 진입 전송 (앱 표시 -> 새 줄 즉시 전송)
    /** 사용자가 방금 재현한 문제를 다음 주기까지 기다리지 않고 보낸다. */
    fun trigger() { scope.launch { upload() } }

    // 새 줄 전송 (보낸 위치 이후 -> 가림 -> 묶음 전송 -> 성공 묶음까지 위치 저장)
    /** 실패한 묶음부터 다음 주기에 다시 보내며 중간 성공분은 다시 보내지 않는다. */
    private suspend fun upload() = mutex.withLock {
        if (!api.available || !settingsStore.settings.first().diagnosticUploadEnabled) return@withLock
        val pending = DiagLog.linesAfter(preferences.getString(KEY_CURSOR, null)).filter { it.isNotBlank() }
        for (batch in diagnosticBatches(pending)) {
            val body = buildJsonObject {
                put("appVersion", BuildConfig.VERSION_NAME)
                putJsonArray("lines") { batch.forEach { add(JsonPrimitive(maskDiagnosticLine(it))) } }
            }.toString().toByteArray()
            if (api.authenticatedPost("/v1/diagnostics", body).code != 200) return@withLock
            preferences.edit().putString(KEY_CURSOR, batch.last()).apply()
        }
    }

    private companion object {
        const val INTERVAL_MILLIS = 5 * 60_000L
        const val KEY_CURSOR = "last_line"
    }
}

/** 서버 본문 상한(8KB) 아래로 줄을 묶고 한 줄은 서버 허용 길이로 자른다. */
internal fun diagnosticBatches(lines: List<String>, maxBytes: Int = 6_000, maxLines: Int = 200): List<List<String>> {
    val batches = mutableListOf<List<String>>()
    var current = mutableListOf<String>()
    var size = 0
    for (line in lines) {
        val text = line.take(1_000)
        val bytes = JsonPrimitive(maskDiagnosticLine(text)).toString().toByteArray().size + 1
        if (current.isNotEmpty() && (size + bytes > maxBytes || current.size >= maxLines)) {
            batches += current
            current = mutableListOf()
            size = 0
        }
        current += text
        size += bytes
    }
    if (current.isNotEmpty()) batches += current
    return batches
}

/** 블루투스 주소와 VIN에서 파생된 차량 검색 이름은 서버로 보내지 않는다. */
internal fun maskDiagnosticLine(line: String): String = line
    .replace(Regex("(?i)\\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\\b"), "**:**:**:**:**:**")
    .replace(Regex("\\bS[0-9a-f]{16}[A-Z]?\\b"), "S****")
