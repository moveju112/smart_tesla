package com.wemade.teslamacro.data.gateway

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
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

/** 외부 음원이나 권한 추가 없이 짧은 확인음을 내며 무음·진동·방해금지는 존중한다. */
class CommandFeedback(private val context: Context, private val scope: CoroutineScope) {
    /** 성공 응답 확인음이며 실제 개폐 완료를 센서로 확인했다는 뜻은 아니다. */
    fun confirmed(command: VehicleCommand) {
        if (!command.hasConfirmationTone()) return
        scope.launch(Dispatchers.Main) {
            var tone: ToneGenerator? = null
            try {
                val audio = context.getSystemService(AudioManager::class.java)
                val notifications = context.getSystemService(NotificationManager::class.java)
                if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL ||
                    audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) == 0 ||
                    notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return@launch
                tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60)
                tone.startTone(ToneGenerator.TONE_PROP_ACK, 180)
                delay(300L)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 효과음 장치 오류를 차량 명령 실패로 바꾸거나 명령을 다시 보내지 않는다.
            } finally {
                tone?.release()
            }
        }
    }
}
