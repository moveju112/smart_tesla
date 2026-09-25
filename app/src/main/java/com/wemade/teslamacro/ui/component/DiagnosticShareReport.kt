package com.wemade.teslamacro.ui.component

private const val MAX_EVENT_CHARS = 600
private const val MAX_SAMPLE_CHARS = 120
private const val MAX_SUMMARY_EVENT_CHARS = 160
private const val MAX_SUMMARY_SETTINGS_CHARS = 800
internal const val MAX_DIAGNOSTIC_SUMMARY_CHARS = 6_000

internal enum class DiagnosticShareScope(val label: String, val section: String) {
    SUMMARY("요약", ""),
    CONNECTION("차량 연결", "차량 연결"),
    SAFETY("안전 안내·도로 매칭", "안전 안내·도로 매칭"),
    COMMAND("명령·매크로", "명령·매크로"),
    OTHER("기타", "기타"),
}

/** AI에는 작은 색인을 먼저 보내고, 필요한 주제의 사건만 따로 내보내 원문 전체 재전송을 피한다. */
internal fun diagnosticShareReport(
    settings: String,
    rawLog: String,
    scope: DiagnosticShareScope = DiagnosticShareScope.SUMMARY,
): String {
    val lines = rawLog.lineSequence().filter { it.isNotBlank() }.toList()
    val repeated = linkedMapOf<String, MutableList<String>>()
    val events = mutableListOf<String>()
    // 후보가 있는 '경보 속도 미달'은 음성 안내가 가능하므로 반복 상태로 접지 않는다.
    val routineStatuses = setOf("GPS 속도 5km/h 미만", "GPS 정확도 부족", "GPS 측정 오래됨",
        "GPS 좌표 확인 불가", "GPS 속도 확인 불가", "GPS 방향 없음",
        "근접 후보는 있지만 경보 거리·방향 미충족", "전방 1km 내 후보 없음")
    lines.forEach { line ->
        val status = line.substringAfter("안전 안내 · ", "").substringBefore(" (")
        if (status in routineStatuses) repeated.getOrPut(status) { mutableListOf() }.add(line)
        else events.add(line)
    }
    val grouped = events.groupBy(::diagnosticSection)
    val repeatedText = buildString {
        appendLine("## 반복 GPS 상태 (${lines.size - events.size}건 · ${repeated.size}유형)")
        repeated.forEach { (status, samples) ->
            appendLine("- $status: ${samples.size}회 · ${diagnosticTime(samples.first())}~${diagnosticTime(samples.last())}")
            appendLine("  - 마지막: ${samples.last().take(MAX_SAMPLE_CHARS)}")
        }
        if (repeated.isEmpty()) appendLine("- 없음")
    }
    val settingsText = settings.trim().take(if (scope == DiagnosticShareScope.SUMMARY) {
        MAX_SUMMARY_SETTINGS_CHARS
    } else 2_000)
    val header = buildString {
        appendLine("# Smart Tesla 진단 ${if (scope == DiagnosticShareScope.SUMMARY) "요약" else scope.label}")
        appendLine("- 범위: ${lines.firstOrNull()?.let(::diagnosticTime) ?: "없음"} ~ ${lines.lastOrNull()?.let(::diagnosticTime) ?: "없음"}")
        appendLine("- 전체 ${lines.size}건 · 사건 ${events.size}건 · 반복 상태 ${lines.size - events.size}건")
        appendLine("- 상세 원문은 앱의 진단 로그 '복사'에서 확인")
        if (settingsText.isNotBlank()) {
            appendLine("\n## 설정")
            appendLine(settingsText)
        }
    }
    if (scope == DiagnosticShareScope.SUMMARY) {
        // 실패가 오래된 사건이어도 각 주제의 최근 사례와 함께 색인에 한 번은 보인다.
        return buildString {
            append(header)
            appendLine("\n## 사건 색인 (필요한 주제는 앱의 '상세 공유'로 요청)")
            DiagnosticShareScope.entries.filter { it != DiagnosticShareScope.SUMMARY }.forEach { category ->
                val categoryEvents = grouped[category.section].orEmpty()
                appendLine("- ${category.label}: ${categoryEvents.size}건")
                val highlight = categoryEvents.lastOrNull {
                    "실패" in it || "오류" in it || "Timed out" in it || "timeout" in it
                } ?: categoryEvents.lastOrNull { "요청 수락" in it }
                (listOfNotNull(highlight) + categoryEvents.takeLast(2)).distinct().forEach { event ->
                    appendLine("  - ${event.take(MAX_SUMMARY_EVENT_CHARS)}")
                }
            }
            appendLine()
            append(repeatedText)
        }
    }
    // 선택한 주제만 최신순으로 고르되 시간순으로 내보내, 길이 상한에 밀려 제목이 잘리지 않는다.
    val eventBudget = (FALLBACK_TEXT_CHARS - header.length - repeatedText.length - 400).coerceAtLeast(0)
    val selected = mutableListOf<String>()
    var used = 0
    for (event in grouped[scope.section].orEmpty().asReversed()) {
        val line = event.take(MAX_EVENT_CHARS)
        if (used + line.length + 3 > eventBudget) break
        selected.add(line)
        used += line.length + 3
    }
    val omitted = grouped[scope.section].orEmpty().size - selected.size
    return buildString {
        append(header)
        appendLine("\n## ${scope.label} (${selected.size}건)")
        if (omitted > 0) appendLine("- 오래된 사건 ${omitted}건 생략 (원문 복사 가능)")
        selected.asReversed().forEach { appendLine("- $it") }
        if (scope == DiagnosticShareScope.SAFETY) {
            appendLine()
            append(repeatedText)
        }
    }
}

/** 원문 날짜를 살리되 연도가 없던 구버전 시각도 반복 상태의 범위에 표시한다. */
private fun diagnosticTime(line: String): String = line.take(if (line.getOrNull(4) == '-') 23 else 18)

/** 분류에 실패한 기록도 '기타'에 남겨 드문 오류를 실수로 버리지 않는다. */
private fun diagnosticSection(line: String): String = when {
    "안전 안내 · " in line || "도로 매칭 · " in line || "속도 감시" in line || "과속 " in line -> "안전 안내·도로 매칭"
    "Fleet" in line || "명령" in line || "매크로" in line || "스마트싱스" in line || "빅스비" in line -> "명령·매크로"
    "BLE" in line || "GATT" in line || "연결 " in line || "직행 " in line || "스캔" in line -> "차량 연결"
    else -> "기타"
}
