package com.wemade.teslamacro.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.wemade.teslamacro.data.fleet.commandLabel
import com.wemade.teslamacro.data.fleet.statusMessage
import com.wemade.teslamacro.ui.component.*
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

/** 서버가 지원하는 범위만 선택하고 기존 서비스의 인증·유효시간·BLE 우선 경로를 재사용한다. */
@Composable
internal fun FleetCommandSheet(enabled: Boolean, onRun: (String) -> Unit, onDismiss: () -> Unit) {
    var temperature by rememberSaveable { mutableStateOf(22.0) }
    var chargeLimit by rememberSaveable { mutableStateOf(80.0) }
    PickerSheet(title = "빠른 차량 명령", onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.md)) {
            Text("연결된 BLE 우선 · 미연결 시 Fleet", style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
            for ((action, label) in listOf(
                "open_frunk" to "보닛 열기", "open_trunk" to "뒤 트렁크 작동",
                "lock" to "문 잠금", "climate_on" to "공조 켜기", "climate_off" to "공조 끄기",
                "charge_start" to "충전 시작", "charge_stop" to "충전 중지",
            )) TButton(label, ButtonTone.Ghost, enabled = enabled, onClick = { onRun(action) })
            Hairline()
            NumberStepper(temperature, 15.0, 28.0, 0.5, "℃", { temperature = it }, label = "공조 온도", enabled = enabled)
            TButton("온도 적용", enabled = enabled, onClick = { onRun("set_temperature_${(temperature * 10).toInt()}") })
            Hairline()
            NumberStepper(chargeLimit, 50.0, 100.0, 1.0, "%", { chargeLimit = it }, label = "충전 한도", enabled = enabled)
            TButton("충전 한도 적용", enabled = enabled, onClick = { onRun("set_charge_limit_${chargeLimit.toInt()}") })
        }
    }
}

/** 결과를 수동 조회하는 기록 화면이며 재시작·재조회 때 명령을 다시 보내는 버튼은 두지 않는다. */
@Composable
internal fun FleetHistorySheet(controls: FleetCredentialControls, onDismiss: () -> Unit) {
    PickerSheet(title = "최근 Fleet 명령", onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.md)) {
            if (controls.history.isEmpty()) Text("Fleet 명령 기록이 없어요", color = T.InkMuted)
            for (entry in controls.history.asReversed()) key(entry.receipt.requestKey) {
                val receipt = entry.receipt
                val observedTime = remember(entry.updatedAt) {
                    java.time.format.DateTimeFormatter.ofPattern("MM/dd HH:mm:ss")
                        .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(entry.updatedAt))
                }
                Text(receipt.commandLabel(), style = MaterialTheme.typography.titleSmall, color = T.Ink)
                Text("마지막 확인 · $observedTime", style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
                Text(receipt.statusMessage(), style = MaterialTheme.typography.bodyMedium, color = T.InkMuted)
                if (entry.canRefresh) TButton(
                    if (controls.state.busy) "확인 중…" else "결과만 다시 확인",
                    enabled = controls.state.stored && !controls.state.busy,
                    onClick = { controls.onRefresh(receipt.requestKey) },
                )
                Hairline()
            }
        }
    }
}
