package com.wemade.teslamacro.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After

/** 버전 비교 검증 — 다운그레이드 APK를 "새 버전"으로 안내하는 사고를 막는 게 핵심 */
class AppUpdaterTest {

    /** 전역 업데이트 상태가 다음 검증에 넘어가지 않게 정리한다. */
    @After fun resetUpdateState() {
        AppUpdater.state.value = null
    }

    /** 화면 종료로 조회가 취소돼도 확인 버튼이 영구 비활성화되지 않는다. */
    @Test fun `cancelled release check restores the previous state and permits retry`() = runTest {
        val previous = UpdateState.Available("1.0.0", "https://example.com/app.apk")
        AppUpdater.state.value = previous
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<UpdateState>()
        val check = launch {
            AppUpdater.checkRelease {
                entered.complete(Unit)
                response.await()
            }
        }
        entered.await()
        assertEquals(UpdateState.Checking, AppUpdater.state.value)
        check.cancelAndJoin()
        assertEquals(previous, AppUpdater.state.value)
        assertTrue(AppUpdater.checkRelease { UpdateState.UpToDate })
        assertEquals(UpdateState.UpToDate, AppUpdater.state.value)
    }

    /** 자동·수동 조회가 겹쳐도 요청은 한 번만 실행하고 진행률을 덮지 않는다. */
    @Test fun `overlapping checks and active installations are not replaced`() = runTest {
        AppUpdater.state.value = null
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<UpdateState>()
        val check = launch {
            AppUpdater.checkRelease {
                entered.complete(Unit)
                response.await()
            }
        }
        entered.await()
        assertFalse(AppUpdater.checkRelease { error("중복 조회가 실행되면 안 됨") })
        assertEquals(UpdateState.Checking, AppUpdater.state.value)
        response.complete(UpdateState.UpToDate)
        check.join()
        assertEquals(UpdateState.UpToDate, AppUpdater.state.value)

        for (active in listOf(UpdateState.Downloading("1.0.0", 50), UpdateState.Installing("1.0.0"))) {
            AppUpdater.state.value = active
            assertFalse(AppUpdater.checkRelease { error("설치 중 조회가 실행되면 안 됨") })
            assertEquals(active, AppUpdater.state.value)
        }
    }

    /** 통신 오류는 취소와 구분해 다시 확인할 수 있는 실패 상태로 남긴다. */
    @Test fun `release check failure permits a later retry`() = runTest {
        AppUpdater.state.value = null
        assertTrue(AppUpdater.checkRelease { throw java.io.IOException("offline") })
        assertTrue(AppUpdater.state.value is UpdateState.Failed)
        assertTrue(AppUpdater.checkRelease { UpdateState.UpToDate })
        assertEquals(UpdateState.UpToDate, AppUpdater.state.value)
    }

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

    @Test
    fun `설치 권한을 허용하면 중단했던 릴리스를 복구한다`() {
        val release = UpdateState.Available("0.9.8", "https://example.com/app.apk")

        assertEquals(
            release,
            AppUpdater.permissionResumeTarget(
                current = UpdateState.NeedsInstallPermission,
                inMemory = release,
                persisted = null,
                permitted = true,
            ),
        )
    }

    @Test
    fun `프로세스가 재생성돼도 저장된 릴리스로 복구한다`() {
        val persisted = UpdateState.Available("0.9.8", "https://example.com/app.apk")

        assertEquals(
            persisted,
            AppUpdater.permissionResumeTarget(
                current = null,
                inMemory = null,
                persisted = persisted,
                permitted = true,
            ),
        )
    }

    @Test
    fun `권한을 허용하지 않았거나 다른 상태면 자동 재개하지 않는다`() {
        val release = UpdateState.Available("0.9.8", "https://example.com/app.apk")

        assertNull(
            AppUpdater.permissionResumeTarget(
                current = UpdateState.NeedsInstallPermission,
                inMemory = release,
                persisted = null,
                permitted = false,
            ),
        )
        assertNull(
            AppUpdater.permissionResumeTarget(
                current = UpdateState.UpToDate,
                inMemory = release,
                persisted = release,
                permitted = true,
            ),
        )
    }

    /** 다른 경로로 이미 갱신했다면 저장된 옛 대기 릴리스로 재설치·다운그레이드하지 않는다. */
    @Test
    fun `저장된 대기 릴리스가 현재 버전 이하면 재개하지 않는다`() {
        val persisted = UpdateState.Available("0.9.8", "https://example.com/app.apk")
        for (installed in listOf("0.9.8", "0.9.9")) {
            assertEquals(
                null,
                AppUpdater.permissionResumeTarget(
                    current = null, inMemory = null, persisted = persisted, permitted = true,
                    currentVersion = installed,
                ),
            )
        }
        assertEquals(
            persisted,
            AppUpdater.permissionResumeTarget(
                current = null, inMemory = null, persisted = persisted, permitted = true,
                currentVersion = "0.9.7",
            ),
        )
    }
}
