package com.wemade.teslable.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * protocol.md에 실린 **공식 테스트 벡터**로 검증한다.
 *
 * 여기가 하나라도 틀리면 실차에서 인증이 깨진다.
 * 자체 판단으로 값을 바꾸지 말고, 벡터를 기준으로 구현을 고쳐야 한다.
 */
class ProtocolVectorTest {

    // protocol.md "Test keys" — 공개된 디버그용 키다. 실차에 등록하면 안 된다
    private val clientScalar = BigInteger(
        "2538CDC29A97C19C1E99A637D6CF4F8C970C118B56EDE1E6323E6D162C4B30DB", 16
    )
    private val vehiclePublicHex =
        "04c7a1f47138486aa4729971494878d33b1a24e39571f748a6e16c5955b3d877d3" +
            "a6aaa0e955166474af5d32c410f439a2234137ad1bb085fd4e8813c958f11d97"
    private val clientPublicHex =
        "04b2b6bc68c2da0665ce656815594996c62394edd8bea905fe781a754fe6a845a7" +
            "14330902f225e9269d466e05b349981fda9d85cc23c6fb444aa73b629105dc6e"

    private val vin = "5YJ30123456789ABC"

    // ---- 메타데이터 직렬화 ----

    @Test
    fun `문서 예시대로 태그 순서대로 붙이고 0xFF로 끝낸다`() {
        val serialized = Metadata.Builder()
            .putInt(Metadata.TAG_COUNTER, 100)          // 일부러 역순으로 넣는다
            .putAscii(Metadata.TAG_PERSONALIZATION, "abc")
            .build()
        assertEquals("0203616263050400000064ff", serialized.hex())
    }

    @Test
    fun `세션 정보 검증용 메타데이터가 문서와 일치한다`() {
        val serialized = Metadata.Builder()
            .putByte(Metadata.TAG_SIGNATURE_TYPE, SIGNATURE_TYPE_HMAC)
            .putAscii(Metadata.TAG_PERSONALIZATION, vin)
            .put(Metadata.TAG_CHALLENGE, "1588d5a30eabc6f8fc9a951b11f6fd11".unhex())
            .build()
        assertEquals(
            "000106021135594a333031323334353637383941424306101588d5a30eabc6f8fc9a951b11f6fd11ff",
            serialized.hex(),
        )
    }

    @Test
    fun `명령 메타데이터가 문서와 일치한다`() {
        val serialized = Metadata.Builder()
            .putByte(Metadata.TAG_SIGNATURE_TYPE, SIGNATURE_TYPE_AES_GCM_PERSONALIZED)
            .putByte(Metadata.TAG_DOMAIN, DOMAIN_INFOTAINMENT)
            .putAscii(Metadata.TAG_PERSONALIZATION, vin)
            .put(Metadata.TAG_EPOCH, "4c463f9cc0d3d26906e982ed224adde6".unhex())
            .putInt(Metadata.TAG_EXPIRES_AT, 2655)
            .putInt(Metadata.TAG_COUNTER, 7)
            .putInt(Metadata.TAG_FLAGS, 2)
            .build()
        assertEquals(
            "000105010103021135594a333031323334353637383941424303104c463f9cc0d3d26906e982ed2" +
                "24adde6040400000a5f050400000007070400000002ff",
            serialized.hex(),
        )
    }

    // ---- 키 합의 ----

    @Test
    fun `ECDH 공유키가 문서의 K와 같다`() {
        val shared = SessionCrypto.deriveSharedKey(
            clientPrivate = SessionCrypto.privateKeyFromScalar(clientScalar),
            vehiclePublic = SessionCrypto.decodePublicKey(vehiclePublicHex.unhex()),
        )
        assertEquals("1b2fce19967b79db696f909cff89ea9a", shared.hex())
    }

    @Test
    fun `공개키 인코딩이 왕복해도 같다`() {
        val decoded = SessionCrypto.decodePublicKey(clientPublicHex.unhex())
        assertEquals(clientPublicHex, SessionCrypto.encodePublicKey(decoded).hex())
    }

    @Test
    fun `세션 정보 파생키가 문서와 같다`() {
        val key = SessionCrypto.sessionInfoKey("1b2fce19967b79db696f909cff89ea9a".unhex())
        assertEquals(
            "fceb679ee7bca756fcd441bf238bf2f338629b41d9eb9c67be1b32c9672ce300",
            key.hex(),
        )
    }

    @Test
    fun `세션 정보 HMAC 태그가 문서와 같다`() {
        val sessionInfo = (
            "0806124104c7a1f47138486aa4729971494878d33b1a24e39571f748a6e16c5955b3d877d3" +
                "a6aaa0e955166474af5d32c410f439a2234137ad1bb085fd4e8813c958f11d97" +
                "1a104c463f9cc0d3d26906e982ed224adde6255a0a0000"
            ).unhex()

        val metadata = Metadata.Builder()
            .putByte(Metadata.TAG_SIGNATURE_TYPE, SIGNATURE_TYPE_HMAC)
            .putAscii(Metadata.TAG_PERSONALIZATION, vin)
            .put(Metadata.TAG_CHALLENGE, "1588d5a30eabc6f8fc9a951b11f6fd11".unhex())
            .build()

        val tag = SessionCrypto.hmacSha256(
            key = SessionCrypto.sessionInfoKey("1b2fce19967b79db696f909cff89ea9a".unhex()),
            message = metadata + sessionInfo,
        )
        assertEquals(
            "996c1fe38331be138f8039c194b14db2198846ed7d8251e6749284d7b32ea002",
            tag.hex(),
        )
    }

    // ---- AES-GCM ----

    @Test
    fun `암호화한 것을 같은 메타데이터로 복호화하면 원문이 나온다`() {
        val key = "1b2fce19967b79db696f909cff89ea9a".unhex()
        val nonce = "dbf79447fa156674dae1caed".unhex()
        val metadata = "000105010103".unhex()
        val plaintext = "120452020801".unhex()   // hvacAutoAction { power_on: true }

        val encrypted = SessionCrypto.encrypt(key, nonce, metadata, plaintext)
        assertEquals(SessionCrypto.TAG_SIZE, encrypted.tag.size)

        val decrypted = SessionCrypto.decrypt(
            key, nonce, metadata, encrypted.ciphertext, encrypted.tag
        )
        assertEquals(plaintext.hex(), decrypted.hex())
    }

    @Test
    fun `메타데이터가 한 바이트만 달라도 복호화가 실패한다`() {
        val key = "1b2fce19967b79db696f909cff89ea9a".unhex()
        val nonce = "dbf79447fa156674dae1caed".unhex()
        val encrypted = SessionCrypto.encrypt(key, nonce, "000105".unhex(), byteArrayOf(1, 2, 3))

        runCatching {
            SessionCrypto.decrypt(key, nonce, "000106".unhex(), encrypted.ciphertext, encrypted.tag)
        }.also { assertTrue("AAD가 달라도 통과하면 인증이 무의미하다", it.isFailure) }
    }

    @Test
    fun `상수시간 비교가 정상 동작한다`() {
        assertTrue(SessionCrypto.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(SessionCrypto.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(SessionCrypto.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2, 3)))
    }

    private companion object {
        const val SIGNATURE_TYPE_HMAC = 6
        const val SIGNATURE_TYPE_AES_GCM_PERSONALIZED = 5
        const val DOMAIN_INFOTAINMENT = 3
    }
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

private fun String.unhex(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()
