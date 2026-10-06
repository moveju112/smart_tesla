package com.wemade.teslamacro.data.nav

import org.junit.Assert.*
import org.junit.Test

class NavigationDiagnosticsTest {
    /** 한글 바이트·제어 응답이 나뉘어 도착해도 완성된 줄만 원문대로 전달한다. */
    @Test fun fragmentedUtf8AndControlResponsesRemainSeparate() {
        val decoder = NavigationSessionOutput()
        val expected = listOf("NAVER_DIAG 화면 관측 · 상단=티맵", "NAVER_READY", "NAVER_CLOSED")
        val messages = mutableListOf<String>()
        for (byte in (expected.joinToString("\r\n") + "\r\n").toByteArray()) {
            messages.addAll(decoder.feed(byteArrayOf(byte), 1))
        }
        assertEquals(expected, messages)
    }

    /** 과도하게 긴 진단은 버리고 다음 정상 종료 응답은 복구한다. */
    @Test fun oversizedRecordDoesNotConsumeFollowingControlResponse() {
        val decoder = NavigationSessionOutput()
        val bytes = ("x".repeat(5000) + "\nNAVER_STOP_FAILED\nNAVER_CLOSED\n").toByteArray()
        assertEquals(listOf("NAVER_STOP_FAILED", "NAVER_CLOSED"), decoder.feed(bytes, bytes.size))
    }

    /** 부분 줄을 성공 응답으로 처리하지 않고 여러 줄과 다음 조각을 이어 받는다. */
    @Test fun incompleteResponseWaitsForItsNewline() {
        val decoder = NavigationSessionOutput()
        val first = "NAVER_DIAG 진입=NAVER_READY\nNAVER_".toByteArray()
        assertEquals(listOf("NAVER_DIAG 진입=NAVER_READY"), decoder.feed(first, first.size))
        val second = "BUSY\nNAVER_ERROR IllegalStateException\n".toByteArray()
        assertEquals(listOf("NAVER_BUSY", "NAVER_ERROR IllegalStateException"), decoder.feed(second, second.size))
    }

    /** 시스템 접수·거절·불명 응답을 구분하며 임의 오류 본문이나 extras는 로그에 넣지 않는다. */
    @Test fun launchResponseOnlyReportsStatusAndComponent() {
        assertEquals("상태=접수 · 진입=com.skt.tmap.ku/.Fixture",
            NaverDisplaySession.launchResultDetails("Status: ok\nActivity: com.skt.tmap.ku/.Fixture\nExtras: secret-fixture"))
        assertEquals("상태=거절 · 진입=미확인",
            NaverDisplaySession.launchResultDetails("Status: ok\nError: secret-fixture\nActivity: intent://private"))
        assertEquals("상태=불명 · 진입=미확인", NaverDisplaySession.launchResultDetails("unrecognized-response"))
    }

}
