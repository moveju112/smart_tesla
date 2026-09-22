package com.wemade.teslamacro.domain.safety

/**
 * 지금 다가오는 안전 지점 하나.
 *
 * 주행 중에는 읽을 시간이 없다 — 종류·거리·제한속도 셋만 남기고 나머지는 버린다.
 */
data class SafetyAlert(
    val kind: SafetyKind,
    /** 남은 거리(m). 못 재면 null */
    val distanceMeters: Int?,
    /** 이 지점의 제한속도(km/h). 단속 카메라가 아니면 null */
    val speedLimitKph: Int? = null,
)

/**
 * 안전 지점의 종류.
 *
 * 공공데이터에서 제공하는 고정식 단속 후보만 생성한다.
 * 나머지 종류는 기존 화면 계약을 위해 남긴다.
 */
enum class SafetyKind(val label: String) {
    /** 도로 매칭 없는 고정식 단속 후보 */
    SPEED_CAMERA("단속 후보"),

    /** 구간 단속. 시작·중간·종점을 하나로 본다 */
    SECTION_CAMERA("구간단속 후보"),

    /** 어린이·노인 보호구역 */
    PROTECTION_ZONE("보호구역"),

    /** 급커브·낙석·결빙 등 도로 위험 */
    ROAD_HAZARD("위험 구간"),

    /** 사고 잦은 곳 */
    ACCIDENT_SPOT("사고 다발"),

    /** 위 어디에도 안 들어가는 것 */
    OTHER("안전 구간"),
}

/**
 * 안전운전 안내의 현재 상태.
 *
 * [ready]가 false면 오프라인 목록을 아직 읽지 못한 것이다 —
 * 그때는 화면에 아무것도 띄우지 않는다. "안내 없음"과 "안내 못 함"은 다르고,
 * 후자를 침묵으로 감추면 운전자가 안내를 믿어버린다.
 */
data class SafetyState(
    val ready: Boolean = false,
    val alert: SafetyAlert? = null,
    /**
     * 켜져 있는데 위치를 못 받고 있는가.
     *
     * [ready]가 true인데 위성이 안 잡히면 화면에는 아무것도 안 뜬다 —
     * 사용자에겐 "안내할 게 없다"와 "안내를 못 한다"가 똑같이 보인다.
     * 그 둘을 가르려고 따로 든다.
     */
    val stalled: Boolean = false,
) {
    /**
     * 후보 제한속도 + [toleranceKph]에 도달했는가. 경계값부터 경보한다.
     *
     * 기준은 **전방 단속 후보의 제한속도뿐**이다.
     * 공공데이터에는 모든 도로의 제한속도나 정확한 단속 방향이 없다.
     * 그래서 카메라가 안 잡히는 구간의 과속은 이 앱이 알 수 없다. 모르는 것을 아는 척하지 않는다.
     */
    fun isOverSpeed(currentKph: Double, toleranceKph: Int = 0): Boolean {
        if (!ready || stalled || !currentKph.isFinite()) return false
        val limit = alert?.speedLimitKph?.takeIf { it > 0 } ?: return false
        return currentKph >= limit.toDouble() + toleranceKph.coerceAtLeast(0)
    }
}
