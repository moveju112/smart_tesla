package com.wemade.teslamacro.service

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QuickActionAccessStoreTest {
    @get:Rule val folder = TemporaryFolder()

    /** 키 없는 다른 앱과 임의 키는 차량 명령을 자동 실행할 수 없다. */
    @Test
    fun `missing and invalid credentials fail closed`() {
        val access = QuickActionAccessStore(folder.root)
        assertFalse(access.isAuthorized("unlock", null, null, null))
        assertFalse(access.isAuthorized("unlock", null, "a".repeat(64), "b".repeat(64)))
        assertFalse(access.hasAutomationKey())
    }

    /** 명령·매크로 식별자를 바꾼 서명은 거부하고 발행한 요청만 수락한다. */
    @Test
    fun `shortcut signatures bind both action and macro`() {
        val access = QuickActionAccessStore(folder.root)
        val token = access.shortcutToken("open_frunk", null)
        assertTrue(access.isAuthorized("open_frunk", null, null, token))
        assertFalse(access.isAuthorized("unlock", null, null, token))
        assertFalse(access.isAuthorized("open_frunk", "macro-1", null, token))
        val macroToken = access.shortcutToken(null, "macro-1")
        assertTrue(access.isAuthorized(null, "macro-1", null, macroToken))
        assertFalse(access.isAuthorized("unlock", "macro-1", null, macroToken))
        assertFalse(access.isAuthorized(null, "macro-2", null, macroToken))
        assertFalse(access.hasAutomationKey())
    }

    /** 앱 재시작 뒤에도 발행된 바로가기는 새 키 발급 없이 실행할 수 있다. */
    @Test
    fun `published shortcuts survive app restart`() {
        val token = QuickActionAccessStore(folder.root).shortcutToken(null, "macro-1")
        assertTrue(QuickActionAccessStore(folder.root).isAuthorized(null, "macro-1", null, token))
    }

    /** 외부 키를 해제·재발급해도 내부 바로가기는 유지하고 폐기한 키는 거부한다. */
    @Test
    fun `automation revocation leaves app shortcuts usable`() {
        val access = QuickActionAccessStore(folder.root)
        val shortcut = access.shortcutToken("open_frunk", null)
        val key = access.automationKey()
        assertTrue(access.hasAutomationKey())
        assertEquals(key, access.automationKey())
        assertTrue(access.isAuthorized("unlock", null, key, null))
        access.revokeAutomationKey()
        assertFalse(access.isAuthorized("unlock", null, key, null))
        assertTrue(access.isAuthorized("open_frunk", null, null, shortcut))
        val replacement = access.automationKey()
        assertNotEquals(key, replacement)
        assertFalse(access.isAuthorized("unlock", null, key, null))
        assertTrue(access.isAuthorized("unlock", null, replacement, null))
    }

    /** 손상된 저장키나 너무 긴 입력은 자동 실행 권한으로 인정하지 않는다. */
    @Test
    fun `corrupted stored key and oversized input are rejected`() {
        val access = QuickActionAccessStore(folder.root)
        val key = access.automationKey()
        File(folder.root, "automation").writeText("invalid")
        assertFalse(access.isAuthorized("unlock", null, key, null))
        assertFalse(access.isAuthorized("unlock", null, "a".repeat(4096), null))
        assertFalse(access.hasAutomationKey())
    }
}
