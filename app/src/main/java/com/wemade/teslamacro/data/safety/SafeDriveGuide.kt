package com.wemade.teslamacro.data.safety

import android.app.Application
import android.location.Location
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import com.wemade.teslamacro.data.location.freshSpeedKph
import com.wemade.teslamacro.data.location.isFreshLocation
import com.wemade.teslamacro.domain.safety.CameraDataset
import com.wemade.teslamacro.domain.safety.CameraIndex
import com.wemade.teslamacro.domain.macro.ConditionEvaluator
import com.wemade.teslamacro.domain.safety.SafetyState
import com.wemade.teslamacro.domain.safety.sourceDateWarning
import com.wemade.teslable.DiagLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.time.LocalDate

/** 안내를 켜면 기본은 오프라인 판단, 카메라 근처에서는 도로 매칭을 보조로 사용한다. */
class SafeDriveGuide(
    private val application: Application,
    private val elapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    private val mutableState = MutableStateFlow(SafetyState())
    val state: StateFlow<SafetyState> = mutableState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var index: CameraIndex? = null
    private var lastFixNanos: Long? = null
    private val roadMatcher = RoadMatcher()
    private var roadMatchEnabled = false
    private val recentPoints = ArrayDeque<RoadPoint>()
    private var matchJob: Job? = null
    private var lastMatchAttemptMillis = 0L
    private var retryDelayMillis = 10_000L
    private var lastMatchLogMillis = 0L
    private var requestsSinceLog = 0
    private var lastMatchOutcome: String? = null
    private var tokenRejected = false
    private var matchedRoad: Pair<Long, MatchedRoad>? = null
    private var lastSoundMillis: Long? = null
    private val soundedCameras = mutableMapOf<String, Long>()
    private var retrievedAt: String? = null
    private var dataWarning: String? = null
    private var sound = false
    private var volume = 2
    var toleranceKph: Int = 5
        private set
    private var tone: ToneGenerator? = null

    /** 목록 로드는 IO에서 하고, GPS 수신 중단 시 과거 제한속도를 즉시 버린다. */
    fun start() {
        if (job?.isActive == true) return
        mutableState.value = SafetyState(stalled = true, unavailableReason = "단속 목록 준비 중")
        job = scope.launch {
            try {
                if (index == null) index = withContext(Dispatchers.IO) {
                    val text = application.assets.open("safety_cameras.json").bufferedReader().use { it.readText() }
                    val dataset = Json { ignoreUnknownKeys = true }.decodeFromString<CameraDataset>(text)
                    require(dataset.schemaVersion == 1)
                    CameraIndex(dataset.cameras).also { require(!it.isEmpty) } to dataset.retrievedAt
                }.also { retrievedAt = it.second }.first
                dataWarning = sourceDateWarning(retrievedAt, LocalDate.now(), 6, "목록 수집일")
                mutableState.value = SafetyState(ready = true, stalled = true, dataWarning = dataWarning)
                while (isActive) {
                    if (retrievedAt != null) {
                        val warning = sourceDateWarning(retrievedAt, LocalDate.now(), 6, "목록 수집일")
                        if (dataWarning != warning) {
                            dataWarning = warning
                            mutableState.value = mutableState.value.copy(dataWarning = warning)
                        }
                    }
                    if (lastFixNanos?.let { isFreshLocation(it, elapsedRealtimeNanos()) } == false) {
                        mutableState.value = SafetyState(ready = true, stalled = true, dataWarning = dataWarning)
                    }
                    delay(1_000)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = SafetyState(stalled = true, unavailableReason = "단속 목록 오류")
                DiagLog.add("안전 안내 · 오프라인 목록을 읽지 못했어요 (${error.javaClass.simpleName})")
            }
        }
    }

    /** 서비스가 받는 GPS를 공유해 별도 위치 구독과 배터리 소모를 만들지 않는다. */
    fun onLocation(location: Location) {
        if (job?.isActive != true || !state.value.ready) return
        val nowNanos = elapsedRealtimeNanos()
        val speed = freshSpeedKph(location, nowNanos)
        if (speed == null) {
            clearRoadMatch()
            lastFixNanos = null
            mutableState.value = SafetyState(ready = true, stalled = true, unavailableReason = "위치·속도 확인 불가", dataWarning = dataWarning)
            return
        }
        lastFixNanos = location.elapsedRealtimeNanos
        if (speed >= 5 && (!location.hasBearing() || !location.bearing.isFinite())) {
            clearRoadMatch()
            mutableState.value = SafetyState(ready = true, stalled = true, unavailableReason = "방향 확인 불가", dataWarning = dataWarning)
            return
        }
        updateRoadMatch(location, speed, nowNanos)
        // 결과가 GPS와 멀거나 오래되면 원래 측위로 되돌아간다. 도로 매칭만으로 단속 방향을 확정하지 않는다.
        val snapped = matchedRoad?.takeIf { (timestamp, road) ->
            location.time / 1_000 - timestamp in 0L..5L &&
                ConditionEvaluator.distanceMeters(location.latitude, location.longitude, road.latitude, road.longitude) <= 30
        }?.second
        val alert = if (location.hasBearing()) index?.nearest(
            snapped?.latitude ?: location.latitude, snapped?.longitude ?: location.longitude,
            location.bearing.toDouble(), speed, location.accuracy.toDouble(),
        ) else null
        mutableState.value = SafetyState(ready = true, alert = alert, speedKph = speed, dataWarning = dataWarning)
        val nowMillis = nowNanos / 1_000_000
        val cameraKey = alert?.cameraKey
        // 같은 좌표를 GPS 흔들림·10초 간격마다 재경보하지 않고, 다른 카메라는 기존 간격을 지킨다.
        if (sound && cameraKey != null && state.value.isOverSpeed(toleranceKph = toleranceKph) &&
            (lastSoundMillis?.let { nowMillis >= it && nowMillis - it >= 10_000 } != false) &&
            (soundedCameras[cameraKey]?.let { nowMillis >= it && nowMillis - it >= 600_000 } != false)) {
            lastSoundMillis = nowMillis
            soundedCameras[cameraKey] = nowMillis
            runCatching {
                if (tone == null) tone = ToneGenerator(AudioManager.STREAM_MUSIC, volume * 25)
                tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
            }
        }
    }

    /** 안내가 켜져 있고 토큰이 있을 때만 매칭하며, 안내 해제 시 대기 요청도 취소한다. */
    fun setRoadMatchEnabled(enabled: Boolean) {
        if (enabled && !roadMatchEnabled) {
            tokenRejected = false
            lastMatchOutcome = null
        }
        roadMatchEnabled = enabled && roadMatcher.available
        if (!roadMatchEnabled) clearRoadMatch()
    }

    /** 후보·주행·시각을 확인한 뒤 10초마다 최대 한 요청만 보낸다. 실패는 오프라인으로 넘긴다. */
    private fun updateRoadMatch(location: Location, speed: Double, nowNanos: Long) {
        if (!roadMatchEnabled || speed < 5 || !location.hasBearing() ||
            index?.hasNearby(location.latitude, location.longitude, location.bearing.toDouble()) != true) {
            clearRoadMatch()
            return
        }
        val timestamp = location.time / 1_000
        if (timestamp !in (System.currentTimeMillis() / 1_000 - 5)..(System.currentTimeMillis() / 1_000 + 5)) return
        if (recentPoints.lastOrNull()?.timestamp?.let { timestamp <= it } == true) return
        if (recentPoints.lastOrNull()?.timestamp?.let { timestamp - it > 30 } == true) recentPoints.clear()
        recentPoints.addLast(RoadPoint(location.latitude, location.longitude, timestamp, location.accuracy.toDouble()))
        while (recentPoints.size > 8) recentPoints.removeFirst()
        val nowMillis = nowNanos / 1_000_000
        if (recentPoints.size < 2 || tokenRejected || matchJob?.isActive == true ||
            (lastMatchAttemptMillis != 0L && nowMillis - lastMatchAttemptMillis < retryDelayMillis)) return
        lastMatchAttemptMillis = nowMillis
        requestsSinceLog += 1
        // 진단 파일은 최근 100줄만 남으므로 매 요청 대신 첫 요청과 1분 요약만 남긴다.
        if (lastMatchLogMillis == 0L || nowMillis - lastMatchLogMillis >= 60_000) {
            DiagLog.add("도로 매칭 · 전방 후보 요청 (최근 구간 ${requestsSinceLog}회)")
            requestsSinceLog = 0
            lastMatchLogMillis = nowMillis
        }
        val points = recentPoints.toList()
        matchJob = scope.launch {
            val result = roadMatcher.match(points)
            if (result.code == 401) tokenRejected = true
            retryDelayMillis = if (result.code in listOf(429, 503, 504)) 30_000L else 10_000L
            matchedRoad = result.road?.let { points.last().timestamp to it }
            // 좌표·토큰·응답 원문 없이 결과 변화와 대기 이유만 기록한다.
            val outcome = when {
                result.code == 401 -> "인증 오류(401), 요청 중지"
                result.code == 429 -> "요청 제한(429), 30초 대기"
                result.code == 503 -> "서버 혼잡(503), 30초 대기"
                result.code == 504 -> "응답 지연(504), 30초 대기"
                result.road != null -> "확정 매칭"
                result.code == 200 -> "경로 불확실/실패, 오프라인 유지"
                else -> "통신 오류, 오프라인 유지"
            }
            if (outcome != lastMatchOutcome) {
                DiagLog.add("도로 매칭 · $outcome")
                lastMatchOutcome = outcome
            }
        }
    }

    /** 주행 구역·동의가 끝나면 이전 결과가 다음 경보에 남지 않게 한다. */
    private fun clearRoadMatch() {
        matchJob?.cancel()
        matchJob = null
        recentPoints.clear()
        matchedRoad = null
        // 후보 경계·권한 변화·설정 토글에도 전역 요청 간격과 서버 재시도 대기를 유지한다.
    }

    /** 설정 변경 시 음량을 다시 적용하고 꺼진 소리는 즉시 해제한다. */
    fun setSound(sound: Boolean, volume: Int, toleranceKph: Int = 5) {
        this.toleranceKph = toleranceKph.coerceIn(0, 30)
        this.sound = sound
        this.volume = volume.coerceIn(1, 3)
        tone?.release()
        tone = null
    }

    /** 감시 종료 시 이전 카메라와 경보를 남기지 않는다. */
    fun stop() {
        job?.cancel()
        job = null
        clearRoadMatch()
        lastMatchLogMillis = 0L
        requestsSinceLog = 0
        lastMatchOutcome = null
        lastFixNanos = null
        lastSoundMillis = null
        soundedCameras.clear()
        tone?.release()
        tone = null
        mutableState.value = SafetyState()
    }
}
