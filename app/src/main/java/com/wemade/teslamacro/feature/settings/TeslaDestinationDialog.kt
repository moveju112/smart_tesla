package com.wemade.teslamacro.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.wemade.teslamacro.data.nav.TeslaDestinationCandidate
import com.wemade.teslamacro.data.nav.TeslaNavigationShare
import com.wemade.teslamacro.ui.component.DraftField
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T
import kotlinx.coroutines.launch

/** 지역이 빠진 주소·동명 장소는 앱이 보일 때 전체 주소를 선택받고 한 번만 공유한다. */
@Composable
internal fun TeslaDestinationDialog(sharing: TeslaNavigationShare) {
    val pending by sharing.selection.collectAsState()
    val request = pending ?: return
    val scope = rememberCoroutineScope()
    var query by remember(request.id) { mutableStateOf(request.query) }
    var selected by remember(request.id) { mutableStateOf<TeslaDestinationCandidate?>(null) }
    AlertDialog(
        onDismissRequest = { scope.launch { sharing.dismiss(request.id) } },
        title = { Text("테슬라 목적지 확인") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DraftField(query, { query = it; selected = null }, label = "전체 주소 또는 장소",
                    enabled = !request.searching, isError = request.error != null, note = request.error,
                    placeholder = "시·군·구를 포함한 주소")
                TextButton(onClick = { scope.launch { sharing.search(request.id, query) } }, enabled = !request.searching) {
                    Text(if (request.searching) "조회 중…" else "주소 검색")
                }
                Spacer(Modifier.height(Space.sm))
                request.candidates.forEach { candidate ->
                    TextButton(onClick = { selected = candidate }, enabled = !request.searching,
                        modifier = Modifier.heightIn(min = Space.xxl)) {
                        Text((if (selected == candidate) "✓ " else "") + candidate.address,
                            color = if (selected == candidate) T.Electric else T.Ink)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !request.searching && selected != null && query == request.query,
                onClick = { selected?.let { candidate -> scope.launch { sharing.confirm(request.id, candidate) } } }) {
                Text(if (selected?.point == null && selected != null) "주소로 공유" else "공유")
            }
        },
        dismissButton = { TextButton(onClick = { scope.launch { sharing.dismiss(request.id) } }) { Text("취소") } },
    )
}
