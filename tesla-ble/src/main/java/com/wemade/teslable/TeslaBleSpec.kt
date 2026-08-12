package com.wemade.teslable

import java.security.MessageDigest
import java.util.UUID

/**
 * Tesla BLE 규격 상수와 VIN 유도 로직.
 * 출처: teslamotors/vehicle-command `pkg/protocol/protocol.md`
 */
object TeslaBleSpec {

    /** 차량이 광고하는 GATT 서비스 */
    val SERVICE_UUID: UUID = UUID.fromString("00000211-b2d1-43f0-9b88-960cebf8b91e")

    /** 클라이언트 -> 차량 (write) */
    val TX_CHARACTERISTIC_UUID: UUID = UUID.fromString("00000212-b2d1-43f0-9b88-960cebf8b91e")

    /** 차량 -> 클라이언트 (notify) */
    val RX_CHARACTERISTIC_UUID: UUID = UUID.fromString("00000213-b2d1-43f0-9b88-960cebf8b91e")

    /** notify 활성화용 표준 CCCD */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** 차량이 받아들이는 단일 메시지 최대 길이 */
    const val MAX_MESSAGE_LENGTH = 1024

    /**
     * VIN에서 BLE 광고 이름을 만든다.
     * 규칙: "S" + SHA1(VIN) 앞 8바이트 소문자 hex + "C"
     * 예) 5YJS0000000000000 -> S1a87a5a75f3df858C
     */
    fun bleLocalName(vin: String): String {
        val normalized = normalizeVin(vin)
        val digest = MessageDigest.getInstance("SHA-1").digest(normalized.toByteArray(Charsets.US_ASCII))
        val hex = digest.take(8).joinToString("") { "%02x".format(it) }
        return "S${hex}C"
    }

    /** VIN 형식 검증 후 대문자로 정규화한다 (17자, I/O/Q 제외) */
    fun normalizeVin(vin: String): String {
        val upper = vin.trim().uppercase()
        require(upper.length == 17) { "VIN은 17자여야 한다 (입력 ${upper.length}자)" }
        require(upper.all { it in VIN_ALPHABET }) { "VIN에 허용되지 않는 문자가 있다" }
        return upper
    }

    /** 검증 예외 없이 유효 여부만 본다 (입력 폼 실시간 체크용) */
    fun isValidVin(vin: String): Boolean = runCatching { normalizeVin(vin) }.isSuccess

    private const val VIN_ALPHABET = "ABCDEFGHJKLMNPRSTUVWXYZ0123456789"
}
