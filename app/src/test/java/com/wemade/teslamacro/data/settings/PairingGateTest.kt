package com.wemade.teslamacro.data.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 등록 화면을 언제 벗어나는지 검증.
 *
 * 실제로 났던 버그: VIN을 저장하는 순간 본 화면으로 넘어가 **카드키 태그 단계를 건너뛰었다.**
 * 등록 절차 첫 단계에서 저장되는 값을 완료 판정에 쓰면 안 된다.
 */
class PairingGateTest {

    @Test
    fun `VIN만 있으면 아직 등록이 끝난 게 아니다`() {
        val midway = AppSettings(vin = "5YJS0000000000000", isEnrolled = false)
        assertTrue("연결은 시도할 수 있다", midway.isPaired)
        assertFalse("본 화면으로 넘어가면 안 된다", midway.isReady)
    }

    @Test
    fun `키 등록까지 끝나야 본 화면으로 넘어간다`() {
        val done = AppSettings(vin = "5YJS0000000000000", isEnrolled = true)
        assertTrue(done.isReady)
    }

    @Test
    fun `VIN 없이 등록 완료 표시만 있으면 준비된 게 아니다`() {
        // 등록 해제 후 플래그만 남는 상황을 막는다
        val broken = AppSettings(vin = "", isEnrolled = true)
        assertFalse(broken.isReady)
    }

    @Test
    fun `기본 상태는 아무것도 등록되지 않은 상태다`() {
        val fresh = AppSettings()
        assertFalse(fresh.isPaired)
        assertFalse(fresh.isReady)
    }
}
