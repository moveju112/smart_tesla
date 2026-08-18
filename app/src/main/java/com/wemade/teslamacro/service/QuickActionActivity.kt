package com.wemade.teslamacro.service

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.wemade.teslamacro.TeslaMacroApplication
import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.domain.command.confirmCategory
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 앱 화면을 열지 않고 명령 하나만 실행하는 진입점.
 *
 * 이게 있으면 **외부에서 부를 수 있는 통로**가 생긴다:
 * - 홈 화면 바로가기 / 런처 아이콘 길게 누르기
 * - 구글 어시스턴트 ("보닛 열기 실행")
 * - 빅스비 루틴 · Tasker · MacroDroid 같은 자동화 앱
 *
 * 창을 띄우지 않고 실행 후 즉시 끝난다.
 */
class QuickActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent?.getStringExtra(EXTRA_ACTION)
            ?: intent?.data?.getQueryParameter("action")
        val command = ACTIONS[action]

        if (command == null) {
            toast("알 수 없는 동작: $action")
            finish()
            return
        }

        val app = application as TeslaMacroApplication
        MainScope().launch {
            // 컨테이너 초기화 전에 눌릴 수 있다 (부팅 직후 바로가기)
            app.ready.first { it }

            val settings = app.container.settingsStore.settings.first()
            // isReady(키 등록까지 완료) — isPaired만 보면 등록 핸드셰이크 도중에 끼어든다
            if (settings.isReady) app.container.gateway.connect(settings.vin)

            val result = app.container.gateway.send(command)
            // 결과를 즉시 다시 읽어, 이어서 앱을 열었을 때 실제 값이 바로 보이게 한다
            if (result.isSuccess) app.container.poller.focusOn(command.confirmCategory())
            toast(
                if (result.isSuccess) "${command.label} 완료"
                else "${command.label} 실패 — ${result.exceptionOrNull()?.message}"
            )
            finish()
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_ACTION = "action"

        /**
         * 외부에 노출하는 동작 목록.
         * 임의 명령을 받지 않고 **화이트리스트**로 제한한다 —
         * 다른 앱이 아무 명령이나 넣어 보내지 못하게 하려는 것이다.
         */
        val ACTIONS: Map<String, VehicleCommand> = mapOf(
            "open_frunk" to VehicleCommand.OpenFrunk,
            "open_trunk" to VehicleCommand.OpenTrunk,
            "lock" to VehicleCommand.Lock,
            "unlock" to VehicleCommand.Unlock,
            "climate_on" to VehicleCommand.ClimateOn,
            "climate_off" to VehicleCommand.ClimateOff,
            "vent_windows" to VehicleCommand.VentWindows,
            "close_windows" to VehicleCommand.CloseWindows,
        )
    }
}
