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
 * 과속 경고음 한 번(약 0.3초) 분량의 16비트 PCM.
 *
 * ToneGenerator 기본 삑은 순수 사각 느낌의 한 음이라 거칠고 음악에 묻힌다.
 * 상용 내비의 "띠링"처럼 올라가는 두 음(C6→G6)에 배음과 감쇠를 넣어 부드럽지만 또렷하게 만든다.
 * 녹음 파일 대신 합성해 저작권·에셋 관리 없이 음량 단계마다 다시 만들 수 있다.
 */
internal fun warningChimePcm(gain: Float, sampleRate: Int = CHIME_SAMPLE_RATE): ShortArray {
    // 1. 두 음과 사이 쉼을 샘플 수로 정한다.
    val notes = listOf(1_046.5 to 0.11, 0.0 to 0.03, 1_568.0 to 0.16)
    val total = notes.sumOf { (sampleRate * it.second).toInt() }
    val samples = DoubleArray(total)
    var offset = 0
    for ((frequency, seconds) in notes) {
        val length = (sampleRate * seconds).toInt()
        if (frequency > 0) for (index in 0 until length) {
            val time = index.toDouble() / sampleRate
            // 2. 6ms 상승·지수 감쇠·끝 8ms 페이드로 클릭 없이 종소리처럼 끊는다.
            val attack = min(1.0, time / 0.006)
            val release = min(1.0, (length - index).toDouble() / (sampleRate * 0.008))
            val envelope = attack * release * exp(-time * 9.0)
            // 3. 2·3배음을 조금 섞어 단일 사인보다 작은 스피커에서도 잘 들리게 한다.
            samples[offset + index] = envelope * (sin(2 * PI * frequency * time) +
                0.25 * sin(4 * PI * frequency * time) + 0.08 * sin(6 * PI * frequency * time))
        }
        offset += length
    }
    // 4. 배음 위상에 따라 실제 최댓값이 달라져, 실측 최댓값으로 맞춰 음량 단계별 크기를 끝까지 쓴다.
    val loudest = samples.maxOf { kotlin.math.abs(it) }.takeIf { it > 0 } ?: return ShortArray(total)
    val scale = gain.coerceIn(0f, 1f) * 0.95 * Short.MAX_VALUE / loudest
    return ShortArray(total) { (samples[it] * scale).toInt().toShort() }
}

/** 합성 경고음을 내비 안내 용도로 재생한다. 음량 단계가 바뀌면 새로 만든다. */
internal class WarningChime {
    private var track: AudioTrack? = null
    private var level = -1

    /** 이전 경고음이 끝나지 않았으면 처음부터 다시 울려 반복 간격을 지킨다. */
    fun play(volumeLevel: Int): Boolean {
        val current = track?.takeIf { level == volumeLevel } ?: build(volumeLevel)
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
    }

    // 1. 음량 단계(1~3)를 진폭으로 바꿔 PCM을 한 번만 만들어 정적 버퍼에 싣는다.
    private fun build(volumeLevel: Int): AudioTrack {
        release()
        val pcm = warningChimePcm(0.45f + 0.275f * (volumeLevel.coerceIn(1, 3) - 1))
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
        return created
    }
}
