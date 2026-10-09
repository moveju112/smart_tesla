package com.wemade.teslamacro.service

import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.wemade.teslamacro.TeslaMacroApplication
import com.wemade.teslamacro.ui.component.ButtonTone
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.ui.theme.TeslaMacroTheme

/** 연동키 없는 기존 외부 요청만 사용자 확인을 거쳐 실행하는 비공개 진입점. */
class QuickActionConfirmationActivity : ComponentActivity() {
    /** 저장된 매크로 이름으로 실행 대상을 표시하고 확인 전에는 명령을 접수하지 않는다. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent.getStringExtra(QuickActionActivity.EXTRA_ACTION)
        val macroId = intent.getStringExtra(QuickActionActivity.EXTRA_MACRO_ID)
        val receivedAt = intent.getLongExtra(EXTRA_RECEIVED_AT, SystemClock.elapsedRealtime())
        val app = application as TeslaMacroApplication
        setContent {
            val ready by app.ready.collectAsState()
            val initializationError by app.initializationError.collectAsState()
            val settings by app.container.settingsStore.settings.collectAsState(initial = app.container.initialSettings)
            val rules by app.container.ruleStore.rules.collectAsState()
            val label = if (macroId != null) rules.firstOrNull { it.id == macroId }?.name
                else QuickActionActivity.ACTIONS[action]?.label
            LaunchedEffect(ready, initializationError, label) {
                if (initializationError != null || (ready && label == null)) {
                    Toast.makeText(this@QuickActionConfirmationActivity, "실행할 명령을 찾지 못했어요", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            if (ready && label != null) TeslaMacroTheme(mode = settings.themeMode) {
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text("외부 요청 확인") },
                    text = { Text("$label 실행할까요?") },
                    confirmButton = {
                        TButton("실행", fillWidth = false, onClick = {
                            runCatching { MacroService.runQuickAction(this@QuickActionConfirmationActivity,
                                action, macroId, receivedAt = receivedAt) }
                                .onFailure { Toast.makeText(this@QuickActionConfirmationActivity,
                                    "명령을 전달하지 못했어요", Toast.LENGTH_SHORT).show() }
                            finish()
                        })
                    },
                    dismissButton = { TButton("취소", ButtonTone.Ghost, fillWidth = false, onClick = { finish() }) },
                )
            }
        }
    }

    companion object {
        const val EXTRA_RECEIVED_AT = "received_at"
    }
}
