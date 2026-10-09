package com.wemade.teslamacro.data.nav

import org.junit.Assert.*
import org.junit.Test

class TeslaNavigationDestinationTest {
    private val tmap = "com.skt.tmap.ku"
    private val kakao = "com.locnall.KimGiSa"
    private val naver = "com.nhn.android.nmap"

    /** 안내 중인 티맵 목적지만 읽고 같은 알림의 반복 업데이트는 무시한다. */
    @Test fun `티맵 안내와 목적지 변경은 각각 한 번 전송한다`() {
        val tracker = TeslaNavigationDestination()
        assertNull(tracker.notification(tmap, "route", "안심주행", "서울역", 0))
        assertEquals("서울역", tracker.notification(tmap, "route", "경로주행", "현재 위치 > 서울역 > ", 1))
        assertNull(tracker.notification(tmap, "route", "경로주행", "현재 위치 > 서울역", 2))
        assertEquals("부산역", tracker.notification(tmap, "route", "경로주행", "현재 위치 > 부산역", 3))
        assertFalse(TeslaNavigationDestination.supports("com.skt.tmap.ku.fake"))
        assertTrue(TeslaNavigationDestination.supports("com.skt.skaf.l001mtm091"))
    }

    /** 카카오의 알림 명시 목적지는 화면 권한 없이도 사용할 수 있다. */
    @Test fun `카카오 일반 안내와 보험 안내 목적지를 읽는다`() {
        val tracker = TeslaNavigationDestination()
        assertNull(tracker.notification(kakao, "safe", "안전운전 주행 중", "목적지 : 서울역", 0))
        assertEquals("서울역", tracker.notification(kakao, "route", "길안내 주행 중", "목적지 : 서울역", 1))
        assertEquals("부산역", tracker.notification(kakao, "route", "보험을 켜고 길안내 주행 중", "목적지 : 부산역", 2))
    }

    /** 네이버의 일반 앱 이름은 목적지가 아니며 같은 앱의 화면 후보가 필요하다. */
    @Test fun `네이버 안내는 최신 화면 목적지와 결합한다`() {
        val tracker = TeslaNavigationDestination()
        assertNull(tracker.screen(naver, "서울역", 0))
        assertNull(tracker.notification(naver, "route", "네이버 지도", "길찾기", 1))
        assertEquals("서울역", tracker.notification(naver, "route", "네이버 지도", "내비게이션 - 안내 중", 2))
        assertNull(tracker.notification(naver, "route", "네이버 지도", "내비게이션 - 안내 중", 3))
    }

    /** 다른 앱 후보와 오래된 목적지는 새 안내에 섞지 않는다. */
    @Test fun `목적지 캐시는 앱별로 분리하고 일 분 뒤 만료한다`() {
        val tracker = TeslaNavigationDestination()
        tracker.screen(kakao, "서울역", 0)
        assertNull(tracker.notification(naver, "naver", "네이버 지도", "내비게이션 - 안내 중", 1))
        tracker.screen(naver, "부산역", 10)
        tracker.removed(naver, "naver")
        tracker.screen(naver, "서울역", 10)
        assertNull(tracker.notification(naver, "expired", "네이버 지도", "내비게이션 - 안내 중", 60_011))
    }

    /** 알림이 먼저 왔어도 짧은 안내 시작 구간의 화면만 보완한다. */
    @Test fun `늦은 화면은 오 초 내에서만 안내를 보완한다`() {
        val tracker = TeslaNavigationDestination()
        assertNull(tracker.notification(naver, "route", "네이버 지도", "내비게이션 - 안내 중", 0))
        assertEquals("서울역", tracker.screen(naver, "서울역", 5_000))
        tracker.removed(naver, "route")
        assertNull(tracker.notification(naver, "next", "네이버 지도", "내비게이션 - 안내 중", 10_000))
        assertNull(tracker.screen(naver, "부산역", 15_001))
    }

    /** 안내 중 새 길찾기 미리보기를 눌러도 실제 안내 변경으로 오인하지 않는다. */
    @Test fun `공유 뒤 화면 후보 변경은 반복 알림으로 재전송하지 않는다`() {
        val tracker = TeslaNavigationDestination()
        tracker.screen(naver, "서울역", 0)
        assertEquals("서울역", tracker.notification(naver, "route", "네이버 지도", "내비게이션 - 안내 중", 1))
        assertNull(tracker.screen(naver, "부산역", 2))
        assertNull(tracker.notification(naver, "route", "네이버 지도", "내비게이션 - 안내 중", 3))
    }

    /** 다른 알림 삭제는 안내를 유지하며 해당 안내 종료와 OFF만 후보를 지운다. */
    @Test fun `안내 종료와 OFF는 다음 안내에 캐시를 남기지 않는다`() {
        val tracker = TeslaNavigationDestination()
        tracker.screen(naver, "서울역", 0)
        assertEquals("서울역", tracker.notification(naver, "route", "네이버 지도", "내비게이션 - 안내 중", 1))
        tracker.removed(naver, "advertisement")
        assertNull(tracker.notification(naver, "route", "네이버 지도", "내비게이션 - 안내 중", 2))
        tracker.removed(naver, "route")
        assertNull(tracker.notification(naver, "new", "네이버 지도", "내비게이션 - 안내 중", 3))
        tracker.clear()
        assertNull(tracker.screen(naver, "부산역", 4))
    }

    /** 화면의 출발·도착 설명에서 도착 문구만 추출한다. */
    @Test fun `카카오 화면은 도착 문구만 읽는다`() {
        assertEquals("서울역", teslaDestinationFromScreen(kakao, listOf(NavigationScreenText("출발 현재 위치, 도착 서울역", 0, 0))))
        assertNull(teslaDestinationFromScreen(kakao, listOf(NavigationScreenText("서울역", 0, 0))))
    }

    /** 도착지 입력 상태에서는 출발지를 목적지로 보내지 않는다. */
    @Test fun `네이버 화면은 위치 순서와 빈 도착지를 구분한다`() {
        assertEquals("서울역", teslaDestinationFromScreen(naver, listOf(
            NavigationScreenText("서울역", 60, 0), NavigationScreenText("현재 위치", 0, 0), NavigationScreenText("경유지 입력", 100, 0))))
        assertNull(teslaDestinationFromScreen(naver, listOf(
            NavigationScreenText("부산역", 0, 0), NavigationScreenText("도착지 입력", 60, 0))))
        assertNull(teslaDestinationFromScreen(naver, listOf(NavigationScreenText("Enter destination", 0, 0))))
    }

    /** 예외적인 입력과 알 수 없는 실행 방식은 제한된 기본값으로 처리한다. */
    @Test fun `네이버 도착지 뒤 출입구 버튼과 다른 버튼은 목적지가 아니다`() {
        assertEquals("위메이드", teslaDestinationFromScreen(naver, listOf(
            NavigationScreenText("현재 위치", 0, 0), NavigationScreenText("위메이드", 60, 0),
            NavigationScreenText("출입구 변경", 60, 100), NavigationScreenText("경로 옵션", 80, 0, true))))
        assertEquals("청마로34번길 6", teslaDestinationFromScreen(naver, listOf(
            NavigationScreenText("현재 위치", 0, 0), NavigationScreenText("청마로 34번길 6", 60, 0),
            NavigationScreenText("출입구 변경", 80, 0))))
        assertNull(TeslaNavigationDestination.normalize("출입구 변경"))
        assertNull(teslaDestinationFromScreen(naver, listOf(NavigationScreenText("출입구 변경", 0, 0))))
    }

    /** 명시한 도착 행과 모호한 다중 필드를 구분해 임의의 마지막 글자를 고르지 않는다. */
    @Test fun `네이버 도착 라벨과 모호한 영역을 구분한다`() {
        assertEquals("위메이드", teslaDestinationFromScreen(naver, listOf(
            NavigationScreenText("출발", 0, 0), NavigationScreenText("다른 출발지", 0, 100),
            NavigationScreenText("도착", 60, 0), NavigationScreenText("위메이드", 60, 100),
            NavigationScreenText("경로 옵션", 100, 0))))
        assertNull(teslaDestinationFromScreen(naver, listOf(
            NavigationScreenText("출발지", 0, 0), NavigationScreenText("도착지", 60, 0), NavigationScreenText("다른 글자", 100, 0))))
    }

    /** 실기기 경로 화면처럼 닫기·더보기·입구 글자가 섞여도 아래쪽 도착 칸을 읽는다. */
    @Test fun `네이버 실제 경로 화면의 조작 글자를 목적지 후보에서 뺀다`() {
        assertEquals("위메이드타워", teslaDestinationFromScreen(naver, listOf(
            NavigationScreenText("출발지 도착지 전환", 183, 900), NavigationScreenText("인천 검단구 청마로34번길 6", 168, 100),
            NavigationScreenText("위메이드타워", 285, 100), NavigationScreenText("경유지 추가", 183, 950),
            NavigationScreenText("닫기", 130, 0), NavigationScreenText("더보기", 235, 950),
            NavigationScreenText("입구", 415, 100), NavigationScreenText("출입구 변경", 415, 300))))
    }

    /** 출입구 이름이 장소마다 달라도 출입구 변경 행 전체를 목적지에서 뺀다. */
    @Test fun `네이버 출입구 행의 장소별 입구 이름을 목적지로 보지 않는다`() {
        assertEquals("검단탑병원", teslaDestinationFromScreen(naver, listOf(
            NavigationScreenText("출발지 도착지 전환", 183, 42), NavigationScreenText("인천 검단구 청마로34번길 6", 168, 243),
            NavigationScreenText("검단탑병원", 285, 243), NavigationScreenText("경유지 추가", 183, 738),
            NavigationScreenText("닫기", 130, 882), NavigationScreenText("더보기", 235, 882),
            NavigationScreenText(" 응급실입구", 415, 96), NavigationScreenText("출입구 변경", 415, 778))))
    }

    /** 실기기 안내 중 경로 미리보기 덤프에서 상단 도착 칸만 읽고 확인창·일반 안내 화면은 무시한다. */
    @Test fun `네이버 안내 중 경로 미리보기의 새 도착지를 읽는다`() {
        val preview = listOf(
            NavigationScreenText("지도", 0, 0), NavigationScreenText("나중에 출발", 2189, 100),
            NavigationScreenText("안내시작", 2188, 600), NavigationScreenText("10", 2200, 900),
            NavigationScreenText("실시간 추천", 1812, 100), NavigationScreenText("4분", 1868, 100),
            NavigationScreenText("757m", 1987, 100), NavigationScreenText("내 위치 보기", 1647, 900),
            NavigationScreenText("테슬라", 1659, 300), NavigationScreenText("경유지 추가", 183, 738),
            NavigationScreenText("닫기", 130, 882), NavigationScreenText("더보기", 235, 882),
            NavigationScreenText("내위치", 168, 243), NavigationScreenText("마전초등학교", 285, 243))
        assertEquals("마전초등학교", naverGuidanceRouteDestination(preview))
        assertNull(naverGuidanceRouteDestination(listOf(
            NavigationScreenText("영업 종료가 예상됩니다.", 1677, 0), NavigationScreenText("오전 12시 56분 도착 예상", 1929, 0),
            NavigationScreenText("안내시작", 2100, 0), NavigationScreenText("닫기", 1493, 0))))
        assertNull(naverGuidanceRouteDestination(listOf(
            NavigationScreenText("262 m", 100, 0), NavigationScreenText("안동포사거리", 160, 0), NavigationScreenText("검색", 900, 0))))
    }

    /** 공유와 보조창이 동일하게 실제 길안내 알림만 인정한다. */
    @Test fun `보조창은 검색과 안심주행 알림에는 표시하지 않는다`() {
        assertTrue(isTeslaNavigationGuidance(naver, "네이버 지도", "내비게이션 - 안내 중"))
        assertFalse(isTeslaNavigationGuidance(naver, "네이버 지도", "검색 결과"))
        assertTrue(isTeslaNavigationGuidance(tmap, "경로주행", "현재 위치 > 서울역"))
        assertFalse(isTeslaNavigationGuidance(tmap, "경로주행", "안심주행"))
        assertFalse(isTeslaNavigationGuidance("untrusted", "경로주행", "서울역"))
    }

    /** 예외적인 입력과 알 수 없는 실행 방식은 제한된 기본값으로 처리한다. */
    @Test fun `입력 검증과 실행 방식 복원은 안전한 기본값을 쓴다`() {
        assertNull(TeslaNavigationDestination.normalize(""))
        assertNull(TeslaNavigationDestination.normalize("a".repeat(201)))
        assertNull(TeslaNavigationDestination.normalize("서울\u0000역"))
        assertEquals("서울 역", TeslaNavigationDestination.normalize(" 서울\n역 "))
        assertEquals(TeslaNavigationLaunchMode.ADB_FIRST, TeslaNavigationLaunchMode.of("unknown"))
        assertEquals(TeslaNavigationLaunchMode.STANDARD, TeslaNavigationLaunchMode.of("STANDARD"))
    }
}
