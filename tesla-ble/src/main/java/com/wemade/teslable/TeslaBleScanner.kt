package com.wemade.teslable

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

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
 * 시스템 스캔 필터(ScanFilter)를 쓰지 않는다.
 * 차량은 서비스 UUID를 광고 패킷에, 이름을 스캔 응답 패킷에 나눠 싣는데
 * 둘을 한 필터에 묶으면 한 패킷 안에서 둘 다 찾다가 영영 못 만난다.
 * 그래서 다 받아서 코드에서 거른다. 스캔은 길어야 15초라 부담이 없다.
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
        val targetName = TeslaBleSpec.bleLocalName(vin)
        // 무필터 스캔과 별개로 하드웨어 필터 스캔도 함께 돌린다.
        // 일부 제조사(삼성 등)는 무필터 스캔을 조용히 제한하는데, 필터 스캔은 그 제한을 우회한다.
        // 단, 필터 하나에 UUID+이름을 같이 걸면 안 된다(패킷 단위 평가) — 반드시 한 필터 한 조건
        val hardwareFilters = listOf(
            android.bluetooth.le.ScanFilter.Builder()
                .setServiceUuid(android.os.ParcelUuid(TeslaBleSpec.SERVICE_UUID))
                .build(),
            android.bluetooth.le.ScanFilter.Builder()
                .setDeviceName(targetName)
                .build(),
        )
        // 이름이 맞으면 확정, 아니어도 테슬라 서비스를 광고하면 후보로 흘린다.
        // 신형 펌웨어가 이름 규칙을 바꿔도 서비스 UUID는 프로토콜이라 못 바꾼다
        return rawScan(hardwareFilters) { name, hasTeslaService ->
            name.equals(targetName, ignoreCase = true) || hasTeslaService
        }
    }

    /**
     * 주변 BLE 기기를 **하나도 거르지 않고** 전부 훑는다. 진단용이다.
     *
     * 테슬라만 걸러 보여주면 "스캔이 안 도는 것"과 "차가 안 뿌리는 것"을 구분할 수 없다.
     * 다른 기기가 잡히는데 차만 없다면 문제는 차 쪽이다.
     */
    fun scanNearby(): Flow<DiscoveredVehicle> = rawScan { _, _ -> true }

    /** 이름이 "S…C" 18자 꼴인가. 차량 광고 이름 규칙이다 */
    fun isTeslaNamePattern(name: String): Boolean =
        name.length == 18 && name.startsWith("S") && name.endsWith("C") &&
            name.substring(1, 17).all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }

    @SuppressLint("MissingPermission")
    private fun rawScan(
        hardwareFilters: List<android.bluetooth.le.ScanFilter> = emptyList(),
        accept: (name: String, hasTeslaService: Boolean) -> Boolean,
    ): Flow<DiscoveredVehicle> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
            ?: run {
                close(IllegalStateException("블루투스를 사용할 수 없다"))
                return@callbackFlow
            }

        // 광고 방식을 추측하지 않는다. 옛날식(legacy)과 확장(extended) 스캔을 둘 다 돌려
        // 어느 쪽으로 뿌리든 받는다. legacy 하나만 켜면 확장 광고를 놓치고, 그 반대도 마찬가지다.
        val legacySettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        val extendedSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setLegacy(false)
            .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            .build()

        // 테슬라형 이름을 처음 받는 순간 한 번씩만 찍는다. 광고가 콜백에 도달하는지 증거.
        // 두 스캐너가 공유하므로 동기화한다
        val teslaSeen = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        // 모든 스캐너의 결과를 한 곳에서 처리한다
        val handle = { label: String, result: ScanResult ->
            val record = result.scanRecord
            val hasService = record?.serviceUuids
                ?.any { it.uuid == TeslaBleSpec.SERVICE_UUID } == true

            // 이름은 광고 패킷에 없을 수 있다. 기기 캐시 이름, 끝내 없으면 주소로 대신한다
            val name = record?.deviceName
                ?: runCatching { result.device.name }.getOrNull()
                ?: runCatching { result.device.address }.getOrNull()

            if (name != null) {
                // accept 필터 전에, 테슬라형 광고면 무조건 로그로 남긴다.
                // 어느 경로(무필터/필터)로 들어왔는지가 스캔 문제 진단의 핵심 증거다
                if ((isTeslaNamePattern(name) || hasService) && teslaSeen.add(result.device.address)) {
                    DiagLog.add("★수신($label) $name · ${result.device.address} · ${result.rssi}dBm" +
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

        fun callbackFor(label: String) = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) { handle(label, result) }
            override fun onScanFailed(errorCode: Int) {
                // 확장 스캔은 기기가 지원 안 하면 실패할 수 있다. 로그만 남기고 계속 간다
                DiagLog.add("BLE 스캔($label) 실패 code=$errorCode")
            }
        }

        val legacyCb = callbackFor("legacy")
        val extendedCb = callbackFor("ext")
        val filteredCb = callbackFor("filtered")

        DiagLog.add("BLE 스캔 시작 (legacy+ext${if (hardwareFilters.isNotEmpty()) "+filtered" else ""})")
        // 무필터 스캔: 다 받아서 코드에서 거른다
        scanner.startScan(null, legacySettings, legacyCb)
        runCatching { scanner.startScan(null, extendedSettings, extendedCb) }
            .onFailure { DiagLog.add("확장 스캔 시작 불가: ${it.message}") }
        // 필터 스캔: 무필터를 제한하는 제조사 스택 우회용. 있을 때만 돌린다
        if (hardwareFilters.isNotEmpty()) {
            runCatching { scanner.startScan(hardwareFilters, legacySettings, filteredCb) }
                .onFailure { DiagLog.add("필터 스캔 시작 불가: ${it.message}") }
        }

        awaitClose {
            runCatching { scanner.stopScan(legacyCb) }
            runCatching { scanner.stopScan(extendedCb) }
            runCatching { scanner.stopScan(filteredCb) }
        }
    }
}
