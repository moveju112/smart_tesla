package com.wemade.teslamacro.data.nav

/** ADB는 공식 앱 공유 실행에만 사용하며 차량 BLE 전송을 의미하지 않는다. */
enum class TeslaNavigationLaunchMode(val label: String) {
    ADB_FIRST("ADB 우선"), STANDARD("일반 방식");

    companion object {
        /** 알 수 없는 저장값은 일반 실행 대체 경로가 있는 ADB 우선으로 복원한다. */
        fun of(value: String?): TeslaNavigationLaunchMode = entries.firstOrNull { it.name == value } ?: ADB_FIRST
    }
}

/** 화면에서 읽은 목적지는 같은 내비의 새 안내 알림과 결합할 때만 전송한다. */
internal class TeslaNavigationDestination {
    private data class Candidate(val text: String, val observedAt: Long)
    private data class Guidance(val key: String, val startedAt: Long, var sent: String? = null)
    private val candidates = mutableMapOf<String, Candidate>()
    private val guidance = mutableMapOf<String, Guidance>()

    /** 길찾기 미리보기는 보관만 하고 이미 시작된 안내의 짧은 대기 구간에서만 결합한다. */
    fun screen(packageName: String, text: String, now: Long): String? {
        if (!supports(packageName)) return null
        val normalized = normalize(text) ?: return null
        candidates[packageName] = Candidate(normalized, now)
        val active = guidance[packageName] ?: return null
        if (now - active.startedAt !in 0..5_000 || active.sent != null) return null
        return reserve(active, normalized)
    }

    /** 안내 상태가 확인된 알림만 목적지로 해석하며 앱 이름이나 회전 안내는 전송하지 않는다. */
    fun notification(packageName: String, key: String, title: String, text: String, now: Long): String? {
        if (!supports(packageName)) return null
        if (!isTeslaNavigationGuidance(packageName, title, text)) return null
        val active = guidance[packageName]?.takeIf { it.key == key }
            ?: Guidance(key, now).also { guidance[packageName] = it }
        val direct = when (packageName) {
            "com.skt.tmap.ku", "com.skt.skaf.l001mtm091" -> text.split('>').lastOrNull { it.isNotBlank() }
            "com.locnall.KimGiSa" -> Regex("^목적지\\s*:\\s*(.+)$").matchEntire(text)?.groupValues?.get(1)
            else -> null
        }?.let(::normalize)
        val cached = if (active.sent == null) candidates[packageName]?.takeIf { now - it.observedAt in 0..60_000 }?.text else null
        return (direct ?: cached)?.let { reserve(active, it) }
    }

    /** 해당 안내 알림이 사라지면 새 안내에서 과거 목적지를 재사용하지 않는다. */
    fun removed(packageName: String, key: String) {
        if (guidance[packageName]?.key == key) {
            guidance.remove(packageName)
            candidates.remove(packageName)
        }
    }

    /** 설정 OFF는 화면 캐시와 전송 예약까지 제거한다. */
    fun clear() { candidates.clear(); guidance.clear() }

    /** 화면 후보가 보완한 안내를 정확한 알림 키와 연결한다. */
    fun activeKey(packageName: String): String? = guidance[packageName]?.key

    /** 실행 전에 예약해 응답 유실이나 반복 알림이 같은 목적지를 다시 보내지 못하게 한다. */
    private fun reserve(active: Guidance, text: String): String? {
        if (active.sent == text) return null
        active.sent = text
        return text
    }

    companion object {
        val packages = setOf("com.skt.tmap.ku", "com.skt.skaf.l001mtm091", "com.locnall.KimGiSa", "com.nhn.android.nmap")

        /** 출처를 정확한 패키지 목록으로 제한한다. */
        fun supports(packageName: String): Boolean = packageName in packages

        /** 자리표시자·제어문자·과도한 입력을 목적지로 보내지 않는다. */
        fun normalize(value: String): String? {
            if (value.any { it.code < 32 && it !in "\n\r\t" }) return null
            val text = normalizeTeslaRoadSpacing(value.trim().replace(Regex("\\s+"), " "))
            // 긴 목적지 링크도 좌표를 먼저 확인해 장소명·경로 옵션 없이 고정된 위치만 남긴다.
            if (text.length <= 2048) teslaDestinationPoint(text)?.let { return "${it.latitude},${it.longitude}" }
            if (text.length !in 1..200) return null
            if (text in setOf("출발", "도착", "경유", "출발지 입력", "도착지 입력", "경유지 입력", "현재 위치", "내 위치", "길찾기", "내비게이션 - 안내 중", "안심주행", "출입구 변경", "출입구 선택", "출발지와 도착지 바꾸기", "경유지 추가",
                    // 네이버 경로 화면 0.9.200 실기기 판독에서 출발·도착 칸과 섞인 조작 글자.
                    "출발지 도착지 전환", "닫기", "더보기", "입구", "출구")) return null
            return text
        }
    }
}

internal data class NavigationScreenText(val text: String, val top: Int, val left: Int, val isButton: Boolean = false)

/** 실제 안내 판정을 공유해 테스트 보조창도 검색·안심주행 알림에는 뜨지 않게 한다. */
internal fun isTeslaNavigationGuidance(packageName: String, title: String, text: String): Boolean = when (packageName) {
    "com.skt.tmap.ku", "com.skt.skaf.l001mtm091" -> title == "경로주행" && text != "안심주행"
    "com.locnall.KimGiSa" -> title in listOf("길안내 주행 중", "보험을 켜고 길안내 주행 중")
    "com.nhn.android.nmap" -> text == "내비게이션 - 안내 중"
    else -> false
}

/** 내비별 고정 화면 영역에서 도착지를 읽으며 출발지와 입력 자리표시자는 제외한다. */
internal fun teslaDestinationFromScreen(packageName: String, entries: List<NavigationScreenText>): String? {
    if (packageName == "com.locnall.KimGiSa") {
        return entries.firstNotNullOfOrNull { entry ->
            Regex("^출발\\s+.+?,\\s*도착\\s+(.+)$").matchEntire(entry.text.trim())
                ?.groupValues?.get(1)?.let(TeslaNavigationDestination::normalize)
        }
    }
    if (packageName != "com.nhn.android.nmap") return null
    val placeholders = setOf("도착지 입력", "출발지 입력", "경유지 입력", "Enter destination", "Enter starting point", "Enter stop", "目的地入力", "出発地入力", "経由地入力", "输入目的地", "输入出发地", "输入经由地")
    // 도착지가 비어 있으면 출발지를 마지막 필드라고 오인하지 않는다.
    if (entries.any { it.text.trim() in setOf("도착지 입력", "Enter destination", "目的地入力", "输入目的地") }) return null
    val boundary = entries.filter { it.text.trim() in placeholders }.minOfOrNull { it.top }
    val allFields = entries.filter { !it.isButton && (boundary == null || it.top < boundary) }
        .sortedWith(compareBy<NavigationScreenText> { it.top }.thenBy { it.left })
        .mapNotNull { entry -> TeslaNavigationDestination.normalize(entry.text)?.let { entry.copy(text = it) } }
    // 도착지 아래 별도 출입구 행(응급실입구 등 장소마다 다른 이름)은 행 전체를 뺀다.
    // 구형 화면처럼 출입구 버튼이 도착지와 같은 행이면 그 행이 유일한 후보라 그대로 둔다.
    val entranceRows = entries.filter { it.text.trim() in setOf("출입구 변경", "출입구 선택") }.map { it.top }.toSet()
    val fields = allFields.filter { it.top !in entranceRows }.ifEmpty { allFields }
    // 도착 라벨이 노출된 화면은 그 행만 읽고, 라벨 없는 구형 화면은 출발·도착 두 필드일 때만 허용한다.
    val destinationLabel = entries.firstOrNull { it.text.trim() == "도착" }
    if (destinationLabel != null) return fields.filter { it.top == destinationLabel.top }.map { it.text }.distinct().singleOrNull()
    val values = fields.map { it.text }.distinct()
    return values.takeIf { it.size <= 2 }?.lastOrNull()
}
