package com.wemade.teslamacro.data.nav

import org.junit.Assert.*
import org.junit.Test

class PairingScreenTest {
    /** 한글·영문 설정에서 코드와 주소를 함께 읽되 일반 설정 숫자는 무시한다. */
    @Test fun readsOnlyCompletePairingDialog() {
        assertEquals("37123" to "123456", PairingScreen.read(listOf("Wi-Fi 페어링 코드", "123 456", "192.168.1.2:37123")))
        assertEquals("37123" to "123456", PairingScreen.read(listOf("Wi-Fi pairing code", "123456", "192.168.1.2:37123")))
        assertNull(PairingScreen.read(listOf("123456", "192.168.1.2:37123")))
        assertNull(PairingScreen.read(listOf("페어링 코드", "123456")))
        assertNull(PairingScreen.read(listOf("페어링 코드", "123456", "192.168.1.2:65536")))
        assertNull(PairingScreen.read(listOf("페어링 코드", "12345", "192.168.1.2:37123")))
    }
}
