package com.wemade.teslamacro.feature.destination

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.wemade.teslamacro.R

/** 런처에서는 입력창만 열고 실제 전송은 사용자가 전송 버튼을 누른 뒤 실행한다. */
class DestinationWidget : AppWidgetProvider() {
    /** 모든 위젯을 같은 입력창에 연결해 중복 창과 백그라운드 전송을 막는다. */
    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        val intent = Intent(context, DestinationQuickSendActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        widgetIds.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.destination_widget)
            views.setOnClickPendingIntent(R.id.destination_widget_input, pendingIntent)
            views.setOnClickPendingIntent(R.id.destination_widget_root, pendingIntent)
            manager.updateAppWidget(id, views)
        }
    }
}
