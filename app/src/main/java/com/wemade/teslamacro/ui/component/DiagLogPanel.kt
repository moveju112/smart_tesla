package com.wemade.teslamacro.ui.component

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import com.wemade.teslable.DiagLog
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

/**
 * 진단 로그 카드.
 *
 * 실차 문제는 개발자가 현장에 없을 때 터진다.
 * 복사(클립보드)와 공유(공유 시트 — 메일·메신저 직행) 두 길을 둔다.
 *
 * @param title 카드 안 제목. 화면이 SectionHeader를 카드 밖에 둘 때는 null로 끈다
 * @param showLines 로그 줄을 화면에 늘어놓을지. 개발자용이라 평소엔 끈다 —
 *   등록 화면처럼 사용자가 진행 과정을 봐야 하는 곳에서만 켠다
 * @param shareExtra 공유 본문 앞에 붙일 추가 정보(설정 덤프 등). 호출부가 채운다
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagLogPanel(
    modifier: Modifier = Modifier,
    title: String? = "진단 로그",
    showLines: Boolean = true,
    shareExtra: () -> String = { "" },
) {
    val lines by DiagLog.lines.collectAsState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    TCard(modifier = modifier, outlined = true) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = T.Ink,
            )
            Spacer(Modifier.height(Space.sm))
        }
        // 버튼 3개는 제목과 별도 줄 — 좁은 화면에서 제목+버튼이 한 줄에 못 들어가 넘치던 문제.
        // FlowRow라 그래도 좁으면 버튼끼리도 줄바꿈된다
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            TButton(
                text = "공유",
                tone = ButtonTone.Secondary,
                fillWidth = false,
                small = true,
                enabled = lines.isNotEmpty(),
                onClick = {
                    // 공유 시트로 바로 보낸다 — 복사→메신저→붙여넣기 삼단을 한 번으로.
                    // 파일에 남은 것까지 전부 싣는다: 화면 버퍼만 보내면
                    // 재시작 전 기록이 빠지는데 원인은 대개 그 앞에 있다
                    val text = listOf(shareExtra(), DiagLog.dumpAll())
                        .filter { it.isNotBlank() }
                        .joinToString("\n\n")
                    runCatching {
                        context.startActivity(
                            Intent.createChooser(shareIntentFor(context, text), "진단 로그 보내기")
                        )
                    }.onFailure { failure ->
                        DiagLog.add("진단 로그 공유 실패 — ${failure.message ?: failure.javaClass.simpleName}")
                        Toast.makeText(context, "진단 로그를 공유하지 못했어요.", Toast.LENGTH_SHORT).show()
                    }
                },
            )
            TButton(
                text = "복사",
                tone = ButtonTone.Secondary,
                fillWidth = false,
                small = true,
                enabled = lines.isNotEmpty(),
                onClick = { clipboard.setText(AnnotatedString(DiagLog.dumpAll())) },
            )
            TButton(
                text = "지우기",
                tone = ButtonTone.Ghost,
                fillWidth = false,
                small = true,
                enabled = lines.isNotEmpty(),
                onClick = { DiagLog.clear() },
            )
        }
        Spacer(Modifier.height(Space.md))

        if (!showLines) {
            // 줄을 늘어놓지 않는다 — 사용자가 읽을 내용이 아니고, 여기가 화면을 제일 많이 먹었다.
            // 공유 한 번으로 전체가 나가므로 몇 줄 쌓였는지만 알려준다
            // 화면 줄 수만 적으면 "300줄뿐"으로 읽힌다 — 파일에 얼마나 쌓였는지가
            // 공유로 실제 나가는 양이다
            val storedKb = remember(lines.size) { DiagLog.storedBytes() / 1024 }
            Text(
                text = when {
                    lines.isEmpty() && storedKb <= 0 -> "아직 기록이 없어요."
                    storedKb > 0 -> "기록 ${lines.size}줄 (파일 ${storedKb}KB). " +
                        "문제가 생기면 공유를 눌러 파일로 보내주세요."
                    else -> "기록 ${lines.size}줄. 문제가 생기면 공유를 눌러 보내주세요."
                },
                style = MaterialTheme.typography.bodySmall,
                color = T.InkFaint,
            )
        } else if (lines.isEmpty()) {
            Text(
                text = "아직 기록이 없어요.\n차량 찾기를 시도하면 여기에 과정이 남아요.",
                style = MaterialTheme.typography.bodySmall,
                color = T.InkFaint,
            )
        } else {
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                // 최근 것만 화면에 보인다. 복사는 전체가 담긴다
                lines.takeLast(VISIBLE_LINES).forEach { line ->
                    // 에러 메시지에 개행이 섞여도 로그는 한 항목 = 한 줄 (잘림 방지)
                    Text(
                        text = line.replace('\n', ' '),
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

/**
 * 로그를 파일 첨부로 내보내는 인텐트.
 *
 * 텍스트로 붙여 보내면 긴 로그가 메신저에서 **말없이 잘린다** —
 * 받은 쪽은 잘린 줄 모르고 원인을 엉뚱한 데서 찾는다.
 * 파일이 안 되는 기기·앱을 위해 본문에도 같은 내용을 함께 싣는다.
 */
private fun shareIntentFor(context: android.content.Context, text: String): Intent {
    val base = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "Smart Tesla 진단 로그")

    val uri = runCatching {
        // 공유 전용 폴더 하나만 FileProvider에 열려 있다 — 내부 저장소 전체를 열면
        // VIN이 든 설정 파일까지 나갈 수 있다
        val dir = java.io.File(context.cacheDir, "logs").apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
            .format(java.util.Date())
        val file = java.io.File(dir, "smart-tesla-$stamp.txt")
        file.writeText(text)
        androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }.getOrNull() ?: return base.putExtra(Intent.EXTRA_TEXT, fallbackShareText(text))

    return base
        // 전체 로그는 파일에만 둔다. 1MB 가까운 본문을 Intent에 중복하면
        // Binder 한도를 넘어 공유 시트 자체가 열리지 않는다.
        .putExtra(Intent.EXTRA_TEXT, "Smart Tesla 진단 로그 파일을 첨부했습니다.")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // EXTRA_STREAM만 보는 앱과 ClipData 권한만 받는 앱을 모두 지원한다.
        .apply {
            clipData = ClipData.newUri(context.contentResolver, "Smart Tesla 진단 로그", uri)
        }
}

/** 파일 생성 실패 때 Intent가 커지지 않도록 본문으로 보내는 최근 로그 상한 */
private const val FALLBACK_TEXT_CHARS = 32_000

/** 파일 첨부를 만들지 못해도 Binder 한도를 넘지 않는 최근 로그만 남긴다 */
internal fun fallbackShareText(text: String): String = text.takeLast(FALLBACK_TEXT_CHARS)
