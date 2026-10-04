package com.wemade.teslamacro.history

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import com.wemade.teslamacro.data.history.VehicleHistoryStore
import com.wemade.teslamacro.domain.model.ShiftState
import com.wemade.teslamacro.domain.model.StateCategory
import com.wemade.teslamacro.domain.model.VehicleSnapshot
import kotlinx.coroutines.runBlocking
import java.io.File

/** Layoutlib이 지원하지 않는 SQLite 저장은 실제 Android에서 독립 임시 DB로 확인한다. */
class HistorySmokeInstrumentation : Instrumentation() {
    /** 외부 테스트 프레임워크를 추가하지 않고 플랫폼 실행기를 시작한다. */
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    /** 실제 앱 DB를 열지 않고 트랜잭션·재조회·차량 분리·중지 경계를 검증한다. */
    override fun onStart() {
        val databases = mutableListOf<SQLiteDatabase>()
        val directory = File(targetContext.cacheDir, "history-smoke-${System.nanoTime()}").apply { check(mkdirs()) }
        val result = Bundle()
        var resultCode = Activity.RESULT_OK
        try {
            val context = object : ContextWrapper(targetContext) {
                /** 애플리케이션 문맥도 테스트 소유 경로를 유지한다. */
                override fun getApplicationContext(): Context = this
                /** 테스트 DB 경로를 앱 캐시의 독립 디렉터리로 한정한다. */
                override fun getDatabasePath(name: String): File = File(directory, name)
                /** 기본 DB 열기도 실제 앱 데이터와 분리한다. */
                override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                    SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory).also(databases::add)
                /** 오류 처리기가 있는 SQLiteOpenHelper 경로도 같은 임시 DB를 쓴다. */
                override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?): SQLiteDatabase =
                    SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, handler).also(databases::add)
            }
            runBlocking {
                val identity = "5YJS0000000000000"
                val store = VehicleHistoryStore(context)
                store.record(snapshot(10_000, ShiftState.DRIVE, 100, 80), identity, 10_000)
                store.record(snapshot(11_000, ShiftState.DRIVE, 105, 80), identity, 11_000)
                store.record(snapshot(15_000, ShiftState.DRIVE, 110, 79), identity, 15_000)
                store.record(snapshot(20_000, ShiftState.PARK, 120, 79), identity, 20_000)
                val reopened = VehicleHistoryStore(context)
                val overview = reopened.overview(identity)
                check(overview.error == null) { overview.error.orEmpty() }
                val trip = overview.sessions.single()
                check(trip.samples == 3 && trip.complete)
                check(kotlin.math.abs(trip.distanceKm!! - 0.3218688) < 0.0000001)
                check(reopened.samples(identity, trip.id).size == 3)
                check(reopened.samples("another-test-vehicle", trip.id).isEmpty())
                check(overview.storageBytes > 0)
                store.record(snapshot(25_000, ShiftState.DRIVE, 130, 78), identity, 25_000)
                store.finish(identity)
                store.record(snapshot(30_000, ShiftState.DRIVE, 140, 78), identity, 30_000)
                check(store.overview(identity).sessions.size == 3)
                // 같은 슬롯의 P단도 끝점으로 저장하고 BLE 지연은 경로 공백만 남긴다.
                val delayedIdentity = "delayed-test-vehicle"
                store.record(snapshot(100_000, ShiftState.DRIVE, 1000, 84), delayedIdentity, 100_000)
                store.record(snapshot(130_000, ShiftState.DRIVE, 1100, 83), delayedIdentity, 130_000)
                store.record(snapshot(132_000, ShiftState.PARK, 1120, 83), delayedIdentity, 132_000)
                val delayedTrip = reopened.overview(delayedIdentity).sessions.single()
                check(delayedTrip.samples == 3 && delayedTrip.complete && delayedTrip.hasGaps)
                check(delayedTrip.firstBattery == 84 && delayedTrip.lastBattery == 83)
                check(delayedTrip.end == 132_000L)
                check(kotlin.math.abs(delayedTrip.distanceKm!! - 1.9312128) < 0.0000001)
                check(reopened.samples(delayedIdentity, delayedTrip.id).map { it.time } == listOf(100_000L, 130_000L, 132_000L))
                val missingIdentity = "missing-gear-test-vehicle"
                store.record(snapshot(200_000, ShiftState.DRIVE, 1000, 84), missingIdentity, 200_000)
                for (time in 205_000L..325_000L step 5_000L) {
                    store.record(VehicleSnapshot(time, categoryReadAt = mapOf(StateCategory.BODY_CONTROLLER to time)), missingIdentity, time)
                }
                check(!store.overview(missingIdentity).sessions.single().complete)
                result.putString("stream", "PASS: SQLite 저장·재조회·5초 중복 방지·종료 경계·차량 분리·명시적 중지·지연 주행 연속성·같은 슬롯 P단")
            }
        } catch (error: Throwable) {
            result.putString("stream", "FAIL: ${error.javaClass.simpleName}: ${error.message}")
            resultCode = Activity.RESULT_CANCELED
        } finally {
            databases.forEach { runCatching { it.close() } }
            // 이 실행이 만든 임시 DB 파일만 정리하고 앱의 실제 기록에는 접근하지 않는다.
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
        finish(resultCode, result)
    }

    /** 더미 차량 응답은 거리와 배터리 관측 범위를 명확하게 만든다. */
    private fun snapshot(time: Long, shift: ShiftState, odometer: Int, battery: Int) = VehicleSnapshot(
        time, categoryReadAt = mapOf(StateCategory.DRIVE to time, StateCategory.CHARGE to time),
        shiftState = shift, odometerHundredthsMile = odometer, batteryLevelPercent = battery,
    )
}
