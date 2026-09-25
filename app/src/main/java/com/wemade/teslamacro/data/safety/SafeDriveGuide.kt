package com.wemade.teslamacro.data.safety

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
import java.util.Locale

/** 경보 기준은 그대로 두고 후보 제한속도 대비 초과분으로 단발음 간격만 고른다. */
internal fun warningIntervalMillis(excessKph: Double, progressive: Boolean): Long = when {
    !progressive -> 2_000L
    excessKph >= 15 -> 1_000L
    excessKph >= 10 -> 2_000L
    else -> 3_000L
}

/** 안내를 켜면 기본은 오프라인 판단, 카메라 근처에서는 도로 매칭을 보조로 사용한다. */
class SafeDriveGuide(
    private val application: Application,
    private val voiceOutput: ((String) -> Unit)? = null,
    private val elapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    // 준비 중인 문장에 카메라를 묶어 엔진 초기화 뒤 지난 후보를 읽거나 단계를 소모하지 않는다.
    private data class SpeechRequest(val text: String, val cameraKey: String? = null, val stage: Int = 0)

    private val mutableState = MutableStateFlow(SafetyState())
    val state: StateFlow<SafetyState> = mutableState.asStateFlow()
    private val mutableSpeechStatus = MutableStateFlow<String?>(null)
    val speechStatus: StateFlow<String?> = mutableSpeechStatus.asStateFlow()
    private val mutableAutomaticSoundStatus = MutableStateFlow("주행 판정 대기")
    val automaticSoundStatus: StateFlow<String> = mutableAutomaticSoundStatus.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var index: CameraIndex? = null
    private var lastFixNanos: Long? = null
    private val roadMatcher = RoadMatcher(application)
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
    private var lastSoundIntervalMillis: Long? = null
    private var lastAlertStatus: String? = null
    private var lastAlertLogMillis: Long? = null
    private var retrievedAt: String? = null
    private var dataWarning: String? = null
    private var sound = false
    private var automaticAlertsAllowed = false
    private var volume = 2
    private var progressiveSound = true
    private var alertDistanceMeters = 500
    private var voiceEnabled = true
    private val spokenStages = mutableMapOf<String, Int>()
    private var lastVoiceMillis: Long? = null
    private var speechEngine: TextToSpeech? = null
    private var speechReady = false
    private var speechUnavailable = false
    private var speechGeneration = 0
    private var speechUtterance = 0L
    private var pendingSpeech: SpeechRequest? = null
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
                mutableState.value = SafetyState(ready = true, stalled = true,
                    unavailableReason = gpsWaitingReason(), dataWarning = dataWarning)
                DiagLog.add("안전 안내 · 시작 (소리 ${if (sound) "켬" else "끔"}, 초과 +${toleranceKph}km/h, ${mutableState.value.unavailableReason})")
                while (isActive) {
                    if (retrievedAt != null) {
                        val warning = sourceDateWarning(retrievedAt, LocalDate.now(), 6, "목록 수집일")
                        if (dataWarning != warning) {
                            dataWarning = warning
                            mutableState.value = mutableState.value.copy(dataWarning = warning)
                        }
                    }
                    if (lastFixNanos?.let { isFreshLocation(it, elapsedRealtimeNanos()) } == false) {
                        pendingSpeech = null
                        val waiting = SafetyState(ready = true, stalled = true,
                            unavailableReason = gpsWaitingReason(), dataWarning = dataWarning)
                        if (mutableState.value != waiting) mutableState.value = waiting
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

    /** GPS가 끊겼을 때 권한·기기 위치 서비스 문제를 단순한 후보 부재와 구분한다. */
    private fun gpsWaitingReason(): String = when {
        runCatching { application.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) }.getOrNull() ==
            PackageManager.PERMISSION_DENIED -> "정확한 위치 권한 없음"
        runCatching { application.getSystemService(LocationManager::class.java)
            ?.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrNull() == false -> "GPS 위치 서비스 꺼짐"
        else -> "GPS 대기"
    }

    /** 서비스가 받는 GPS를 공유해 별도 위치 구독과 배터리 소모를 만들지 않는다. */
    fun onLocation(location: Location) {
        if (job?.isActive != true || !state.value.ready) return
        val nowNanos = elapsedRealtimeNanos()
        val speed = freshSpeedKph(location, nowNanos)
        if (speed == null) {
            // GPS 품질 때문에 경보가 막혔는지 구분해야 후보 부재로 오해하지 않는다.
            val reason = when {
                !isFreshLocation(location.elapsedRealtimeNanos, nowNanos) -> "GPS 측정 오래됨"
                !location.hasAccuracy() || location.accuracy !in 0f..30f -> "GPS 정확도 부족"
                location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0 -> "GPS 좌표 확인 불가"
                else -> "GPS 속도 확인 불가"
            }
            clearRoadMatch()
            lastFixNanos = null
            mutableState.value = SafetyState(ready = true, stalled = true, unavailableReason = reason, dataWarning = dataWarning)
            lastSoundMillis = null
            lastSoundIntervalMillis = null
            pendingSpeech = null
            logAlertStatus(reason, "오차 ${if (location.hasAccuracy()) location.accuracy.toInt() else "미확인"}m", nowNanos / 1_000_000)
            return
        }
        lastFixNanos = location.elapsedRealtimeNanos
        if (speed >= 5 && (!location.hasBearing() || !location.bearing.isFinite())) {
            clearRoadMatch()
            mutableState.value = SafetyState(ready = true, stalled = true, unavailableReason = "방향 확인 불가", dataWarning = dataWarning)
            lastSoundMillis = null
            lastSoundIntervalMillis = null
            pendingSpeech = null
            logAlertStatus("방향 확인 불가", "GPS ${speed.toInt()}km/h, 방향 없음", nowNanos / 1_000_000)
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
            maxDistanceMeters = alertDistanceMeters,
        ) else null
        mutableState.value = SafetyState(ready = true, alert = alert, speedKph = speed, dataWarning = dataWarning)
        val nowMillis = nowNanos / 1_000_000
        if (alert != null && sound && voiceEnabled && automaticAlertsAllowed) announceCamera(alert, nowMillis)
        else pendingSpeech = null
        val overSpeed = state.value.isOverSpeed(toleranceKph = toleranceKph)
        val detail = "GPS ${speed.toInt()}km/h, 오차 ${if (location.hasAccuracy()) location.accuracy.toInt() else "미확인"}m, " +
            "후보 ${alert?.speedLimitKph?.let { "제한 ${it}km/h" } ?: "없음"}, 거리 ${alert?.distanceMeters ?: "-"}m, " +
            "초과 설정 +${toleranceKph}km/h, 소리 ${if (sound) "켬" else "끔"}"
        val status = when {
            speed < 5 -> "GPS 속도 5km/h 미만"
            !location.hasBearing() -> "GPS 방향 없음"
            alert == null && index?.hasNearby(location.latitude, location.longitude, location.bearing.toDouble()) == true ->
                "근접 후보는 있지만 경보 거리·방향 미충족"
            alert == null -> "전방 1km 내 후보 없음"
            alert.limitConflict -> "후보 제한속도 상충"
            alert.speedLimitKph == null -> "후보 제한속도 없음"
            !overSpeed -> "경보 속도 미달"
            !sound -> "경고음 꺼짐"
            !automaticAlertsAllowed -> "주행 확인 전 · 소리 보류"
            else -> "경고음 반복"
        }
        // 과속이 커지면 이전 느린 간격을 기다리지 않고 즉시 새 단계로 올린다.
        if (status == "경고음 반복") {
            val interval = warningIntervalMillis(speed - alert!!.speedLimitKph!!, progressiveSound)
            val elapsed = lastSoundMillis?.let { nowMillis - it }
            if (elapsed == null || elapsed < 0 || interval < (lastSoundIntervalMillis ?: interval) || elapsed >= interval) {
                lastSoundMillis = nowMillis
                lastSoundIntervalMillis = interval
                // 앱 음량과 별개로 기기 미디어 음량 0이면 들리지 않으므로 요청 수락과 구별한다.
                val mediaVolume = runCatching {
                    application.getSystemService(AudioManager::class.java)?.getStreamVolume(AudioManager.STREAM_MUSIC)
                }.getOrNull()
                if (mediaVolume == 0) {
                    logAlertStatus("경고음 무음 · 미디어 음량 0", detail, nowMillis)
                } else {
                    val attempt = runCatching {
                        if (tone == null) tone = ToneGenerator(AudioManager.STREAM_MUSIC, volume * 25)
                        // 이중 삑 소리 대신 단발음을 써 반복 간격으로 긴박감을 전한다.
                        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 300) == true
                    }
                    val result = when {
                        attempt.getOrNull() == true -> "경고음 요청 수락 · 미디어 음량 ${mediaVolume ?: "확인 불가"}"
                        attempt.exceptionOrNull() != null -> "경고음 재생 오류 · ${attempt.exceptionOrNull()!!.javaClass.simpleName}"
                        else -> "경고음 재생 실패"
                    }
                    logAlertStatus("$result · ${interval / 1_000}초 간격", detail, nowMillis)
                }
            }
        } else {
            lastSoundMillis = null
            lastSoundIntervalMillis = null
            logAlertStatus(status, detail, nowMillis)
        }
    }

    /** 진입 시와 200m 안에서만 말하고 GPS 흔들림에 같은 카메라를 반복 안내하지 않는다. */
    private fun announceCamera(alert: com.wemade.teslamacro.domain.safety.SafetyAlert, nowMillis: Long) {
        val key = alert.cameraKey ?: return
        val distance = alert.distanceMeters ?: return
        val stage = if (distance <= 200) 2 else 1
        val previous = spokenStages[key] ?: 0
        if (stage <= previous) return
        // 두 문장이 겹치면 첫 안내가 잘리므로 가까운 안내를 잠시 늦춘다.
        if (stage == 2 && previous == 1 && lastVoiceMillis?.let { nowMillis - it in 0..4_999 } == true) return
        val roundedDistance = if (distance >= 100) ((distance + 50) / 100) * 100
            else ((distance + 5) / 10) * 10
        requestSpeech(SpeechRequest("전방 약 ${roundedDistance.coerceAtLeast(10)}미터에 단속 카메라가 있습니다.", key, stage))
    }

    /** 음성 점검을 누르면 설치·언어 변경 뒤 잠긴 엔진도 다시 초기화한다. */
    fun testSpeech() {
        speechGeneration++
        runCatching { speechEngine?.shutdown() }
        speechEngine = null
        speechReady = false
        speechUnavailable = false
        pendingSpeech = null
        mutableSpeechStatus.value = "한국어 음성 확인 중"
        requestSpeech(SpeechRequest("한국어 단속 안내 음성 점검입니다."))
    }

    /** 음성 엔진이 준비되기 전에는 최신 안내만 보관해 지나간 카메라를 늦게 읽지 않는다. */
    private fun requestSpeech(request: SpeechRequest) {
        if (speechUnavailable || (request.cameraKey != null && job?.isActive != true)) return
        pendingSpeech = request
        if (voiceOutput != null || speechReady) { speakPendingSpeech(); return }
        if (speechEngine != null) return
        val generation = speechGeneration
        runCatching {
            speechEngine = TextToSpeech(application) { status ->
                // 음성 엔진 콜백은 위치 콜백과 다른 스레드에서도 오므로 안내 상태를 한곳에서 다룬다.
                scope.launch {
                    if (generation != speechGeneration || speechUnavailable) return@launch
                    if (status != TextToSpeech.SUCCESS) {
                        disableSpeech("엔진 초기화 실패")
                    } else {
                        speechReady = true
                        if (speechEngine != null) prepareSpeech()
                    }
                }
            }
            speechEngine?.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            if (speechUnavailable) { runCatching { speechEngine?.shutdown() }; speechEngine = null }
            else if (speechReady) prepareSpeech()
        }.onFailure { disableSpeech("엔진 시작 실패 · ${it.javaClass.simpleName}") }
    }

    /** 한국어 데이터가 없으면 다른 언어로 읽지 않고 기기 설정에서 설치할 수 있게 알린다. */
    private fun prepareSpeech() {
        val engine = speechEngine ?: return
        val language = runCatching { engine.setLanguage(Locale.KOREAN) }.getOrNull()
        if (language == null || language < 0) { disableSpeech("한국어 음성 없음"); return }
        val selectedVoice = runCatching { engine.voice }.getOrNull()
        if (selectedVoice?.isNetworkConnectionRequired == true) {
            disableSpeech("오프라인 한국어 음성 없음")
            return
        }
        speakPendingSpeech()
    }

    /** 실제 발화 요청이 수락됐을 때만 해당 거리 단계를 소모한다. */
    private fun speakPendingSpeech() {
        val request = pendingSpeech ?: return
        if (request.cameraKey != null && (!voiceEnabled || !sound || !automaticAlertsAllowed || state.value.stalled ||
                state.value.alert?.cameraKey != request.cameraKey)) {
            pendingSpeech = null
            return
        }
        pendingSpeech = null
        val result = runCatching {
            if (voiceOutput != null) {
                voiceOutput.invoke(request.text)
                TextToSpeech.SUCCESS
            } else {
                val generation = speechGeneration
                val utteranceId = (++speechUtterance).toString()
                speechEngine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    // 실제 재생 오류는 speak()가 성공을 반환한 뒤에도 올 수 있어 단계 기록을 되돌린다.
                    override fun onStart(id: String) = Unit
                    // 완료 신호만으로 기기에서 소리가 들렸다고 단정할 수는 없다.
                    override fun onDone(id: String) = Unit
                    // 콜백은 다른 스레드에서 오므로 최신 발화일 때만 상태를 갱신한다.
                    override fun onError(id: String) {
                        scope.launch {
                            if (generation != speechGeneration || id != speechUtterance.toString() || speechUnavailable) return@launch
                            request.cameraKey?.let { key ->
                                if (spokenStages[key] == request.stage) spokenStages.remove(key)
                            }
                            disableSpeech("음성 출력 오류")
                        }
                    }
                })
                speechEngine?.speak(request.text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            }
        }
        if (result.getOrNull() == TextToSpeech.SUCCESS && !speechUnavailable) {
            request.cameraKey?.let { key ->
                spokenStages[key] = maxOf(spokenStages[key] ?: 0, request.stage)
                lastVoiceMillis = elapsedRealtimeNanos() / 1_000_000
                // 경고음이 없어도 음성으로 카메라 안내가 나갈 수 있어 요청 수락 시각을 남긴다.
                DiagLog.add("안전 안내 · 단속카메라 음성 요청 수락 (거리 ${state.value.alert?.distanceMeters ?: "-"}m, 단계 ${request.stage})")
            }
            mutableSpeechStatus.value = "음성 재생 요청됨 · 들리지 않으면 미디어 음량을 확인하세요."
        } else if (!speechUnavailable) {
            disableSpeech("재생 실패 · ${result.exceptionOrNull()?.javaClass?.simpleName ?: "엔진 응답"}")
        }
    }

    /** 음성 실패는 설정에 표시하되 GPS·과속 경고음은 계속 동작시킨다. */
    private fun disableSpeech(reason: String) {
        speechUnavailable = true
        speechReady = false
        pendingSpeech = null
        runCatching { speechEngine?.shutdown() }
        speechEngine = null
        mutableSpeechStatus.value = "한국어 음성 사용 불가 ($reason) · 음성 설정을 확인하세요."
        DiagLog.add("안전 안내 · 음성 사용할 수 없음 ($reason)")
    }

    /** 경고음 변화는 즉시, 일반 상태는 최대 10초 전환·1분 요약으로 남겨 진단 기록을 지킨다. */
    private fun logAlertStatus(status: String, detail: String, nowMillis: Long) {
        val elapsed = lastAlertLogMillis?.let { nowMillis - it }
        if ((status != lastAlertStatus && (status.startsWith("경고음") || elapsed == null || elapsed < 0 || elapsed >= 10_000)) ||
            elapsed == null || elapsed < 0 || elapsed >= 60_000) {
            DiagLog.add("안전 안내 · $status ($detail)")
            lastAlertStatus = status
            lastAlertLogMillis = nowMillis
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
        // 서버의 120초 창과 최소 1m 오차 범위를 지켜 장시간 주행에서도 요청이 거절되지 않게 한다.
        recentPoints.addLast(RoadPoint(location.latitude, location.longitude, timestamp, location.accuracy.toDouble().coerceAtLeast(1.0)))
        while (recentPoints.size > 8 || timestamp - recentPoints.first().timestamp > 120) recentPoints.removeFirst()
        val nowMillis = nowNanos / 1_000_000
        if (recentPoints.size < 2 || tokenRejected || matchJob?.isActive == true ||
            (lastMatchAttemptMillis != 0L && nowMillis - lastMatchAttemptMillis < retryDelayMillis)) return
        lastMatchAttemptMillis = nowMillis
        requestsSinceLog += 1
        // 진단 파일은 줄 수 상한이 있으므로 매 요청 대신 첫 요청과 1분 요약만 남긴다.
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

    /** 설정 거리에 들어온 뒤에만 화면 경보·과속음을 시작하며 도로 매칭 범위는 건드리지 않는다. */
    fun setAlertOptions(distanceMeters: Int, voice: Boolean) {
        alertDistanceMeters = distanceMeters.takeIf { it in listOf(300, 500, 700) } ?: 500
        if (voice && !voiceEnabled && speechUnavailable) {
            speechGeneration++
            speechUnavailable = false
            mutableSpeechStatus.value = null
        }
        voiceEnabled = voice
        if (!voice) {
            pendingSpeech = null
            runCatching { speechEngine?.stop() }
        }
    }

    /** 보행·미판정으로 바뀌는 순간 이미 대기하거나 재생 중인 자동 안내도 취소한다. */
    fun setAutomaticAlertsAllowed(allowed: Boolean, reason: String = "주행 판정 대기") {
        mutableAutomaticSoundStatus.value = if (allowed) "주행 확인 · 자동 소리 사용" else reason
        if (automaticAlertsAllowed == allowed) return
        automaticAlertsAllowed = allowed
        if (!allowed) {
            pendingSpeech = null
            spokenStages.clear()
            lastSoundMillis = null
            lastSoundIntervalMillis = null
            runCatching { speechEngine?.stop() }
            runCatching { tone?.stopTone() }
        }
        if (job?.isActive == true) DiagLog.add("안전 안내 · 자동 소리 ${if (allowed) "주행 확인" else "보행/주행 미확인 · 보류"}")
    }

    /** 설정 변경 시 음량을 다시 적용하고 꺼진 소리는 즉시 해제한다. */
    fun setSound(sound: Boolean, volume: Int, toleranceKph: Int = 5, progressive: Boolean = true) {
        val adjustedTolerance = toleranceKph.coerceIn(0, 30)
        val adjustedVolume = volume.coerceIn(1, 3)
        val changed = this.sound != sound || this.volume != adjustedVolume ||
            this.toleranceKph != adjustedTolerance || progressiveSound != progressive
        this.toleranceKph = adjustedTolerance
        this.sound = sound
        this.volume = adjustedVolume
        progressiveSound = progressive
        if (changed) {
            lastSoundMillis = null
            lastSoundIntervalMillis = null
        }
        tone?.release()
        tone = null
        if (!sound) {
            pendingSpeech = null
            runCatching { speechEngine?.stop() }
        }
        if (changed && job?.isActive == true) {
            DiagLog.add("안전 안내 · 소리 설정 변경 (소리 ${if (sound) "켬" else "끔"}, 음량 $adjustedVolume, 초과 +${adjustedTolerance}km/h, 속도별 ${if (progressive) "켬" else "끔"})")
        }
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
        lastSoundIntervalMillis = null
        spokenStages.clear()
        automaticAlertsAllowed = false
        lastVoiceMillis = null
        pendingSpeech = null
        speechReady = false
        speechUnavailable = false
        speechGeneration++
        runCatching { speechEngine?.shutdown() }
        speechEngine = null
        mutableSpeechStatus.value = null
        lastAlertStatus = null
        lastAlertLogMillis = null
        tone?.release()
        tone = null
        mutableState.value = SafetyState()
    }
}
