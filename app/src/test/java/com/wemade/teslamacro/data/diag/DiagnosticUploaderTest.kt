package com.wemade.teslamacro.data.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticUploaderTest {
    /** 구버전 원문 로그가 남아 있어도 알려진 내비 진단 형식의 내용을 전송하지 않는다. */
    @Test fun legacyNavigationContentIsRemovedBeforeUpload() {
        for (prefix in listOf(
            "테슬라 내비 연동 — 목적지 자동 선택",
            "테슬라 내비 연동 — 화면 목적지 읽음",
            "테슬라 내비 연동 — 화면 목적지 판독 실패",
            "테슬라 내비 연동 — 안내 중 경로 미리보기",
            "테슬라 내비 연동 — 안내 중 목적지 변경 감지",
            "테슬라 내비 연동 — 안내 중 카드",
            "테슬라 내비 연동 — 안내 알림 보조 문구",
            "네이버 지도 안내 실행 요청 →",
            "주소를 좌표로 못 바꿨어요:",
        )) {
            val masked = maskDiagnosticLine("12:00:00 $prefix · 1.123456,2.654321 · 비공개 목적지")
            assertFalse(masked.contains("1.123456"))
            assertFalse(masked.contains("2.654321"))
            assertFalse(masked.contains("비공개 목적지"))
            assertTrue(masked.contains("내용 가림"))
        }
        assertEquals("기기 API · 요청 HTTP 503", maskDiagnosticLine("기기 API · 요청 HTTP 503"))
    }

    /** 실제 로그 형식의 MAC과 차량 검색 이름을 가린다. */
    @Test fun `MAC과 차량 검색 이름을 가린다`() {
        val masked = maskDiagnosticLine("연결 시작 (검색 이름 S0123456789abcdefC|S0123456789abcdefD) 0A:1B:2C:3D:4E:5F")
        assertFalse(masked.contains("0123456789abcdef"))
        assertFalse(masked.contains("0A:1B"))
        assertTrue(masked.contains("S****|S****"))
        assertEquals("목적지 위메이드타워", maskDiagnosticLine("목적지 위메이드타워"))
    }

    /** 묶음은 서버 본문 상한 아래로 나뉘고 순서·내용이 유지된다. */
    @Test fun `묶음은 크기와 줄 수 상한을 지킨다`() {
        val lines = List(50) { "가".repeat(400) + it }
        val batches = diagnosticBatches(lines)
        assertTrue(batches.size > 1)
        assertEquals(lines, batches.flatten())
        assertTrue(batches.all { batch -> batch.sumOf { it.toByteArray().size + 3 } <= 6_000 })
        // 전송 시에만 자르고 커서는 원문을 보존해야 같은 시각의 다음 로그를 놓치지 않는다.
        val longLine = "x".repeat(1_500)
        assertEquals(longLine, diagnosticBatches(listOf(longLine)).single().single())
        assertEquals(3, diagnosticBatches(List(450) { "a" }).size)
    }
}
