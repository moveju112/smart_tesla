package com.wemade.teslamacro.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 재부팅 후 감시 서비스를 되살린다.
 *
 * 태블릿은 차량 전원에 물려 있어 시동을 끄면 같이 꺼지는 경우가 많다.
 * 사용자가 앱을 다시 열어야만 매크로가 도는 구조면 자동화가 아니다.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        MacroService.start(context)
    }
}
