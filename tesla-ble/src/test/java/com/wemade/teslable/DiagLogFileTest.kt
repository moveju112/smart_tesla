package com.wemade.teslable

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        val now = System.currentTimeMillis()
        current.writeText("${timestamp(now - 1_000L)} 지난 실행의 마지막 줄\n")
        DiagLog.attachFile(current, previous, nowMillis = now)
        DiagLog.addAt("이번 실행", now + 1_000L)

        val all = DiagLog.dumpAll()
        assertTrue(all.contains("지난 실행의 마지막 줄"))
        assertTrue(all.contains("이번 실행"))
        // 화면 버퍼는 이번 실행 것만 안다 — 둘의 차이가 이 기능의 존재 이유다
        assertFalse(DiagLog.dump().contains("지난 실행의 마지막 줄"))
    }

    /** 화면과 파일 모두 마지막 100줄만 남겨 저장량이 다시 커지지 않아야 한다. */
    @Test
    fun `100줄을 넘으면 가장 오래된 줄부터 지운다`() {
        val now = System.currentTimeMillis()
        DiagLog.attachFile(current, previous, maxLines = 100, nowMillis = now)
        repeat(120) { index -> DiagLog.addAt("채우는 줄 $index", now + index + 1L) }

        assertEquals(100, current.readLines().size)
        assertFalse(DiagLog.dumpAll().contains("채우는 줄 0\n"))
        assertTrue(DiagLog.dumpAll().contains("채우는 줄 119"))
    }

    /** 정확히 12시간이 된 줄도 보관 대상에서 빠져야 한다. */
    @Test
    fun `12시간 이상 지난 로그를 붙일 때 지운다`() {
        val now = System.currentTimeMillis()
        val twelveHours = 12L * 60L * 60L * 1_000L
        current.writeText(
            "${timestamp(now - twelveHours)} 만료된 줄\n" +
                "${timestamp(now - twelveHours + 1L)} 남아 있는 줄\n"
        )
        DiagLog.attachFile(current, previous, nowMillis = now)

        assertFalse(current.readText().contains("만료된 줄"))
        assertTrue(current.readText().contains("남아 있는 줄"))
    }

    /** 0.9.36까지 쓰던 연도 없는 날짜도 업데이트 직후 버리지 않고 같은 기준으로 정리한다. */
    @Test
    fun `이전 날짜 형식의 최근 로그를 이어받는다`() {
        val now = System.currentTimeMillis()
        previous.writeText("${legacyTimestamp(now - 1_000L)} 이전 형식의 줄\n")

        DiagLog.attachFile(current, previous, nowMillis = now)

        assertTrue(current.readText().contains("이전 형식의 줄"))
        assertFalse(previous.exists())
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

    /** 테스트용 시각을 실제 로그와 같은 형식으로 만든다. */
    private fun timestamp(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(millis))

    /** 이전 버전 로그가 쓰던 연도 없는 시각을 만든다. */
    private fun legacyTimestamp(millis: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(millis))
}
