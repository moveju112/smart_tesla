package com.wemade.teslamacro.data.gateway

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.media.AudioAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import com.wemade.teslamacro.domain.command.VehicleCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

/** 개폐·잠금 성공만 짧게 알리고 폴링·공조·충전 전류 자동 조절에는 소리를 내지 않는다. */
internal fun VehicleCommand.hasConfirmationTone(): Boolean = when (this) {
    VehicleCommand.OpenFrunk, VehicleCommand.OpenTrunk, VehicleCommand.CloseTrunk,
    VehicleCommand.Lock, VehicleCommand.Unlock, VehicleCommand.VentWindows,
    VehicleCommand.CloseWindows, is VehicleCommand.SetChargePort -> true
    else -> false
}

/** 기기 소리 정책을 유지하면서 생략 이유를 진단 로그와 테스트에서 공유한다. */
internal fun confirmationToneSkipReason(ringerMode: Int, mediaVolume: Int, interruptionFilter: Int): String? = when {
    ringerMode == AudioManager.RINGER_MODE_SILENT -> "무음 모드"
    ringerMode != AudioManager.RINGER_MODE_NORMAL && ringerMode != AudioManager.RINGER_MODE_VIBRATE -> "소리 모드 확인 불가"
    mediaVolume == 0 -> "미디어 음량 0"
    interruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL -> "방해금지 또는 알림 허용 상태 확인 불가"
    else -> null
}

/** 외부 음성·바로가기 요청은 서비스의 실제 결과를 본 뒤 별도 완료음을 내며 공유 게이트웨이의 중복음을 막는다. */
internal object ExternalQuickActionSound : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> = Key
    object Key : CoroutineContext.Key<ExternalQuickActionSound>
}

/** 진동 모드에서도 미디어 확인음을 내되 무음·방해금지·사용자 음량은 유지한다. */
class CommandFeedback(private val context: Context, private val scope: CoroutineScope) {
    private val toneLock = Mutex()

    /** 수신음은 실행 성공으로 오해하지 않도록 진동 없이 한 번만 낸다. */
    fun received(label: String) = play(label, 1, false)

    /** 기존 화면·자동 명령은 개폐 성공 때만 한 번 알린다. */
    fun confirmed(command: VehicleCommand) {
        if (command.hasConfirmationTone()) play(command.label, 1, true)
    }

    /** 음성·바로가기는 실제 차량 성공 응답 뒤 모든 단일 명령을 두 번 알린다. */
    fun quickActionConfirmed(command: VehicleCommand) = play(command.label, 2, command.hasConfirmationTone())

    /** 비동기 매크로의 마지막 단계까지 성공했을 때만 전체 완료음을 낸다. */
    fun quickActionCompleted(label: String) = play(label, 2, false)

    /** 비동기 수신음과 빠른 성공음이 겹치지 않게 직렬 재생한다. */
    private fun play(label: String, count: Int, vibrate: Boolean) {
        scope.launch(Dispatchers.Main) {
            toneLock.withLock {
                var tone: ToneGenerator? = null
                try {
                    val audio = context.getSystemService(AudioManager::class.java)
                    val notifications = context.getSystemService(NotificationManager::class.java)
                    if (vibrate && audio.ringerMode != AudioManager.RINGER_MODE_SILENT &&
                        notifications.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
                        runCatching {
                            val vibrator = context.getSystemService(Vibrator::class.java)
                            if (vibrator?.hasVibrator() == true) {
                                vibrator.vibrate(
                                    VibrationEffect.createOneShot(180L, VibrationEffect.DEFAULT_AMPLITUDE),
                                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                                )
                                com.wemade.teslable.DiagLog.add("명령 확인 진동 [$label] 요청")
                            }
                        }.onFailure {
                            com.wemade.teslable.DiagLog.add("명령 확인 진동 오류 — ${it.javaClass.simpleName}")
                        }
                    }
                    val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val skipReason = confirmationToneSkipReason(audio.ringerMode, volume, notifications.currentInterruptionFilter)
                    if (skipReason != null) {
                        com.wemade.teslable.DiagLog.add("명령 확인음 [$label] 생략 — $skipReason")
                        return@withLock
                    }
                    // 기존 단발음 길이는 보존하고, 두 번 울릴 때만 짧은 음·간격을 사용한다.
                    tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
                    repeat(count) { index ->
                        val started = tone.startTone(ToneGenerator.TONE_PROP_BEEP2, if (count == 1) 400 else 170)
                        com.wemade.teslable.DiagLog.add(
                            "명령 확인음 [$label] ${index + 1}/$count " + if (started)
                                "재생 시작 요청 수락 — 미디어 음량 $volume · 실제 청취 확인 아님"
                            else "재생 시작 실패 — 오디오 장치가 요청을 거부했어요"
                        )
                        if (started) delay(if (count == 1) 550L else 280L)
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // 오디오 실패는 이미 보낸 차량 명령의 결과를 바꾸거나 재전송하지 않는다.
                    com.wemade.teslable.DiagLog.add("명령 확인음 [$label] 오류 — ${error.javaClass.simpleName}")
                } finally {
                    runCatching { tone?.release() }
                }
            }
        }
    }
}
