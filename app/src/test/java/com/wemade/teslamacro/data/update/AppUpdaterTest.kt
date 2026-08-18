package com.wemade.teslamacro.data.update

import org.junit.Assert.assertFalse
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

    @Test
    fun `꼬리표는 무시하고 빈 문자열은 새 버전이 아니다`() {
        assertTrue(AppUpdater.isNewer("0.8.21-beta", "0.8.20"))
        // 태그를 못 읽었을 때 업데이트를 권하면 안 된다
        assertFalse(AppUpdater.isNewer("", "0.8.20"))
    }
}
