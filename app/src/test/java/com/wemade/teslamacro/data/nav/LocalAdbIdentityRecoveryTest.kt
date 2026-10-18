package com.wemade.teslamacro.data.nav

import app.cash.paparazzi.Paparazzi
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalAdbIdentityRecoveryTest {
    @get:Rule val paparazzi = Paparazzi()
    @get:Rule val temporary = TemporaryFolder()

    /** 원본 교체 중 중단되어 백업만 남아도 기존 페어링 개인키를 복구한다. */
    @Test fun restoresIdentityFromAtomicBackup() {
        val file = temporary.root.resolve("adb.p12")
        val original = LocalAdbIdentity(file).load()
        assertTrue(file.renameTo(temporary.root.resolve("adb.p12.bak")))
        val restored = LocalAdbIdentity(file).load()
        assertArrayEquals(original.first.encoded, restored.first.encoded)
        assertArrayEquals(original.second.encoded, restored.second.encoded)
    }
}
