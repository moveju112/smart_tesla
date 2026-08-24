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
 * KNSDK가 주는 코드는 50가지가 넘는데, 주행 중 한 눈에 읽어야 하므로
 * **사람이 반응을 바꾸는 단위**로만 묶는다. 세분화는 화면에서 소음이 된다.
 */
enum class SafetyKind(val label: String) {
    /** 고정식·이동식 과속 단속 */
    SPEED_CAMERA("과속 단속"),

    /** 구간 단속. 시작·중간·종점을 하나로 본다 */
    SECTION_CAMERA("구간 단속"),

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
 * [ready]가 false면 SDK가 아직 못 떴거나 앱 키가 없는 것이다 —
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
     * 제한속도를 알고, 그보다 [toleranceKph] 넘게 빠른가.
     *
     * 기준은 **다가오는 단속 카메라의 제한속도뿐**이다 — KNSDK 1.12.8에서 제한속도를
     * 들고 있는 객체는 `KNSafety_Camera` 하나이고, 경로 없이 도는 free-drive 모드에는
     * "지금 달리는 도로의 제한속도"라는 값이 아예 오지 않는다.
     * 그래서 카메라가 안 잡히는 구간의 과속은 이 앱이 알 수 없다. 모르는 것을 아는 척하지 않는다.
     */
    fun isOverSpeed(currentKph: Double, toleranceKph: Int = 0): Boolean {
        val limit = alert?.speedLimitKph ?: return false
        return currentKph > limit + toleranceKph
    }
}
