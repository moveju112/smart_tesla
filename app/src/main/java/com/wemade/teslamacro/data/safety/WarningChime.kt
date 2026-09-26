package com.wemade.teslamacro.data.safety

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

private const val CHIME_SAMPLE_RATE = 44_100

/**
 * 사용자가 고르는 과속 경고음 종류.
 *
 * 녹음 파일 대신 음 높이·길이·감쇠·배음만 달리 합성해 저작권·에셋 관리 없이 종류를 늘린다.
 * 모두 0.4초 안에 끝나 가장 빠른 반복 간격(0.6초)에서도 다음 소리와 겹치지 않는다.
 */
enum class WarningSound(
    val settingValue: String,
    val label: String,
    /** 음 높이(Hz)와 길이(초). 높이 0은 쉼이다 */
    internal val notes: List<Pair<Double, Double>>,
    /** 초당 지수 감쇠. 클수록 종처럼 빨리 사라지고 작을수록 평평한 삑에 가깝다 */
    internal val decay: Double,
    /** 2·3배음 비율. 높을수록 작은 스피커에서 또렷하지만 거칠어진다 */
    internal val overtones: Pair<Double, Double>,
) {
    CHIME("chime", "띠링", listOf(1_046.5 to 0.11, 0.0 to 0.03, 1_568.0 to 0.16), 9.0, 0.25 to 0.08),
    DING_DONG("dingdong", "딩동", listOf(1_318.5 to 0.15, 0.0 to 0.02, 1_046.5 to 0.2), 6.0, 0.2 to 0.06),
    BEEP("beep", "삐빅", listOf(1_760.0 to 0.07, 0.0 to 0.05, 1_760.0 to 0.07), 3.0, 0.35 to 0.15),
    ALERT("alert", "긴급 삐삐삐", listOf(1_480.0 to 0.06, 0.0 to 0.04, 1_480.0 to 0.06, 0.0 to 0.04, 1_480.0 to 0.06), 2.0, 0.3 to 0.12),
    SOFT("soft", "부드러운 벨", listOf(880.0 to 0.35), 7.0, 0.15 to 0.05);

    companion object {
        /** 알 수 없는 저장값·옛 백업은 기본 띠링으로 되돌린다. */
        fun of(value: String?): WarningSound = entries.firstOrNull { it.settingValue == value } ?: CHIME
    }
}

/**
 * 과속 경고음 한 번 분량의 16비트 PCM.
 * ToneGenerator 기본 삑은 거칠고 음악에 묻혀, 배음과 감쇠를 넣어 부드럽지만 또렷하게 만든다.
 */
internal fun warningChimePcm(sound: WarningSound, gain: Float, sampleRate: Int = CHIME_SAMPLE_RATE): ShortArray {
    // 1. 음과 쉼을 샘플 수로 정한다.
    val total = sound.notes.sumOf { (sampleRate * it.second).toInt() }
    val samples = DoubleArray(total)
    val (second, third) = sound.overtones
    var offset = 0
    for ((frequency, seconds) in sound.notes) {
        val length = (sampleRate * seconds).toInt()
        if (frequency > 0) for (index in 0 until length) {
            val time = index.toDouble() / sampleRate
            // 2. 6ms 상승·지수 감쇠·끝 8ms 페이드로 클릭 없이 끊는다.
            val attack = min(1.0, time / 0.006)
            val release = min(1.0, (length - index).toDouble() / (sampleRate * 0.008))
            val envelope = attack * release * exp(-time * sound.decay)
            // 3. 배음을 섞어 단일 사인보다 작은 스피커에서도 잘 들리게 한다.
            samples[offset + index] = envelope * (sin(2 * PI * frequency * time) +
                second * sin(4 * PI * frequency * time) + third * sin(6 * PI * frequency * time))
        }
        offset += length
    }
    // 4. 배음 위상에 따라 실제 최댓값이 달라져, 실측 최댓값으로 맞춰 음량 단계별 크기를 끝까지 쓴다.
    val loudest = samples.maxOf { kotlin.math.abs(it) }.takeIf { it > 0 } ?: return ShortArray(total)
    val scale = gain.coerceIn(0f, 1f) * 0.95 * Short.MAX_VALUE / loudest
    return ShortArray(total) { (samples[it] * scale).toInt().toShort() }
}

/** 합성 경고음을 내비 안내 용도로 재생한다. 음량 단계나 종류가 바뀌면 새로 만든다. */
internal class WarningChime {
    private var track: AudioTrack? = null
    private var level = -1
    private var sound: WarningSound? = null

    /** 이전 경고음이 끝나지 않았으면 처음부터 다시 울려 반복 간격을 지킨다. */
    fun play(volumeLevel: Int, warningSound: WarningSound): Boolean {
        val current = track?.takeIf { level == volumeLevel && sound == warningSound } ?: build(volumeLevel, warningSound)
        if (current.playState == AudioTrack.PLAYSTATE_PLAYING) current.stop()
        current.reloadStaticData()
        current.play()
        return current.state == AudioTrack.STATE_INITIALIZED
    }

    /** 경보가 끝나면 남은 소리를 바로 끊는다. */
    fun stop() {
        runCatching { track?.takeIf { it.playState == AudioTrack.PLAYSTATE_PLAYING }?.stop() }
    }

    /** 안내 종료·설정 변경 시 오디오 자원을 돌려준다. */
    fun release() {
        runCatching { track?.release() }
        track = null
        level = -1
        sound = null
    }

    // 1. 음량 단계(1~3)를 진폭으로 바꿔 PCM을 한 번만 만들어 정적 버퍼에 싣는다.
    private fun build(volumeLevel: Int, warningSound: WarningSound): AudioTrack {
        release()
        val pcm = warningChimePcm(warningSound, 0.45f + 0.275f * (volumeLevel.coerceIn(1, 3) - 1))
        val created = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(CHIME_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        created.write(pcm, 0, pcm.size)
        track = created
        level = volumeLevel
        sound = warningSound
        return created
    }
}
