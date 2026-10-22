package com.wemade.teslamacro.data.fleet

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import javax.crypto.SecretKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class FleetHistoryEntry(val receipt: FleetReceipt, val updatedAt: Long, val refreshAfter: Long = 0) {
    val canRefresh: Boolean get() = receipt.id != null &&
        (receipt.pending || receipt.status == FleetQueueStatus.Unknown)
}

/** 서버 명령은 복원해 재실행하지 않고, 결과 조회에 필요한 최근 20건만 암호화해 보관한다. */
class FleetHistoryStore internal constructor(
    private val file: AtomicFile,
    private val keyProvider: () -> SecretKey,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** 토큰과 다른 Keystore 키 및 백업 제외 파일을 사용한다. */
    constructor(context: Context) : this(
        AtomicFile(File(context.noBackupFilesDir, "fleet-history.enc")),
        { FleetTokenStore.key("smart_tesla.fleet_history.v1") },
    )

    private val mutableEntries = MutableStateFlow<List<FleetHistoryEntry>>(emptyList())
    val entries = mutableEntries.asStateFlow()
    private var loaded = false
    private val serializer = ListSerializer(FleetHistoryEntry.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    /** 파일을 읽는 것만으로 네트워크·효과음·차량 명령을 발생시키지 않는다. IO에서 호출한다. */
    @Synchronized
    fun load() {
        if (loaded) return
        mutableEntries.value = try {
            json.decodeFromString(serializer, FleetTokenCipher.decrypt(file.readFully(), keyProvider())).takeLast(20)
        } catch (_: FileNotFoundException) { emptyList() }
        catch (_: Exception) { error("Fleet 결과 기록을 읽지 못했어요") }
        loaded = true
    }

    /** GET 결과는 같은 요청을 갱신하고 서버 재조회 기한도 재시작 뒤 보존한다. */
    @Synchronized
    fun record(receipt: FleetReceipt) {
        load()
        val previous = mutableEntries.value.firstOrNull { it.receipt.requestKey == receipt.requestKey }
        // 늦은 조회 응답이 이미 확인된 종료 결과를 다시 대기/미확인으로 돌리지 않는다.
        if (previous != null && !previous.canRefresh && previous.receipt.id != null) return
        val now = clock()
        val wait = receipt.retryAfterMillis?.coerceIn(0L, Long.MAX_VALUE - now) ?: 0L
        val updated = (mutableEntries.value.filterNot { it.receipt.requestKey == receipt.requestKey } +
            FleetHistoryEntry(receipt, now, maxOf(previous?.refreshAfter ?: 0L, now + wait))).takeLast(20)
        mutableEntries.value = updated
        val encrypted = FleetTokenCipher.encrypt(json.encodeToString(serializer, updated), keyProvider())
        val stream = file.startWrite()
        try { stream.write(encrypted); file.finishWrite(stream) }
        catch (_: Exception) { file.failWrite(stream); error("Fleet 결과 기록을 저장하지 못했어요") }
    }
}
