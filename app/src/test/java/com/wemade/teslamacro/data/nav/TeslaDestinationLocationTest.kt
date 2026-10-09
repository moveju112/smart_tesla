package com.wemade.teslamacro.data.nav

import com.wemade.teslamacro.domain.macro.GeoPoint
import org.junit.Assert.*
import org.junit.Test

class TeslaDestinationLocationTest {
    /** 목적지 좌표만 읽고 출발지·지도 중심을 목적지로 바꾸지 않는다. */
    @Test fun `원본 목적지 좌표와 지도 검색 좌표를 읽는다`() {
        val point = GeoPoint(37.56, 126.97)
        assertEquals(point, teslaDestinationPoint("37.56,126.97"))
        assertEquals(point, teslaDestinationPoint("nmap://route/car?slat=35&slng=129&dlat=37.56&dlng=126.97"))
        assertEquals(point, teslaDestinationPoint("nmap://place?lat=37.56&lng=126.97&name=Test"))
        assertEquals(point, teslaDestinationPoint("https://maps.google.com/maps?q=37.56%2C126.97"))
        assertEquals(point, teslaDestinationPoint("https://www.google.com/maps/search/?api=1&query=37.56%2C126.97"))
        assertEquals("37.56,126.97", TeslaNavigationDestination.normalize("nmap://route/car?dlat=37.56&dlng=126.97&dname=" + "x".repeat(300)))
    }

    /** 링크 확장이나 출처 추측 없이 검증 가능한 완전한 좌표만 사용한다. */
    @Test fun `잘못된 좌표와 모호한 링크를 거부한다`() {
        listOf("북지길 13", "37.56", "NaN,126.97", "91,126.97", "37.56,181", "0,0",
            "nmap://route/car?slat=37.56&slng=126.97", "nmap://route/car?dlat=37&dlat=38&dlng=126",
            "https://map.naver.com/p/entry/place/123", "https://naver.me/test", "https://evil.example/maps?q=37,126",
            "https://maps.google.com/maps?q=37,126#x", "https://maps.google.com:443/maps?q=37,126",
            "https://maps.google.com/maps?q=37,126&q=38,127", "https://maps.google.com/maps?center=37,126")
            .forEach { assertNull(it, teslaDestinationPoint(it)) }
    }

    /** 시·도와 시·군·구까지 있어야 자동 일치 검사를 시작한다. */
    @Test fun `북지길 13 같은 일부 주소는 자동 확정하지 않는다`() {
        listOf("북지길 13", "서울 북지길 13", "종로구 북지길 13", "테스트카페", "서울특별시 종로구 북지길")
            .forEach { assertFalse(it, qualifiedTeslaRoadAddress(it)) }
        listOf("서울특별시 종로구 북지길 13", "서울 종로구 북지길 13", "경기도 성남시 테스트로 13-1",
            "세종특별자치시 테스트길 13").forEach { assertTrue(it, qualifiedTeslaRoadAddress(it)) }
    }

    /** 지역이 빠지면 한 후보라도 확인받고 완전한 주소의 유일한 일치만 허용한다. */
    @Test fun `단일 결과나 가까운 결과는 일부 주소 자동 공유 근거가 아니다`() {
        val correct = TeslaDestinationCandidate("서울 종로구 북지길 13", GeoPoint(37.56, 126.97))
        assertNull(automaticTeslaCandidate("북지길 13", listOf(correct)))
        assertNull(automaticTeslaCandidate("테스트카페", listOf(correct)))
        assertEquals(correct, automaticTeslaCandidate("서울특별시 종로구 북지길 13", listOf(correct)))
        assertNull(automaticTeslaCandidate(correct.address, listOf(correct, correct.copy(point = GeoPoint(37.57, 126.98)))))
        assertNull(automaticTeslaCandidate(correct.address, listOf(correct.copy(point = null))))
    }

    /** 건물번호와 행정구역의 다른 결과를 정확 일치로 처리하지 않는다. */
    @Test fun `13과 130 및 다른 구를 구분한다`() {
        val wrongNumber = TeslaDestinationCandidate("서울 종로구 북지길 130", GeoPoint(37.56, 126.97))
        val wrongDistrict = wrongNumber.copy(address = "서울 중구 북지길 13")
        assertNull(automaticTeslaCandidate("서울 종로구 북지길 13", listOf(wrongNumber, wrongDistrict)))
        assertEquals("경기 성남시 테스트로 13", canonicalTeslaAddress("대한민국 경기도 성남시 테스트로 13"))
    }

    /** 위치는 표시 순서에만 쓰며 중복·잘못된 좌표는 선택 목록에서 제거한다. */
    @Test fun `후보 정렬 중복 제거와 좌표 검증`() {
        val near = TeslaDestinationCandidate("서울 종로구 북지길 13", GeoPoint(37.56, 126.97))
        val far = TeslaDestinationCandidate("부산 중구 북지길 13", GeoPoint(35.1, 129.0))
        val invalid = near.copy(address = "Invalid", point = GeoPoint(Double.NaN, 126.97))
        assertEquals(listOf(near, far), rankedTeslaCandidates(listOf(far, near, near, invalid), near.point))
        assertEquals(listOf(far, near), rankedTeslaCandidates(listOf(far, near), null))
        assertNull(automaticTeslaCandidate("북지길 13", rankedTeslaCandidates(listOf(near, far), near.point)))
        assertEquals("37.56,126.97", near.shareText)
        assertEquals(near.address, near.copy(point = null).shareText)
    }
}
