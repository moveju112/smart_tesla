package com.wemade.teslamacro.data.fleet

import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

class FleetTokenCipherTest {
    /** 암호문에 평문을 남기지 않고 동일 토큰도 매번 다른 IV를 사용한다. */
    @Test
    fun `token ciphertext is randomized and round trips`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val token = "dummy-user-api-token-not-a-real-secret"
        val first = FleetTokenCipher.encrypt(token, key)
        val second = FleetTokenCipher.encrypt(token, key)
        assertFalse(first.contentEquals(second))
        assertFalse(first.toString(Charsets.UTF_8).contains(token))
        assertEquals(token, FleetTokenCipher.decrypt(first, key))
        assertEquals(token, FleetTokenCipher.decrypt(second, key))
    }

    /** 저장파일 변조·다른 키·지원하지 않는 버전을 모두 거부한다. */
    @Test
    fun `tampered ciphertext and wrong key fail authentication`() {
        val generator = KeyGenerator.getInstance("AES").apply { init(256) }
        val key = generator.generateKey()
        val encrypted = FleetTokenCipher.encrypt("dummy-api-token", key)
        val tampered = encrypted.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { FleetTokenCipher.decrypt(tampered, key) }
        assertThrows(Exception::class.java) { FleetTokenCipher.decrypt(encrypted, generator.generateKey()) }
        assertThrows(IllegalArgumentException::class.java) { FleetTokenCipher.decrypt(byteArrayOf(2), key) }
    }
}
