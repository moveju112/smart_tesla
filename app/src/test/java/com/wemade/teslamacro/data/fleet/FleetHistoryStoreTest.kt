package com.wemade.teslamacro.data.fleet

import android.util.AtomicFile
import app.cash.paparazzi.Paparazzi
import java.io.File
import javax.crypto.KeyGenerator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 저장 암호화와 재시작 복원은 테스트 AES 키만 사용하며 Android Keystore 검증과 구분한다. */
class FleetHistoryStoreTest {
    @get:Rule val paparazzi = Paparazzi()
    @get:Rule val temporary = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").generateKey()
    private val vin = "5YJS0000000000000"
    private val receipt = FleetReceipt("dummy-id", "dummy-key", vin, "charge_start", FleetQueueStatus.Running,
        "waiting_for_vehicle_online", accountFingerprint = fleetAccountFingerprint("dummy-token"))

    /** 재시작 뒤 ID·계정 지문을 복원하고 조회만 수행하며 VIN과 요청 ID는 평문으로 남기지 않는다. */
    @Test fun restartRestoresEncryptedReceiptAndRefreshUsesGetOnly() = runTest {
        val file = File(temporary.root, "history.enc")
        val store = FleetHistoryStore(AtomicFile(file), { key })
        store.record(receipt)
        val bytes = file.readBytes().toString(Charsets.UTF_8)
        assertFalse(bytes.contains(vin))
        assertFalse(bytes.contains("dummy-id"))
        val restored = FleetHistoryStore(AtomicFile(file), { key })
        restored.load()
        assertEquals(receipt, restored.entries.value.single().receipt)
        val calls = mutableListOf<String>()
        var tones = 0
        val client = FleetQueuedClient(FleetHttpTransport { method, path, _, body, requestKey ->
            calls += method
            assertEquals("/v1/commands/dummy-id", path)
            assertNull(body)
            assertNull(requestKey)
            FleetHttpResponse(200, """{"id":"dummy-id","vin":"$vin","name":"charge_start","status":"succeeded"}""")
        }, { "dummy-token" }) { tones++ }
        val result = client.refresh(restored.entries.value.single().receipt)
        restored.record(result)
        assertEquals(listOf("GET"), calls)
        assertEquals(0, tones)
        assertFalse(restored.entries.value.single().canRefresh)
        restored.record(receipt)
        assertEquals(FleetQueueStatus.Succeeded, restored.entries.value.single().receipt.status)
    }

    /** 서버 제한 기한과 미확인을 보존하고 최근 20건 이외의 오래된 기록은 파일에 늘리지 않는다. */
    @Test fun retryAfterAndBoundedHistorySurviveRestart() {
        val file = File(temporary.root, "history.enc")
        val store = FleetHistoryStore(AtomicFile(file), { key }, { 1_000L })
        for (index in 0..20) store.record(receipt.copy(requestKey = "request-$index", id = "id-$index"))
        store.record(receipt.copy(requestKey = "request-20", id = "id-20",
            status = FleetQueueStatus.Unknown, result = "result_unavailable", retryAfterMillis = 8_000L))
        val restored = FleetHistoryStore(AtomicFile(file), { key })
        restored.load()
        assertEquals(20, restored.entries.value.size)
        assertFalse(restored.entries.value.any { it.receipt.requestKey == "request-0" })
        assertEquals(9_000L, restored.entries.value.last().refreshAfter)
        assertTrue(restored.entries.value.last().canRefresh)
        assertFalse(FleetHistoryEntry(receipt.copy(id = null), 0).canRefresh)
    }

    /** 자동 관찰이 먼저 끝나더라도 서버가 지정한 더 긴 재조회 기한은 지우지 않는다. */
    @Test fun observationTimeoutCannotShortenServerBackoff() {
        val file = File(temporary.root, "timeout.enc")
        var now = 1_000L
        val store = FleetHistoryStore(AtomicFile(file), { key }, { now })
        store.record(receipt.copy(status = FleetQueueStatus.Unknown, result = "result_unavailable", retryAfterMillis = 120_000L))
        now = 61_000L
        store.record(receipt.copy(status = FleetQueueStatus.Unknown, result = "result_wait_timeout"))
        val restored = FleetHistoryStore(AtomicFile(file), { key })
        restored.load()
        assertEquals(121_000L, restored.entries.value.single().refreshAfter)
    }

    /** 손상·다른 키는 안전한 오류로 끝내고 파일을 빈 기록으로 덮어쓰지 않는다. */
    @Test fun corruptOrWrongKeyHistoryIsNotSilentlyReplaced() {
        val file = File(temporary.root, "history.enc")
        FleetHistoryStore(AtomicFile(file), { key }).record(receipt)
        val original = file.readBytes()
        val wrongKey = KeyGenerator.getInstance("AES").generateKey()
        val restored = FleetHistoryStore(AtomicFile(file), { wrongKey })
        val error = assertThrows(IllegalStateException::class.java) { restored.record(receipt) }
        assertFalse(error.message.orEmpty().contains(vin))
        assertArrayEquals(original, file.readBytes())
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertThrows(IllegalStateException::class.java) { FleetHistoryStore(AtomicFile(file), { key }).load() }
    }
}
