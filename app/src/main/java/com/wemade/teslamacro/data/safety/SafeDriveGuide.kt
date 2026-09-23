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
import com.wemade.teslamacro.domain.safety.SafetyState
import com.wemade.teslamacro.domain.safety.sourceDateWarning
import com.wemade.teslable.DiagLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.time.LocalDate

/** 번들 공공데이터와 기존 HUD GPS만 사용한다. 주행 위치를 외부로 보내지 않는다. */
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
            lastFixNanos = null
            mutableState.value = SafetyState(ready = true, stalled = true, unavailableReason = "위치·속도 확인 불가", dataWarning = dataWarning)
            return
        }
        lastFixNanos = location.elapsedRealtimeNanos
        if (speed >= 5 && (!location.hasBearing() || !location.bearing.isFinite())) {
            mutableState.value = SafetyState(ready = true, stalled = true, unavailableReason = "방향 확인 불가", dataWarning = dataWarning)
            return
        }
        val alert = if (location.hasBearing()) index?.nearest(
            location.latitude, location.longitude, location.bearing.toDouble(), speed, location.accuracy.toDouble(),
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
        lastFixNanos = null
        lastSoundMillis = null
        soundedCameras.clear()
        tone?.release()
        tone = null
        mutableState.value = SafetyState()
    }
}
