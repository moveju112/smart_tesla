package com.wemade.teslamacro.data.poll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 장시간 단절과 주행 중 짧은 재연결을 구분하는 탑승 기록 유효시간 검증 */
class PresenceTrustTest {

    private val now = 1_000_000L
    private val trust = 300_000L

    /** 시계가 뒤로 조정되면 미래의 탑승 기록으로 새 탑승을 억제하지 않는다. */
    @Test fun futureObservationIsNotTrusted() {
        assertNull(trustedPresence(true, now + 1, now, trust))
        assertNull(trustedPresence(false, now + trust, now, trust))
        assertEquals(true, trustedPresence(true, now, now, trust))
    }

    @Test
    fun `유효시간 안의 탑승 기록은 그대로 쓴다`() {
        assertEquals(true, trustedPresence(true, now - trust + 1, now, trust))
        assertEquals(false, trustedPresence(false, now - trust + 1, now, trust))
    }

    @Test
    fun `유효시간 경계부터 오래된 탑승 기록을 버린다`() {
        assertNull(trustedPresence(true, now - trust, now, trust))
        assertNull(trustedPresence(false, now - trust - 1, now, trust))
    }

    @Test
    fun `값이나 확인 시각이 없으면 기록을 쓰지 않는다`() {
        assertNull(trustedPresence(null, now, now, trust))
        assertNull(trustedPresence(true, null, now, trust))
    }
}
