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

/** 진동 모드에서도 진동과 미디어 확인음을 함께 내되 무음·방해금지·사용자 음량은 유지한다. */
class CommandFeedback(private val context: Context, private val scope: CoroutineScope) {
    /** 성공 응답 확인음이며 실제 개폐 완료를 센서로 확인했다는 뜻은 아니다. */
    fun confirmed(command: VehicleCommand) {
        if (!command.hasConfirmationTone()) return
        scope.launch(Dispatchers.Main) {
            var tone: ToneGenerator? = null
            try {
                val audio = context.getSystemService(AudioManager::class.java)
                val notifications = context.getSystemService(NotificationManager::class.java)
                // 소리 실패나 음량 0이 진동까지 막지 않도록 별도로 요청한다.
                if (audio.ringerMode != AudioManager.RINGER_MODE_SILENT &&
                    notifications.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
                    runCatching {
                        val vibrator = context.getSystemService(Vibrator::class.java)
                        if (vibrator?.hasVibrator() == true) {
                            vibrator.vibrate(
                                VibrationEffect.createOneShot(180L, VibrationEffect.DEFAULT_AMPLITUDE),
                                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                            )
                            com.wemade.teslable.DiagLog.add("명령 확인 진동 [${command.label}] 요청")
                        }
                    }.onFailure {
                        com.wemade.teslable.DiagLog.add("명령 확인 진동 오류 — ${it.javaClass.simpleName}")
                    }
                }
                val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                val skipReason = confirmationToneSkipReason(audio.ringerMode, volume, notifications.currentInterruptionFilter)
                if (skipReason != null) {
                    com.wemade.teslable.DiagLog.add("명령 확인음 [${command.label}] 생략 — $skipReason")
                    return@launch
                }
                // 알림 스트림은 진동 모드에서 묵음이므로 미디어 스트림을 쓰며 음량은 강제로 올리지 않는다.
                tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
                val started = tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
                com.wemade.teslable.DiagLog.add(
                    "명령 확인음 [${command.label}] " + if (started)
                        "재생 시작 요청 수락 — 미디어 음량 $volume · 실제 청취 확인 아님"
                    else "재생 시작 실패 — 오디오 장치가 요청을 거부했어요"
                )
                if (started) delay(550L)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // 효과음 장치 오류를 차량 명령 실패로 바꾸거나 명령을 다시 보내지 않는다.
                com.wemade.teslable.DiagLog.add("명령 확인음 [${command.label}] 오류 — ${error.javaClass.simpleName}")
            } finally {
                runCatching { tone?.release() }
            }
        }
    }
}
