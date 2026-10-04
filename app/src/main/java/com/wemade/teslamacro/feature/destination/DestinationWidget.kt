package com.wemade.teslamacro.feature.destination

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import com.wemade.teslamacro.R
import com.wemade.teslamacro.TeslaMacroApplication
import com.wemade.teslamacro.data.settings.DestinationWidgetAppearance
import com.wemade.teslamacro.data.settings.DestinationWidgetTheme
import com.wemade.teslamacro.data.settings.DestinationWidgetText
import com.wemade.teslamacro.ui.theme.LightPalette
import com.wemade.teslamacro.ui.theme.DarkPalette
import com.wemade.teslable.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** 런처에서는 입력창만 열고 실제 전송은 사용자가 전송 버튼을 누른 뒤 실행한다. */
class DestinationWidget : AppWidgetProvider() {
    /** 런처 재시작·위젯 추가 때도 저장된 꾸미기 설정을 복원한다. */
    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store = (context.applicationContext as TeslaMacroApplication).container.settingsStore
                val appearance = withTimeout(8_000) { store.settings.first().destinationWidgetAppearance }
                updateWidgets(context, manager, widgetIds, appearance)
            } catch (_: Exception) {
                DiagLog.add("목적지 위젯 설정을 불러오지 못했어요")
            } finally { pending.finish() }
        }
    }

    /** 런처 크기 변경 즉시 현재 폭에 맞는 아이콘·문구를 다시 그린다. */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, widgetId: Int, newOptions: Bundle) {
        onUpdate(context, manager, intArrayOf(widgetId))
    }

    companion object {

        /** 저장 직후 이미 배치한 위젯에도 같은 설정을 적용한다. */
        fun updateAll(context: Context, appearance: DestinationWidgetAppearance) {
            val manager = AppWidgetManager.getInstance(context)
            updateWidgets(context, manager, manager.getAppWidgetIds(ComponentName(context, DestinationWidget::class.java)), appearance)
        }

        /** 미리보기와 실제 위젯에 같은 뷰를 사용해 투명도·색상이 어긋나지 않게 한다. */
        internal fun createViews(context: Context, appearance: DestinationWidgetAppearance, compact: Boolean = false): RemoteViews {
            val background = when (appearance.theme) {
                DestinationWidgetTheme.LIGHT -> LightPalette.void
                DestinationWidgetTheme.DARK -> DarkPalette.carbon
                DestinationWidgetTheme.BLUE -> LightPalette.electricFaint
            }
            val text = when (appearance.text) {
                DestinationWidgetText.LIGHT -> DarkPalette.ink
                DestinationWidgetText.DARK -> LightPalette.ink
                DestinationWidgetText.AUTO -> if (appearance.theme == DestinationWidgetTheme.DARK) DarkPalette.ink else LightPalette.ink
            }.toArgb()
            return RemoteViews(context.packageName, R.layout.destination_widget).apply {
                setInt(R.id.destination_widget_background, "setColorFilter", background.toArgb())
                setInt(R.id.destination_widget_background, "setImageAlpha", (100 - appearance.transparency.coerceIn(0, 100)) * 255 / 100)
                setViewVisibility(R.id.destination_widget_input, if (compact) View.GONE else View.VISIBLE)
                setViewPadding(R.id.destination_widget_content,
                    if (compact) 0 else context.resources.getDimensionPixelSize(R.dimen.widget_icon_padding), 0,
                    if (compact) 0 else context.resources.getDimensionPixelSize(R.dimen.widget_spacing), 0)
                setContentDescription(R.id.destination_widget_root, context.getString(R.string.destination_widget_title))
                setTextColor(R.id.destination_widget_input, text)
                setInt(R.id.destination_widget_search, "setColorFilter", text)
                setTextViewText(R.id.destination_widget_input, context.getString(
                    if (appearance.showTitle) R.string.destination_widget_title else R.string.destination_widget_input,
                ))
            }
        }

        /** 검색창을 누르면 입력창을 열고 꾸미기는 런처의 재설정 메뉴에서 연다. */
        private fun updateWidgets(context: Context, manager: AppWidgetManager, widgetIds: IntArray, appearance: DestinationWidgetAppearance) {
            val input = Intent(context, DestinationQuickSendActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val openInput = PendingIntent.getActivity(context, 0, input, flags)
            widgetIds.forEach { id ->
                val width = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 240)
                val views = createViews(context, appearance, compact = width < 120)
                views.setOnClickPendingIntent(R.id.destination_widget_input, openInput)
                views.setOnClickPendingIntent(R.id.destination_widget_root, openInput)
                manager.updateAppWidget(id, views)
            }
        }
    }
}
