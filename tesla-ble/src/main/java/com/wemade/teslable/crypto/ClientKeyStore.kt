package com.wemade.teslable.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec

/**
 * 앱의 클라이언트 키를 관리한다. 이 키가 곧 차량 접근 권한이다.
 *
 * 가능하면 **Android Keystore**에 두어 개인키가 앱 밖으로 나오지 않게 한다.
 * ECDH(PURPOSE_AGREE_KEY)는 API 31부터 지원하므로 그 아래에서는 소프트웨어 키로 떨어진다.
 *
 * ponytail: API 30 이하 소프트웨어 키는 파일 평문 저장이다.
 *           해당 기기를 실제로 쓸 거면 EncryptedFile 또는 Keystore AES 래핑으로 올려야 한다.
 */
class ClientKeyStore(private val context: Context) {

    private val softwareKeyFile = File(context.filesDir, "client_key.bin")

    /** 키가 없으면 만들고, 있으면 불러온다 */
    fun loadOrCreate(): ClientKey =
        if (supportsHardwareAgreement) loadOrCreateHardware() else loadOrCreateSoftware()

    /** 키를 폐기한다. 차량 쪽 키 목록은 별도로 지워야 한다 */
    fun delete() {
        runCatching { androidKeyStore().deleteEntry(ALIAS) }
        softwareKeyFile.delete()
    }

    val exists: Boolean
        get() = if (supportsHardwareAgreement) {
            runCatching { androidKeyStore().containsAlias(ALIAS) }.getOrDefault(false)
        } else {
            softwareKeyFile.exists()
        }

    // ---- 하드웨어 경로 (API 31+) ----

    private fun loadOrCreateHardware(): ClientKey {
        val keyStore = androidKeyStore()
        if (!keyStore.containsAlias(ALIAS)) generateHardwareKey()

        val entry = keyStore.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
        return ClientKey(
            privateKey = entry.privateKey,
            publicKey = entry.certificate.publicKey,
            isHardwareBacked = true,
        )
    }

    private fun generateHardwareKey() {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
            .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        generator.initialize(spec)
        generator.generateKeyPair()
    }

    // ---- 소프트웨어 경로 (API 26~30) ----

    private fun loadOrCreateSoftware(): ClientKey {
        val scalar = if (softwareKeyFile.exists()) {
            BigInteger(1, softwareKeyFile.readBytes())
        } else {
            generateSoftwareScalar().also { softwareKeyFile.writeBytes(it.toByteArray()) }
        }
        val privateKey = SessionCrypto.privateKeyFromScalar(scalar)
        return ClientKey(
            privateKey = privateKey,
            publicKey = derivePublicKey(scalar),
            isHardwareBacked = false,
        )
    }

    private fun generateSoftwareScalar(): BigInteger {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(CURVE), SecureRandom())
        val pair = generator.generateKeyPair()
        return (pair.private as java.security.interfaces.ECPrivateKey).s
    }

    /** 스칼라에서 공개키를 복원한다 (G * scalar) */
    private fun derivePublicKey(scalar: BigInteger): PublicKey {
        val params = SessionCrypto.p256Params()
        val point = EcMath.multiplyGenerator(params, scalar)
        return SessionCrypto.decodePublicKey(EcMath.encodePoint(point))
    }

    private fun androidKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private val supportsHardwareAgreement: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "tesla_client_key"
        const val CURVE = "secp256r1"
    }
}

/** 차량 인증에 쓰는 클라이언트 키 한 쌍 */
class ClientKey(
    val privateKey: PrivateKey,
    val publicKey: PublicKey,
    val isHardwareBacked: Boolean,
) {
    /** 차량에 등록할 때 보내는 65바이트 비압축 인코딩 */
    val encodedPublicKey: ByteArray by lazy { SessionCrypto.encodePublicKey(publicKey) }

    /** 차량 공개키와 합의한 128비트 세션키 */
    fun sharedKeyWith(vehiclePublicKey: ByteArray): ByteArray =
        SessionCrypto.deriveSharedKey(privateKey, SessionCrypto.decodePublicKey(vehiclePublicKey))
}
