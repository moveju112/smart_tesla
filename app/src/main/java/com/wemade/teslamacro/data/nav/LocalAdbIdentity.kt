package com.wemade.teslamacro.data.nav

import android.util.AtomicFile
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** 무선 페어링 키는 목적지 인증 키와 분리하고 앱 전용 백업 제외 영역에 보관한다. */
internal class LocalAdbIdentity(private val file: File) {
    /** RSA 개인키를 요구하는 ADB 통신용 키와 인증서를 한 파일로 원자 저장한다. */
    fun load(): Pair<PrivateKey, Certificate> {
        val store = KeyStore.getInstance("PKCS12")
        val password = CharArray(0)
        val atomic = AtomicFile(file)
        if (file.exists()) atomic.openRead().use { store.load(it, password) }
        else {
            store.load(null, password)
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val certificate = certificate(pair.private, pair.public.encoded)
            store.setKeyEntry("adb", pair.private, password, arrayOf(certificate))
            val output = atomic.startWrite()
            try {
                store.store(output, password)
                atomic.finishWrite(output)
            } catch (error: Exception) {
                atomic.failWrite(output)
                throw error
            }
        }
        return store.getKey("adb", password) as PrivateKey to store.getCertificate("adb")
    }

    companion object {
        /** 의존성 내부 인증 API에 기대지 않고 고정된 SHA256/RSA 자체 서명 인증서를 만든다. */
        internal fun certificate(key: PrivateKey, publicKey: ByteArray): Certificate {
            val algorithm = der(0x30, byteArrayOf(6, 9, 42, -122, 72, -122, -9, 13, 1, 1, 11, 5, 0))
            val name = der(0x30, der(0x31, der(0x30, byteArrayOf(6, 3, 85, 4, 3) + der(12, "Smart Tesla".toByteArray()))))
            val format = SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val now = System.currentTimeMillis()
            val validity = der(0x30, der(24, format.format(Date(now - 86_400_000)).toByteArray()) +
                der(24, format.format(Date(now + 10L * 365 * 86_400_000)).toByteArray()))
            val body = der(0x30, der(2, BigInteger.valueOf(now).toByteArray()) + algorithm + name + validity + name + publicKey)
            val signature = Signature.getInstance("SHA256withRSA").apply { initSign(key); update(body) }.sign()
            return CertificateFactory.getInstance("X.509").generateCertificate(
                der(0x30, body + algorithm + der(3, byteArrayOf(0) + signature)).inputStream())
        }

        /** 인증서의 길이를 DER 규칙으로 인코딩하며 외부 입력이나 임의 알고리즘은 받지 않는다. */
        private fun der(tag: Int, bytes: ByteArray): ByteArray {
            val length = when {
                bytes.size < 128 -> byteArrayOf(bytes.size.toByte())
                bytes.size < 256 -> byteArrayOf(0x81.toByte(), bytes.size.toByte())
                else -> byteArrayOf(0x82.toByte(), (bytes.size shr 8).toByte(), bytes.size.toByte())
            }
            return byteArrayOf(tag.toByte()) + length + bytes
        }
    }
}
