package com.wemade.teslamacro.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wemade.teslamacro.ui.theme.Radius
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T
import kotlin.math.round

/**
 * −/+ 로 숫자를 조절한다.
 *
 * 차 안에서 쓰는 앱이라 키보드 입력을 피한다.
 * 44dp 타겟이라 흔들리는 차에서도 누를 수 있다.
 */
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
        StepButton("−", enabled = value > min) {
            onChange(snap((value - step).coerceAtLeast(min), step))
        }
        Text(
            text = format(value) + unit,
            style = MaterialTheme.typography.titleMedium,
            color = T.Ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(76.dp),
        )
        StepButton("+", enabled = value < max) {
            onChange(snap((value + step).coerceAtMost(max), step))
        }
    }
}

@Composable
private fun StepButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(T.Slate, RoundedCornerShape(Radius.button))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbol,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) T.Ink else T.InkFaint,
        )
    }
}

/** 부동소수 누적 오차로 22.499999가 되는 걸 막는다 */
private fun snap(value: Double, step: Double): Double = round(value / step) * step

private fun format(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)
