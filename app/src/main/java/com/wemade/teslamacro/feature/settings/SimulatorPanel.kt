package com.wemade.teslamacro.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.wemade.teslamacro.ui.component.ButtonTone
import com.wemade.teslamacro.ui.component.NumberStepper
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.ui.component.Hairline
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

/**
 * 시뮬레이터 조작판.
 *
 * 차 없이 매크로 전체 흐름을 확인하려면 "문이 열렸다", "실내가 31℃다" 같은
 * 사건을 사람이 만들어줘야 한다. 차량이 등록되지 않았을 때만 나온다.
 */
@OptIn(ExperimentalLayoutApi::class)
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
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Space.md)) {
        LabeledStepper("실내 온도", insideTemp, onInsideTempChange)
        Hairline()
        LabeledStepper("외부 온도", outsideTemp, onOutsideTempChange)
        Spacer(Modifier.height(Space.sm))
        Hairline()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            // 문 열림 + 탑승을 한 번에 만든다 (엣지 조건이 걸리는 순간)
            TButton("탑승 재현", fillWidth = false, onClick = onBoard)
            TButton("하차 재현", ButtonTone.Secondary, fillWidth = false, onClick = onLeave)
        }
    }
}

/** 큰 글씨에서 값 조작 영역이 잘리지 않게 이름과 입력을 세로로 나눈다. */
@Composable
private fun LabeledStepper(label: String, value: Double, onChange: (Double) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = T.Ink)
        NumberStepper(
            value = value,
            min = -20.0, max = 60.0, step = 1.0, unit = "℃",
            onChange = onChange,
        )
    }
}
