package com.wemade.teslamacro.data.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 내비 앱별 길안내 URI.
 *
 * 좌표 순서를 한 번 뒤집으면 앱은 정상적으로 뜨고 **엉뚱한 곳으로 안내한다** —
 * 실패가 조용해서 제일 위험한 자리다. 앱마다 x·y 규칙이 달라 여기서 못박는다.
 */
class NavigatorAppTest {

    private val lat = 37.5665
    private val lng = 126.9780
    private val pkg = "com.wemade.teslamacro"

    @Test
    fun `네이버는 dlat이 위도 dlng가 경도다`() {
        val uri = NavigatorApp.NAVER.uris(lat, lng, "회사", pkg).first().toString()
        assertTrue(uri, uri.contains("dlat=37.5665"))
        assertTrue(uri, uri.contains("dlng=126.978"))
        // 호출자 식별이 없으면 앱은 뜨고도 안내를 안 건다
        assertTrue(uri, uri.contains("appname=$pkg"))
    }

    /** 티맵은 goalx가 경도, goaly가 위도 — 이름만 보고 반대로 넣기 쉬운 자리다 */
    @Test
    fun `티맵은 goalx가 경도 goaly가 위도다`() {
        val uri = NavigatorApp.TMAP.uris(lat, lng, "회사", pkg).first().toString()
        assertTrue(uri, uri.contains("goalx=126.978"))
        assertTrue(uri, uri.contains("goaly=37.5665"))
    }

    /** 카카오내비 ep는 "위도,경도" 순서다 (지도 API의 x,y와 반대) */
    @Test
    fun `카카오내비는 위도 경도 순서다`() {
        val uri = NavigatorApp.KAKAO.uris(lat, lng, "회사", pkg).first().toString()
        assertTrue(uri, uri.contains("ep=37.5665,126.978"))
    }

    @Test
    fun `구글은 위도 경도 순서로 내비 모드를 연다`() {
        val uri = NavigatorApp.GOOGLE.uris(lat, lng, "회사", pkg).first().toString()
        assertTrue(uri, uri.startsWith("google.navigation:q=37.5665,126.978"))
    }

    /** 같은 앱도 버전에 따라 받는 스킴이 갈린다 — 후보가 하나뿐이면 조용히 실패한다 */
    @Test
    fun `앱마다 URI 후보가 둘 이상이다`() {
        NavigatorApp.entries.forEach { app ->
            assertTrue(app.label, app.uris(lat, lng, "회사", pkg).size >= 2)
        }
    }

    @Test
    fun `목적지 이름은 인코딩된다`() {
        val uri = NavigatorApp.NAVER.uris(lat, lng, "회사 앞 주차장", pkg).first().toString()
        assertTrue(uri, uri.contains("dname=%ED%9A%8C%EC%82%AC"))
    }

    @Test
    fun `저장값이 깨졌으면 기본 앱으로 돌아간다`() {
        assertEquals(NavigatorApp.NAVER, NavigatorApp.of(null))
        assertEquals(NavigatorApp.NAVER, NavigatorApp.of("없는앱"))
        assertEquals(NavigatorApp.TMAP, NavigatorApp.of("TMAP"))
    }

    /** 티맵은 패키지가 둘이다 (구버전·신버전) — 하나만 보면 설치돼 있어도 못 찾는다 */
    @Test
    fun `티맵은 대체 패키지를 함께 본다`() {
        assertTrue(NavigatorApp.TMAP.packages.size >= 2)
        assertTrue(NavigatorApp.TMAP.packages.contains("com.skt.skaf.l001mtm091"))
    }
}
