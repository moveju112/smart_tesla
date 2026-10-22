package com.wemade.teslamacro.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.wemade.teslamacro.ui.component.ButtonTone
import com.wemade.teslamacro.ui.component.DraftField
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.ui.component.Hairline
import com.wemade.teslamacro.ui.component.SettingActionRow
import com.wemade.teslamacro.ui.component.SettingRow
import com.wemade.teslamacro.data.fleet.FleetHistoryEntry
import com.wemade.teslamacro.ui.theme.T
import com.wemade.teslamacro.ui.theme.Space

/** 토큰 원문은 화면 상태에 포함하지 않는다. */
data class FleetCredentialState(val stored: Boolean = false, val busy: Boolean = false, val message: String? = null)
data class FleetCredentialControls(
    val state: FleetCredentialState,
    val onSave: (String) -> Unit,
    val onDelete: () -> Unit,
    val onCheck: () -> Unit,
    val onDismissMessage: () -> Unit = {},
    val onConnect: () -> Unit = {},
    val history: List<FleetHistoryEntry> = emptyList(),
    val onRefresh: (String) -> Unit = {},
    val onRun: (String) -> Unit = {},
    val commandsEnabled: Boolean = false,
)

/** 토큰 입력과 관리 동작은 Fleet 상세 시트에만 놓고 비밀값은 제출 즉시 비운다. */
@Composable
internal fun FleetCredentialPanel(controls: FleetCredentialControls) {
    var token by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val state = controls.state
    var showCommands by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        if (!state.stored) {
            DraftField(
                value = token,
                onValueChange = { if (it.length <= 8192) token = it },
                label = "토큰",
                enabled = !state.busy,
                placeholder = "API 토큰 붙여넣기",
                note = "32~256자 사용자 API 토큰 · Tesla Client Secret 제외",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                visualTransformation = PasswordVisualTransformation(),
            )
            TButton(text = if (state.busy) "처리 중…" else "토큰 저장",
                enabled = !state.busy && com.wemade.teslamacro.data.fleet.isValidFleetToken(token.trim()), onClick = {
                val submitted = token
                token = ""
                focus.clearFocus()
                controls.onSave(submitted)
            })
        } else {
            SettingActionRow("Tesla 계정") {
                TButton("연결·재연결", enabled = !state.busy, fillWidth = false, onClick = controls.onConnect)
            }
            Hairline()
            SettingActionRow("연결 확인") {
                TButton("확인", enabled = !state.busy, fillWidth = false, onClick = controls.onCheck)
            }
            Hairline()
            SettingActionRow("저장된 토큰") {
                TButton("삭제", ButtonTone.Danger, enabled = !state.busy,
                    fillWidth = false, onClick = { token = ""; controls.onDelete() })
            }
            Text("암호화해 저장됨", style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
            SettingRow("빠른 차량 명령", "열기", enabled = controls.commandsEnabled && !state.busy,
                onClick = { showCommands = true })
        }
        SettingRow("최근 Fleet 명령", "${controls.history.size}건", onClick = { showHistory = true })
    }
    if (showCommands) FleetCommandSheet(
        enabled = controls.commandsEnabled && !state.busy,
        onRun = { action -> showCommands = false; controls.onRun(action) },
        onDismiss = { showCommands = false },
    )
    if (showHistory) FleetHistorySheet(controls, onDismiss = { showHistory = false })
}
