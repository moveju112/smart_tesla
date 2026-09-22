package com.wemade.teslamacro.data.safety

import android.app.Application
import android.location.Location
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import com.wemade.teslamacro.domain.safety.CameraDataset
import com.wemade.teslamacro.domain.safety.CameraIndex
import com.wemade.teslamacro.domain.safety.SafetyState
import com.wemade.teslable.DiagLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/** 번들 공공데이터와 기존 HUD GPS만 사용한다. 네트워크·지도 SDK·앱 키는 사용하지 않는다. */
class SafeDriveGuide(private val application: Application) {
    private val mutableState = MutableStateFlow(SafetyState())
    val state: StateFlow<SafetyState> = mutableState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var index: CameraIndex? = null
    private var lastFixMillis = 0L
    private var lastSoundMillis = 0L
    private var sound = false
    private var volume = 2
    var toleranceKph: Int = 5
        private set
    private var tone: ToneGenerator? = null

    /** 목록 로드는 IO에서 하고, GPS 수신 중단 시 과거 제한속도를 즉시 버린다. */
    fun start() {
        if (job?.isActive == true) return
        mutableState.value = SafetyState(stalled = true)
        job = scope.launch {
            try {
                if (index == null) index = withContext(Dispatchers.IO) {
                    val text = application.assets.open("safety_cameras.json").bufferedReader().use { it.readText() }
                    val dataset = Json { ignoreUnknownKeys = true }.decodeFromString<CameraDataset>(text)
                    require(dataset.cameras.isNotEmpty())
                    CameraIndex(dataset.cameras)
                }
                mutableState.value = SafetyState(ready = true, stalled = true)
                while (isActive) {
                    if (SystemClock.elapsedRealtime() - lastFixMillis > 5_000) {
                        mutableState.value = SafetyState(ready = true, stalled = true)
                    }
                    delay(1_000)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = SafetyState(stalled = true)
                DiagLog.add("안전 안내 · 오프라인 목록을 읽지 못했어요 (${error.javaClass.simpleName})")
            }
        }
    }

    /** 서비스가 받는 GPS를 공유해 별도 위치 구독과 배터리 소모를 만들지 않는다. */
    fun onLocation(location: Location) {
        if (job?.isActive != true || !state.value.ready) return
        val age = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        if (age !in 0..5_000_000_000L || !location.hasAccuracy() || location.accuracy !in 0f..30f) {
            mutableState.value = SafetyState(ready = true, stalled = true)
            return
        }
        lastFixMillis = SystemClock.elapsedRealtime()
        val speed = if (location.hasSpeed()) location.speed * 3.6 else 0.0
        val alert = if (location.hasBearing() && location.hasSpeed()) index?.nearest(
            location.latitude, location.longitude, location.bearing.toDouble(), speed, location.accuracy.toDouble(),
        ) else null
        mutableState.value = SafetyState(ready = true, alert = alert)
        if (sound && state.value.isOverSpeed(speed, toleranceKph) && lastFixMillis - lastSoundMillis >= 10_000) {
            lastSoundMillis = lastFixMillis
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
        lastFixMillis = 0
        lastSoundMillis = 0
        tone?.release()
        tone = null
        mutableState.value = SafetyState()
    }
}
