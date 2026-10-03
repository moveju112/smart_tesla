package com.wemade.teslamacro.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wemade.teslamacro.ui.theme.Radius
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T
import kotlin.math.round
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.KeyboardType
import java.util.Locale

/** 값은 한 줄 입력 면에 두고 직접 입력 또는 작은 증감 버튼으로 조절한다. */
@Composable
fun NumberStepper(
    value: Double,
    min: Double,
    max: Double,
    step: Double,
    unit: String,
    onChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    if (label != null) {
        SettingRow(label, "${format(value)} $unit", onClick = { editing = true }, modifier = modifier)
    } else Row(
        modifier = modifier.fillMaxWidth().border(1.dp, T.Hairline, RoundedCornerShape(Radius.button)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(DraftMark.Minus, "줄이기", enabled = value > min) {
            onChange(snap((value - step).coerceAtLeast(min), step).coerceIn(min, max))
        }
        Text(
            text = "${format(value)} $unit",
            style = MaterialTheme.typography.bodyMedium,
            color = T.Ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).clickable(onClickLabel = "값 직접 입력") { editing = true }
                .heightIn(min = Space.xxl).padding(vertical = Space.sm),
        )
        StepButton(DraftMark.Add, "늘리기", enabled = value < max) {
            onChange(snap((value + step).coerceAtMost(max), step).coerceIn(min, max))
        }
    }
    if (editing) {
        ValueInputSheet(
            title = label ?: "값 입력", initial = format(value), label = "$unit · ${format(min)}~${format(max)}",
            keyboardType = KeyboardType.Decimal,
            valid = { parseNumberInput(it, min, max, step) != null },
            onApply = { parseNumberInput(it, min, max, step)?.let(onChange); editing = false },
            onDismiss = { editing = false },
        )
    }
}

/** 시각은 두 줄 증감판 대신 HH:mm 입력 행으로 표시하고 확인한 값만 반영한다. */
@Composable
fun HourMinuteStepper(minutesOfDay: Int, onChange: (Int) -> Unit) {
    HourMinuteStepper(minutesOfDay, onChange, label = null)
}

/** 설정 행에서도 기존 시각 입력·검증을 재사용한다. */
@Composable
internal fun HourMinuteStepper(minutesOfDay: Int, onChange: (Int) -> Unit, label: String?) {
    var editing by rememberSaveable { mutableStateOf(false) }
    val text = String.format(Locale.ROOT, "%02d:%02d", minutesOfDay / 60, minutesOfDay % 60)
    if (label != null) {
        SettingRow(label, text, onClick = { editing = true })
    } else Text(
        text = text, color = T.Ink, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.button))
            .border(1.dp, T.Hairline, RoundedCornerShape(Radius.button))
            .clickable(onClickLabel = "시각 입력") { editing = true }
            .heightIn(min = Space.xxl).padding(horizontal = Space.md, vertical = Space.sm),
    )
    if (editing) {
        ValueInputSheet(
            title = label ?: "시각 입력", initial = text, label = "24시간 · HH:mm",
            keyboardType = KeyboardType.Text,
            valid = { parseTimeInput(it) != null },
            onApply = { parseTimeInput(it)?.let(onChange); editing = false },
            onDismiss = { editing = false },
        )
    }
}

/** 잘못된 입력이나 취소는 원래 값을 바꾸지 않고 적용 버튼으로만 확정한다. */
@Composable
private fun ValueInputSheet(
    title: String,
    initial: String,
    label: String,
    keyboardType: KeyboardType,
    valid: (String) -> Boolean,
    onApply: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val accepted = valid(text)
    PickerSheet(title, onDismiss, footer = {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            TButton("취소", ButtonTone.Ghost, modifier = Modifier.weight(1f), onClick = onDismiss)
            TButton("적용", modifier = Modifier.weight(1f), enabled = accepted, onClick = { onApply(text) })
        }
    }) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            DraftField(
                value = text, onValueChange = { text = it }, label = label,
                singleLine = true, isError = !accepted,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                note = if (!accepted) "표시된 형식과 범위로 입력해 주세요." else null,
            )
        }
    }
}

/** 직접 입력은 유한한 범위 안의 값만 허용하고 기존 조절 단위에 맞춘다. */
internal fun parseNumberInput(text: String, min: Double, max: Double, step: Double): Double? {
    val value = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
    if (!value.isFinite() || value < min || value > max) return null
    return snap(value, step).coerceIn(min, max)
}

/** 시·분 범위를 검사해 저장 가능한 하루 안의 분으로 변환한다. */
internal fun parseTimeInput(text: String): Int? {
    val parts = text.trim().split(':')
    if (parts.size != 2 || parts[0].length !in 1..2 || parts[1].length != 2 ||
        parts.any { part -> part.any { !it.isDigit() } }) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
}

/** 아이콘의 동작 이름을 터치 영역에 제공한다. */
@Composable
private fun StepButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(Space.xxl),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) T.Ink else T.InkFaint,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 부동소수 누적 오차로 22.499999가 되는 걸 막는다 */
private fun snap(value: Double, step: Double): Double = round(value / step) * step

/** 소수 구분자를 고정해 값 입력 시트에서도 같은 수치를 다시 읽는다. */
private fun format(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.ROOT, "%.1f", value)
