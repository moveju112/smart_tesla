package com.wemade.teslable

import android.annotation.SuppressLint
import android.os.Build
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.SystemClock
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/** 폰에 이미 페어링된 기기 하나 */
data class BondedDevice(
    val name: String,
    val address: String,
    val uuids: List<String>,
)

/** 스캔으로 찾은 차량 1대 */
data class DiscoveredVehicle(
    val device: BluetoothDevice,
    val localName: String,
    val rssi: Int,
    /** 테슬라 차량 서비스 UUID를 광고에 싣고 있는가. 이름보다 확실한 증거다 */
    val hasTeslaService: Boolean = false,
    /** GATT 접속을 받는 기기인가. 방송만 하는 비콘에 붙어봐야 시간만 버린다 */
    val isConnectable: Boolean = true,
    /** 광고 내용 요약(서비스·제조사 ID). 정체를 가릴 때 로그로 쓴다 */
    val advSummary: String = "",
)

/**
 * 테슬라 차량을 BLE로 찾는다.
 *
 * 테파일럿처럼 시스템 필터 없이 BALANCED 모드로 legacy와 extended를 차례로 훑는다.
 * 여러 스캔을 동시에 열면 일부 삼성 기기에서 시작은 성공하지만 결과가 0건으로 끝난다.
 */
class TeslaBleScanner(context: Context) {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter

    val isBluetoothReady: Boolean get() = adapter?.isEnabled == true

    /** 저장해 둔 주소로 기기 핸들을 만든다. 스캔 없이 바로 접속할 때 쓴다 */
    fun deviceOf(address: String): BluetoothDevice? =
        runCatching { adapter?.getRemoteDevice(address) }.getOrNull()

    /**
     * 이미 폰에 페어링된 기기 목록. **스캔이 아니라 저장된 목록**이라
     * 차가 없어도, 집에서도, 인터넷 없이도 읽힌다.
     *
     * 차에 지은 별칭(예 "Tesla Model Y Why")이 대소문자 그대로 여기 들어 있다.
     * 호출 전에 BLUETOOTH_CONNECT 권한이 있어야 이름이 나온다.
     */
    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<BondedDevice> =
        adapter?.bondedDevices.orEmpty().map { device ->
            BondedDevice(
                name = runCatching { device.name }.getOrNull() ?: "(이름 없음)",
                address = device.address,
                uuids = device.uuids?.map { it.uuid.toString().take(8) }.orEmpty(),
            )
        }

    /** 페어링 목록에서 테슬라로 보이는 기기. 별칭으로 찾는다 */
    fun bondedTesla(): BondedDevice? =
        bondedDevices().firstOrNull { it.name.contains("tesla", ignoreCase = true) }

    /**
     * 해당 VIN의 차량이 보일 때마다 방출한다. 수집을 멈추면 스캔도 멈춘다.
     * 호출 전에 BLUETOOTH_SCAN 권한을 확보해야 한다.
     */
    fun scan(vin: String): Flow<DiscoveredVehicle> {
        val targetNames = TeslaBleSpec.bleLocalNames(vin)
        // 이름이 맞으면 확정, 아니어도 테슬라 서비스를 광고하면 후보로 흘린다.
        // 신형 펌웨어가 이름 규칙을 바꿔도 서비스 UUID는 프로토콜이라 못 바꾼다
        return rawScan { name, hasTeslaService ->
            targetNames.any { name.equals(it, ignoreCase = true) } || hasTeslaService
        }
    }

    /**
     * 주변 BLE 기기를 **하나도 거르지 않고** 전부 훑는다. 진단용이다.
     *
     * 테슬라만 걸러 보여주면 "스캔이 안 도는 것"과 "차가 안 뿌리는 것"을 구분할 수 없다.
     * 다른 기기가 잡히는데 차만 없다면 문제는 차 쪽이다.
     */
    fun scanNearby(): Flow<DiscoveredVehicle> = rawScan { _, _ -> true }

    /** 이름이 테파일럿에서 확인한 "S…[CDRP]" 18자 꼴인가 */
    fun isTeslaNamePattern(name: String): Boolean =
        name.length == 18 && name.startsWith("S") && name.last() in "CDRP" &&
            name.substring(1, 17).all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }

    /** 앱 전체에서 스캔을 하나씩만 열고 legacy → extended 순서로 실행한다 */
    private fun rawScan(
        accept: (name: String, hasTeslaService: Boolean) -> Boolean,
    ): Flow<DiscoveredVehicle> = flow {
        scanMutex.lock()
        try {
            val elapsed = SystemClock.elapsedRealtime() - lastScanStartedAt
            val cooldown = (SCAN_COOLDOWN_MS - elapsed).coerceAtLeast(0L)
            if (lastScanStartedAt > 0L && cooldown > 0L) {
                DiagLog.add("BLE 스캔 재시도 제한 — ${cooldown / 1000 + 1}초 대기")
                delay(cooldown)
            }
            lastScanStartedAt = SystemClock.elapsedRealtime()

            ScanMode.entries.forEach { mode ->
                withTimeoutOrNull(MODE_SCAN_MS) {
                    scanMode(mode, accept).collect { emit(it) }
                }
            }
        } finally {
            scanMutex.unlock()
        }
    }

    /** 테파일럿과 같은 설정으로 한 광고 방식만 스캔한다 */
    @SuppressLint("MissingPermission")
    private fun scanMode(
        mode: ScanMode,
        accept: (name: String, hasTeslaService: Boolean) -> Boolean,
    ): Flow<DiscoveredVehicle> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
            ?: run {
                close(IllegalStateException("블루투스를 사용할 수 없다"))
                return@callbackFlow
            }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0L)
            .apply {
                if (mode == ScanMode.EXTENDED) {
                    setLegacy(false)
                    setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                }
            }
            .build()

        val teslaSeen = mutableSetOf<String>()

        val handle = { result: ScanResult ->
            val record = result.scanRecord
            // 테슬라 서비스 UUID는 세 곳 중 아무 데나 실려 올 수 있다 — 검증된 참고 앱(테파일럿)이
            // 셋 다 본다. 광고 UUID만 보면 solicitation/service-data로만 광고하는 차를 놓친다
            val hasService = listOfNotNull(
                record?.serviceUuids,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) record?.serviceSolicitationUuids else null,
                record?.serviceData?.keys?.toList(),
            ).any { uuids -> uuids.any { it.uuid == TeslaBleSpec.SERVICE_UUID } }

            // 이름은 광고 패킷에 없을 수 있다. 기기 캐시 이름, 끝내 없으면 주소로 대신한다
            val name = record?.deviceName
                ?: runCatching { result.device.name }.getOrNull()
                ?: runCatching { result.device.address }.getOrNull()

            if (name != null) {
                // accept 필터 전에, 테슬라형 광고면 무조건 로그로 남긴다.
                // 어느 광고 방식으로 들어왔는지가 스캔 문제 진단의 핵심 증거다
                if ((isTeslaNamePattern(name) || hasService) && teslaSeen.add(result.device.address)) {
                    DiagLog.add("★수신(${mode.label}) $name · ${result.device.address} · ${result.rssi}dBm" +
                        (if (hasService) " svc:00000211" else ""))
                }

                if (accept(name, hasService)) {
                    val services = record?.serviceUuids
                        ?.joinToString(",") { it.uuid.toString().take(8) }.orEmpty()
                    val makers = record?.manufacturerSpecificData?.let { data ->
                        (0 until data.size()).joinToString(",") { "0x%04x".format(data.keyAt(it)) }
                    }.orEmpty()
                    val summary = buildString {
                        if (services.isNotEmpty()) append("svc=[$services]")
                        if (makers.isNotEmpty()) {
                            if (isNotEmpty()) append(" ")
                            append("mfr=[$makers]")
                        }
                    }
                    trySend(
                        DiscoveredVehicle(
                            device = result.device,
                            localName = name,
                            rssi = result.rssi,
                            hasTeslaService = hasService,
                            isConnectable = result.isConnectable,
                            advSummary = summary,
                        )
                    )
                }
            }
            Unit
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(handle)
            }

            override fun onScanFailed(errorCode: Int) {
                val message = "BLE 스캔 실패 code=$errorCode (${mode.label})"
                DiagLog.add(message)
                close(IOException(message))
            }
        }

        DiagLog.add("BLE 스캔 시작 (${mode.label}, balanced, unfiltered)")
        runCatching { scanner.startScan(emptyList(), settings, callback) }
            .onFailure {
                DiagLog.add("BLE 스캔 시작 불가(${mode.label}): ${it.message}")
                close(it)
            }

        awaitClose {
            runCatching { scanner.stopScan(callback) }
        }
    }

    private enum class ScanMode(val label: String) {
        LEGACY("legacy"),
        EXTENDED("extended"),
    }

    private companion object {
        const val MODE_SCAN_MS = 7_500L
        const val SCAN_COOLDOWN_MS = 8_000L
        val scanMutex = Mutex()

        @Volatile
        var lastScanStartedAt = 0L
    }
}
