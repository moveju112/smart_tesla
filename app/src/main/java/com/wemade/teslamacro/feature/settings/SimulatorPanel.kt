package com.wemade.teslamacro.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.wemade.teslamacro.ui.component.ButtonTone
import com.wemade.teslamacro.ui.component.NumberSettingRow
import com.wemade.teslamacro.ui.component.SettingActionRow
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.ui.component.Hairline

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
    Column(modifier = modifier) {
        NumberSettingRow("실내 온도", insideTemp,
            min = -20.0, max = 60.0, step = 1.0, unit = "℃", onChange = onInsideTempChange)
        Hairline()
        NumberSettingRow("외부 온도", outsideTemp,
            min = -20.0, max = 60.0, step = 1.0, unit = "℃", onChange = onOutsideTempChange)
        Hairline()
        SettingActionRow("탑승 재현") {
            // 문 열림과 탑승을 함께 발생시켜 매크로 조건을 점검한다.
            TButton("실행", fillWidth = false, onClick = onBoard)
        }
        SettingActionRow("하차 재현") {
            TButton("실행", ButtonTone.Secondary, fillWidth = false, onClick = onLeave)
        }
    }
}
