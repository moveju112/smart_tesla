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
}
