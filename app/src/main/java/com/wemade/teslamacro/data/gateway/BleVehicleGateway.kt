package com.wemade.teslamacro.data.gateway

import android.content.Context
import com.wemade.teslable.TeslaBleLink
import com.wemade.teslable.TeslaBleScanner
import com.wemade.teslable.TeslaClient
import com.tesla.generated.carserver.server.CarServer
import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.domain.command.requiresPark
import com.wemade.teslamacro.domain.gateway.EnrollmentState
import com.wemade.teslamacro.domain.gateway.LinkState
import com.wemade.teslamacro.domain.gateway.VehicleGateway
import com.wemade.teslamacro.domain.model.ShiftState
import com.wemade.teslamacro.domain.model.StateCategory
import com.wemade.teslamacro.domain.model.VehicleSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * BLE 게이트웨이. 스캔 -> 연결 -> 서명 세션 -> 명령/상태.
 *
 * 실패를 삼키지 않는다. 상위 계층이 재시도할지 사용자에게 알릴지 판단할 수 있게
 * 원인을 그대로 [Result]에 담아 올린다.
 */
class BleVehicleGateway(
    private val context: Context,
    private val settingsStore: com.wemade.teslamacro.data.settings.SettingsStore,
    scope: kotlinx.coroutines.CoroutineScope,
) : VehicleGateway {

    private val scanner = TeslaBleScanner(context)
    private val link = TeslaBleLink(context)
    private var client: TeslaClient? = null

    init {
        // GATT 끊김을 그 자리에서 상태에 반영한다.
        // 폴링 때까지 기다리면 그 사이 음성 서비스가 "연결됨"으로 믿고 마이크를 계속 연다
        scope.launch {
            link.disconnects.collect { ensureLinked() }
        }
    }

    // 연결 시도를 한 번에 하나로 묶는다. 폴러와 사용자 조작이 동시에 붙으면
    // 같은 링크에 두 번 connectGatt가 걸려 연결이 뒤엉킨다
    private val connectMutex = kotlinx.coroutines.sync.Mutex()

    private val _linkState = MutableStateFlow<LinkState>(LinkState.Idle)
    override val linkState: StateFlow<LinkState> = _linkState.asStateFlow()

    private val _enrollmentState = MutableStateFlow<EnrollmentState>(EnrollmentState.NotEnrolled)
    override val enrollmentState: StateFlow<EnrollmentState> = _enrollmentState.asStateFlow()

    override suspend fun connect(vin: String, allowProbe: Boolean): Result<Unit> = connectMutex.withLock {
        if (link.isConnected && client != null) return@withLock Result.success(Unit)
        if (!scanner.isBluetoothReady) {
            _linkState.value = LinkState.Failed("블루투스가 꺼져 있어요")
            return@withLock Result.failure(IllegalStateException("블루투스가 꺼져 있어요"))
        }

        runCatching {
            _linkState.value = LinkState.Scanning
            // 백그라운드 재시도는 몇 시간씩 같은 실패를 반복한다 — 직전과 같은 실패가
            // 이어지는 동안은 시작·실패 로그를 생략해 버퍼(300줄)를 지킨다
            val repeatAttempt = lastConnectFailure != null
            if (!repeatAttempt) {
                com.wemade.teslable.DiagLog.add(
                    "연결 시작 (검색 이름 ${com.wemade.teslable.TeslaBleSpec.bleLocalName(vin)})"
                )
            }

            // 0. 전에 검증해 둔 차 주소가 있으면 스캔 없이 바로 붙는다. 제일 빠른 길
            val saved = settingsStore.settings.first().vehicleAddress
            if (saved.isNotBlank() && !repeatAttempt) {
                com.wemade.teslable.DiagLog.add("저장된 주소로 직행 시도 $saved")
            }
            if (saved.isNotBlank() && connectSaved(saved, quiet = repeatAttempt)) {
                lastConnectFailure = null
                com.wemade.teslable.DiagLog.add("직행 연결 성공 $saved")
                client = TeslaClient(context, link, vin)
                _linkState.value = LinkState.Ready
                return@runCatching
            }

            // 0-1. 백그라운드 재시도는 여기서 끝낸다 — 이 폰 스캔은 차 광고를 못 받아(BLE_RULES)
            //      아래 12초 스캔이 귀머거리 시간일 뿐이고, allowProbe=false라 후보 검증도 못 쓴다
            if (!allowProbe && saved.isNotBlank()) error("차량이 보이지 않아요")

            // 1. VIN으로 계산한 이름을 잠깐 찾아보고, 그동안 주변 후보도 모아둔다.
            //    규칙대로 광고하는 차(주로 구형)는 여기서 몇 초 만에 끝난다.
            //    신형은 광고 이름이 규칙과 달라 이 단계로는 못 찾는다 (실측 확인)
            val targetName = com.wemade.teslable.TeslaBleSpec.bleLocalName(vin)
            val candidates = linkedMapOf<String, com.wemade.teslable.DiscoveredVehicle>()
            val weak = mutableSetOf<String>()

            val exact = withTimeoutOrNull(NAME_WINDOW_MS) {
                scanner.scanNearby().first { candidate ->
                    if (candidate.localName.equals(targetName, ignoreCase = true)) {
                        true
                    } else {
                        // 너무 먼 기기는 후보에서 뺀다. 옆 주차칸 차를 잡으면 안 된다
                        if (candidate.rssi <= NEARBY_RSSI_FLOOR) weak += candidate.device.address
                        if (candidate.rssi > NEARBY_RSSI_FLOOR) {
                            val key = candidate.device.address
                            val known = candidates[key]
                            if (known == null || candidate.rssi > known.rssi) {
                                candidates[key] = candidate
                            }
                        }
                        false
                    }
                }
            }

            // 2. GATT 연결 + notify 구독. 이름 일치가 없으면 후보를 순서대로 검증한다.
            //    connect()는 테슬라 서비스가 없는 기기면 실패하므로 그 자체가 판별이다
            val found = if (exact != null) {
                com.wemade.teslable.DiagLog.add("이름 일치 발견 ${exact.localName} (${exact.rssi}dBm)")
                _linkState.value = LinkState.Connecting(exact.rssi)
                link.connect(exact.device)
                exact
            } else {
                com.wemade.teslable.DiagLog.add(
                    "이름 일치 없음. 후보 ${candidates.size}대" +
                        " (신호 약해서 제외 ${weak.size}대)"
                )
                // 후보마다 광고 내용을 한 줄씩. 어떤 기기인지 가리는 단서가 된다
                candidates.values.forEach { c ->
                    com.wemade.teslable.DiagLog.add(
                        "  ${c.localName} ${c.rssi}dBm" +
                            (if (c.isConnectable) "" else " [접속불가 비콘]") +
                            (if (c.advSummary.isNotEmpty()) " ${c.advSummary}" else "")
                    )
                }
                // 접속 검증은 사용자가 시켰을 때만. 백그라운드가 남의 기기에 붙으면 안 된다
                if (!allowProbe) {
                    com.wemade.teslable.DiagLog.add("백그라운드 연결이라 후보 검증 생략")
                    error("차량이 보이지 않아요")
                }

                // (페어링된 클래식 MAC은 핸즈프리 채널이라 제외. 10초만 버렸던 실험이다)
                probeCandidates(candidates.values)
                    ?: error("차량을 찾지 못했어요.\n차 가까이에서 다시 시도해 주세요")
            }

            // 3. 검증된 주소를 저장한다. 다음 연결은 0단계에서 끝난다
            com.wemade.teslable.DiagLog.add("차량 확정 ${found.device.address} (${found.localName}) — 주소 저장")
            settingsStore.setVehicleAddress(found.device.address)

            // 4. 프로토콜 클라이언트 준비 (핸드셰이크는 첫 명령 때 지연 수행)
            client = TeslaClient(context, link, vin)
            _linkState.value = LinkState.Ready
        }.onFailure { throwable ->
            // 같은 실패가 이어지면 첫 번째만 남긴다 — 원인이 바뀌는 순간은 반드시 남긴다
            if (throwable.message != lastConnectFailure) {
                com.wemade.teslable.DiagLog.add("연결 실패: ${throwable.message}")
            }
            lastConnectFailure = throwable.message ?: "연결 실패"
            _linkState.value = LinkState.Failed(throwable.message ?: "연결 실패")
            client = null
        }
    }

    // 직전 백그라운드 연결 시도의 실패 사유 — 같은 실패의 반복 로그를 막는 기준.
    // 성공하거나 사용자가 직접 연결을 시도하면 비운다
    @Volatile
    private var lastConnectFailure: String? = null

    /**
     * 스캔을 건너뛰고 주어진 주소로 곧바로 붙는다.
     * 테파일럿처럼 주소를 알면 광고를 기다릴 필요가 없다.
     */
    override suspend fun connectDirect(vin: String, address: String): Result<Unit> = connectMutex.withLock {
        lastConnectFailure = null   // 사용자가 직접 시도했다 — 이번 결과는 조용히 넘기지 않는다
        if (link.isConnected && client != null) return@withLock Result.success(Unit)
        if (!scanner.isBluetoothReady) {
            _linkState.value = LinkState.Failed("블루투스가 꺼져 있어요")
            return@withLock Result.failure(IllegalStateException("블루투스가 꺼져 있어요"))
        }

        runCatching {
            com.wemade.teslable.DiagLog.add("주소로 직접 연결 시도 $address")
            _linkState.value = LinkState.Connecting(0)
            link.close()   // 이전 프로브가 남긴 연결을 정리한다
            val device = scanner.deviceOf(address) ?: error("주소 형식이 올바르지 않아요")

            // autoConnect로 붙는다. 차가 광고를 짧게 뿌려도 OS가 나타나는 순간 이어준다.
            // connect()는 테슬라 서비스가 없으면 실패한다 — 그 자체가 검증이다
            com.wemade.teslable.DiagLog.add("autoConnect 대기 중… 최대 ${DIRECT_TIMEOUT_MS / 1000}초")
            link.connect(device, DIRECT_TIMEOUT_MS, autoConnect = true)

            settingsStore.setVin(vin)
            settingsStore.setVehicleAddress(address)
            scanner.bondedTesla()?.let { settingsStore.setVehicleName(it.name) }

            client = TeslaClient(context, link, vin)
            _linkState.value = LinkState.Ready
            com.wemade.teslable.DiagLog.add("직접 연결 성공 $address")
        }.onFailure { throwable ->
            com.wemade.teslable.DiagLog.add("직접 연결 실패: ${throwable.message}")
            _linkState.value = LinkState.Failed(throwable.message ?: "연결 실패")
            client = null
            link.close()
        }
    }

    /**
     * 저장된 주소로 직행. 실패해도 치명적이지 않다 — 스캔 경로가 뒤에 있다.
     * 직접 연결과 같은 autoConnect 방식을 쓴다 — 그래야 최초 성공이 매번 재현된다.
     */
    private suspend fun connectSaved(address: String, quiet: Boolean = false): Boolean {
        val device = scanner.deviceOf(address) ?: return false
        val result = runCatching {
            link.connect(device, DIRECT_TIMEOUT_MS, autoConnect = true, quiet = quiet)
        }
        if (result.isFailure) {
            // 반복 재시도(quiet) 중에는 같은 실패를 다시 적지 않는다
            if (!quiet) {
                com.wemade.teslable.DiagLog.add(
                    "직행 실패(${result.exceptionOrNull()?.message}) → 스캔으로 전환"
                )
            }
            link.close()
        }
        return result.isSuccess
    }

    /**
     * 이름이 안 맞는 주변 기기 중 진짜 차를 가려낸다.
     * 광고 서비스 UUID 실린 것 → 신호 센 것 순으로 직접 붙어본다.
     * 성공하면 링크가 이미 열린 상태로 돌아온다.
     */
    private suspend fun probeCandidates(
        candidates: Collection<com.wemade.teslable.DiscoveredVehicle>,
    ): com.wemade.teslable.DiscoveredVehicle? {
        val ordered = candidates
            .filter { it.isConnectable }   // 방송만 하는 비콘은 10초 타임아웃만 먹는다
            .sortedWith(
                compareByDescending<com.wemade.teslable.DiscoveredVehicle> { it.hasTeslaService }
                    .thenByDescending { it.rssi }
            )
            .take(MAX_PROBES)

        for (candidate in ordered) {
            // status=133 같은 일시 오류는 곧바로 다시 붙으면 뚫리는 경우가 많다.
            // 빨리 실패했을 때만 한 번 더 — 타임아웃까지 두 번 기다릴 가치는 없다
            for (attempt in 0..1) {
                com.wemade.teslable.DiagLog.add(
                    "후보 검증 ${candidate.localName} (${candidate.rssi}dBm)" +
                        if (attempt > 0) " 재시도" else ""
                )
                _linkState.value = LinkState.Connecting(candidate.rssi)

                val startedAt = System.currentTimeMillis()
                val ok = runCatching { link.connect(candidate.device, PROBE_TIMEOUT_MS) }.isSuccess
                if (ok) return candidate
                link.close()

                // 타임아웃까지 갔으면 재시도 가치가 없다. 다음 후보로
                if (System.currentTimeMillis() - startedAt >= QUICK_FAILURE_MS) break
            }
        }
        return null
    }

    override suspend fun disconnect() {
        client = null
        link.close()
        _linkState.value = LinkState.Idle
    }

    /**
     * 링크가 소리 없이 끊겼는지 확인한다. 끊겼으면 상태를 내려 폴러가 재연결하게 한다.
     *
     * GATT가 끊겨도(status=8 등) linkState가 Ready로 남아 있으면
     * 폴러는 "연결됨"으로 믿고 실패하는 읽기를 영원히 반복한다 — 실차에서 실제로 났던 사고다.
     */
    private fun ensureLinked(): Boolean {
        if (link.isConnected) return true
        if (client != null || _linkState.value is LinkState.Ready) {
            com.wemade.teslable.DiagLog.add("링크 끊김 감지 → 재연결 대기로 전환")
            client = null
            // 죽은 gatt 껍데기를 정리해야 다음 connect()가 붙는다.
            // 안 닫으면 "이미 연결되어 있다"로 직행이 실패해 재연결 1회차를 통째로 버린다
            link.close()
            _linkState.value = LinkState.Failed("차량과 연결이 끊어졌어요")
        }
        return false
    }

    override suspend fun requestKeyEnrollment(): Result<Unit> {
        val active = client ?: return Result.failure(IllegalStateException("먼저 차량에 연결해 주세요"))
        _enrollmentState.value = EnrollmentState.AwaitingCardTap

        return runCatching { active.requestKeyEnrollment() }
            .onFailure {
                _enrollmentState.value =
                    EnrollmentState.Failed(it.message ?: "키 등록 요청에 실패했어요")
            }
            .map { }
        // 전송 성공은 "요청이 갔다"까지다. 실제 승인은 사용자가 카드를 대야 끝난다.
        // 승인 완료 여부는 이후 명령이 성공하는지로 판단한다
    }

    override suspend fun verifyKeyEnrollment(): Result<Unit> {
        val active = client ?: return Result.failure(IllegalStateException("먼저 차량에 연결해 주세요"))
        return runCatching { active.verifyEnrollment() }
            .onSuccess { _enrollmentState.value = EnrollmentState.Enrolled }
    }

    override suspend fun send(command: VehicleCommand): Result<Unit> {
        if (!ensureLinked()) return Result.failure(IllegalStateException("차량과 연결이 끊어졌어요"))
        val active = client ?: return Result.failure(IllegalStateException("차량에 연결되어 있지 않아요"))

        // 1. 보닛/트렁크는 주행 중 열리면 치명적이다. P단이 확인될 때만 차량에 보낸다.
        //    기어를 못 읽으면(차가 잠듦 등) 여는 쪽이 아니라 막는 쪽으로 넘어진다
        if (command.requiresPark()) {
            val shift = read(StateCategory.DRIVE).getOrNull()?.shiftState
            if (shift != ShiftState.PARK) {
                com.wemade.teslable.DiagLog.add("${command.label} 차단 — 기어=$shift")
                val reason = if (shift == null || shift == ShiftState.UNKNOWN) {
                    "기어 상태를 확인하지 못해 열지 않았어요"
                } else {
                    "P단에서만 열 수 있어요"
                }
                return Result.failure(IllegalStateException(reason))
            }
        }

        return runCatching {
            when (val encoded = CommandEncoder.encode(command)) {
                is EncodedCommand.Infotainment -> {
                    // 봉투가 무사히 왕복해도 차는 본문에 거부를 실어 보낸다.
                    // 이걸 안 읽으면 "완료"라고 기록해 놓고 차는 안 움직이는 거짓 성공이 된다
                    // (실차 0.8.35: 창문 환기·닫기가 조용히 무시됨)
                    val responseBytes = active.sendToInfotainment(encoded.action.toByteArray())
                    checkInfotainmentResult(command, responseBytes)
                }
                is EncodedCommand.Vehicle ->
                    active.sendToVcsec(encoded.message)
            }
            // 여기까지 왔으면 차량이 인증하고 받아들인 것이다
            _enrollmentState.value = EnrollmentState.Enrolled
        }.map { }
    }

    /**
     * 인포테인먼트 응답 본문의 실행 결과를 검사한다.
     *
     * 차는 명령을 거부할 때 예외를 내지 않는다 — actionStatus에 ERROR와 사유 문장을
     * 실어 보낼 뿐이다. 사유가 있으면 그대로 사용자에게 보여준다 (차가 제일 정확히 안다).
     */
    private fun checkInfotainmentResult(command: VehicleCommand, responseBytes: ByteArray) {
        val reason = infotainmentRejection(responseBytes) ?: return
        com.wemade.teslable.DiagLog.add("${command.label} 거부됨 — $reason")
        throw IllegalStateException(reason)
    }

    override suspend fun read(category: StateCategory): Result<VehicleSnapshot> {
        if (!ensureLinked()) return Result.failure(IllegalStateException("차량과 연결이 끊어졌어요"))
        val active = client ?: return Result.failure(IllegalStateException("차량에 연결되어 있지 않아요"))
        val now = System.currentTimeMillis()

        return runCatching {
            when (category) {
                // VCSEC은 차가 자고 있어도 응답한다. 주기 폴링이라 전송 로그는 생략(quiet)
                StateCategory.BODY_CONTROLLER, StateCategory.CLOSURES -> {
                    val response = active.sendToVcsec(
                        CommandEncoder.encodeBodyControllerStateRequest(), quiet = true,
                    )
                    SnapshotDecoder.fromVcsecStatus(response, now)
                }

                StateCategory.CLIMATE -> {
                    val bytes = active.sendToInfotainment(
                        CommandEncoder.encodeClimateStateRequest().toByteArray(), quiet = true,
                    )
                    SnapshotDecoder.fromClimateResponse(bytes, now).also {
                        logParsedIfChanged(
                            category,
                            "CLIMATE → 실내=${it.insideTempC} 외부=${it.outsideTempC} 공조=${it.isClimateOn}",
                        )
                    }
                }

                StateCategory.CHARGE -> {
                    val bytes = active.sendToInfotainment(
                        CommandEncoder.encodeChargeStateRequest().toByteArray(), quiet = true,
                    )
                    SnapshotDecoder.fromChargeResponse(bytes, now).also {
                        logParsedIfChanged(category, "CHARGE → 배터리=${it.batteryLevelPercent}")
                    }
                }

                StateCategory.DRIVE -> {
                    val bytes = active.sendToInfotainment(
                        CommandEncoder.encodeDriveStateRequest().toByteArray(), quiet = true,
                    )
                    SnapshotDecoder.fromDriveResponse(bytes, now)
                }
            }
        }.onFailure {
            // 같은 실패(차가 자는 동안 무응답 등)는 첫 번째만 — 원인이 바뀌면 다시 남긴다
            val message = it.message ?: "원인 불명"
            if (lastReadFailure[category] != message) {
                lastReadFailure[category] = message
                com.wemade.teslable.DiagLog.add("$category 읽기 실패: $message")
            }
        }.onSuccess { lastReadFailure.remove(category) }
    }

    private val lastReadFailure = mutableMapOf<StateCategory, String>()

    // 파싱 결과는 값이 바뀔 때만 남긴다 — "배터리=99"를 15초마다 반복하면
    // 300줄 버퍼에서 연결·매크로 로그를 밀어낸다
    private val lastParsedLog = mutableMapOf<StateCategory, String>()
    private fun logParsedIfChanged(category: StateCategory, summary: String) {
        if (lastParsedLog[category] == summary) return
        lastParsedLog[category] = summary
        com.wemade.teslable.DiagLog.add(summary)
    }

    private companion object {
        /**
         * 계산된 이름을 기다리는 시간.
         * 규칙대로 광고하는 차는 이 안에 잡히고, 아닌 차는 아무리 기다려도 안 잡힌다.
         * 길게 잡을수록 신형에서 접속 검증 시작만 늦어진다
         */
        const val NAME_WINDOW_MS = 12_000L

        /** 이름 없이 후보로 삼을 최소 신호. 옆 주차칸 차를 잡으면 안 된다 */
        const val NEARBY_RSSI_FLOOR = -70

        /** 후보 검증은 가까운 기기 몇 대만. 이어폰까지 다 붙어보면 하세월이다 */
        const val MAX_PROBES = 4
        const val PROBE_TIMEOUT_MS = 10_000L

        /** 이보다 빨리 실패하면 일시 오류(133 등)로 보고 한 번 더 붙어본다 */
        const val QUICK_FAILURE_MS = 3_000L

        /** autoConnect 직접 연결은 차가 나타날 때까지 넉넉히 기다린다 */
        const val DIRECT_TIMEOUT_MS = 30_000L
    }
}

/**
 * 인포테인먼트 응답에서 거부 사유를 뽑는다. 거부가 아니면 null.
 * 게이트웨이 없이도 검사할 수 있게 순수 함수로 둔다.
 */
internal fun infotainmentRejection(responseBytes: ByteArray): String? {
    val status = runCatching { CarServer.Response.parseFrom(responseBytes).actionStatus }
        .getOrNull() ?: return null // 본문 해석 실패는 여기서 판정하지 않는다
    if (status.result != CarServer.OperationStatus_E.OPERATIONSTATUS_ERROR) return null
    return status.resultReason.plainText.ifBlank { "차량이 거부함 (사유 없음)" }
}
