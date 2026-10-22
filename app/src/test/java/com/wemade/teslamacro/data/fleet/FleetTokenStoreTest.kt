package com.wemade.teslamacro.data.fleet

import android.util.AtomicFile
import app.cash.paparazzi.Paparazzi
import java.io.File
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FleetTokenStoreTest {
    @get:Rule val paparazzi = Paparazzi()
    @get:Rule val temporary = TemporaryFolder()
    private val token = "a".repeat(43)
    private val replacementToken = "b".repeat(43)

    /** 중단된 원자 쓰기의 백업만 남아도 저장 여부와 실제 토큰을 복원한다. */
    @Test
    fun `backup only credential remains available and readable`() {
        val file = File(temporary.root, "token.enc")
        val backup = File(temporary.root, "token.enc.bak")
        val key = KeyGenerator.getInstance("AES").generateKey()
        val store = FleetTokenStore(AtomicFile(file)) { key }
        store.save(token)
        assertTrue(file.renameTo(backup))
        assertTrue(store.hasToken())
        assertEquals(token, store.read())
        assertTrue(file.renameTo(backup))
        assertEquals(token, store.read())
    }

    /** 실제 Keystore 접근 없이 동일 파일/키로 재생성해 암호문 저장·복원·교체·삭제를 검증한다. */
    @Test
    fun `credential persists encrypted and can be replaced and deleted`() {
        val file = File(temporary.root, "token.enc")
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = FleetTokenStore(AtomicFile(file)) { key }
        assertFalse(store.hasToken())
        store.save(token)
        assertTrue(store.hasToken())
        assertFalse(file.readBytes().toString(Charsets.UTF_8).contains(token))
        val restored = FleetTokenStore(AtomicFile(file)) { key }
        assertEquals(token, restored.read())
        restored.save(" $replacementToken ")
        assertEquals(replacementToken, store.read())
        restored.clear()
        assertFalse(store.hasToken())
        assertThrows(IllegalStateException::class.java) { store.read() }
    }

    /** 잘못된 입력은 기존 토큰을 덮지 않으며 읽기 오류는 암호문/비밀값을 노출하지 않는다. */
    @Test
    fun `invalid replacement preserves token and tampering fails closed`() {
        val file = File(temporary.root, "token.enc")
        val key = KeyGenerator.getInstance("AES").generateKey()
        val store = FleetTokenStore(AtomicFile(file)) { key }
        store.save(token)
        assertThrows(IllegalArgumentException::class.java) { store.save("invalid\r\nheader") }
        assertEquals(token, store.read())
        file.writeBytes(byteArrayOf(1, 2, 3))
        val error = assertThrows(IllegalStateException::class.java) { store.read() }
        assertFalse(error.message.orEmpty().contains(token))
    }

    /** 서버 길이 경계와 ASCII 조건을 벗어난 교체는 저장 전 차단한다. */
    @Test fun tokenValidationMatchesServerWithoutReplacingExistingCredential() {
        val key = KeyGenerator.getInstance("AES").generateKey()
        val store = FleetTokenStore(AtomicFile(File(temporary.root, "token.enc"))) { key }
        for (length in listOf(32, 256)) {
            val valid = "x".repeat(length)
            assertTrue(isValidFleetToken(valid))
            store.save(" $valid ")
            assertEquals(valid, store.read())
        }
        for (invalid in listOf("", "x".repeat(31), "x".repeat(257), "x".repeat(8192),
            "한".repeat(32), "x".repeat(31) + "\n", "x".repeat(16) + " " + "x".repeat(16))) {
            assertFalse(isValidFleetToken(invalid))
            assertThrows(IllegalArgumentException::class.java) { store.save(invalid) }
            assertEquals("x".repeat(256), store.read())
        }
    }
}
