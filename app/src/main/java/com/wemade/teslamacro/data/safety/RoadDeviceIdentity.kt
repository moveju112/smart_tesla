package com.wemade.teslamacro.data.safety

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** 기기 개인키는 Android Keystore에만 두고 서버 서명 인증서만 백업 제외 파일에 남긴다. */
internal class RoadDeviceIdentity internal constructor(
    private val certificateFile: AtomicFile,
    private val keyProvider: () -> KeyPair,
) {
    constructor(context: Context) : this(AtomicFile(File(context.noBackupFilesDir, "road-device-cert.txt")), ::deviceKey)

    /** 기기 공개키만 가입 요청으로 보내며 개인키는 복사하지 않는다. */
    @Synchronized
    fun publicKey(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(keyProvider().public.encoded)

    /** 서버가 확인하는 인증서·시각·nonce를 정확히 같은 바이트로 묶어 서명한다. */
    @Synchronized
    fun sign(certificate: String, timestamp: Long, nonce: String): String {
        val message = "road-session-v1\n$certificate\n$timestamp\n$nonce".toByteArray(Charsets.US_ASCII)
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(keyProvider().private)
        signature.update(message)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign())
    }

    /** 인증서는 비밀이 아니지만 재설치 때 다른 개인키와 섞이지 않게 백업하지 않는다. */
    @Synchronized
    fun readCertificate(): String? = runCatching {
        if (!certificateFile.baseFile.exists()) return null
        certificateFile.readFully().toString(Charsets.US_ASCII).takeIf { it.length in 1..1024 && it.startsWith("dc1.") }
    }.getOrNull()

    /** 쓰기 실패 시 이전 인증서를 복구해 다음 주행에서 다시 발급할 수 있게 한다. */
    @Synchronized
    fun saveCertificate(certificate: String) {
        require(certificate.length in 1..1024 && certificate.startsWith("dc1."))
        val output = certificateFile.startWrite()
        try {
            output.write(certificate.toByteArray(Charsets.US_ASCII))
            certificateFile.finishWrite(output)
        } catch (error: Exception) {
            certificateFile.failWrite(output)
            throw error
        }
    }

    /** 기기 키 손실·인증서 만료 때 기존 인증서만 버리고 새 가입을 허용한다. */
    @Synchronized
    fun clearCertificate() { certificateFile.delete() }

    private companion object {
        const val ALIAS = "smart_tesla.road_device.v1"

        /** 별도 로그인 없이 기기별로 구분하되 개인키가 백업·APK에 담기지 않게 한다. */
        fun deviceKey(): KeyPair {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val privateKey = store.getKey(ALIAS, null)
            val publicKey = store.getCertificate(ALIAS)?.publicKey
            if (privateKey != null && publicKey != null) return KeyPair(publicKey, privateKey as java.security.PrivateKey)
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            generator.initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build())
            return generator.generateKeyPair()
        }
    }
}
