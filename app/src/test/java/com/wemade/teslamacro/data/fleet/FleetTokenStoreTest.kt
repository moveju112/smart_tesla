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

    /** 실제 Keystore 접근 없이 동일 파일/키로 재생성해 암호문 저장·복원·교체·삭제를 검증한다. */
    @Test
    fun `credential persists encrypted and can be replaced and deleted`() {
        val file = File(temporary.root, "token.enc")
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = FleetTokenStore(AtomicFile(file)) { key }
        assertFalse(store.hasToken())
        store.save("dummy-first-token")
        assertTrue(store.hasToken())
        assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("dummy-first-token"))
        val restored = FleetTokenStore(AtomicFile(file)) { key }
        assertEquals("dummy-first-token", restored.read())
        restored.save(" dummy-second-token ")
        assertEquals("dummy-second-token", store.read())
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
        store.save("dummy-valid-token")
        assertThrows(IllegalArgumentException::class.java) { store.save("invalid\r\nheader") }
        assertEquals("dummy-valid-token", store.read())
        file.writeBytes(byteArrayOf(1, 2, 3))
        val error = assertThrows(IllegalStateException::class.java) { store.read() }
        assertFalse(error.message.orEmpty().contains("dummy-valid-token"))
    }
}
