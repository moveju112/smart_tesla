package com.wemade.teslamacro.data.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
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

    /** 발급 응답의 타입·토큰 접두어·만료 한도가 틀리면 GPS를 보내지 않는다. */
    @Test fun shortLivedAccessOnly() {
        val valid = """{"accessToken":"rm1.abc.def","expiresInSeconds":1800}"""
        assertEquals("rm1.abc.def" to 1800L, parseRoadAccess(valid))
        assertNull(parseRoadAccess(valid.replace("rm1.", "dc1.")))
        assertNull(parseRoadAccess(valid.replace("1800", "1801")))
        assertNull(parseRoadAccess(valid.replace("1800", "\"1800\"")))
        assertNull(parseRoadAccess("not json"))
    }

    /** 늦게 온 매칭 좌표는 과거 지점에 멈추지 않고 현재 GPS 진행량만큼 도로축에서 전진한다. */
    @Test fun delayedMatchProjectsToCurrentProgress() {
        val anchor = RoadMatchAnchor(
            RoadPoint(37.0, 127.0, 1_000, 5.0),
            MatchedRoad(37.00005, 127.00005, 0.0),
        )
        val projected = projectRoadMatch(anchor, 37.00090, 127.0, 1_003, 0.0, 100.0, 5.0)
        assertNotNull(projected)
        assertTrue(projected!!.latitude > 37.00080)
        assertEquals(127.00005, projected.longitude, 0.00002)
    }

    /** 5초 넘은 응답이나 현재 진행방향과 맞지 않는 도로는 GPS를 보정하지 않는다. */
    @Test fun staleOrWrongHeadingMatchIsIgnored() {
        val anchor = RoadMatchAnchor(
            RoadPoint(37.0, 127.0, 1_000, 5.0),
            MatchedRoad(37.0, 127.0, 0.0),
        )
        assertNull(projectRoadMatch(anchor, 37.0002, 127.0, 1_006, 0.0, 60.0, 5.0))
        assertNull(projectRoadMatch(anchor, 37.0002, 127.0, 1_001, 90.0, 60.0, 5.0))
    }

    /** 200이어도 불확실·실패·형식 오류는 GPS를 대체하지 않는다. */
    @Test fun uncertainAndInvalidPaths() {
        val base = """{"status":"matched","matchings":[{"confidence":0.9,"geometry":{"coordinates":[[127.0,37.0],[127.1,37.1]],"type":"LineString"}}],"unmatchedCount":0}"""
        assertNull(parseRoadMatch(base.replace("matched", "uncertain")))
        assertNull(parseRoadMatch(base.replace("matched", "failed")))
        assertNull(parseRoadMatch(base.replace("0.9", "0.79")))
        assertNull(parseRoadMatch(base.replace("\"unmatchedCount\":0", "\"unmatchedCount\":1")))
        assertNull(parseRoadMatch(base.replace("127.1", "999.0")))
        assertNull(parseRoadMatch("not json"))
    }
}
