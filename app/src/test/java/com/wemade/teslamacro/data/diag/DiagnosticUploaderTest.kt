package com.wemade.teslamacro.data.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticUploaderTest {
    /** 실제 로그 형식의 MAC과 차량 검색 이름을 가린다. */
    @Test fun `MAC과 차량 검색 이름을 가린다`() {
        val masked = maskDiagnosticLine("연결 시작 (검색 이름 S0123456789abcdefC|S0123456789abcdefD) 0C:4B:EE:4F:BD:E2")
        assertFalse(masked.contains("0123456789abcdef"))
        assertFalse(masked.contains("0C:4B"))
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
        assertEquals(1_000, diagnosticBatches(listOf("x".repeat(1_500))).single().single().length)
        assertEquals(3, diagnosticBatches(List(450) { "a" }).size)
    }
}
