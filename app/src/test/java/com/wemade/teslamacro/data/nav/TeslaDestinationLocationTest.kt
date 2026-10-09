package com.wemade.teslamacro.data.nav

import com.wemade.teslamacro.domain.macro.GeoPoint
import org.junit.Assert.*
import org.junit.Test

class TeslaDestinationLocationTest {
    /** 공백 보정은 숫자 번길에 한정해 일반 장소명은 유지한다. */
    @Test fun `숫자 번길 도로명의 띄어쓰기만 보정한다`() {
        assertEquals("청마로34번길 6", TeslaNavigationDestination.normalize("청마로 34번길 6"))
        assertEquals("청마로34번길 6", normalizeTeslaRoadSpacing("청마로34번길 6"))
        assertEquals("위메이드 본사", normalizeTeslaRoadSpacing("위메이드 본사"))
        assertTrue(qualifiedTeslaRoadAddress("인천 서구 청마로 34번길 6"))
        assertFalse(qualifiedTeslaRoadAddress("청마로 34번길 6"))
    }

    /** 단일 검색 결과도 요청한 도로명·건물번호·지역이 다르면 공유 후보에서 제외한다. */
    @Test fun `주소 검색의 오검색과 번호 일부 일치를 제외한다`() {
        val correct = TeslaDestinationCandidate("인천 서구 청마로34번길 6", GeoPoint(37.5, 126.6))
        val wrongNumber = correct.copy(address = "인천 서구 청마로34번길 60")
        val wrongRoad = correct.copy(address = "인천 서구 다른로34번길 6")
        val wrongDistrict = correct.copy(address = "인천 부평구 청마로34번길 6")
        assertEquals(listOf(correct, wrongDistrict), matchingTeslaAddressCandidates("청마로 34번길 6",
            listOf(correct, wrongNumber, wrongRoad, wrongDistrict)))
        assertEquals(listOf(correct), matchingTeslaAddressCandidates("인천 서구 청마로 34번길 6",
            listOf(correct, wrongDistrict)))
        assertTrue(matchingTeslaAddressCandidates("청마로34번길 6", listOf(wrongNumber)).isEmpty())
        assertEquals(listOf(correct), matchingTeslaAddressCandidates("위메이드", listOf(correct)))
    }

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

    /** 좌표 조회 실패 때 전체 주소만 주소 문자열 공유 후보로 사용할 수 있다. */
    @Test fun `전체 주소와 지역이 빠진 일부 주소를 구분한다`() {
        listOf("북지길 13", "서울 북지길 13", "종로구 북지길 13", "테스트카페", "서울특별시 종로구 북지길")
            .forEach { assertFalse(it, qualifiedTeslaRoadAddress(it)) }
        listOf("서울특별시 종로구 북지길 13", "서울 종로구 북지길 13", "경기도 성남시 테스트로 13-1",
            "세종특별자치시 테스트길 13").forEach { assertTrue(it, qualifiedTeslaRoadAddress(it)) }
    }

    /** 선택창 없이 경로 거리에 맞는 후보를, 경로가 없으면 가장 가까운 후보를 고른다. */
    @Test fun `가장 그럴듯한 후보를 자동 선택한다`() {
        val here = GeoPoint(37.56, 126.97)
        val close = TeslaDestinationCandidate("서울 종로구 북지길 13", GeoPoint(37.561, 126.971))
        val farther = close.copy(address = "서울 중구 북지길 13", point = GeoPoint(37.60, 126.97))
        assertEquals(close, bestTeslaCandidate(listOf(farther, close), here, null))
        assertEquals(farther, bestTeslaCandidate(listOf(close, farther), here, 5_800))
        assertNull(bestTeslaCandidate(emptyList(), here, null))
        assertEquals(close, bestTeslaCandidate(listOf(close, farther), null, null))
    }

    /** 후보가 없으면 현재 시·군·구를 붙인 이름을 테슬라 검색어로 쓴다. */
    @Test fun `후보가 없으면 지역을 붙인 이름으로 넘긴다`() {
        assertEquals("인천 검단구 알베로", fallbackTeslaCandidate("알베로", "대한민국 인천광역시 검단구 당하동 1").address)
        assertEquals("알베로", fallbackTeslaCandidate("알베로", null).address)
        assertEquals("서울 종로구 북지길 13", fallbackTeslaCandidate("서울 종로구 북지길 13", "대한민국 인천광역시 검단구 당하동 1").address)
        assertNull(fallbackTeslaCandidate("알베로", null).point)
    }

    /** 건물번호와 행정구역의 다른 결과를 정확 일치로 처리하지 않는다. */
    @Test fun `13과 130 및 다른 구를 구분한다`() {
        val wrongNumber = TeslaDestinationCandidate("서울 종로구 북지길 130", GeoPoint(37.56, 126.97))
        val wrongDistrict = wrongNumber.copy(address = "서울 중구 북지길 13")
        assertEquals(emptyList<TeslaDestinationCandidate>(), matchingTeslaAddressCandidates("서울 종로구 북지길 13", listOf(wrongNumber, wrongDistrict)))
        assertEquals("경기 성남시 테스트로 13", canonicalTeslaAddress("대한민국 경기도 성남시 테스트로 13"))
    }

    /** 위치는 표시 순서에만 쓰며 중복·잘못된 좌표는 선택 목록에서 제거한다. */
    @Test fun `후보 정렬 중복 제거와 좌표 검증`() {
        val near = TeslaDestinationCandidate("서울 종로구 북지길 13", GeoPoint(37.56, 126.97))
        val far = TeslaDestinationCandidate("부산 중구 북지길 13", GeoPoint(35.1, 129.0))
        val invalid = near.copy(address = "Invalid", point = GeoPoint(Double.NaN, 126.97))
        assertEquals(listOf(near, far), rankedTeslaCandidates(listOf(far, near, near, invalid), near.point))
        assertEquals(listOf(far, near), rankedTeslaCandidates(listOf(far, near), null))
        assertEquals("37.56,126.97", near.shareText)
        assertEquals(near.address, near.copy(point = null).shareText)
    }

    /** 네이버 경로 거리 글자만 미터로 읽는다. */
    @Test fun `네이버 경로 거리 글자를 미터로 바꾼다`() {
        assertEquals(415, naverDistanceMeters("415m"))
        assertEquals(311, naverDistanceMeters("311 m"))
        assertEquals(1_100, naverDistanceMeters("1.1 km"))
        assertNull(naverDistanceMeters("통행료 0원"))
        assertNull(naverDistanceMeters("128"))
    }

    /** 당하동에서 수백 m 경로인데 하남의 같은 이름 단지가 단일 후보로 자동 선택되던 문제를 막는다. */
    @Test fun `경로 거리보다 먼 같은 이름 후보는 뺀다`() {
        val here = GeoPoint(37.595, 126.668)
        val hanam = TeslaDestinationCandidate("경기 하남시 보미골드리즌빌", GeoPoint(37.54, 127.21))
        val nearby = TeslaDestinationCandidate("인천 검단구 보미골드리즌빌", GeoPoint(37.597, 126.670))
        assertEquals(emptyList<TeslaDestinationCandidate>(), withinTeslaRouteDistance(listOf(hanam), here, 600))
        assertEquals(listOf(nearby), withinTeslaRouteDistance(listOf(hanam, nearby), here, 600))
        assertEquals(listOf(hanam), withinTeslaRouteDistance(listOf(hanam), here, null))
        assertEquals(listOf(hanam), withinTeslaRouteDistance(listOf(hanam), null, 600))
    }
}
