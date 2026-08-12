package com.wemade.teslamacro.ui.component

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import com.wemade.teslable.DiagLog
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

/**
 * 진단 로그 카드.
 *
 * 실차 문제는 개발자가 현장에 없을 때 터진다.
 * 복사 버튼 하나로 로그 전체를 메신저에 붙여넣을 수 있게 한다.
 */
@Composable
fun DiagLogPanel(modifier: Modifier = Modifier) {
    val lines by DiagLog.lines.collectAsState()
    val clipboard = LocalClipboardManager.current

    TCard(modifier = modifier, outlined = true) {
        SectionHeader(
            title = "진단 로그",
            trailing = {
                Row {
                    TButton(
                        text = "복사",
                        tone = ButtonTone.Secondary,
                        fillWidth = false,
                        enabled = lines.isNotEmpty(),
                        onClick = { clipboard.setText(AnnotatedString(DiagLog.dump())) },
                    )
                    Spacer(Modifier.width(Space.sm))
                    TButton(
                        text = "지우기",
                        tone = ButtonTone.Ghost,
                        fillWidth = false,
                        enabled = lines.isNotEmpty(),
                        onClick = { DiagLog.clear() },
                    )
                }
            },
        )

        if (lines.isEmpty()) {
            Text(
                text = "아직 기록이 없어요. 차량 찾기를 시도하면 여기에 과정이 남아요.",
                style = MaterialTheme.typography.bodySmall,
                color = T.InkFaint,
            )
        } else {
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                // 최근 것만 화면에 보인다. 복사는 전체가 담긴다
                lines.takeLast(VISIBLE_LINES).forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = T.InkMuted,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(Space.xs))
            Text(
                text = "복사를 눌러 전체 로그를 붙여넣어 주세요 (${lines.size}줄)",
                style = MaterialTheme.typography.bodySmall,
                color = T.InkFaint,
            )
        }
    }
}

private const val VISIBLE_LINES = 14
