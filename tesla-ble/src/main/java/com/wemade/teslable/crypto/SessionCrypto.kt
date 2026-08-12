package com.wemade.teslable.crypto

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.math.BigInteger
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.interfaces.ECPublicKey
import java.security.spec.ECParameterSpec

/**
 * 세션 암호 연산 (protocol.md "Key Agreement" ~ "Response decryption").
 *
 * 규격 그대로만 구현한다. 임의로 바꾸면 조용히 인증이 깨진다.
 */
object SessionCrypto {

    private const val GCM_TAG_BITS = 128
    const val NONCE_SIZE = 12
    const val TAG_SIZE = 16

    /**
     * 공유키 K = SHA1(BIG_ENDIAN(Sx, 32))[:16]
     * SHA1을 쓰는 건 규격이 그래서다. 여기서 SHA256으로 "개선"하면 차가 거부한다.
     */
    fun deriveSharedKey(clientPrivate: PrivateKey, vehiclePublic: PublicKey): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(clientPrivate)
        agreement.doPhase(vehiclePublic, true)
        val sharedX = agreement.generateSecret()   // JCA는 Sx만 32바이트로 돌려준다
        return MessageDigest.getInstance("SHA-1").digest(sharedX).copyOf(16)
    }

    /** 세션 정보 응답 검증용 파생키 */
    fun sessionInfoKey(sharedKey: ByteArray): ByteArray =
        hmacSha256(sharedKey, "session info".toByteArray(Charsets.US_ASCII))

    /** HMAC 인증 명령용 파생키 (Fleet API 경로에서 쓴다) */
    fun authenticatedCommandKey(sharedKey: ByteArray): ByteArray =
        hmacSha256(sharedKey, "authenticated command".toByteArray(Charsets.US_ASCII))

    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /** AES-GCM 암호화. AAD는 SHA256(메타데이터)다 */
    fun encrypt(
        sharedKey: ByteArray,
        nonce: ByteArray,
        metadata: ByteArray,
        plaintext: ByteArray,
    ): EncryptedPayload {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(sharedKey, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        cipher.updateAAD(sha256(metadata))
        val combined = cipher.doFinal(plaintext)
        // JCA는 [ciphertext || tag]로 붙여서 준다. 프로토콜은 둘을 따로 실어야 한다
        return EncryptedPayload(
            ciphertext = combined.copyOf(combined.size - TAG_SIZE),
            tag = combined.copyOfRange(combined.size - TAG_SIZE, combined.size),
        )
    }

    fun decrypt(
        sharedKey: ByteArray,
        nonce: ByteArray,
        metadata: ByteArray,
        ciphertext: ByteArray,
        tag: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(sharedKey, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        cipher.updateAAD(sha256(metadata))
        return cipher.doFinal(ciphertext + tag)
    }

    /** HMAC 태그 비교는 반드시 상수 시간으로 한다 (타이밍 공격 방지) */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    /**
     * 비압축 곡선점(0x04 || X || Y) 65바이트를 P-256 공개키로 되돌린다.
     * 차량이 세션 정보로 보내주는 형식이 이것뿐이다.
     */
    fun decodePublicKey(uncompressed: ByteArray): PublicKey {
        require(uncompressed.size == 65 && uncompressed[0] == 0x04.toByte()) {
            "비압축 P-256 공개키(65바이트)가 아니다"
        }
        val x = BigInteger(1, uncompressed.copyOfRange(1, 33))
        val y = BigInteger(1, uncompressed.copyOfRange(33, 65))
        val factory = KeyFactory.getInstance("EC")
        return factory.generatePublic(ECPublicKeySpec(ECPoint(x, y), p256Params()))
    }

    /** 공개키를 0x04 || X || Y 65바이트로 인코딩한다 */
    fun encodePublicKey(publicKey: PublicKey): ByteArray {
        val point = (publicKey as ECPublicKey).w
        val out = ByteArray(65)
        out[0] = 0x04
        padTo32(point.affineX).copyInto(out, 1)
        padTo32(point.affineY).copyInto(out, 33)
        return out
    }

    private fun padTo32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        // BigInteger는 부호 비트 때문에 앞에 0x00을 붙이거나 32바이트보다 짧을 수 있다
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32).also { raw.copyInto(it, 32 - raw.size) }
        }
    }

    /** 원시 스칼라에서 P-256 개인키를 만든다 (소프트웨어 키 복원·테스트 벡터용) */
    fun privateKeyFromScalar(scalar: BigInteger): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(scalar, p256Params()))

    /** P-256 도메인 파라미터를 표준 API로 얻는다 (하드코딩 금지) */
    fun p256Params(): ECParameterSpec {
        val generated = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(P256_SAMPLE_SPKI)) as ECPublicKey
        return generated.params
    }

    /**
     * P-256 파라미터를 뽑아내기 위한 샘플 공개키(SPKI).
     * protocol.md의 공개 테스트 벡터라 비밀이 아니다.
     */
    private val P256_SAMPLE_SPKI: ByteArray = byteArrayOf(
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(),
        0x3d, 0x02, 0x01, 0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d,
        0x03, 0x01, 0x07, 0x03, 0x42, 0x00, 0x04,
        0xc7.toByte(), 0xa1.toByte(), 0xf4.toByte(), 0x71, 0x38, 0x48, 0x6a, 0xa4.toByte(),
        0x72, 0x99.toByte(), 0x71, 0x49, 0x48, 0x78, 0xd3.toByte(), 0x3b,
        0x1a, 0x24, 0xe3.toByte(), 0x95.toByte(), 0x71, 0xf7.toByte(), 0x48, 0xa6.toByte(),
        0xe1.toByte(), 0x6c, 0x59, 0x55, 0xb3.toByte(), 0xd8.toByte(), 0x77, 0xd3.toByte(),
        0xa6.toByte(), 0xaa.toByte(), 0xa0.toByte(), 0xe9.toByte(), 0x55, 0x16, 0x64, 0x74,
        0xaf.toByte(), 0x5d, 0x32, 0xc4.toByte(), 0x10, 0xf4.toByte(), 0x39, 0xa2.toByte(),
        0x23, 0x41, 0x37, 0xad.toByte(), 0x1b, 0xb0.toByte(), 0x85.toByte(), 0xfd.toByte(),
        0x4e, 0x88.toByte(), 0x13, 0xc9.toByte(), 0x58, 0xf1.toByte(), 0x1d, 0x97.toByte(),
    )
}

data class EncryptedPayload(val ciphertext: ByteArray, val tag: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is EncryptedPayload &&
            ciphertext.contentEquals(other.ciphertext) &&
            tag.contentEquals(other.tag)

    override fun hashCode(): Int = 31 * ciphertext.contentHashCode() + tag.contentHashCode()
}
