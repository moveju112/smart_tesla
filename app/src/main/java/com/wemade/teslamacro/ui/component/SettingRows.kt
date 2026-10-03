package com.wemade.teslamacro.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

/** 설정 이름과 현재 값을 같은 행에 두고 편집은 공통 시트로 연결한다. */
@Composable
fun SettingRow(label: String, value: String? = null, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PickerRow(label = label, value = value, onClick = onClick, showChevron = true,
        modifier = modifier.heightIn(min = Space.xxl + Space.sm))
}

/** 버튼 폭을 제한해 큰 글씨에서도 설정 이름과 조작이 서로 밀어내지 않는다. */
@Composable
fun SettingActionRow(label: String, description: String? = null, action: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = Space.sm)) {
        val actionWidth = maxWidth * 0.55f
        Row(Modifier.fillMaxWidth().heightIn(min = Space.xxl + Space.sm)
            .padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            if (description != null) {
                HelpTitle(label, description, modifier = Modifier.weight(1f))
            } else {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = T.Ink,
                    modifier = Modifier.weight(1f))
            }
            Box(Modifier.widthIn(max = actionWidth), contentAlignment = Alignment.CenterEnd) { action() }
        }
    }
}

/** 오른쪽 스위치에도 항목 이름을 제공해 읽기 도구에서 조작 대상을 구분한다. */
@Composable
fun SettingToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit,
    description: String? = null) {
    SettingActionRow(label, description) {
        DraftToggle(checked, onCheckedChange, modifier = Modifier.semantics { contentDescription = label })
    }
}

/** 수치 편집은 기존 범위 검사와 적용·취소 동작을 그대로 사용한다. */
@Composable
fun NumberSettingRow(label: String, value: Double, min: Double, max: Double, step: Double,
    unit: String, onChange: (Double) -> Unit) {
    NumberStepper(value, min, max, step, unit, onChange, label = label)
}

/** 시각도 현재 값 행에서 기존 24시간 입력 시트로 이동한다. */
@Composable
fun TimeSettingRow(label: String, minutesOfDay: Int, onChange: (Int) -> Unit) {
    HourMinuteStepper(minutesOfDay, onChange, label = label)
}

/** 선택지는 필요할 때만 열고 선택 즉시 저장한 뒤 현재 값으로 돌아온다. */
@Composable
fun ChoiceSettingRow(label: String, options: List<Pair<String, String>>, selected: String,
    onSelect: (String) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    SettingRow(label, options.firstOrNull { it.first == selected }?.second ?: selected,
        onClick = { editing = true })
    if (editing) {
        PickerSheet(label, onDismiss = { editing = false }) {
            PickerList(options) { option ->
                PickerRow(label = option.second, value = if (option.first == selected) "선택됨" else null,
                    onClick = { onSelect(option.first); editing = false })
            }
        }
    }
}
