package com.wemade.teslamacro.feature.destination

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import com.wemade.teslamacro.data.settings.DestinationWidgetAppearance
import com.wemade.teslamacro.data.settings.DestinationWidgetTheme
import com.wemade.teslamacro.data.settings.DestinationWidgetText
import com.wemade.teslamacro.data.settings.SettingsStore
import com.wemade.teslamacro.ui.component.ButtonTone
import com.wemade.teslamacro.ui.component.ChoiceSettingRow
import com.wemade.teslamacro.ui.component.SettingToggleRow
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 선택은 임시로 미리보고 저장을 누른 경우에만 모든 홈 위젯에 적용한다. */
@Composable
internal fun DestinationWidgetAppearanceScreen(
    initial: DestinationWidgetAppearance,
    store: SettingsStore,
    onBack: () -> Unit,
) {
    var theme by rememberSaveable { mutableStateOf(initial.theme.name) }
    var transparency by rememberSaveable { mutableStateOf(initial.transparency) }
    var text by rememberSaveable { mutableStateOf(initial.text.name) }
    var showTitle by rememberSaveable { mutableStateOf(initial.showTitle) }
    var busy by androidx.compose.runtime.remember { mutableStateOf(false) }
    var error by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    val appearance = DestinationWidgetAppearance(
        DestinationWidgetTheme.valueOf(theme), transparency, DestinationWidgetText.valueOf(text), showTitle,
    )
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler { if (!busy) onBack() }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text("위젯 꾸미기", style = MaterialTheme.typography.titleLarge, color = T.Ink)
        Text("홈 화면의 모든 목적지 위젯에 적용해요. 입력창은 앱 테마를 따라요.",
            style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
        DestinationWidgetPreview(appearance)
        ChoiceSettingRow("배경 색상", DestinationWidgetTheme.entries.map { it.name to it.label }, theme) { theme = it }
        Text("배경 투명도 $transparency%", style = MaterialTheme.typography.bodyMedium, color = T.Ink)
        Slider(
            value = transparency.toFloat(), onValueChange = { transparency = it.roundToInt() },
            valueRange = 0f..100f, steps = 19, enabled = !busy,
            modifier = Modifier.semantics { contentDescription = "배경 투명도" },
        )
        Text("0% 불투명 · 100% 완전 투명", style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
        TButton("완전 투명", tone = ButtonTone.Secondary, enabled = !busy, onClick = { transparency = 100 })
        ChoiceSettingRow("글자 색상", DestinationWidgetText.entries.map { it.name to it.label }, text) { text = it }
        SettingToggleRow("제목 표시", showTitle, { showTitle = it })
        error?.let { Text(it, color = T.Danger, style = MaterialTheme.typography.bodySmall) }
        TButton(if (busy) "적용 중…" else "저장", enabled = !busy, onClick = {
            busy = true
            error = null
            scope.launch {
                try {
                    store.setDestinationWidgetAppearance(appearance)
                    DestinationWidget.updateAll(context, appearance)
                    onBack()
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    error = "위젯 설정을 적용하지 못했어요. 다시 시도해 주세요."
                } finally { busy = false }
            }
        })
        TButton("취소", tone = ButtonTone.Ghost, enabled = !busy, onClick = onBack)
    }
}

/** 체크 무늬 위에 실제 위젯을 얹어 배경만 투명해지고 글자는 남는 모습을 보여준다. */
@Composable
private fun DestinationWidgetPreview(appearance: DestinationWidgetAppearance) {
    val light = T.Void
    val dark = T.Slate
    AndroidView(
        factory = { context -> DestinationWidget.createViews(context, appearance).apply(context, null) },
        update = { view -> DestinationWidget.createViews(view.context, appearance).reapply(view.context, view) },
        modifier = Modifier.fillMaxWidth().height(Space.xxl * 3).drawBehind {
            val cell = Space.md.toPx()
            for (row in 0..(size.height / cell).toInt()) {
                for (column in 0..(size.width / cell).toInt()) {
                    drawRect(if ((row + column) % 2 == 0) light else dark,
                        Offset(column * cell, row * cell), Size(cell, cell))
                }
            }
        },
    )
}
