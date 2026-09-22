package com.wemade.teslamacro.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.wemade.teslamacro.service.QuickActionRequests
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

/** 화면 이동과 무관하게 대기 명령을 먼저 보여 주고, 전송 처리 중에는 취소 성공을 약속하지 않는다. */
@Composable
fun QuickActionRequestPanel(
    requests: List<QuickActionRequests.Request>,
    onCancel: (Long) -> Unit,
    onDismiss: (Long) -> Unit,
) {
    if (requests.isEmpty()) return
    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = Space.xxl * 5).background(T.Slate),
    ) {
        items(requests.sortedBy { if (it.status == QuickActionRequests.Status.Waiting) 0 else if (it.active) 1 else 2 }, key = { it.id }) { request ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(Space.md),
                horizontalArrangement = Arrangement.spacedBy(Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                    verticalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    Text(request.label, style = MaterialTheme.typography.titleSmall, color = T.Ink)
                    Text(request.status.message, style = MaterialTheme.typography.bodyMedium, color = T.Ink)
                }
                if (request.status == QuickActionRequests.Status.Waiting) {
                    TButton(text = "취소", small = true, fillWidth = false, onClick = { onCancel(request.id) })
                } else if (!request.active) {
                    TButton(text = "닫기", tone = ButtonTone.Ghost, small = true, fillWidth = false, onClick = { onDismiss(request.id) })
                }
            }
        }
    }
}
