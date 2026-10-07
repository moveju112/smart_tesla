package com.wemade.teslamacro.data.nav

import org.junit.Assert.*
import org.junit.Test

class LocalAdbTcpPortTest {
    /** 현재 데몬 포트가 저장 설정보다 우선하며 오래된 포트로 접속하지 않는다. */
    @Test fun activePortWins() {
        assertEquals(43123, localAdbTcpPort("[persist.adb.tcp.port]: [5555]\n[service.adb.tcp.port]: [43123]"))
        assertNull(localAdbTcpPort("[persist.adb.tcp.port]: [5555]\n[service.adb.tcp.port]: [-1]"))
        assertNull(localAdbTcpPort("[persist.adb.tcp.port]: [5555]\n[service.adb.tcp.port]: [0]"))
    }

    /** 현재 설정이 없을 때만 영구 설정을 사용하며 범위 밖 값은 연결하지 않는다. */
    @Test fun missingAndInvalidPorts() {
        assertEquals(5555, localAdbTcpPort("[persist.adb.tcp.port]: [5555]"))
        assertEquals(5555, localAdbTcpPort("[service.adb.tcp.port]: []\n[persist.adb.tcp.port]: [5555]"))
        for (value in listOf("", "-1", "0", "65536", "abc", "5555;id")) {
            assertNull(localAdbTcpPort("[service.adb.tcp.port]: [$value]"))
        }
    }
}
