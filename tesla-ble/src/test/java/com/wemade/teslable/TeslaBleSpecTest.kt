package com.wemade.teslable

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeslaBleSpecTest {

    /** 공식 문서에 박혀 있는 유일한 정답 벡터. 여기가 틀리면 차를 영원히 못 찾는다 */
    @Test
    fun `공식 예시 VIN이 문서와 같은 광고 이름을 만든다`() {
        assertEquals("S1a87a5a75f3df858C", TeslaBleSpec.bleLocalName("5YJS0000000000000"))
    }

    @Test
    fun `테파일럿과 같은 접미사 후보를 순서대로 만든다`() {
        assertEquals(
            listOf(
                "S1a87a5a75f3df858C",
                "S1a87a5a75f3df858D",
                "S1a87a5a75f3df858R",
                "S1a87a5a75f3df858P",
            ),
            TeslaBleSpec.bleLocalNames("5YJS0000000000000"),
        )
    }

    @Test
    fun `소문자 VIN도 같은 결과를 낸다`() {
        assertEquals(
            TeslaBleSpec.bleLocalName("5YJS0000000000000"),
            TeslaBleSpec.bleLocalName("  5yjs0000000000000  "),
        )
    }

    @Test
    fun `잘못된 VIN은 거른다`() {
        assertFalse(TeslaBleSpec.isValidVin("5YJS000000000000"))   // 16자
        assertFalse(TeslaBleSpec.isValidVin("5YJS00000000000000")) // 18자
        assertFalse(TeslaBleSpec.isValidVin("5YJI0000000000000"))  // I 금지
        assertTrue(TeslaBleSpec.isValidVin("5YJS0000000000000"))
    }
}

class BleFramingTest {

    @Test
    fun `길이 헤더를 붙이고 청크로 자른다`() {
        val chunks = BleFraming.frame(byteArrayOf(1, 2, 3, 4), chunkSize = 3)
        // [0,4,1,2] [3,4] — 헤더 2바이트 + 본문 4바이트 = 6바이트를 3씩
        assertEquals(2, chunks.size)
        assertArrayEquals(byteArrayOf(0, 4, 1), chunks[0])
        assertArrayEquals(byteArrayOf(2, 3, 4), chunks[1])
    }

    @Test
    fun `쪼개진 청크를 원래 메시지로 되돌린다`() {
        val payload = ByteArray(300) { (it % 251).toByte() }
        val reassembler = BleFraming.Reassembler()

        var restored: ByteArray? = null
        BleFraming.frame(payload, chunkSize = 20).forEach { chunk ->
            reassembler.push(chunk)?.let { restored = it }
        }

        assertArrayEquals(payload, restored)
    }

    @Test
    fun `한 청크에 두 메시지가 붙어 와도 순서대로 뱉는다`() {
        val reassembler = BleFraming.Reassembler()
        val merged = BleFraming.frame(byteArrayOf(9), 100).first() +
            BleFraming.frame(byteArrayOf(8), 100).first()

        // 첫 push에서 첫 메시지가 완성되고, 나머지는 버퍼에 남는다
        assertArrayEquals(byteArrayOf(9), reassembler.push(merged))
        assertArrayEquals(byteArrayOf(8), reassembler.push(ByteArray(0)))
    }

    @Test
    fun `비정상 길이 헤더는 버퍼를 비우고 무시한다`() {
        val reassembler = BleFraming.Reassembler()
        assertNull(reassembler.push(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 1, 2)))
        // 버퍼가 비워졌으므로 이어지는 정상 메시지는 정상 파싱된다
        assertArrayEquals(byteArrayOf(7), reassembler.push(BleFraming.frame(byteArrayOf(7), 100).first()))
    }
}

/**
 * 세션 정보 검증 — 실패 사유를 갈라서 말하는지.
 *
 * 실차에서 재연결마다 "차량 공개키가 올바르지 않다"가 찍혔는데,
 * 실제로는 깨는 중인 차가 공개키 자리를 비워 보낸 것이었다.
 * 두 경우가 같은 문구로 나오면 등록이 깨진 줄 알고 엉뚱한 데를 판다.
 */
class SessionInfoDiagnosisTest {

    private fun session(): com.wemade.teslable.session.DomainSession {
        // 검증이 공개키 단계에서 먼저 걸리므로 키 쌍은 아무거나면 된다
        val pair = java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        return com.wemade.teslable.session.DomainSession(
            domain = com.tesla.generated.universalmessage.UniversalMessage.Domain
                .DOMAIN_VEHICLE_SECURITY,
            vin = "5YJS0000000000000",
            clientKey = com.wemade.teslable.crypto.ClientKey(
                privateKey = pair.private,
                publicKey = pair.public,
                isHardwareBacked = false,
            ),
        )
    }

    @Test
    fun `공개키가 비면 깨는 중이라고 말한다`() {
        val info = com.tesla.generated.signatures.Signatures.SessionInfo.newBuilder().build()
        val reason = session().applySessionInfo(
            sessionInfoBytes = info.toByteArray(),
            tagFromVehicle = ByteArray(32),
            challenge = ByteArray(16),
        )
        assertEquals(com.wemade.teslable.session.REASON_VEHICLE_NOT_READY, reason)
    }

    @Test
    fun `공개키가 쓰레기면 해석 실패라고 말한다`() {
        val info = com.tesla.generated.signatures.Signatures.SessionInfo.newBuilder()
            .setPublicKey(com.google.protobuf.ByteString.copyFrom(ByteArray(9) { 1 }))
            .build()
        val reason = session().applySessionInfo(
            sessionInfoBytes = info.toByteArray(),
            tagFromVehicle = ByteArray(32),
            challenge = ByteArray(16),
        )
        assertEquals("차량 공개키를 해석하지 못했다 (9바이트)", reason)
    }
}
