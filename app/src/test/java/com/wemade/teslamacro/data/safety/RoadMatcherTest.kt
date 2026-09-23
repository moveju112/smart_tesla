package com.wemade.teslamacro.data.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoadMatcherTest {
    /** 실제 서버 응답의 경도·위도 순서와 마지막 도로 좌표를 지킨다. */
    @Test fun matchedPath() {
        val body = """{"status":"matched","matchings":[{"confidence":0.98028863,"geometry":{"coordinates":[[127.010008,37.500102],[127.010015,37.500306]],"type":"LineString"}}],"unmatchedCount":0}"""
        assertEquals(MatchedRoad(37.500306, 127.010015), parseRoadMatch(body))
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
