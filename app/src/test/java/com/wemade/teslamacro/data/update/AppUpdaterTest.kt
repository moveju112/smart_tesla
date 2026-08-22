package com.wemade.teslamacro.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 버전 비교 검증 — 다운그레이드 APK를 "새 버전"으로 안내하는 사고를 막는 게 핵심 */
class AppUpdaterTest {

    @Test
    fun `자리별 수치로 비교한다`() {
        assertTrue(AppUpdater.isNewer("0.8.21", "0.8.20"))
        assertTrue(AppUpdater.isNewer("0.9.0", "0.8.99"))
        assertTrue(AppUpdater.isNewer("1.0.0", "0.9.9"))
        // 문자열 비교라면 "0.8.9" > "0.8.10"으로 뒤집힌다
        assertTrue(AppUpdater.isNewer("0.8.10", "0.8.9"))
    }

    @Test
    fun `같거나 낮으면 새 버전이 아니다`() {
        assertFalse(AppUpdater.isNewer("0.8.20", "0.8.20"))
        assertFalse(AppUpdater.isNewer("0.8.19", "0.8.20"))
        // 릴리스보다 앞선 로컬 빌드 — 여기서 true면 다운그레이드를 권하게 된다
        assertFalse(AppUpdater.isNewer("0.8.20", "0.9.0"))
    }

    @Test
    fun `자리 수가 달라도 짧은 쪽을 0으로 채운다`() {
        assertTrue(AppUpdater.isNewer("0.8.1", "0.8"))
        assertFalse(AppUpdater.isNewer("0.8", "0.8.1"))
        assertFalse(AppUpdater.isNewer("0.8.0", "0.8"))
    }

    // 하루 한 번 스로틀 — 서비스가 재시작될 때마다 GitHub를 두드리면 안 된다
    @Test
    fun `마지막 확인이 하루가 안 됐으면 건너뛴다`() {
        val day = 24L * 60 * 60 * 1000
        assertFalse(AppUpdater.isCheckDue(lastCheckMillis = 1_000, nowMillis = 1_000 + day - 1))
        assertTrue(AppUpdater.isCheckDue(lastCheckMillis = 1_000, nowMillis = 1_000 + day))
        // 한 번도 확인한 적 없으면(0) 바로 확인한다
        assertTrue(AppUpdater.isCheckDue(lastCheckMillis = 0, nowMillis = day))
    }

    @Test
    fun `꼬리표는 무시하고 빈 문자열은 새 버전이 아니다`() {
        assertTrue(AppUpdater.isNewer("0.8.21-beta", "0.8.20"))
        // 태그를 못 읽었을 때 업데이트를 권하면 안 된다
        assertFalse(AppUpdater.isNewer("", "0.8.20"))
    }

    @Test
    fun `릴리스 본문에서 헤딩과 빈 줄을 걷는다`() {
        val raw = "## 0.9.1\n\n- 잠든 차 자동 깨우기\n\n- 거부 사유 표시\n"
        assertEquals("0.9.1\n- 잠든 차 자동 깨우기\n- 거부 사유 표시", tidyNotes(raw))
    }

    @Test
    fun `쓸 내용이 없으면 표시하지 않는다`() {
        assertNull(tidyNotes(""))
        assertNull(tidyNotes("\n\n###\n"))
    }

    /** 설정 화면은 좁다. 길면 자르되 잘렸다는 걸 숨기지 않는다 */
    @Test
    fun `길면 자르고 잘렸음을 표시한다`() {
        val raw = (1..10).joinToString("\n") { "줄 $it" }
        val tidied = tidyNotes(raw, maxLines = 3)
        assertEquals("줄 1\n줄 2\n줄 3\n…", tidied)
    }
}
