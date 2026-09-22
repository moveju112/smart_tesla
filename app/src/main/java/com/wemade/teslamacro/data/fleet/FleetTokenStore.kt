package com.wemade.teslamacro.data.fleet

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 키는 Android Keystore, 암호문은 noBackupFilesDir에 보관한다. 앱 설정/백업/소스에 토큰을 넣지 않는다. */
class FleetTokenStore internal constructor(private val file: AtomicFile, private val keyProvider: () -> SecretKey) {
    /** 실제 앱은 Keystore만 사용하며 내부 생성자는 암호문 파일 동작의 오프라인 검증용이다. */
    constructor(context: Context) : this(AtomicFile(File(context.noBackupFilesDir, "fleet-api-token.enc")), ::key)

    /** 저장 실패 시 기존 암호문을 복구하며 오류에 입력 토큰을 포함하지 않는다. IO 디스패처에서 호출한다. */
    @Synchronized
    fun save(token: String) {
        val value = token.trim()
        require(value.isNotEmpty() && value.length <= 8192 && value.all { it.code in 33..126 }) { "Fleet API 토큰 형식을 확인해 주세요" }
        val encrypted = FleetTokenCipher.encrypt(value, keyProvider())
        val stream = file.startWrite()
        try { stream.write(encrypted); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw IllegalStateException("Fleet API 토큰을 저장하지 못했어요") }
    }

    /** 재설치·Keystore 손실·변조는 빈 토큰으로 전송하지 않고 재입력을 요구한다. */
    @Synchronized
    fun read(): String {
        if (!file.baseFile.exists()) error("Fleet API 토큰을 먼저 등록해 주세요")
        return try { FleetTokenCipher.decrypt(file.readFully(), keyProvider()) }
        catch (_: Exception) { error("저장된 Fleet API 토큰을 읽지 못했어요 · 다시 등록해 주세요") }
    }

    /** UI에는 토큰 대신 저장 여부만 노출한다. */
    @Synchronized
    fun hasToken(): Boolean = file.baseFile.exists()

    /** 로그아웃/토큰 제거 시 암호문을 삭제한다. */
    @Synchronized
    fun clear() { file.delete() }

    private companion object {
        const val ALIAS = "smart_tesla.fleet_api_token.v1"

        /** 백그라운드 음성 명령에서 사용할 수 있게 매번 생체인증을 요구하지 않는 전용 AES 키를 만든다. */
        fun key(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
            return generator.generateKey()
        }
    }
}

/** Keystore와 분리해 암호문 변조·오키·평문 미포함을 JVM에서도 검증한다. */
internal object FleetTokenCipher {
    private val aad = "smart_tesla.fleet_api_token.v1".toByteArray(Charsets.UTF_8)

    /** 매번 새 IV로 인증 암호화하고 버전·IV·암호문만 저장한다. */
    fun encrypt(token: String, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        check(cipher.iv.size == 12)
        cipher.updateAAD(aad)
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
    }

    /** 다른 키나 수정된 암호문은 인증 태그 검사로 거부한다. */
    fun decrypt(bytes: ByteArray, key: SecretKey): String {
        require(bytes.size >= 29 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(aad)
        return cipher.doFinal(bytes.copyOfRange(13, bytes.size)).toString(Charsets.UTF_8)
    }
}
