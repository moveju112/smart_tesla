package com.wemade.teslamacro.feature.settings

import com.wemade.teslamacro.data.backup.BackupFile
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 새 형식을 구버전 앱으로 복원해 알 수 없는 필드가 사라지는 일을 막는다. */
class BackupVersionTest {
    @Test
    fun `legacy and current backup versions remain restorable`() {
        for (version in listOf(1, 5, BackupFile.CURRENT_VERSION)) {
            requireSupportedBackupVersion(version)
        }
    }

    @Test
    fun `future and invalid backup versions are rejected before restore`() {
        for (version in listOf(0, -1, BackupFile.CURRENT_VERSION + 1)) {
            try {
                requireSupportedBackupVersion(version)
                fail("Unsupported backup version $version must be rejected")
            } catch (error: IllegalArgumentException) {
                assertTrue(error.message.orEmpty().contains("지원하지 않는 백업 형식"))
            }
        }
    }

    /** 내보낸 파일이 아닌 JSON은 기본값 설정으로 덮어쓰기 전에 거부한다. */
    @Test
    fun `json without backup version is rejected`() {
        for (text in listOf("{\"foo\":1}", "[]", "not json")) {
            try {
                decodeBackupFile(text)
                fail("Non-backup JSON must be rejected: $text")
            } catch (error: IllegalArgumentException) {
                assertTrue(error.message.orEmpty().contains("백업 파일이 아니에요"))
            }
        }
        val exported = BackupFile.json.encodeToString(BackupFile.serializer(), BackupFile(createdAtMillis = 1L))
        assertTrue(decodeBackupFile(exported).createdAtMillis == 1L)
    }
}
