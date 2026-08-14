package com.wemade.teslamacro.feature.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wemade.teslable.TeslaBleSpec
import com.wemade.teslamacro.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class PairingViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(PairingUiState())
    val uiState: StateFlow<PairingUiState> = _uiState.asStateFlow()

    init {
        // 페어링 목록에 테슬라가 있으면 별칭을 미리 채운다. 차 없이도 읽힌다
        container.scanner.bondedTesla()?.let { tesla ->
            _uiState.update { it.copy(detectedName = tesla.name) }
        }
    }


    fun onVinChange(input: String) {
        // 입력 단계에서 대문자로 정규화하고 17자를 넘기지 않는다
        val cleaned = input.uppercase().filter { it.isLetterOrDigit() }.take(17)
        _uiState.update { it.copy(vin = cleaned, message = null, isError = false) }
    }

    /** VIN을 저장하고 BLE로 차량을 찾는다 */
    fun findVehicle() {
        val vin = _uiState.value.vin
        if (!TeslaBleSpec.isValidVin(vin)) return

        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, message = "차량을 찾는 중…", isError = false) }

            // 시뮬레이터가 붙어 있으면 가짜로 성공해버린다. 실차로 갈아끼운다
            container.useRealVehicle()

            var result = container.gateway.connect(vin, allowProbe = true)

            // 스캔이 차 광고를 아예 못 받는 폰이 있다 (실기기 확인).
            // 그런 폰을 위해 페어링 목록의 테슬라 후보 주소로 직행을 이어서 시도한다
            if (result.isFailure) result = connectViaBonded(vin) ?: result

            // 차를 실제로 찾았을 때만 VIN을 저장한다.
            // 실패했는데 저장하면 "등록된 차"로 남아 백그라운드가 헛돌고,
            // 설정 화면에도 유령 VIN이 찍힌다
            if (result.isSuccess) {
                container.settingsStore.setVin(vin)
                // 페어링 목록에 별칭이 있으면 같이 저장해 화면에 이름으로 쓴다
                container.scanner.bondedTesla()?.let {
                    container.settingsStore.setVehicleName(it.name)
                }
            }

            _uiState.update {
                if (result.isSuccess) {
                    it.copy(
                        step = PairingStep.TapCard,
                        isBusy = false,
                        message = "차량을 찾았어요.\n이제 앱 키를 등록할게요",
                    )
                } else {
                    // 실패했을 때만 검색 이름을 덧붙인다.
                    // 평소엔 볼 이유가 없지만, 못 찾을 땐 VIN 오타를 가려내는 단서가 된다
                    val reason = result.exceptionOrNull()?.message ?: "차량을 찾지 못했어요"
                    it.copy(
                        step = PairingStep.FindVehicle,
                        isBusy = false,
                        isError = true,
                        message = "$reason\n(검색한 이름 ${TeslaBleSpec.bleLocalName(vin)})",
                    )
                }
            }
        }
    }

    /**
     * 주변 테슬라를 그대로 훑는다.
     *
     * 목록에 차가 뜨는데 이름이 다르면 VIN이 틀린 것이고,
     * 하나도 안 뜨면 스캔 자체가 막힌 것이다.
     */
    fun scanNearby() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isBusy = true, nearby = null, message = "주변 기기를 훑는 중…", isError = false)
            }

            val expected = TeslaBleSpec.bleLocalName(_uiState.value.vin)
            com.wemade.teslable.DiagLog.add("전체 스캔 시작 — 찾는 이름 $expected")

            // 진단이므로 주소로 묶어 하나도 빠뜨리지 않는다. 이름 없는 기기도 남긴다.
            // 목표: 내 앱의 스캔 콜백이 그 이름/주소를 받는지 눈으로 확인하는 것
            val seen = linkedMapOf<String, NearbyDevice>()
            var matchedRaw = false
            withTimeoutOrNull(DIAG_SCAN_MS) {
                container.scanner.scanNearby().collect { found ->
                    val addr = found.device.address
                    val isMine = found.localName.equals(expected, ignoreCase = true)
                    // 찾는 이름이 실제로 콜백에 도착하는 순간을 놓치지 않고 찍는다
                    if (isMine && !matchedRaw) {
                        matchedRaw = true
                        com.wemade.teslable.DiagLog.add("★ 찾는 이름 수신! $addr ${found.rssi}dBm")
                    }
                    val prev = seen[addr]
                    if (prev == null || found.rssi > prev.rssi) {
                        seen[addr] = NearbyDevice(
                            name = found.localName,
                            rssi = found.rssi,
                            isTesla = isMine || found.hasTeslaService ||
                                container.scanner.isTeslaNamePattern(found.localName),
                        )
                    }
                }
            }

            val all = seen.values.sortedByDescending { it.rssi }
            val mine = all.any { it.name.equals(expected, ignoreCase = true) }

            // 받은 걸 전부 로그로 덤프한다. nRF이 보는 것과 대조할 수 있게
            com.wemade.teslable.DiagLog.add("전체 스캔 결과 ${all.size}대:")
            all.forEach { com.wemade.teslable.DiagLog.add("  ${it.name} ${it.rssi}dBm") }

            _uiState.update {
                it.copy(
                    isBusy = false,
                    nearby = all.take(20),
                    isError = !mine,
                    message = when {
                        mine -> "내 차를 찾았어요.\n다시 찾기를 눌러 주세요"
                        all.isEmpty() -> "스캔이 한 건도 잡지 못했어요"
                        else -> "기기 ${all.size}대를 봤지만 내 차 이름은 없었어요.\n로그를 복사해 보내 주세요"
                    },
                )
            }
        }
    }

    /**
     * 스캔 실패 시 마지막 수단 — 페어링 목록에서 테슬라로 보이는 기기 주소로 직행한다.
     *
     * 페어링 목록은 저장된 데이터라 광고 수신이 안 되는 폰에서도 읽힌다.
     * connectDirect는 테슬라 서비스가 없으면 실패하므로 엉뚱한 기기에 붙을 걱정은 없다.
     * 후보가 하나도 없으면 null — 원래 실패를 그대로 쓰라는 뜻이다.
     */
    private suspend fun connectViaBonded(vin: String): Result<Unit>? {
        val candidates = container.scanner.bondedDevices()
            .filter { device ->
                device.name.contains("tesla", ignoreCase = true) ||
                    container.scanner.isTeslaNamePattern(device.name) ||
                    // 테슬라 차량 서비스 UUID(00000211-…)가 캐시에 남아 있는 경우
                    device.uuids.any { it.startsWith("00000211") }
            }
            .take(MAX_BONDED_TRIES)

        if (candidates.isEmpty()) {
            com.wemade.teslable.DiagLog.add("페어링 목록에 테슬라 후보 없음 — 폴백 생략")
            return null
        }

        // 1. 후보를 순서대로 붙어본다. autoConnect라 한 대에 최대 30초 걸린다
        var last: Result<Unit>? = null
        for (device in candidates) {
            _uiState.update {
                it.copy(message = "스캔 실패 → 페어링된 ${device.name}(으)로 직접 연결 중…")
            }
            last = container.gateway.connectDirect(vin, device.address)
            if (last.isSuccess) return last
        }
        return last
    }

    /**
     * 스캔을 건너뛰고 주소로 곧바로 붙는다.
     * nRF 등에서 확인한 차의 BLE 주소를 알 때 쓰는 지름길이다.
     */
    fun connectDirect(address: String) {
        val vin = _uiState.value.vin
        if (!TeslaBleSpec.isValidVin(vin)) {
            _uiState.update { it.copy(isError = true, message = "먼저 VIN을 입력해 주세요") }
            return
        }
        val cleaned = address.trim().uppercase()
        if (!Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}").matches(cleaned)) {
            _uiState.update { it.copy(isError = true, message = "주소 형식이 올바르지 않아요 (예 AA:BB:CC:11:22:33)") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, message = "주소로 직접 연결 중…", isError = false) }
            container.settingsStore.setVin(vin)
            container.useRealVehicle()

            val result = container.gateway.connectDirect(vin, cleaned)
            // update 람다는 경합 시 재실행된다 — 부수효과(코루틴 기동)는 밖에서 한 번만
            if (result.isSuccess) {
                container.scanner.bondedTesla()?.let { t ->
                    launch { container.settingsStore.setVehicleName(t.name) }
                }
            }
            _uiState.update {
                if (result.isSuccess) {
                    it.copy(step = PairingStep.TapCard, isBusy = false,
                        message = "연결됐어요!\n이제 앱 키를 등록할게요")
                } else {
                    it.copy(isBusy = false, isError = true,
                        message = result.exceptionOrNull()?.message ?: "직접 연결에 실패했어요")
                }
            }
        }
    }

    /**
     * 폰에 이미 페어링된 기기를 읽어 로그에 남긴다.
     *
     * 스캔이 아니라 저장된 목록이라 차가 없어도 집에서도 읽힌다.
     * 차에 지은 별칭이 여기 그대로 들어 있는지 확인하는 용도다.
     */
    fun loadBonded() {
        viewModelScope.launch {
            val bonded = container.scanner.bondedDevices()
            com.wemade.teslable.DiagLog.add("페어링된 기기 ${bonded.size}대:")
            bonded.forEach {
                com.wemade.teslable.DiagLog.add("  ${it.name} · ${it.address} uuids=${it.uuids}")
            }
            _uiState.update {
                it.copy(
                    // 신호값은 페어링 목록에 없다. 0으로 두고 이름만 보여준다
                    nearby = bonded.map { device ->
                        NearbyDevice(device.name, 0, device.name.contains("tesla", ignoreCase = true))
                    },
                    isError = true,
                    message = "페어링된 기기 ${bonded.size}대를 로그에 남겼어요.\n복사해서 보내 주세요",
                )
            }
        }
    }

    /**
     * 앱 공개키 등록을 요청하고, 카드키 태그가 실제로 승인될 때까지 기다린다.
     *
     * 요청 전송은 시작일 뿐이다 — 예전엔 전송 성공을 완료로 쳐서
     * 태그 안내가 뜨기도 전에 화면이 넘어가 버렸다.
     * 승인 전에는 차량이 이 키의 세션을 거부하므로, 세션이 서는 순간이 곧 완료다.
     */
    fun requestEnrollment() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isBusy = true, message = "차량에 키 등록을 요청하는 중…", isError = false)
            }

            val request = container.gateway.requestKeyEnrollment()
            if (request.isFailure) {
                _uiState.update {
                    it.copy(isBusy = false, isError = true,
                        message = request.exceptionOrNull()?.message ?: "등록 요청에 실패했어요")
                }
                return@launch
            }

            // 1. 태그를 기다리며 몇 초마다 승인 여부를 차량에 물어본다
            repeat(TAP_WAIT_TRIES) { attempt ->
                _uiState.update {
                    it.copy(
                        isBusy = true, isError = false,
                        message = "센터콘솔에 카드키를 대고, 차량 화면에서 승인을 눌러 주세요 " +
                            "(${attempt * TAP_POLL_SECONDS}초 경과)",
                    )
                }
                kotlinx.coroutines.delay(TAP_POLL_SECONDS * 1000L)

                if (container.gateway.verifyKeyEnrollment().isSuccess) {
                    // 2. 차량이 이 키를 받아들였다. 여기가 진짜 등록 완료다
                    container.settingsStore.setEnrolled(true)
                    _uiState.update {
                        it.copy(step = PairingStep.Done, isBusy = false,
                            message = "등록이 확인됐어요.\n이제 매크로가 동작해요")
                    }
                    return@launch
                }
            }

            _uiState.update {
                it.copy(isBusy = false, isError = true,
                    message = "카드키 승인을 확인하지 못했어요.\n카드를 다시 대고 한 번 더 시도해 주세요")
            }
        }
    }

    private companion object {
        // 진단 스캔은 넉넉히. 광고 주기가 길면 짧은 창에서 놓친다
        const val DIAG_SCAN_MS = 12_000L

        // 카드키 태그 대기: 3초 간격 × 30회 = 90초. 카드 꺼내는 시간까지 넉넉히
        const val TAP_POLL_SECONDS = 3
        const val TAP_WAIT_TRIES = 30

        // 페어링 폴백은 가장 그럴듯한 후보 몇 대만. 한 대에 최대 30초라 늘리면 하세월이다
        const val MAX_BONDED_TRIES = 2
    }
}
