package com.wemade.teslamacro.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.wemade.teslamacro.ui.component.ButtonTone
import com.wemade.teslamacro.ui.component.DraftField
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

/** 토큰 원문은 화면 상태에 포함하지 않는다. */
data class FleetCredentialState(val stored: Boolean = false, val busy: Boolean = false, val message: String? = null)
data class FleetCredentialControls(
    val state: FleetCredentialState,
    val onSave: (String) -> Unit,
    val onDelete: () -> Unit,
    val onCheck: () -> Unit,
)

/** 기존 카드 안에 입력 영역만 추가한다. 비밀값은 저장 복원하지 않고 제출 즉시 비운다. */
@Composable
internal fun FleetCredentialPanel(controls: FleetCredentialControls) {
    var token by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val state = controls.state
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text(if (state.stored) "토큰 저장됨" else "토큰 미등록",
            style = MaterialTheme.typography.bodyMedium, color = T.Ink)
        if (!state.stored) {
            DraftField(
                value = token,
                onValueChange = { if (it.length <= 8192) token = it },
                label = "사용자 API 토큰",
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
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            TButton(text = "연결 확인", tone = ButtonTone.Ghost, modifier = Modifier.weight(1f),
                enabled = state.stored && !state.busy, onClick = controls.onCheck)
            TButton(text = "토큰 삭제", tone = ButtonTone.Ghost, modifier = Modifier.weight(1f),
                enabled = state.stored && !state.busy, onClick = { token = ""; controls.onDelete() })
        }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = T.Ink) }
    }
}
