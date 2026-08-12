package com.wemade.teslamacro.domain.gateway

import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.domain.model.StateCategory
import com.wemade.teslamacro.domain.model.VehicleSnapshot
import kotlinx.coroutines.flow.StateFlow

/** 연결 진행 상태. 화면은 이 값만 보고 그린다 */
sealed interface LinkState {
    data object Idle : LinkState
    data object Scanning : LinkState
    data class Connecting(val rssi: Int) : LinkState
    data object Ready : LinkState
    data class Failed(val reason: String) : LinkState
}

/** 키 등록 진행 단계 — 카드키 태그 안내 UI가 이 값을 따라간다 */
sealed interface EnrollmentState {
    data object NotEnrolled : EnrollmentState
    data object AwaitingCardTap : EnrollmentState
    data object Enrolled : EnrollmentState
    data class Failed(val reason: String) : EnrollmentState
}

/**
 * 차량과 통신하는 유일한 창구.
 *
 * 전송 수단을 숨기는 게 목적이다. 지금은 BLE 구현 하나뿐이지만,
 * 나중에 Fleet API 구현을 추가해도 화면과 매크로는 손대지 않는다.
 */
interface VehicleGateway {

    val linkState: StateFlow<LinkState>
    val enrollmentState: StateFlow<EnrollmentState>

    /**
     * VIN으로 차량을 찾아 연결한다. 이미 연결돼 있으면 아무것도 하지 않는다.
     *
     * @param allowProbe 이름으로 못 찾으면 주변 기기에 직접 붙어 차인지 검증한다.
     *   사용자가 버튼을 눌렀을 때만 켠다 — 백그라운드 폴링이 이걸 켜면
     *   집에서도 30초마다 이웃 기기에 GATT 접속을 시도하게 된다.
     */
    suspend fun connect(vin: String, allowProbe: Boolean = false): Result<Unit>

    /**
     * 스캔을 건너뛰고 지정한 BLE 주소로 곧바로 붙는다.
     *
     * 광고를 기다릴 필요가 없어 제일 빠르다. 주소를 아는 경우에만 쓴다.
     * 기본 구현은 일반 연결로 넘긴다 (시뮬레이터 등).
     */
    suspend fun connectDirect(vin: String, address: String): Result<Unit> =
        connect(vin, allowProbe = true)

    suspend fun disconnect()

    /** 앱 키를 차량에 등록 요청한다. 이후 사용자가 카드키를 센터콘솔에 태그해야 한다 */
    suspend fun requestKeyEnrollment(): Result<Unit>

    /**
     * 카드키 승인이 실제로 끝났는지 확인한다.
     * 승인 전에는 차량이 이 키의 세션을 거부하므로, 성공 = 등록 완료다.
     */
    suspend fun verifyKeyEnrollment(): Result<Unit> = Result.success(Unit)

    suspend fun send(command: VehicleCommand): Result<Unit>

    /** 상태 한 묶음을 읽는다. 응답 크기 제한 때문에 카테고리는 하나씩만 요청한다 */
    suspend fun read(category: StateCategory): Result<VehicleSnapshot>
}
