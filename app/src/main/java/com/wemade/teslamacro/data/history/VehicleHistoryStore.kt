package com.wemade.teslamacro.data.history

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.wemade.teslamacro.domain.model.VehicleSnapshot
import com.wemade.teslable.DiagLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.security.MessageDigest
import java.util.UUID

data class HistoryOverview(
    val sessions: List<HistorySession> = emptyList(),
    val latest: HistorySample? = null,
    val sampleCount: Long = 0,
    val storageBytes: Long = 0,
    val hasMore: Boolean = false,
    val error: String? = null,
    val insights: HistoryInsights = HistoryInsights(),
)

/** SQLite 트랜잭션에 압축 표본과 요약을 함께 저장해 종료 직전에도 두 값이 어긋나지 않는다. */
class VehicleHistoryStore(context: Context) {
    private val databaseFile = context.getDatabasePath("vehicle_history.db")
    private val helper = HistoryDatabase(context.applicationContext)
    private val mutex = Mutex()
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()
    private var lastError: String? = null
    private var insightVehicle: String? = null
    private val energyCache = mutableMapOf<String, Pair<HistorySession, HistoryEnergy>>()
    private var previewCache: Pair<HistorySession, List<HistorySample>>? = null

    /** 차량 식별 원문은 DB에 담지 않고 차량별 분리 키만 만든다. */
    private fun vehicleKey(identity: String): String = MessageDigest.getInstance("SHA-256")
        .digest(identity.uppercase(java.util.Locale.ROOT).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    /** 실제 이번 응답만 5초 슬롯에 한 번 저장하고 시뮬레이터 필터는 호출자가 맡는다. */
    suspend fun record(snapshot: VehicleSnapshot, identity: String, time: Long) = withContext(Dispatchers.IO) {
        if (identity.isBlank() || snapshot.categoryReadAt.isEmpty()) return@withContext
        mutex.withLock {
            try {
                val database = helper.writableDatabase
                val key = vehicleKey(identity)
                val sample = VehicleHistory.sample(snapshot, time)
                database.beginTransaction()
                try {
                    val old = latestSession(database, key)
                    val previous = old?.let { readLastSample(database, it.id) }
                    val kind = VehicleHistory.kind(sample, old?.takeUnless { isClosed(database, it.id) })
                    if (old != null && old.kind == kind && previous != null && !isClosed(database, old.id) &&
                        previous.time / VehicleHistory.SAMPLE_MILLIS == time / VehicleHistory.SAMPLE_MILLIS) {
                        database.setTransactionSuccessful()
                        return@withLock
                    }
                    val continuing = old != null && previous != null && !isClosed(database, old.id) &&
                        VehicleHistory.continues(old, sample)
                    if (old != null && !continuing && !isClosed(database, old.id)) {
                        val boundaryObserved = previous != null &&
                            sample.time - previous.time in 1..VehicleHistory.SESSION_GAP_MILLIS
                        // P단·충전 종료 응답의 최종 거리와 충전량까지 이전 구간에 포함한다.
                        val ended = if (boundaryObserved) VehicleHistory.append(old, previous, sample) else old
                        if (boundaryObserved) saveSample(database, old.id, sample)
                        // 기어 미수신의 유효시간 만료는 실제 종료를 관측한 것으로 표시하지 않는다.
                        val transitionObserved = sample.shift in setOf("DRIVE", "REVERSE", "PARK") ||
                            sample.charging == true || (old.kind == HistoryKind.CHARGE && sample.charging == false)
                        saveSession(database, key, ended.copy(complete = boundaryObserved && transitionObserved), closed = true)
                    }
                    val base = if (continuing) old!! else HistorySession(UUID.randomUUID().toString(), kind, time, time)
                    val session = VehicleHistory.append(base, previous.takeIf { continuing }, sample)
                    saveSample(database, session.id, sample)
                    saveSession(database, key, session, closed = false)
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
                lastError = null
                _revision.value++
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (lastError == null) DiagLog.add("차량 기록 저장 실패 — 기존 기록 유지, 다음 수신 때 재시도")
                lastError = "기록 저장 실패 · 저장공간을 확인해 주세요"
                _revision.value++
            }
        }
    }

    /** 기록을 끈 경계를 닫아 다시 켰을 때 미관측 구간이 같은 주행으로 합쳐지지 않게 한다. */
    suspend fun finish(identity: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val database = helper.writableDatabase
                val key = vehicleKey(identity)
                latestSession(database, key)?.let { saveSession(database, key, it, closed = true) }
                _revision.value++
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { DiagLog.add("차량 기록 종료 저장 실패") }
        }
    }

    /** 목록은 요약을 읽고 전비에 필요한 원본 계산은 세션별 캐시를 재사용한다. */
    suspend fun overview(identity: String, limit: Int = 50, days: Int = 30): HistoryOverview = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val database = helper.readableDatabase
                val key = vehicleKey(identity)
                val sessions = mutableListOf<HistorySession>()
                database.rawQuery("SELECT summary FROM sessions WHERE vehicle=? AND kind<>? ORDER BY end DESC LIMIT ?",
                    arrayOf(key, HistoryKind.PARK.name, (limit + 1).toString())).use { cursor ->
                    while (cursor.moveToNext()) sessions += VehicleHistory.json.decodeFromString<HistorySession>(cursor.getString(0))
                }
                val count = database.rawQuery("SELECT COALESCE(SUM(sample_count),0) FROM sessions WHERE vehicle=?", arrayOf(key))
                    .use { it.moveToFirst(); it.getLong(0) }
                HistoryOverview(sessions.take(limit), latestSession(database, key)?.let { readLastSample(database, it.id) },
                    count, databaseFile.length() + java.io.File(databaseFile.path + "-wal").length() +
                        java.io.File(databaseFile.path + "-shm").length(), sessions.size > limit, lastError,
                    readInsights(database, key, sessions.take(limit), days))
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { HistoryOverview(error = "기록을 읽지 못했어요. 기존 데이터는 유지돼요.") }
        }
    }

    /** 차량 소유권을 함께 검사해 등록 차량을 바꿔도 이전 차량의 경로를 섞지 않는다. */
    suspend fun samples(identity: String, sessionId: String): List<HistorySample> = withContext(Dispatchers.IO) {
        mutex.withLock {
            readSamples(helper.readableDatabase, vehicleKey(identity), sessionId)
        }
    }

    /** 기간 전체를 목록 페이지와 독립적으로 읽고 바뀌지 않은 원본은 다시 압축 해제하지 않는다. */
    private fun readInsights(database: SQLiteDatabase, key: String, visible: List<HistorySession>, days: Int): HistoryInsights {
        val today = java.time.LocalDate.now()
        val zone = java.time.ZoneId.systemDefault()
        if (insightVehicle != key) {
            insightVehicle = key
            energyCache.clear()
            previewCache = null
        }
        return try {
            val since = today.minusDays(days.toLong() * 2 - 1).atStartOfDay(zone).toInstant().toEpochMilli()
            val sessions = linkedMapOf<String, HistorySession>()
            database.rawQuery("SELECT summary FROM sessions WHERE vehicle=? AND kind=? AND end>=? ORDER BY end DESC",
                arrayOf(key, HistoryKind.DRIVE.name, since.toString())).use { cursor ->
                while (cursor.moveToNext()) {
                    val session = VehicleHistory.json.decodeFromString<HistorySession>(cursor.getString(0))
                    sessions[session.id] = session
                }
            }
            visible.filter { it.kind == HistoryKind.DRIVE }.forEach { sessions[it.id] = it }
            energyCache.keys.retainAll(sessions.keys)
            val preview = visible.firstOrNull { it.kind == HistoryKind.DRIVE }
            if (preview == null) previewCache = null
            sessions.values.forEach { session ->
                val cached = energyCache[session.id]
                if (cached?.first != session || (session == preview && previewCache?.first != session)) {
                    val points = readSamples(database, key, session.id)
                    energyCache[session.id] = session to historyEnergy(points)
                    if (session == preview) previewCache = session to points
                }
            }
            historyInsights(sessions.values.toList(), energyCache.mapValues { it.value.second }, days, today, zone)
                .copy(previewSessionId = previewCache?.first?.id, previewSamples = previewCache?.second.orEmpty())
        } catch (error: CancellationException) { throw error
        } catch (_: Exception) { HistoryInsights(days = days, error = "주행 요약을 읽지 못했어요") }
    }

    /** 상세와 요약 모두 차량 소유권을 확인한 동일 원본 조회를 사용한다. */
    private fun readSamples(database: SQLiteDatabase, key: String, sessionId: String): List<HistorySample> {
        val result = mutableListOf<HistorySample>()
        database.rawQuery(
            "SELECT b.payload FROM blocks b JOIN sessions s ON s.id=b.session_id WHERE s.vehicle=? AND s.id=? ORDER BY b.bucket",
            arrayOf(key, sessionId),
        ).use { cursor -> while (cursor.moveToNext()) result += VehicleHistory.decode(cursor.getBlob(0)) }
        return result
    }

    /** 시계가 바뀌어도 마지막으로 기록한 세션을 재개 후보로 사용한다. */
    private fun latestSession(database: SQLiteDatabase, key: String): HistorySession? =
        database.rawQuery("SELECT summary FROM sessions WHERE vehicle=? ORDER BY rowid DESC LIMIT 1", arrayOf(key))
            .use { if (it.moveToFirst()) VehicleHistory.json.decodeFromString<HistorySession>(it.getString(0)) else null }

    /** 프로세스 재시작 후에도 명시적 종료 상태를 보존한다. */
    private fun isClosed(database: SQLiteDatabase, id: String): Boolean =
        database.rawQuery("SELECT closed FROM sessions WHERE id=?", arrayOf(id)).use { it.moveToFirst() && it.getInt(0) != 0 }

    /** 직전 표본은 마지막 압축 묶음 하나만 읽어 복구한다. */
    private fun readLastSample(database: SQLiteDatabase, id: String): HistorySample? =
        database.rawQuery("SELECT payload FROM blocks WHERE session_id=? ORDER BY bucket DESC LIMIT 1", arrayOf(id))
            .use { if (it.moveToFirst()) VehicleHistory.decode(it.getBlob(0)).lastOrNull() else null }

    /** 같은 시간 묶음만 갱신하므로 이력이 늘어도 매 저장 비용은 일정하다. */
    private fun readBlock(database: SQLiteDatabase, id: String, block: Long): List<HistorySample> =
        database.rawQuery("SELECT payload FROM blocks WHERE session_id=? AND bucket=?", arrayOf(id, block.toString()))
            .use { if (it.moveToFirst()) VehicleHistory.decode(it.getBlob(0)) else emptyList() }

    /** 전환 경계 표본은 양쪽 구간의 끝·시작 근거로 같은 원문을 보관한다. */
    private fun saveSample(database: SQLiteDatabase, id: String, sample: HistorySample) {
        val block = sample.time / VehicleHistory.BLOCK_MILLIS
        val points = readBlock(database, id, block) + sample
        val values = ContentValues().apply {
            put("session_id", id); put("bucket", block); put("payload", VehicleHistory.encode(points))
        }
        check(database.insertWithOnConflict("blocks", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
    }

    /** 요약과 검색 열을 같은 값으로 저장한다. */
    private fun saveSession(database: SQLiteDatabase, key: String, session: HistorySession, closed: Boolean) {
        val values = ContentValues().apply {
            put("vehicle", key); put("kind", session.kind.name); put("end", session.end)
            put("sample_count", session.samples); put("closed", if (closed) 1 else 0)
            put("summary", VehicleHistory.json.encodeToString(session))
        }
        if (database.update("sessions", values, "id=?", arrayOf(session.id)) == 0) {
            values.put("id", session.id)
            check(database.insertOrThrow("sessions", null, values) != -1L)
        }
    }
}

/** 기존 Android SQLite로 충분해 추가 저장소 프레임워크를 도입하지 않는다. */
private class HistoryDatabase(context: Context) : SQLiteOpenHelper(context, "vehicle_history.db", null, 1) {
    /** 쓰기 중 조회도 가능하게 하되 모든 변경은 저장소 mutex와 트랜잭션으로 직렬화한다. */
    override fun onConfigure(database: SQLiteDatabase) { database.enableWriteAheadLogging() }

    /** 기간·차량 목록은 요약 인덱스만 읽고 원본은 독립 압축 BLOB에 보관한다. */
    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY, vehicle TEXT NOT NULL, kind TEXT NOT NULL, end INTEGER NOT NULL, sample_count INTEGER NOT NULL, closed INTEGER NOT NULL, summary TEXT NOT NULL)")
        database.execSQL("CREATE INDEX sessions_vehicle_end ON sessions(vehicle,end DESC)")
        database.execSQL("CREATE TABLE blocks(session_id TEXT NOT NULL, bucket INTEGER NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(session_id,bucket))")
    }

    /** 지원하지 않는 버전은 초기화하지 않고 열기를 실패시켜 원본을 지킨다. */
    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("차량 기록 스키마 변경 절차 필요")
    }
}
