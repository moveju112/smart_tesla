package com.wemade.teslamacro.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 파일 첨부 실패 시 공유 Intent 본문이 커지지 않는지 검증 */
class DiagLogShareTest {

    @Test
    fun `짧은 로그는 그대로 보낸다`() {
        assertEquals("최근 로그", fallbackShareText("최근 로그"))
    }

    /** GPS 대기 반복은 집계하되 BLE 연결 전환·경고음·서버 오류는 사건별로 남긴다. */
    @Test
    fun `공유 요약은 반복 상태를 접고 중요한 사건을 주제별로 보존한다`() {
        val lines = listOf("2026-09-25 09:35:17.600 GATT 접속 시도", "2026-09-25 09:35:43.666 차량 BLE 연결 해제") +
            List(120) { "2026-09-25 09:36:18.998 안전 안내 · GPS 속도 5km/h 미만 (GPS 0km/h, 오차 12m)" } +
            listOf("2026-09-25 09:40:00.000 안전 안내 · 경고음 요청 수락",
                "2026-09-25 09:40:00.500 안전 안내 · 경보 속도 미달 (GPS 9km/h, 후보 제한 60km/h)",
                "2026-09-25 09:40:00.800 안전 안내 · 단속카메라 음성 요청 수락 (거리 200m, 단계 2)",
                "2026-09-25 09:40:01.000 도로 매칭 · 서버 혼잡(503)",
                "2026-09-25 09:41:00.000 스마트싱스 트렁크 열기 알림 수신")
        val report = diagnosticShareReport("설정: 과속안내=true", lines.joinToString("\n"))

        assertTrue(report.startsWith("# Smart Tesla 진단 요약"))
        assertTrue(report.contains("## 설정\n설정: 과속안내=true"))
        assertTrue(report.contains("## 차량 연결 (2건)"))
        assertTrue(report.contains("## 안전 안내·도로 매칭 (4건)"))
        assertTrue(report.contains("## 명령·매크로 (1건)"))
        assertTrue(report.contains("## 반복 GPS 상태 (120건 · 1유형)"))
        assertTrue(report.contains("GPS 속도 5km/h 미만: 120회"))
        assertEquals(1, Regex("안전 안내 · GPS 속도 5km/h 미만").findAll(report).count())
        assertTrue(report.contains("경고음 요청 수락"))
        assertTrue(report.contains("경보 속도 미달"))
        assertTrue(report.contains("단속카메라 음성 요청 수락"))
        assertTrue(report.contains("서버 혼잡(503)"))
    }

    /** 사건이 넘치면 최신 사건을 남기고 생략 건수를 표시하며 제목·반복 집계는 유지한다. */
    @Test
    fun `긴 공유 요약은 제목을 잘라내지 않고 최신 사건을 남긴다`() {
        val logs = (0 until 300).joinToString("\n") { index ->
            "2026-09-25 09:35:17.600 차량 BLE 사건-${index.toString().padStart(3, '0')} " + "x".repeat(300)
        }
        val report = diagnosticShareReport("설정", logs)

        assertTrue(report.length <= 32_000)
        assertEquals(report, fallbackShareText(report))
        assertTrue(report.startsWith("# Smart Tesla 진단 요약"))
        assertTrue(report.contains("오래된 사건"))
        assertFalse(report.contains("사건-000"))
        assertTrue(report.contains("사건-299"))
        assertTrue(report.contains("## 반복 GPS 상태 (0건 · 0유형)"))
    }

    /** 이전 버전의 연도 없는 로그도 원본 시간 범위를 잃지 않고 표시한다. */
    @Test
    fun `구버전 시간과 사건 없는 공유도 범위를 표시한다`() {
        val report = diagnosticShareReport("", "09-25 09:35:17.600 안전 안내 · GPS 속도 5km/h 미만 (GPS 0km/h)")

        assertTrue(report.contains("- 범위: 09-25 09:35:17.600 ~ 09-25 09:35:17.600"))
        assertTrue(report.contains("## 반복 GPS 상태 (1건 · 1유형)"))
    }

    @Test
    fun `큰 로그는 최근 32000자만 보낸다`() {
        val text = "앞".repeat(10_000) + "뒤".repeat(40_000)
        val result = fallbackShareText(text)

        assertEquals(32_000, result.length)
        assertTrue(result.all { it == '뒤' })
    }
}
