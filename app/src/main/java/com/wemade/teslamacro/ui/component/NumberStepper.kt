package com.wemade.teslamacro.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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

/** 입력 키보드 없이 값을 조절하며 경계에서는 해당 동작을 비활성화한다. */
@Composable
fun NumberStepper(
    value: Double,
    min: Double,
    max: Double,
    step: Double,
    unit: String,
    onChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        StepButton(DraftMark.Minus, "줄이기", enabled = value > min) {
            onChange(snap((value - step).coerceAtLeast(min), step))
        }
        // 고정 폭이면 "3600초"나 글꼴 확대 시 잘린다 — 최소 폭만 보장
        Text(
            text = format(value) + unit,
            style = MaterialTheme.typography.titleMedium,
            color = T.Ink,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 76.dp),
        )
        StepButton(DraftMark.Add, "늘리기", enabled = value < max) {
            onChange(snap((value + step).coerceAtMost(max), step))
        }
    }
}

/** 좁은 화면에서도 잘리지 않게 시·분 스테퍼를 세로로 쌓는다. */
@Composable
fun HourMinuteStepper(minutesOfDay: Int, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        NumberStepper(
            value = (minutesOfDay / 60).toDouble(),
            min = 0.0,
            max = 23.0,
            step = 1.0,
            unit = "시",
            onChange = { onChange(it.toInt() * 60 + minutesOfDay % 60) },
        )
        NumberStepper(
            value = (minutesOfDay % 60).toDouble(),
            min = 0.0,
            max = 55.0,
            step = 5.0,
            unit = "분",
            onChange = { onChange((minutesOfDay / 60) * 60 + it.toInt()) },
        )
    }
}

/** 아이콘의 동작 이름을 터치 영역에 제공한다. */
@Composable
private fun StepButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            // clip을 먼저 — 리플이 둥근 모서리 밖으로 번지지 않게
            .clip(RoundedCornerShape(Radius.button))
            .background(T.Slate)
            .clickable(
                enabled = enabled,
                role = androidx.compose.ui.semantics.Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) T.Ink else T.InkFaint,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 부동소수 누적 오차로 22.499999가 되는 걸 막는다 */
private fun snap(value: Double, step: Double): Double = round(value / step) * step

private fun format(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)
