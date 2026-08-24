package com.wemade.teslable

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Test

/**
 * 진단 로그의 파일 보관.
 *
 * 이 앱이 사는 곳은 차내 태블릿이라 `adb logcat`을 붙일 수 없다 —
 * 기기 혼자 로그를 모아 공유 시트로 내보내는 게 유일한 통로다.
 * 그 통로가 조용히 깨지면 실차 문제를 영영 못 본다.
 */
class DiagLogFileTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var current: java.io.File
    private lateinit var previous: java.io.File

    @Before
    fun setUp() {
        current = folder.newFile("diag.log")
        previous = folder.newFile("diag-prev.log")
        previous.delete()   // 아직 밀려난 세대가 없는 상태에서 시작한다
        DiagLog.clear()
    }

    @After
    fun tearDown() {
        DiagLog.clear()
    }

    @Test
    fun `붙이면 파일에 남는다`() {
        DiagLog.attachFile(current, previous)
        DiagLog.add("차량 전원 연결")

        assertTrue(current.readText().contains("차량 전원 연결"))
    }

    /** 재시작 경계를 파일에서 눈으로 찾을 수 있어야 원인이 어느 실행의 것인지 갈린다 */
    @Test
    fun `붙일 때 시작 표시를 남긴다`() {
        DiagLog.attachFile(current, previous)

        assertTrue(current.readText().contains("앱 시작"))
    }

    /**
     * 앱이 재시작해도 앞선 기록이 남아야 한다.
     * 메모리 버퍼만 보내면 정작 원인이 있는 구간이 빠진다.
     */
    @Test
    fun `dumpAll은 화면 버퍼보다 앞선 것까지 담는다`() {
        current.writeText("11-01 09:00:00.000 지난 실행의 마지막 줄\n")
        DiagLog.attachFile(current, previous)
        DiagLog.add("이번 실행")

        val all = DiagLog.dumpAll()
        assertTrue(all.contains("지난 실행의 마지막 줄"))
        assertTrue(all.contains("이번 실행"))
        // 화면 버퍼는 이번 실행 것만 안다 — 둘의 차이가 이 기능의 존재 이유다
        assertFalse(DiagLog.dump().contains("지난 실행의 마지막 줄"))
    }

    /** 상한을 넘으면 한 세대 밀고 새로 쓴다. 살아 있는 두 세대는 공유에 함께 실린다 */
    @Test
    fun `가득 차면 직전 세대로 밀고 새로 쓴다`() {
        DiagLog.attachFile(current, previous, maxBytes = 400)

        // 딱 한 번 밀릴 때까지만 채운다. 더 채우면 첫 줄은 규칙대로 버려지므로
        // "첫 줄이 남아 있는가"로 검사하면 안 된다
        var n = 0
        while (!previous.exists() && n < 500) DiagLog.add("채우는 줄 ${n++}")
        assertTrue("직전 세대가 만들어져야 한다", previous.exists())

        val movedLine = previous.readLines().last { it.isNotBlank() }
        DiagLog.add("밀린 뒤의 줄")

        val all = DiagLog.dumpAll()
        assertTrue("밀려난 세대도 공유에 실린다", all.contains(movedLine))
        assertTrue("새 세대도 함께 실린다", all.contains("밀린 뒤의 줄"))
    }

    /** 세 세대째가 오면 가장 오래된 것은 버린다 — 무한히 쌓이면 안 된다 */
    @Test
    fun `두 세대만 남는다`() {
        DiagLog.attachFile(current, previous, maxBytes = 120)
        repeat(60) { DiagLog.add("줄 $it") }

        assertTrue(current.length() + previous.length() < 120 * 3)
    }

    @Test
    fun `지우면 파일도 사라진다`() {
        DiagLog.attachFile(current, previous)
        DiagLog.add("남길 것 없음")
        DiagLog.clear()

        assertFalse(current.exists())
        assertEquals("", DiagLog.dump())
    }

    /** 파일을 안 붙인 상태에서도 죽지 않아야 한다 (테스트·초기화 전 순간) */
    @Test
    fun `파일이 없으면 메모리만 쓴다`() {
        DiagLog.add("파일 없이도 남는다")

        assertTrue(DiagLog.dump().contains("파일 없이도 남는다"))
        assertTrue(DiagLog.dumpAll().contains("파일 없이도 남는다"))
    }
}
