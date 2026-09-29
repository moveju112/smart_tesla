package com.wemade.teslamacro.data.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadMatcherTest {
    /** 실제 서버 응답의 경도·위도 순서와 마지막 도로 좌표를 지킨다. */
    @Test fun matchedPath() {
        val body = """{"status":"matched","matchings":[{"confidence":0.98028863,"geometry":{"coordinates":[[127.010008,37.500102],[127.010015,37.500306]],"type":"LineString"}}],"unmatchedCount":0}"""
        val matched = parseRoadMatch(body)
        assertEquals(37.500306, matched?.latitude ?: 0.0, 0.0)
        assertEquals(127.010015, matched?.longitude ?: 0.0, 0.0)
        assertTrue((matched?.bearingDegrees ?: -1.0) in 1.3..1.8)
    }

    /** 서버 도로명은 보조 근거라 형식이 틀리면 이름만 버리고 매칭 좌표는 유지한다. */
    @Test fun matchedRoadName() {
        val body = """{"status":"matched","roadName":" 경수대로 ","matchings":[{"confidence":0.9,"geometry":{"coordinates":[[127.010008,37.500102],[127.010015,37.500306]],"type":"LineString"}}],"unmatchedCount":0}"""
        assertEquals("경수대로", parseRoadMatch(body)?.roadName)
        for (invalid in listOf("\"\"", "123", "\"${"로".repeat(65)}\"")) {
            val road = parseRoadMatch(body.replace("\" 경수대로 \"", invalid))
            assertEquals(37.500306, road?.latitude ?: 0.0, 0.0)
            assertNull(road?.roadName)
        }
    }

    /** 발급 응답의 타입·토큰 접두어·만료 한도가 틀리면 GPS를 보내지 않는다. */
    @Test fun shortLivedAccessOnly() {
        val valid = """{"accessToken":"rm1.abc.def","expiresInSeconds":1800}"""
        assertEquals("rm1.abc.def" to 1800L, parseRoadAccess(valid))
        assertNull(parseRoadAccess(valid.replace("rm1.", "dc1.")))
        assertNull(parseRoadAccess(valid.replace("1800", "1801")))
        assertNull(parseRoadAccess(valid.replace("1800", "\"1800\"")))
        assertNull(parseRoadAccess("not json"))
    }

    /** 200이어도 불확실·실패·형식 오류는 GPS를 대체하지 않는다. */
    @Test fun uncertainAndInvalidPaths() {
        val base = """{"status":"matched","matchings":[{"confidence":0.9,"geometry":{"coordinates":[[127.0,37.0],[127.1,37.1]],"type":"LineString"}}],"unmatchedCount":0}"""
        assertNull(parseRoadMatch(base.replace("matched", "uncertain")))
        assertNull(parseRoadMatch(base.replace("matched", "failed")))
        assertNull(parseRoadMatch(base.replace("0.9", "0.79")))
        assertNull(parseRoadMatch(base.replace("0.9", "1.01")))
        assertNull(parseRoadMatch(base.replace("\"unmatchedCount\":0", "\"unmatchedCount\":1")))
        assertNull(parseRoadMatch(base.replace("127.1", "999.0")))
        assertNull(parseRoadMatch("not json"))
    }
}
