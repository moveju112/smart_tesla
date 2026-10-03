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
import com.wemade.teslamacro.ui.theme.T
import com.wemade.teslamacro.ui.theme.Space

/** 토큰 원문은 화면 상태에 포함하지 않는다. */
data class FleetCredentialState(val stored: Boolean = false, val busy: Boolean = false, val message: String? = null)
data class FleetCredentialControls(
    val state: FleetCredentialState,
    val onSave: (String) -> Unit,
    val onDelete: () -> Unit,
    val onCheck: () -> Unit,
)

/** 토큰 입력과 관리 동작은 Fleet 상세 시트에만 놓고 비밀값은 제출 즉시 비운다. */
@Composable
internal fun FleetCredentialPanel(controls: FleetCredentialControls) {
    var token by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val state = controls.state
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        if (!state.stored) {
            DraftField(
                value = token,
                onValueChange = { if (it.length <= 8192) token = it },
                label = "토큰",
                enabled = !state.busy,
                placeholder = "API 토큰 붙여넣기",
                note = "Tesla Client Secret은 입력하지 마세요",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                visualTransformation = PasswordVisualTransformation(),
            )
            TButton(text = if (state.busy) "처리 중…" else "토큰 저장", enabled = !state.busy && token.isNotBlank(), onClick = {
                val submitted = token
                token = ""
                focus.clearFocus()
                controls.onSave(submitted)
            })
        } else {
            SettingActionRow("연결 확인") {
                TButton("확인", enabled = !state.busy, fillWidth = false, onClick = controls.onCheck)
            }
            Hairline()
            SettingActionRow("저장된 토큰") {
                TButton("삭제", ButtonTone.Danger, enabled = !state.busy,
                    fillWidth = false, onClick = { token = ""; controls.onDelete() })
            }
            Text("암호화해 저장됨", style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
        }
        state.message?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall,
                color = if (message.startsWith("연결 확인 완료") || message.startsWith("토큰을 삭제하고") ||
                    message.startsWith("토큰을 암호화")) T.InkMuted else T.Danger)
        }
    }
}
