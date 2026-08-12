package com.wemade.teslamacro.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.wemade.teslamacro.ui.component.ButtonTone
import com.wemade.teslamacro.ui.component.NumberStepper
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.ui.component.TCard
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

/**
 * 시뮬레이터 조작판.
 *
 * 차 없이 매크로 전체 흐름을 확인하려면 "문이 열렸다", "실내가 31℃다" 같은
 * 사건을 사람이 만들어줘야 한다. 차량이 등록되지 않았을 때만 나온다.
 */
@Composable
fun SimulatorPanel(
    insideTemp: Double,
    outsideTemp: Double,
    onInsideTempChange: (Double) -> Unit,
    onOutsideTempChange: (Double) -> Unit,
    onBoard: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TCard(modifier = modifier, outlined = true) {
        Text(
            text = "가상 차량",
            style = MaterialTheme.typography.titleMedium,
            color = T.Warn,
        )
        Text(
            text = "실제 차량이 아니에요. 매크로가 제대로 발동하는지 여기서 확인할 수 있어요.",
            style = MaterialTheme.typography.bodySmall,
            color = T.InkFaint,
        )

        Spacer(Modifier.height(Space.md))
        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            LabeledStepper("실내 온도", insideTemp, onInsideTempChange)
            LabeledStepper("외부 온도", outsideTemp, onOutsideTempChange)
        }

        Spacer(Modifier.height(Space.md))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            // 문 열림 + 탑승을 한 번에 만든다 (엣지 조건이 걸리는 순간)
            TButton("탑승 재현", modifier = Modifier.weight(1f), onClick = onBoard)
            TButton(
                "하차 재현", ButtonTone.Secondary, modifier = Modifier.weight(1f), onClick = onLeave
            )
        }
    }
}

@Composable
private fun LabeledStepper(label: String, value: Double, onChange: (Double) -> Unit) {
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = T.InkMuted,
            modifier = Modifier.weight(1f),
        )
        NumberStepper(
            value = value,
            min = -20.0, max = 60.0, step = 1.0, unit = "℃",
            onChange = onChange,
        )
    }
}
