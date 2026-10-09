package com.wemade.teslamacro.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.wemade.teslamacro.service.QuickActionAccessStore
import com.wemade.teslamacro.ui.component.ButtonTone
import com.wemade.teslamacro.ui.component.SettingRow
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.ui.theme.Space

/** 외부 자동화에 필요한 키 발급·복사·해제만 기존 명령 전송 설정에서 제공한다. */
@Composable
internal fun ExternalQuickActionPanel(access: QuickActionAccessStore) {
    val context = LocalContext.current
    var hasKey by remember(access) { mutableStateOf(access.hasAutomationKey()) }
    var showSettings by remember { mutableStateOf(false) }
    SettingRow("외부 앱 연동", if (hasKey) "키 발급됨" else "사용자 확인", onClick = { showSettings = true })
    if (showSettings) AlertDialog(
        onDismissRequest = { showSettings = false },
        title = { Text("외부 앱 연동") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                Text("자동화 요청의 automation_key에 연동키를 추가하세요.")
                TButton("연동키 복사", onClick = {
                    runCatching {
                        val key = access.automationKey()
                        hasKey = true
                        val clip = ClipData.newPlainText("Smart Tesla 연동키", key)
                        if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply {
                            putBoolean("android.content.extra.IS_SENSITIVE", true)
                        }
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                    }.onSuccess { Toast.makeText(context, "연동키를 복사했어요", Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(context, "연동키를 복사하지 못했어요", Toast.LENGTH_SHORT).show() }
                })
                if (hasKey) TButton("연동 해제", ButtonTone.Danger, onClick = {
                    runCatching { access.revokeAutomationKey() }
                        .onSuccess {
                            hasKey = false
                            Toast.makeText(context, "외부 앱 연동을 해제했어요", Toast.LENGTH_SHORT).show()
                        }.onFailure { Toast.makeText(context, "외부 앱 연동을 해제하지 못했어요", Toast.LENGTH_SHORT).show() }
                })
            }
        },
        confirmButton = { TButton("닫기", ButtonTone.Ghost, fillWidth = false, onClick = { showSettings = false }) },
    )
}
