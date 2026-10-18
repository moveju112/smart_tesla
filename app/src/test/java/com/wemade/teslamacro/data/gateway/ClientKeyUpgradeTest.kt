package com.wemade.teslamacro.data.gateway

import android.content.ContextWrapper
import android.os.Build
import app.cash.paparazzi.Paparazzi
import com.wemade.teslable.crypto.ClientKeyStore
import com.wemade.teslable.crypto.SessionCrypto
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.ECPublicKeySpec
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClientKeyUpgradeTest {
    @get:Rule val paparazzi = Paparazzi()
    @get:Rule val temporary = TemporaryFolder()

    /** 키 저장소는 테스트 전용 파일만 읽어 실제 앱 등록에 영향을 주지 않는다. */
    private fun context() = object : ContextWrapper(paparazzi.context) {
        /** 기본 저장소 대신 임시 경로를 사용한다. */
        override fun getFilesDir() = temporary.root
    }

    /** Android 12 이상에서도 이전 OS에서 등록한 개인키와 공개키를 그대로 복원한다. */
    @Test fun preservesSoftwareIdentityAfterOsUpgrade() {
        assertTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val scalar = BigInteger.ONE
        val file = temporary.root.resolve("client_key.bin")
        file.writeBytes(scalar.toByteArray())
        val store = ClientKeyStore(context())
        assertTrue(store.exists)
        val key = store.loadOrCreate()
        assertFalse(key.isHardwareBacked)
        assertArrayEquals(SessionCrypto.privateKeyFromScalar(scalar).encoded, key.privateKey.encoded)
        val parameters = SessionCrypto.p256Params()
        val expectedPublic = KeyFactory.getInstance("EC")
            .generatePublic(ECPublicKeySpec(parameters.generator, parameters))
        assertArrayEquals(expectedPublic.encoded, key.publicKey.encoded)
        assertArrayEquals(scalar.toByteArray(), file.readBytes())
    }

    /** 원본 교체 중 중단돼 백업만 남아도 새 하드웨어 키로 바꾸지 않는다. */
    @Test fun restoresSoftwareBackupBeforeChoosingHardware() {
        val scalar = BigInteger.TWO
        temporary.root.resolve("client_key.bin.bak").writeBytes(scalar.toByteArray())
        val store = ClientKeyStore(context())
        assertTrue(store.exists)
        val key = store.loadOrCreate()
        assertFalse(key.isHardwareBacked)
        assertArrayEquals(SessionCrypto.privateKeyFromScalar(scalar).encoded, key.privateKey.encoded)
        assertArrayEquals(scalar.toByteArray(), temporary.root.resolve("client_key.bin").readBytes())
    }
}
