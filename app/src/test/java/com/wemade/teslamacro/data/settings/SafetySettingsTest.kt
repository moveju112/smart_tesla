package com.wemade.teslamacro.data.settings

import android.content.ContextWrapper
import android.app.Application
import android.location.Location
import com.wemade.teslamacro.data.location.freshSpeedKph
import com.wemade.teslamacro.data.safety.RoadPoint
import com.wemade.teslamacro.data.safety.SafeDriveGuide
import com.wemade.teslamacro.data.safety.warningIntervalMillis
import com.wemade.teslamacro.domain.safety.CameraIndex
import com.wemade.teslamacro.domain.safety.OfflineCamera
import com.wemade.teslamacro.domain.safety.SafetyState
import com.wemade.teslable.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.paparazzi.Paparazzi
import com.wemade.teslamacro.data.backup.BackupFile
import com.wemade.teslamacro.data.backup.BackupSettings
import com.wemade.teslamacro.data.backup.toBackup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SafetySettingsTest {
    @get:Rule val paparazzi = Paparazzi()
    @get:Rule val temporaryFolder = TemporaryFolder()

    /** 기본값·저장·범위 제한·복원을 같은 Store 계약으로 확인한다. */
    @Test fun tolerancePersistsAndRestores() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(temporaryFolder.root, "safety.preferences_pb")
        }
        val store = SettingsStore(ContextWrapper(paparazzi.context), preferences)
        assertEquals(5, store.settings.first().safeDriveToleranceKph)
        assertTrue(store.settings.first().safeDriveProgressiveSound)
        assertEquals(500, store.settings.first().safeDriveAlertDistanceMeters)
        assertTrue(store.settings.first().safeDriveVoice)
        assertFalse(store.settings.first().safeDrive)
        store.setSafeDrive(true)
        store.setSafeDriveToleranceKph(7)
        store.setSafeDriveProgressiveSound(false)
        store.setSafeDriveAlertDistanceMeters(300)
        store.setSafeDriveVoice(false)
        val restored = SettingsStore(ContextWrapper(paparazzi.context), preferences)
        assertEquals(7, restored.settings.first().safeDriveToleranceKph)
        assertFalse(restored.settings.first().safeDriveProgressiveSound)
        assertEquals(300, restored.settings.first().safeDriveAlertDistanceMeters)
        assertFalse(restored.settings.first().safeDriveVoice)
        assertTrue(restored.settings.first().safeDrive)
        assertEquals(7, restored.settings.first().toBackup().safeDriveToleranceKph)
        assertFalse(restored.settings.first().toBackup().safeDriveProgressiveSound)
        assertEquals(300, restored.settings.first().toBackup().safeDriveAlertDistanceMeters)
        assertFalse(restored.settings.first().toBackup().safeDriveVoice)
        store.setSafeDriveAlertDistanceMeters(550)
        assertEquals(500, store.settings.first().safeDriveAlertDistanceMeters)
        store.setSafeDriveToleranceKph(-1)
        assertEquals(0, store.settings.first().safeDriveToleranceKph)
        store.setSafeDriveToleranceKph(31)
        assertEquals(30, store.settings.first().safeDriveToleranceKph)
        store.restore(BackupSettings(safeDriveToleranceKph = 9))
        assertEquals(9, store.settings.first().safeDriveToleranceKph)
        assertTrue(store.settings.first().safeDriveProgressiveSound)
        assertEquals(500, store.settings.first().safeDriveAlertDistanceMeters)
        assertTrue(store.settings.first().safeDriveVoice)
        store.restore(BackupSettings(safeDriveProgressiveSound = false, safeDriveAlertDistanceMeters = 700, safeDriveVoice = false))
        assertEquals(700, store.settings.first().safeDriveAlertDistanceMeters)
        assertFalse(store.settings.first().safeDriveVoice)
        store.restore(BackupSettings(safeDriveAlertDistanceMeters = 900))
        assertEquals(500, store.settings.first().safeDriveAlertDistanceMeters)
        assertTrue(store.settings.first().safeDriveProgressiveSound)
        assertFalse("안내가 꺼져 있으면 위치를 사용하지 않는다", store.settings.first().safeDrive)
        store.restore(BackupSettings(safeDrive = true, safeDriveToleranceKph = 9))
        assertTrue("복원된 안내 선택도 유지한다", store.settings.first().safeDrive)
        store.restore(BackupSettings(safeDriveToleranceKph = 99))
        assertEquals(30, store.settings.first().safeDriveToleranceKph)
    }

    /** 안내 설정이 연결하는 실제 안내기의 GPS 단절·복구·오입력·종료를 이미지 없이 검증한다. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun guideLifecycleAndUnknownFixes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var nowNanos = 100_000_000_000L
        val guide = SafeDriveGuide(Application()) { nowNanos }
        SafeDriveGuide::class.java.getDeclaredField("index").apply { isAccessible = true }
            .set(guide, CameraIndex(listOf(OfflineCamera("test", 37.003, 127.0, 50))))
        // 실차 위치 대신 고정 가상 좌표로 측정 시각과 센서 필드를 조절한다.
        fun fix(ageNanos: Long = 0): Location = Location("gps").apply {
            latitude = 37.0
            longitude = 127.0
            speed = 20f
            bearing = 0f
            accuracy = 10f
            elapsedRealtimeNanos = nowNanos - ageNanos
        }
        try {
            guide.start()
            runCurrent()
            assertTrue(guide.state.value.stalled)
            guide.onLocation(fix())
            assertEquals(72.0, guide.state.value.speedKph!!, 0.001)
            assertTrue(guide.state.value.isOverSpeed(toleranceKph = 5))
            assertEquals(50, guide.state.value.alert?.speedLimitKph)

            val invalidFixes = listOf<(Location) -> Unit>(
                { it.removeSpeed() }, { it.speed = Float.NaN }, { it.speed = -1f },
                { it.speed = Float.POSITIVE_INFINITY }, { it.removeBearing() },
                { it.removeAccuracy() }, { it.accuracy = 31f },
                { it.accuracy = Float.NaN }, { it.latitude = Double.NaN },
                { it.longitude = 181.0 }, { it.elapsedRealtimeNanos = nowNanos + 1 },
                { it.elapsedRealtimeNanos = nowNanos - 5_000_000_000L },
            )
            invalidFixes.forEachIndexed { position, mutate ->
                guide.onLocation(fix().also(mutate))
                assertTrue("누락·비정상 입력 $position", guide.state.value.stalled)
                assertNull(guide.state.value.alert)
                guide.onLocation(fix())
                assertFalse(guide.state.value.stalled)
            }
            guide.onLocation(fix().apply { speed = 0f; removeBearing() })
            assertFalse(guide.state.value.stalled)
            assertNull(guide.state.value.alert)

            val delayed = fix(4_900_000_000L)
            guide.onLocation(delayed)
            assertNotNull(guide.state.value.alert)
            nowNanos += 1_000_000_000L
            advanceTimeBy(1_000)
            runCurrent()
            assertTrue(guide.state.value.stalled)
            assertNull(freshSpeedKph(delayed, nowNanos))
            guide.onLocation(fix())
            assertNotNull(guide.state.value.alert)
            nowNanos += 5_000_000_000L
            advanceTimeBy(5_000)
            runCurrent()
            assertTrue(guide.state.value.stalled)

            guide.stop()
            guide.onLocation(fix())
            assertEquals(SafetyState(), guide.state.value)
            guide.start()
            runCurrent()
            guide.onLocation(fix())
            assertNotNull(guide.state.value.alert)
        } finally {
            guide.stop()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    /** 후보 밖으로 벗어나거나 안내를 다시 시작해도 매칭 요청·429 대기 시각은 잊지 않는다. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun roadMatchCooldownSurvivesExitAndRestart() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val guide = SafeDriveGuide(Application()) { 100_000_000_000L }
        SafeDriveGuide::class.java.getDeclaredField("index").apply { isAccessible = true }
            .set(guide, CameraIndex(listOf(OfflineCamera("test", 37.003, 127.0, 50))))
        val lastAttempt = SafeDriveGuide::class.java.getDeclaredField("lastMatchAttemptMillis")
            .apply { isAccessible = true }
        val retryDelay = SafeDriveGuide::class.java.getDeclaredField("retryDelayMillis")
            .apply { isAccessible = true }
        try {
            guide.start()
            runCurrent()
            lastAttempt.setLong(guide, 90_000L)
            retryDelay.setLong(guide, 30_000L)
            // 네트워크 호출 없이 후보 이탈·안내 해제·서비스 재시작 경로만 확인한다.
            guide.onLocation(Location("gps").apply {
                latitude = 37.02; longitude = 127.0
                speed = 0f; accuracy = 10f; elapsedRealtimeNanos = 100_000_000_000L
            })
            guide.setRoadMatchEnabled(false)
            guide.stop()
            guide.start()
            runCurrent()
            assertEquals(90_000L, lastAttempt.getLong(guide))
            assertEquals(30_000L, retryDelay.getLong(guide))
        } finally {
            guide.stop()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    /** 서버의 120초 창 경계는 보존하고 오래된 표본·0m 오차는 전송 전에 정리한다. */
    @Test fun roadMatchSamplesStayWithinServerWindow() {
        val guide = SafeDriveGuide(Application())
        SafeDriveGuide::class.java.getDeclaredField("index").apply { isAccessible = true }
            .set(guide, CameraIndex(listOf(OfflineCamera("test", 37.003, 127.0, 50))))
        SafeDriveGuide::class.java.getDeclaredField("roadMatchEnabled").apply { isAccessible = true }.setBoolean(guide, true)
        // 실제 네트워크 요청 없이 위치 표본 수집만 확인한다.
        SafeDriveGuide::class.java.getDeclaredField("tokenRejected").apply { isAccessible = true }.setBoolean(guide, true)
        @Suppress("UNCHECKED_CAST")
        val points = SafeDriveGuide::class.java.getDeclaredField("recentPoints").apply { isAccessible = true }
            .get(guide) as ArrayDeque<RoadPoint>
        val timestamp = System.currentTimeMillis() / 1_000
        listOf(121L, 120L, 90L, 60L, 30L, 5L).forEach { age ->
            points.addLast(RoadPoint(37.0, 127.0, timestamp - age, 10.0))
        }
        val location = Location("gps").apply {
            latitude = 37.0; longitude = 127.0
            bearing = 0f; accuracy = 0f; time = timestamp * 1_000
        }
        SafeDriveGuide::class.java.getDeclaredMethod(
            "updateRoadMatch", Location::class.java, java.lang.Double.TYPE, java.lang.Long.TYPE,
        ).apply { isAccessible = true }.invoke(guide, location, 20.0, 100_000_000_000L)
        assertEquals(timestamp - 120, points.first().timestamp)
        assertEquals(6, points.size)
        assertEquals(1.0, points.last().accuracyMeters, 0.0)
    }

    /** 같은 카메라 앞에서 과속이 이어지면 2초마다 울리고, 속도를 낮췄다가 올리면 즉시 재경보한다. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun soundRequestCooldownAndSettings() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var nowNanos = 1_000_000_000L
        val guide = SafeDriveGuide(Application()) { nowNanos }
        SafeDriveGuide::class.java.getDeclaredField("index").apply { isAccessible = true }
            .set(guide, CameraIndex(listOf(
                OfflineCamera("first", 37.003, 127.0, 50),
                OfflineCamera("second", 37.004, 127.0, 50),
            )))
        val lastSound = SafeDriveGuide::class.java.getDeclaredField("lastSoundMillis").apply { isAccessible = true }
        // 실제 스피커 출력 대신 요청 시각만 검증하고 JVM의 미구현 ToneGenerator는 비운다.
        fun approach(latitudeDegrees: Double = 37.0, speedMetersPerSecond: Float = 20f) {
            guide.onLocation(Location("gps").apply {
                latitude = latitudeDegrees; longitude = 127.0
                speed = speedMetersPerSecond; bearing = 0f; accuracy = 10f
                elapsedRealtimeNanos = nowNanos
            })
            SafeDriveGuide::class.java.getDeclaredField("tone").apply { isAccessible = true }.set(guide, null)
        }
        try {
            guide.setSound(true, 99, -1, progressive = false)
            guide.setAutomaticAlertsAllowed(true)
            assertEquals(0, guide.toleranceKph)
            guide.start()
            runCurrent()
            approach()
            assertEquals(1_000L, lastSound.get(guide))
            assertTrue(DiagLog.lines.value.last().contains("안전 안내 · 경고음"))
            assertTrue(DiagLog.lines.value.last().contains("GPS 72km/h"))
            nowNanos += 1_999_000_000L
            approach(37.00001)
            assertEquals(1_000L, lastSound.get(guide))
            nowNanos += 1_000_000L
            approach() // 같은 카메라에서도 2초 간격으로 다시 요청한다.
            assertEquals(3_000L, lastSound.get(guide))
            nowNanos += 1_000_000_000L
            approach(37.0034) // 카메라가 바뀌어도 전역 간격은 지킨다.
            assertEquals(3_000L, lastSound.get(guide))
            nowNanos += 1_000_000_000L
            approach(37.0034)
            assertEquals(5_000L, lastSound.get(guide))
            nowNanos += 10_000_000_000L
            approach(speedMetersPerSecond = 10f) // 과속 해제 직후 재진입은 바로 울린다.
            assertNull(lastSound.get(guide))
            assertTrue(DiagLog.lines.value.last().contains("안전 안내 · 경보 속도 미달"))
            approach()
            assertEquals(15_000L, lastSound.get(guide))
            guide.setSound(false, -1, 0)
            nowNanos += 2_000_000_000L
            approach(37.0034)
            assertNull(lastSound.get(guide))
            assertTrue(DiagLog.lines.value.last().contains("안전 안내 · 경고음 꺼짐"))
            guide.setSound(false, -1, 99)
            assertEquals(30, guide.toleranceKph)
            guide.stop()
            assertNull(lastSound.get(guide))
        } finally {
            guide.stop()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    /** 제한 대비 105→110→115에서 간격이 3→2→1초로 줄고 고정 모드는 2초를 유지한다. */
    @Test fun warningIntervalByOverspeed() {
        assertEquals(3_000L, warningIntervalMillis(5.0, true))
        assertEquals(3_000L, warningIntervalMillis(9.99, true))
        assertEquals(2_000L, warningIntervalMillis(10.0, true))
        assertEquals(2_000L, warningIntervalMillis(14.99, true))
        assertEquals(1_000L, warningIntervalMillis(15.0, true))
        assertEquals(2_000L, warningIntervalMillis(15.0, false))
    }

    /** 보행·미판정 때는 같은 카메라의 화면 경보를 남기되 자동 음성과 경고음 요청은 취소한다. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun automaticSoundsNeedConfirmedDriving() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var nowNanos = 1_000_000_000L
        val spoken = mutableListOf<String>()
        val guide = SafeDriveGuide(Application(), voiceOutput = spoken::add) { nowNanos }
        SafeDriveGuide::class.java.getDeclaredField("index").apply { isAccessible = true }
            .set(guide, CameraIndex(listOf(OfflineCamera("first", 37.003, 127.0, 50))))
        val lastSound = SafeDriveGuide::class.java.getDeclaredField("lastSoundMillis").apply { isAccessible = true }
        // 출력 권한 변경 직후 같은 위치를 재수신해 예전 대기 음성이 새로 시작되지 않는지 본다.
        fun approach() {
            guide.onLocation(Location("gps").apply {
                latitude = 36.9986; longitude = 127.0
                speed = 20f; bearing = 0f; accuracy = 10f
                elapsedRealtimeNanos = nowNanos
            })
            SafeDriveGuide::class.java.getDeclaredField("tone").apply { isAccessible = true }.set(guide, null)
        }
        try {
            guide.setSound(true, 2, 5)
            guide.start()
            runCurrent()
            approach()
            assertNotNull(guide.state.value.alert)
            assertTrue(spoken.isEmpty())
            assertNull(lastSound.get(guide))
            guide.setAutomaticAlertsAllowed(true)
            nowNanos += 1_000_000_000L
            approach()
            assertEquals(1, spoken.size)
            assertNotNull(lastSound.get(guide))
            guide.setAutomaticAlertsAllowed(false)
            nowNanos += 1_000_000_000L
            approach()
            assertEquals(1, spoken.size)
            assertNull(lastSound.get(guide))
        } finally {
            guide.stop()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    /** GPS가 1초마다 올 때 느린 간격·단계 상승 즉시 경보·빠른 간격을 실제 요청 시각으로 확인한다. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun progressiveWarningRequests() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var nowNanos = 1_000_000_000L
        val guide = SafeDriveGuide(Application()) { nowNanos }
        SafeDriveGuide::class.java.getDeclaredField("index").apply { isAccessible = true }
            .set(guide, CameraIndex(listOf(OfflineCamera("first", 37.003, 127.0, 100))))
        val lastSound = SafeDriveGuide::class.java.getDeclaredField("lastSoundMillis").apply { isAccessible = true }
        // JVM에서 ToneGenerator는 실제로 소리를 못 내므로 요청 시각만 검사한다.
        fun approach(speedKph: Int) {
            guide.onLocation(Location("gps").apply {
                latitude = 37.0; longitude = 127.0
                speed = (speedKph / 3.6).toFloat(); bearing = 0f; accuracy = 10f
                elapsedRealtimeNanos = nowNanos
            })
            SafeDriveGuide::class.java.getDeclaredField("tone").apply { isAccessible = true }.set(guide, null)
        }
        try {
            guide.setSound(true, 2, 5, progressive = true)
            guide.setAutomaticAlertsAllowed(true)
            guide.start()
            runCurrent()
            approach(106)
            assertEquals(1_000L, lastSound.get(guide))
            nowNanos += 1_000_000_000L
            approach(106)
            assertEquals(1_000L, lastSound.get(guide))
            nowNanos += 1_000_000_000L
            approach(111) // 3초 간격이 남았어도 더 빠른 단계에서는 즉시 알린다.
            assertEquals(3_000L, lastSound.get(guide))
            nowNanos += 1_000_000_000L
            approach(111)
            assertEquals(3_000L, lastSound.get(guide))
            nowNanos += 1_000_000_000L
            approach(111)
            assertEquals(5_000L, lastSound.get(guide))
            nowNanos += 1_000_000_000L
            approach(116)
            assertEquals(6_000L, lastSound.get(guide))
            nowNanos += 1_000_000_000L
            approach(116)
            assertEquals(7_000L, lastSound.get(guide))
            nowNanos += 1_000_000_000L
            approach(106)
            assertEquals(7_000L, lastSound.get(guide))
            nowNanos += 1_000_000_000L
            approach(101) // 과속 해제 뒤 다시 초과하면 느린 단계도 즉시 울린다.
            assertNull(lastSound.get(guide))
            approach(106)
            assertEquals(9_000L, lastSound.get(guide))
            nowNanos += 2_000_000_000L
            approach(106)
            assertEquals(9_000L, lastSound.get(guide))
            nowNanos += 1_000_000_000L
            approach(106)
            assertEquals(12_000L, lastSound.get(guide))
        } finally {
            guide.stop()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    /** 선택 거리 안에서만 안내·과속음이 시작되고 진입·200m 음성은 카메라별 한 번만 나온다. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun spokenCameraDistanceAndSoundGate() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var nowNanos = 1_000_000_000L
        val spoken = mutableListOf<String>()
        val guide = SafeDriveGuide(Application(), voiceOutput = spoken::add) { nowNanos }
        SafeDriveGuide::class.java.getDeclaredField("index").apply { isAccessible = true }
            .set(guide, CameraIndex(listOf(OfflineCamera("first", 37.003, 127.0, 50))))
        val lastSound = SafeDriveGuide::class.java.getDeclaredField("lastSoundMillis").apply { isAccessible = true }
        fun approach(latitudeDegrees: Double) {
            guide.onLocation(Location("gps").apply {
                latitude = latitudeDegrees; longitude = 127.0
                speed = 20f; bearing = 0f; accuracy = 10f
                elapsedRealtimeNanos = nowNanos
            })
            // JVM ToneGenerator는 실제 오디오가 없으므로 재생 요청 시각만 검사한다.
            SafeDriveGuide::class.java.getDeclaredField("tone").apply { isAccessible = true }.set(guide, null)
        }
        try {
            guide.setAlertOptions(500, voice = true)
            guide.setSound(true, 2, 5)
            guide.setAutomaticAlertsAllowed(true)
            guide.start()
            runCurrent()
            approach(36.997) // 약 667m: 매칭 후보지만 화면·음성·과속음 모두 범위 밖.
            assertNull(guide.state.value.alert)
            assertNull(lastSound.get(guide))
            assertTrue(spoken.isEmpty())
            nowNanos += 1_000_000_000L
            approach(36.9986) // 약 489m: 첫 진입.
            assertNotNull(guide.state.value.alert)
            assertEquals(2_000L, lastSound.get(guide))
            assertEquals(listOf("전방 약 500미터에 단속 카메라가 있습니다."), spoken)
            nowNanos += 1_000_000_000L
            approach(36.9988)
            assertEquals(1, spoken.size)
            nowNanos += 1_000_000_000L
            approach(37.0013) // 약 189m: 첫 문장을 자르지 않게 기다린다.
            assertEquals(1, spoken.size)
            nowNanos += 5_000_000_000L
            approach(37.0013) // 5초 뒤 두 번째 안내.
            assertEquals(2, spoken.size)
            assertEquals("전방 약 200미터에 단속 카메라가 있습니다.", spoken.last())
            nowNanos += 1_000_000_000L
            approach(37.0012)
            assertEquals(2, spoken.size)
            guide.stop()
            runCurrent()
            guide.setSound(false, 2, 5)
            guide.start()
            runCurrent()
            nowNanos += 1_000_000_000L
            approach(36.9986)
            assertNotNull(guide.state.value.alert)
            assertNull(lastSound.get(guide))
            assertEquals(2, spoken.size)
            guide.setSound(true, 2, 5)
            guide.setAutomaticAlertsAllowed(true)
            guide.setAlertOptions(300, voice = false)
            nowNanos += 1_000_000_000L
            approach(37.001)
            assertNotNull(lastSound.get(guide))
            assertEquals(2, spoken.size)
            guide.setAlertOptions(300, voice = true)
            nowNanos += 1_000_000_000L
            approach(37.001)
            assertEquals(3, spoken.size)
        } finally {
            guide.stop()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    /** 음성 요청 실패·엔진 재점검 뒤에도 같은 카메라의 진입 안내가 사라지지 않는다. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun failedVoiceRequestCanBeRetried() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var nowNanos = 1_000_000_000L
        var unavailable = true
        var attempts = 0
        val spoken = mutableListOf<String>()
        val guide = SafeDriveGuide(Application(), voiceOutput = { text ->
            attempts++
            if (unavailable) error("engine unavailable")
            spoken.add(text)
        }) { nowNanos }
        SafeDriveGuide::class.java.getDeclaredField("index").apply { isAccessible = true }
            .set(guide, CameraIndex(listOf(OfflineCamera("first", 37.003, 127.0, 50))))
        fun approach(latitudeDegrees: Double) {
            guide.onLocation(Location("gps").apply {
                latitude = latitudeDegrees; longitude = 127.0
                speed = 20f; bearing = 0f; accuracy = 10f
                elapsedRealtimeNanos = nowNanos
            })
            SafeDriveGuide::class.java.getDeclaredField("tone").apply { isAccessible = true }.set(guide, null)
        }
        try {
            guide.setSound(true, 2)
            guide.setAutomaticAlertsAllowed(true)
            guide.start()
            runCurrent()
            approach(36.9986)
            assertEquals(1, attempts)
            assertTrue(guide.speechStatus.value!!.contains("사용 불가"))
            nowNanos += 1_000_000_000L
            approach(36.9986)
            assertEquals("실패 뒤 GPS마다 재시도하면 엔진이 로그·음성을 반복한다", 1, attempts)
            unavailable = false
            guide.testSpeech()
            assertEquals("한국어 단속 안내 음성 점검입니다.", spoken.single())
            nowNanos += 1_000_000_000L
            approach(36.9986)
            assertEquals("전방 약 500미터에 단속 카메라가 있습니다.", spoken.last())
            nowNanos += 5_000_000_000L
            approach(37.0013)
            assertEquals("전방 약 200미터에 단속 카메라가 있습니다.", spoken.last())
        } finally {
            guide.stop()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    /** 도로 매칭 후보와 경보 후보를 혼동하거나 GPS 저정밀을 후보 누락으로 기록하지 않는다. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun alertSilenceReasons() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var nowNanos = 1_000_000_000L
        val guide = SafeDriveGuide(Application()) { nowNanos }
        SafeDriveGuide::class.java.getDeclaredField("index").apply { isAccessible = true }
            .set(guide, CameraIndex(listOf(OfflineCamera("first", 37.003, 127.0, 50))))
        try {
            guide.setSound(true, 2, 5)
            guide.setAutomaticAlertsAllowed(true)
            guide.start()
            runCurrent()
            // 위치·정확도만 바꿔 무음 진단 상태를 비교한다.
            fun approach(latitudeDegrees: Double, accuracyMeters: Float) {
                guide.onLocation(Location("gps").apply {
                    latitude = latitudeDegrees; longitude = 127.0
                    speed = 20f; bearing = 0f; accuracy = accuracyMeters
                    elapsedRealtimeNanos = nowNanos
                })
            }
            approach(36.995, 10f) // 1km 매칭 범위지만 기본 500m 경보 범위 밖.
            assertNull(guide.state.value.alert)
            assertTrue(DiagLog.lines.value.last().contains("근접 후보는 있지만 경보 거리·방향 미충족"))
            nowNanos += 1_000_000_000L
            approach(36.9986, 10f) // 설정한 500m 안으로 들어오면 과속 경고음을 요청한다.
            assertTrue(guide.state.value.isOverSpeed(toleranceKph = guide.toleranceKph))
            assertTrue(DiagLog.lines.value.last().contains("안전 안내 · 경고음"))
            nowNanos += 10_000_000_000L
            approach(37.0, 31f)
            assertNull(guide.state.value.alert)
            assertTrue(DiagLog.lines.value.last().contains("GPS 정확도 부족"))
            nowNanos += 10_000_000_000L
            approach(36.98, 10f)
            assertTrue(DiagLog.lines.value.last().contains("전방 1km 내 후보 없음"))
        } finally {
            // JVM에는 ToneGenerator.release()가 없어 재생 요청 객체만 비운다.
            SafeDriveGuide::class.java.getDeclaredField("tone").apply { isAccessible = true }.set(guide, null)
            guide.stop()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    /** 구형 백업은 기본값으로 열고 새 설정은 JSON 왕복 후에도 유지한다. */
    @Test fun backupCompatibility() {
        val old = BackupFile.json.decodeFromString<BackupFile>("""{"version":3,"settings":{}}""")
        assertEquals(5, old.settings.safeDriveToleranceKph)
        assertTrue(old.settings.safeDriveProgressiveSound)
        assertEquals(500, old.settings.safeDriveAlertDistanceMeters)
        assertTrue(old.settings.safeDriveVoice)
        val backup = BackupFile(settings = BackupSettings(safeDriveToleranceKph = 8, safeDriveProgressiveSound = false,
            safeDriveAlertDistanceMeters = 300, safeDriveVoice = false))
        val text = BackupFile.json.encodeToString(BackupFile.serializer(), backup)
        assertEquals(backup, BackupFile.json.decodeFromString<BackupFile>(text))
    }
}
