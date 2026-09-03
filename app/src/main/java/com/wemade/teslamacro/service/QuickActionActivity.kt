package com.wemade.teslamacro.service

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslable.DiagLog

/**
 * 앱 화면을 열지 않고 명령 하나만 실행하는 진입점.
 *
 * 이게 있으면 **외부에서 부를 수 있는 통로**가 생긴다:
 * - 홈 화면 바로가기 / 런처 아이콘 길게 누르기
 * - 구글 어시스턴트 ("보닛 열기 실행")
 * - 빅스비 루틴 · Tasker · MacroDroid 같은 자동화 앱
 * - 저장 매크로 동적 바로가기
 *
 * 창을 띄우지 않고 감시 서비스에 요청을 넘긴 뒤 즉시 끝난다.
 */
class QuickActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val macroId = intent?.getStringExtra(EXTRA_MACRO_ID)
            ?: intent?.data?.getQueryParameter(QUERY_MACRO_ID)
        val action = intent?.getStringExtra(EXTRA_ACTION)
            ?: intent?.data?.getQueryParameter("action")
        val command = ACTIONS[action]

        val requestLabel = macroId?.let { "매크로 $it" } ?: command?.label
        if (requestLabel == null) {
            DiagLog.add("빅스비 바로가기 거부 — 알 수 없는 동작: $action")
            toast("알 수 없는 동작: $action")
            finish()
            return
        }

        // NoDisplay 화면은 onResume 전에 끝나야 한다. BLE 작업은 이미 살아 있는
        // 감시 서비스에 넘겨야 연결을 기다리는 동안 시스템이 화면을 강제 종료하지 않는다.
        DiagLog.add("빅스비 바로가기 수신 — $requestLabel")
        runCatching { MacroService.runQuickAction(this, action, macroId) }
            .onFailure { error ->
                DiagLog.add("빅스비 바로가기 전달 실패 — $requestLabel: ${error.message}")
                toast("$requestLabel 실행 실패")
            }
        finish()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_ACTION = "action"
        const val EXTRA_MACRO_ID = "macro_id"
        const val QUERY_MACRO_ID = "macro"

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
