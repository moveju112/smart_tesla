package com.wemade.teslamacro.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.wemade.teslamacro.data.nav.TeslaDestinationSelection
import com.wemade.teslamacro.data.nav.TeslaNavigationShare
import com.wemade.teslamacro.ui.component.DraftField
import com.wemade.teslamacro.ui.theme.*
import kotlinx.coroutines.launch

/** 내비 화면 위에서 주소를 고르면 기존 공유 경로로 한 번만 전달한다. */
@Composable
internal fun TeslaDestinationChooser(request: TeslaDestinationSelection, sharing: TeslaNavigationShare, maxHeight: Dp) {
    val scope = rememberCoroutineScope()
    var query by remember(request.id) { mutableStateOf(request.query) }
    Surface(shape = RoundedCornerShape(Radius.card), color = T.Carbon,
        border = BorderStroke(Stroke.thin, T.Hairline)) {
        Column(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(Space.md)) {
            Text("테슬라 목적지 선택", style = MaterialTheme.typography.titleLarge, color = T.Ink)
            Spacer(Modifier.height(Space.sm))
            DraftField(query, { query = it }, label = "전체 주소 또는 장소", enabled = !request.searching,
                isError = request.error != null, note = request.error, placeholder = "시·군·구를 포함한 주소")
            TextButton(onClick = { scope.launch { sharing.search(request.id, query) } }, enabled = !request.searching,
                modifier = Modifier.heightIn(min = Space.xxl)) {
                Text(if (request.searching) "조회 중…" else "주소 검색")
            }
            request.candidates.forEach { candidate ->
                TextButton(onClick = { sharing.select(request.id, candidate) },
                    enabled = !request.searching && query == request.query,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Space.xxl)) {
                    Text(candidate.address, color = T.Electric)
                }
            }
            TextButton(onClick = { scope.launch { sharing.dismiss(request.id) } },
                modifier = Modifier.heightIn(min = Space.xxl)) { Text("취소") }
        }
    }
}
