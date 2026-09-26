package com.wemade.teslamacro.data.safety

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.SystemClock
import com.wemade.teslamacro.data.location.freshSpeedKph
import com.wemade.teslamacro.data.location.isFreshLocation
import com.wemade.teslamacro.domain.safety.CameraDataset
import com.wemade.teslamacro.domain.safety.CameraIndex
import com.wemade.teslamacro.domain.macro.ConditionEvaluator
import com.wemade.teslamacro.domain.safety.SafetyKind
import com.wemade.teslamacro.domain.safety.SafetyState
import com.wemade.teslamacro.domain.safety.sourceDateWarning
import com.wemade.teslable.DiagLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.io.File
import java.util.Locale

/**
 * 경보 기준은 그대로 두고 후보 제한속도 대비 초과분으로 이중 삑 반복 간격만 고른다.
 * 이중 삑 한 번이 약 0.3초라 0.6초보다 좁히면 끊김 없는 소리로 들려 단계 구분이 사라진다.
 */
internal fun warningIntervalMillis(excessKph: Double, progressive: Boolean): Long = when {
    !progressive -> 1_000L
    excessKph >= 15 -> 600L
    excessKph >= 10 -> 800L
    else -> 1_200L
}

/** GPS 갱신이 이 시간 넘게 끊기면 지난 속도로 경고음을 계속 반복하지 않는다 */
private const val WARNING_REFRESH_NANOS = 2_000_000_000L

/** 첫 안내 문장이 단독 카메라인지, 뒤에 카메라가 더 있는지, 앞 카메라에 바로 이어지는지 */
internal enum class CameraSequence { SINGLE, CONTINUOUS, FOLLOWING }

/** 딩동(약 0.37초)이 끝난 뒤 음성이 시작되게 두는 무음 길이. 겹치면 둘 다 알아듣기 어렵다 */
private const val ANNOUNCE_CHIME_LEAD_MILLIS = 450L

/** 합성 음성 임시 파일 이름 앞부분. 안내 종료 때 남은 파일을 이 이름으로 찾아 지운다 */
private const val SPEECH_FILE_PREFIX = "safe_drive_tts_"

/**
 * "N미터 앞 시속 N킬로미터 단속구간입니다." 한 문장에 거리·제한속도를 담는다.
 * 가까울수록 반올림 단위를 줄여 "0미터"나 지나친 과장 없이 실제 거리에 가깝게 읽는다.
 */
internal fun cameraAnnouncement(kind: SafetyKind, distanceMeters: Int, limitKph: Int?, sequence: CameraSequence): String {
    val rounded = when {
        distanceMeters >= 200 -> ((distanceMeters + 50) / 100) * 100
        distanceMeters >= 100 -> ((distanceMeters + 25) / 50) * 50
        else -> (((distanceMeters + 5) / 10) * 10).coerceAtLeast(10)
    }
    // 구간단속은 평균속도 판정이라 지점 단속과 구분해 읽는다.
    val zone = if (kind == SafetyKind.SECTION_CAMERA) "구간단속 구간" else "단속구간"
    // 같은 좌표에 제한속도가 엇갈리면 임의 숫자를 읽지 않고 표지 확인을 요청한다.
    val body = limitKph?.let { "${rounded}미터 앞 시속 ${it}킬로미터 ${zone}입니다." }
        ?: "${rounded}미터 앞 ${zone}입니다. 제한속도는 표지판을 확인하세요."
    return when (sequence) {
        CameraSequence.SINGLE -> body
        CameraSequence.CONTINUOUS -> "연속 단속 구간입니다. $body"
        CameraSequence.FOLLOWING -> "이어서 $body"
    }
}

/** 카메라 200m 안에서도 과속이면 감속만 짧게 요청한다. */
internal fun slowDownAnnouncement(limitKph: Int?): String =
    limitKph?.let { "속도를 줄이세요. 제한속도 ${it}킬로미터입니다." } ?: "속도를 줄이세요."

/** 첫 공백 기록 기준. GPS 콜백은 1초 주기라 30초 무수신이면 위성·권한·OS 차단 중 하나다 */
private const val GPS_SILENCE_FIRST_LOG_NANOS = 30_000_000_000L

/** 공백이 이어질 때 반복 기록 간격. 300줄 진단 로그를 공백 기록만으로 채우지 않는다 */
private const val GPS_SILENCE_REPEAT_NANOS = 60_000_000_000L

/** 마지막 수신(없으면 안내 준비) 뒤 30초부터 1분마다 GPS 공백을 기록할 초를 돌려준다. */
internal fun gpsSilenceSecondsToLog(nowNanos: Long, readyNanos: Long, lastLocationNanos: Long?, lastLogNanos: Long?): Long? {
    val silentNanos = nowNanos - maxOf(readyNanos, lastLocationNanos ?: readyNanos)
    if (silentNanos < GPS_SILENCE_FIRST_LOG_NANOS) return null
    if (lastLogNanos != null && nowNanos - lastLogNanos < GPS_SILENCE_REPEAT_NANOS) return null
    return silentNanos / 1_000_000_000L
}

/** 안내를 켜면 기본은 오프라인 판단, 카메라 근처에서는 도로 매칭을 보조로 사용한다. */
class SafeDriveGuide(
    private val application: Application,
    private val voiceOutput: ((String) -> Unit)? = null,
    private val elapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    // 준비 중인 문장에 카메라를 묶어 엔진 초기화 뒤 지난 후보를 읽거나 단계를 소모하지 않는다.
    // 딩동은 카메라 진입 안내와, 그 안내를 그대로 들려주는 음성 점검에만 붙인다.
    private data class SpeechRequest(val text: String, val cameraKey: String? = null, val stage: Int = 0,
                                     val chimeLead: Boolean = stage == 1)

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
    // 첫 위치조차 오지 않으면 상태 로그가 멈춰 지하·실내와 OS 위치 차단을 구분할 수 없어 수신 공백을 따로 센다.
    private var guideReadyNanos: Long? = null
    private var lastLocationNanos: Long? = null
    private var lastSilenceLogNanos: Long? = null
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
    // GPS는 1초마다 와서 위치 콜백만으로는 1초보다 좁은 간격을 낼 수 없어 반복은 별도 타이머가 맡는다.
    private var warningJob: Job? = null
    private var warningRefreshedNanos = 0L
    private var warningDetail = ""
    // 연속 카메라에서 경고음이 끊김 없이 이어지면 두 번째 카메라를 알 수 없어 후보 전환을 따로 기억한다.
    private var lastAlertCameraKey: String? = null
    // 앞 카메라를 방금 지났는지 판단해 다음 카메라 안내를 "이어서"로 시작한다.
    private var lastAlertSeenMillis: Long? = null
    private var lastAlertStatus: String? = null
    private var lastAlertLogMillis: Long? = null
    private var retrievedAt: String? = null
    private var dataWarning: String? = null
    private var sound = false
    private var automaticAlertsAllowed = false
    private var volume = 2
    private var warningSound = WarningSound.CHIME
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
    // TTS 엔진이 직접 재생하면 소리가 엔진 앱 소유라 분리 앱 사운드를 따르지 않아, 파일로 받아 앱이 재생한다.
    private var speechPlayer: MediaPlayer? = null
    private var speechFile: File? = null
    private var speechPlaybackJob: Job? = null
    var toleranceKph: Int = 5
        private set
    private val chime = WarningChime()
    // 안내 음성이 나오는 동안 경고음을 겹치면 둘 다 알아듣기 어려워 음성을 우선한다.
    private var speaking = false
    private var audioFocus: AudioFocusRequest? = null
    // 설정 화면 미리 듣기는 주행 경보 음원을 건드리지 않게 따로 둔다. 크기 변경이 주행 음원을 새로 만들기 때문이다.
    private val previewChime = WarningChime()
    // 카메라 진입 안내 앞 딩동은 사용자가 고른 과속 경고음과 달라 음원을 따로 둬 매번 다시 만들지 않는다.
    private val announceChime = WarningChime()
    private var previewJob: Job? = null

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
                guideReadyNanos = elapsedRealtimeNanos()
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
                        stopWarning()
                        val waiting = SafetyState(ready = true, stalled = true,
                            unavailableReason = gpsWaitingReason(), dataWarning = dataWarning)
                        if (mutableState.value != waiting) mutableState.value = waiting
                    }
                    // 위치가 30초 넘게 오지 않으면 1분마다 권한·GPS 설정 판정과 함께 남긴다.
                    guideReadyNanos?.let { readyNanos ->
                        val nowNanos = elapsedRealtimeNanos()
                        gpsSilenceSecondsToLog(nowNanos, readyNanos, lastLocationNanos, lastSilenceLogNanos)?.let { seconds ->
                            DiagLog.add("안전 안내 · GPS 수신 없음 (${seconds}초, ${gpsWaitingReason()})")
                            lastSilenceLogNanos = nowNanos
                        }
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
        // 공백을 기록했다면 첫 수신을 남겨 지하 출구·차단 해제 시점을 로그에서 이어 보게 한다.
        if (lastSilenceLogNanos != null) {
            val silentSeconds = (nowNanos - (lastLocationNanos ?: guideReadyNanos ?: nowNanos)) / 1_000_000_000L
            DiagLog.add("안전 안내 · GPS 수신 재개 (${silentSeconds}초 만)")
            lastSilenceLogNanos = null
        }
        lastLocationNanos = nowNanos
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
            stopWarning()
            pendingSpeech = null
            logAlertStatus(reason, "오차 ${if (location.hasAccuracy()) location.accuracy.toInt() else "미확인"}m", nowNanos / 1_000_000)
            return
        }
        lastFixNanos = location.elapsedRealtimeNanos
        if (speed >= 5 && (!location.hasBearing() || !location.bearing.isFinite())) {
            clearRoadMatch()
            mutableState.value = SafetyState(ready = true, stalled = true, unavailableReason = "방향 확인 불가", dataWarning = dataWarning)
            stopWarning()
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
        // 연속 카메라 중 어느 것을 인식했는지 로그로 확인하도록 새 후보를 만날 때만 남긴다.
        // 후보가 잠깐 사라졌다 같은 카메라로 돌아오는 GPS 흔들림은 새 후보로 치지 않는다.
        val previousCameraKey = lastAlertCameraKey
        val cameraChanged = alert?.cameraKey != null && alert.cameraKey != previousCameraKey
        val followsPrevious = cameraChanged && previousCameraKey != null &&
            lastAlertSeenMillis?.let { nowMillis - it in 0..15_000 } == true
        if (cameraChanged) {
            DiagLog.add("안전 안내 · 카메라 후보 진입 (제한 ${alert?.speedLimitKph?.let { "${it}km/h" } ?: "확인 필요"}, " +
                "거리 ${alert?.distanceMeters ?: "-"}m${if (followsPrevious) ", 이어서" else ""})")
            lastAlertCameraKey = alert?.cameraKey
        }
        if (alert != null) lastAlertSeenMillis = nowMillis
        val limit = alert?.speedLimitKph
        // 경계 속도에서 1~2km/h 흔들림으로 경고음이 켜졌다 꺼지지 않게, 울리는 중에는 2km/h 더 낮아져야 멈춘다.
        val overSpeed = state.value.isOverSpeed(toleranceKph = toleranceKph) ||
            (warningJob?.isActive == true && limit != null && speed >= limit + toleranceKph - 2)
        if (alert != null && sound && voiceEnabled && automaticAlertsAllowed) {
            announceCamera(alert, nowMillis, overSpeed, followsPrevious) {
                index?.hasFollowing(snapped?.latitude ?: location.latitude, snapped?.longitude ?: location.longitude,
                    location.bearing.toDouble(), alert.distanceMeters ?: 0) == true
            }
        } else pendingSpeech = null
        val detail = "GPS ${speed.toInt()}km/h, 오차 ${if (location.hasAccuracy()) location.accuracy.toInt() else "미확인"}m, " +
            "후보 ${limit?.let { "제한 ${it}km/h" } ?: "없음"}, 거리 ${alert?.distanceMeters ?: "-"}m, " +
            "초과 설정 +${toleranceKph}km/h, 소리 ${if (sound) "켬" else "끔"}"
        val status = when {
            speed < 5 -> "GPS 속도 5km/h 미만"
            !location.hasBearing() -> "GPS 방향 없음"
            alert == null && index?.hasNearby(location.latitude, location.longitude, location.bearing.toDouble()) == true ->
                "근접 후보는 있지만 경보 거리·방향 미충족"
            alert == null -> "전방 1km 내 후보 없음"
            alert.limitConflict -> "후보 제한속도 상충"
            limit == null -> "후보 제한속도 없음"
            !overSpeed -> "경보 속도 미달"
            !sound -> "경고음 꺼짐"
            !automaticAlertsAllowed -> "주행 확인 전 · 소리 보류"
            else -> "경고음 반복"
        }
        if (status == "경고음 반복") {
            val interval = warningIntervalMillis(speed - limit!!, progressiveSound)
            warningDetail = detail
            warningRefreshedNanos = nowNanos
            // 과속이 커지거나 다음 카메라로 넘어가면 남은 간격을 기다리지 않고 바로 울려 새 경보임을 알린다.
            val restart = warningJob?.isActive != true || cameraChanged || interval < (lastSoundIntervalMillis ?: interval)
            lastSoundIntervalMillis = interval
            if (restart) startWarning()
        } else {
            stopWarning()
            logAlertStatus(status, detail, nowMillis)
        }
    }

    /** 첫 경고음은 바로 내고, 이후는 GPS 갱신이 이어지는 동안 현재 단계 간격으로 반복한다. */
    private fun startWarning() {
        warningJob?.cancel()
        holdAudioFocus()
        playWarning()
        warningJob = scope.launch {
            while (isActive) {
                delay(lastSoundIntervalMillis ?: break)
                if (elapsedRealtimeNanos() - warningRefreshedNanos > WARNING_REFRESH_NANOS) {
                    stopWarning()
                    break
                }
                playWarning()
            }
        }
    }

    /** 과속 해제·GPS 끊김·설정 변경 시 예약된 반복을 멈추고 다음 과속은 즉시 울리게 한다. */
    private fun stopWarning() {
        warningJob?.cancel()
        warningJob = null
        lastSoundMillis = null
        lastSoundIntervalMillis = null
        chime.stop()
        releaseAudioFocusIfIdle()
    }

    /** 기기 미디어 음량 0은 요청 수락과 구별하고, 실제 청취는 확인할 수 없어 요청 결과만 남긴다. */
    private fun playWarning() {
        val interval = lastSoundIntervalMillis ?: return
        val nowMillis = elapsedRealtimeNanos() / 1_000_000
        // 안내 음성이 나오는 중이면 이번 차례는 건너뛰고 다음 간격에 다시 울린다.
        // 엔진이 종료 콜백을 빠뜨려도 경고음이 영영 막히지 않게 8초가 지나면 다시 울린다.
        if (speaking && lastVoiceMillis?.let { nowMillis - it in 0..8_000 } == true) return
        lastSoundMillis = nowMillis
        val mediaVolume = runCatching {
            application.getSystemService(AudioManager::class.java)?.getStreamVolume(AudioManager.STREAM_MUSIC)
        }.getOrNull()
        if (mediaVolume == 0) {
            logAlertStatus("경고음 무음 · 미디어 음량 0", warningDetail, nowMillis)
            return
        }
        val attempt = runCatching { chime.play(volume, warningSound) }
        val result = when {
            attempt.getOrNull() == true -> "경고음 요청 수락 · 미디어 음량 ${mediaVolume ?: "확인 불가"}"
            attempt.exceptionOrNull() != null -> "경고음 재생 오류 · ${attempt.exceptionOrNull()!!.javaClass.simpleName}"
            else -> "경고음 재생 실패"
        }
        logAlertStatus("$result · ${String.format(Locale.ROOT, "%.1f", interval / 1_000.0)}초 간격", warningDetail, nowMillis)
    }

    /**
     * 안내 음성·경고음 동안만 음악을 줄여 달라고 요청한다.
     * 음악을 멈추는 대신 일시 감쇠라 안내가 끝나면 원래 음량으로 돌아간다.
     */
    private fun holdAudioFocus() {
        if (audioFocus != null) return
        runCatching {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .build()
            val granted = application.getSystemService(AudioManager::class.java)?.requestAudioFocus(request)
            if (granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) audioFocus = request
        }
    }

    /** 말하는 중도, 경고음 반복·미리 듣기 중도 아니면 감쇠를 풀어 음악을 원래 음량으로 돌린다. */
    private fun releaseAudioFocusIfIdle(force: Boolean = false) {
        if (!force && (speaking || warningJob?.isActive == true || previewJob?.isActive == true)) return
        val request = audioFocus ?: return
        runCatching { application.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request) }
        audioFocus = null
    }

    /**
     * 경고음 크기·종류를 고를 때 실제 주행과 같은 소리·간격으로 세 번(약 3초) 들려준다.
     * 주행 중 실제 경보가 울리고 있으면 헷갈리지 않게 미리 듣기를 건너뛴다.
     */
    fun previewWarning(volumeLevel: Int, sound: WarningSound = warningSound) {
        if (warningJob?.isActive == true) return
        previewJob?.cancel()
        val mediaVolume = runCatching {
            application.getSystemService(AudioManager::class.java)?.getStreamVolume(AudioManager.STREAM_MUSIC)
        }.getOrNull()
        // 미디어 음량 0이면 아무것도 안 들려 고장으로 오해하기 쉬워 원인을 함께 남긴다.
        DiagLog.add("안전 안내 · 경고음 미리 듣기 (${sound.label}, 크기 ${volumeLevel.coerceIn(1, 3)}, 미디어 음량 ${mediaVolume ?: "확인 불가"})")
        holdAudioFocus()
        previewJob = scope.launch {
            val current = coroutineContext[Job]
            try {
                repeat(3) { index ->
                    runCatching { previewChime.play(volumeLevel, sound) }
                    if (index < 2) delay(warningIntervalMillis(0.0, progressiveSound))
                }
                // 마지막 소리가 끝날 때까지 음악 감쇠를 유지한다.
                delay(400)
            } finally {
                // 새 미리 듣기로 교체된 경우엔 새 재생을 끊지 않도록 자기 차례일 때만 정리한다.
                if (previewJob === current) {
                    previewJob = null
                    previewChime.stop()
                    releaseAudioFocusIfIdle()
                }
            }
        }
    }

    /**
     * 상용 내비처럼 카메라마다 진입 때 한 번 거리·제한속도를 말하고, 200m 안에서 여전히 과속이면 감속만 한 번 더 요청한다.
     * 정속 주행 중 같은 카메라를 두 번 읽으면 번잡해 두 번째 안내는 과속일 때만 낸다.
     */
    private fun announceCamera(alert: com.wemade.teslamacro.domain.safety.SafetyAlert, nowMillis: Long,
                               overSpeed: Boolean, followsPrevious: Boolean, continuous: () -> Boolean) {
        val key = alert.cameraKey ?: return
        val distance = alert.distanceMeters ?: return
        val previous = spokenStages[key] ?: 0
        val text = when {
            previous == 0 -> cameraAnnouncement(alert.kind, distance, alert.speedLimitKph, when {
                followsPrevious -> CameraSequence.FOLLOWING
                continuous() -> CameraSequence.CONTINUOUS
                else -> CameraSequence.SINGLE
            })
            // 진입 안내를 자르지 않도록 4초가 지난 뒤에만 감속을 요청한다.
            previous == 1 && distance <= 200 && overSpeed &&
                lastVoiceMillis?.let { nowMillis - it in 0..3_999 } != true -> slowDownAnnouncement(alert.speedLimitKph)
            else -> return
        }
        requestSpeech(SpeechRequest(text, key, previous + 1))
    }

    /** 음성 점검을 누르면 설치·언어 변경 뒤 잠긴 엔진도 다시 초기화하고, 실제 진입 안내와 같은 딩동·문구로 들려준다. */
    fun testSpeech() {
        speechGeneration++
        runCatching { speechEngine?.shutdown() }
        speechEngine = null
        releaseSpeechPlayer()
        speaking = false
        speechReady = false
        speechUnavailable = false
        pendingSpeech = null
        mutableSpeechStatus.value = "한국어 음성 확인 중"
        requestSpeech(SpeechRequest(cameraAnnouncement(SafetyKind.SPEED_CAMERA, 500, 100, CameraSequence.SINGLE),
            chimeLead = true))
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
        // 말하기 속도·높이는 기기 음성 설정을 그대로 쓴다. 1.1배를 강제하면 엔진의 속도 변환(음 높이 보정) 때문에
        // 음성 설정 미리 듣기와 음이 달라지고 기계음처럼 뭉개져, 속도가 필요하면 기기 음성 설정에서 바꾼다.
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
                // 새 안내는 합성 중이거나 재생 중인 지난 안내를 끊는다.
                runCatching { speechEngine?.stop() }
                releaseSpeechPlayer()
                holdAudioFocus()
                // 합성·딩동 대기 중 과속 경고음이 끼어들지 않게 요청 시점부터 말하는 중으로 본다.
                speaking = true
                val nowMillis = elapsedRealtimeNanos() / 1_000_000
                val leadUntilMillis = if (request.chimeLead) {
                    // 진입 안내는 딩동으로 먼저 주의를 끈 뒤 딩동이 끝나면 읽는다.
                    runCatching { announceChime.play(volume, WarningSound.DING_DONG) }
                    nowMillis + ANNOUNCE_CHIME_LEAD_MILLIS
                } else nowMillis
                speechEngine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    // 파일 합성 시작은 소리가 나는 시점이 아니라 재생 시작에서 경고음을 끊는다.
                    override fun onStart(id: String) = Unit
                    // 합성이 끝난 최신 안내만 재생하고 지난 안내 파일은 버린다.
                    override fun onDone(id: String) {
                        scope.launch {
                            val file = speechFileFor(id) ?: return@launch
                            if (generation != speechGeneration || id != speechUtterance.toString() || speechUnavailable) {
                                file.delete()
                                return@launch
                            }
                            playSpeechFile(id, file, leadUntilMillis, request)
                        }
                    }
                    // 새 안내가 끼어들거나 설정 변경으로 끊긴 합성도 같은 정리를 거친다.
                    override fun onStop(id: String, interrupted: Boolean) {
                        speechFileFor(id)?.delete()
                        finishSpeaking(id)
                    }
                    // 콜백은 다른 스레드에서 오므로 최신 발화일 때만 상태를 갱신한다.
                    override fun onError(id: String) {
                        scope.launch {
                            speechFileFor(id)?.delete()
                            if (generation != speechGeneration || id != speechUtterance.toString() || speechUnavailable) return@launch
                            failSpeech(request)
                        }
                    }
                })
                val file = speechFileFor(utteranceId) ?: error("캐시 폴더 없음")
                speechEngine?.synthesizeToFile(request.text, null, file, utteranceId)
            }
        }
        if (result.getOrNull() == TextToSpeech.SUCCESS && !speechUnavailable) {
            request.cameraKey?.let { key ->
                spokenStages[key] = maxOf(spokenStages[key] ?: 0, request.stage)
                lastVoiceMillis = elapsedRealtimeNanos() / 1_000_000
                // 경고음이 없어도 음성으로 카메라 안내가 나갈 수 있어 요청 수락 시각과 실제 문구를 남긴다.
                DiagLog.add("안전 안내 · 단속카메라 음성 요청 수락 (거리 ${state.value.alert?.distanceMeters ?: "-"}m, 단계 ${request.stage}) \"${request.text}\"")
            }
            mutableSpeechStatus.value = "음성 재생 요청됨 · 들리지 않으면 미디어 음량을 확인하세요."
        } else if (!speechUnavailable) {
            disableSpeech("재생 실패 · ${result.exceptionOrNull()?.javaClass?.simpleName ?: "엔진 응답"}")
            releaseAudioFocusIfIdle()
        }
    }

    /** 최신 발화가 끝났을 때만 경고음 재개·음악 복구를 허용한다. */
    private fun finishSpeaking(id: String) {
        scope.launch {
            if (id != speechUtterance.toString()) return@launch
            releaseSpeechPlayer()
            speaking = false
            releaseAudioFocusIfIdle()
        }
    }

    /** 발화마다 다른 파일을 써 지난 합성이 늦게 끝나도 재생 중인 파일을 덮지 않는다. */
    private fun speechFileFor(id: String): File? =
        runCatching { application.cacheDir }.getOrNull()?.let { File(it, "$SPEECH_FILE_PREFIX$id.wav") }

    /** 합성한 음성을 앱이 직접 재생해 분리 앱 사운드 같은 앱별 출력 지정을 따르게 한다. */
    private fun playSpeechFile(id: String, file: File, leadUntilMillis: Long, request: SpeechRequest) {
        speechFile = file
        speechPlaybackJob = scope.launch {
            // 합성이 딩동보다 먼저 끝나면 남은 시간만큼 기다려 둘이 겹치지 않게 한다.
            val wait = leadUntilMillis - elapsedRealtimeNanos() / 1_000_000
            if (wait > 0) delay(wait)
            val player = runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    setDataSource(file.path)
                    prepare()
                    setOnCompletionListener { finishSpeaking(id) }
                    setOnErrorListener { _, _, _ ->
                        if (id == speechUtterance.toString()) failSpeech(request)
                        true
                    }
                }
            }.getOrElse {
                failSpeech(request)
                return@launch
            }
            speechPlayer = player
            // 음성이 시작되면 울리던 경고음을 끊어 음성만 들리게 한다.
            chime.stop()
            player.start()
        }
    }

    /** 합성·재생 실패는 이번 단계를 되돌리고 음성을 끄되 GPS·과속 경고음은 계속 동작시킨다. */
    private fun failSpeech(request: SpeechRequest) {
        request.cameraKey?.let { key ->
            if (spokenStages[key] == request.stage) spokenStages.remove(key)
        }
        disableSpeech("음성 출력 오류")
        releaseAudioFocusIfIdle()
    }

    /** 재생 대기·재생 중인 음성을 멈추고 임시 파일을 지운다. */
    private fun releaseSpeechPlayer() {
        speechPlaybackJob?.cancel()
        speechPlaybackJob = null
        speechPlayer?.let { runCatching { it.release() } }
        speechPlayer = null
        speechFile?.delete()
        speechFile = null
    }

    /** 소리 끄기·자동 소리 보류 때 합성과 재생을 함께 멈추고 음악 감쇠를 푼다. */
    private fun stopSpeechOutput() {
        runCatching { speechEngine?.stop() }
        releaseSpeechPlayer()
        speaking = false
        releaseAudioFocusIfIdle()
    }

    /** 음성 실패는 설정에 표시하되 GPS·과속 경고음은 계속 동작시킨다. */
    private fun disableSpeech(reason: String) {
        speechUnavailable = true
        speechReady = false
        pendingSpeech = null
        runCatching { speechEngine?.shutdown() }
        speechEngine = null
        releaseSpeechPlayer()
        speaking = false
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
            stopSpeechOutput()
        }
    }

    /** 차량 오디오 해제나 탑승 근거 소멸 시 대기·재생 중인 자동 안내도 즉시 취소한다. */
    fun setAutomaticAlertsAllowed(allowed: Boolean, reason: String = "주행 판정 대기",
                                  allowedStatus: String = "주행 확인 · 자동 소리 사용") {
        mutableAutomaticSoundStatus.value = if (allowed) allowedStatus else reason
        if (automaticAlertsAllowed == allowed) return
        automaticAlertsAllowed = allowed
        if (!allowed) {
            pendingSpeech = null
            spokenStages.clear()
            stopWarning()
            stopSpeechOutput()
            chime.stop()
        }
        if (job?.isActive == true) DiagLog.add("안전 안내 · 자동 소리 ${if (allowed) "연결/주행 확인" else reason}")
    }

    /** 설정 변경 시 음량·경고음 종류를 다시 적용하고 꺼진 소리는 즉시 해제한다. */
    fun setSound(sound: Boolean, volume: Int, toleranceKph: Int = 5, progressive: Boolean = true,
                 warningSound: WarningSound = this.warningSound) {
        val adjustedTolerance = toleranceKph.coerceIn(0, 30)
        val adjustedVolume = volume.coerceIn(1, 3)
        val changed = this.sound != sound || this.volume != adjustedVolume ||
            this.toleranceKph != adjustedTolerance || progressiveSound != progressive || this.warningSound != warningSound
        this.toleranceKph = adjustedTolerance
        this.sound = sound
        this.volume = adjustedVolume
        progressiveSound = progressive
        this.warningSound = warningSound
        if (changed) {
            stopWarning()
        }
        chime.release()
        if (!sound) {
            pendingSpeech = null
            stopSpeechOutput()
        }
        if (changed && job?.isActive == true) {
            DiagLog.add("안전 안내 · 소리 설정 변경 (소리 ${if (sound) "켬" else "끔"}, ${warningSound.label}, 음량 $adjustedVolume, 초과 +${adjustedTolerance}km/h, 속도별 ${if (progressive) "켬" else "끔"})")
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
        guideReadyNanos = null
        lastLocationNanos = null
        lastSilenceLogNanos = null
        stopWarning()
        lastAlertCameraKey = null
        lastAlertSeenMillis = null
        spokenStages.clear()
        automaticAlertsAllowed = false
        lastVoiceMillis = null
        pendingSpeech = null
        speechReady = false
        speechUnavailable = false
        speechGeneration++
        runCatching { speechEngine?.shutdown() }
        speechEngine = null
        releaseSpeechPlayer()
        // 합성 중 종료돼 지우지 못한 임시 파일도 정리한다.
        runCatching { application.cacheDir?.listFiles { file -> file.name.startsWith(SPEECH_FILE_PREFIX) }?.forEach { it.delete() } }
        mutableSpeechStatus.value = null
        lastAlertStatus = null
        lastAlertLogMillis = null
        speaking = false
        chime.release()
        announceChime.release()
        releaseAudioFocusIfIdle(force = true)
        mutableState.value = SafetyState()
    }
}
