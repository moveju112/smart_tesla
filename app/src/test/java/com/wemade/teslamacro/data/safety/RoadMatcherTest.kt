package com.wemade.teslamacro.data.safety

import org.junit.Assert.*
import org.junit.Test

class RoadMatcherTest {
    /** 발급 응답의 타입·토큰 접두어·만료 한도가 틀리면 GPS를 보내지 않는다. */
    @Test fun shortLivedAccessOnly() {
        val valid = """{"accessToken":"rm1.abc.def","expiresInSeconds":1800}"""
        assertEquals("rm1.abc.def" to 1800L, parseRoadAccess(valid))
        assertNull(parseRoadAccess(valid.replace("rm1.", "dc1.")))
        assertNull(parseRoadAccess(valid.replace("1800", "1801")))
        assertNull(parseRoadAccess(valid.replace("1800", "\"1800\"")))
        assertNull(parseRoadAccess("not json"))
    }

}
