package com.wemade.teslamacro.feature.destination

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.wemade.teslamacro.TeslaMacroApplication
import com.wemade.teslamacro.data.settings.ThemeMode
import com.wemade.teslamacro.ui.component.ButtonTone
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.ui.theme.Radius
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T
import com.wemade.teslamacro.ui.theme.TeslaMacroTheme

/** 런처의 길게 누르기 설정은 입력창과 분리해 위젯 구성 결과를 돌려준다. */
class DestinationWidgetConfigurationActivity : ComponentActivity() {
    /** 유효한 목적지 위젯만 구성하며 취소하면 기존 꾸미기를 유지한다. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        setResult(RESULT_CANCELED, result)
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider
        if (provider != ComponentName(this, DestinationWidget::class.java)) {
            finish()
            return
        }
        setFinishOnTouchOutside(false)
        val app = application as TeslaMacroApplication
        setContent {
            val ready by app.ready.collectAsState()
            val initializationError by app.initializationError.collectAsState()
            val settings by app.container.settingsStore.settings.collectAsState(initial = null)
            TeslaMacroTheme(mode = settings?.themeMode ?: ThemeMode.AUTO) {
                Surface(shape = RoundedCornerShape(Radius.card), color = T.Carbon) {
                    if (ready && settings != null) {
                        DestinationWidgetAppearanceScreen(
                            initial = settings!!.destinationWidgetAppearance,
                            store = app.container.settingsStore,
                            onSaved = { setResult(RESULT_OK, result); finish() },
                            onBack = ::finish,
                        )
                    } else {
                        Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                            Text(initializationError ?: "위젯 설정 준비 중…", color = T.Ink)
                            if (initializationError != null) TButton("다시 시도", onClick = app::retryInitialization)
                            TButton("닫기", tone = ButtonTone.Ghost, onClick = ::finish)
                        }
                    }
                }
            }
        }
    }
}
