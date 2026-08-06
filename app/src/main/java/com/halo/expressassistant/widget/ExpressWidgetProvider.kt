package com.halo.expressassistant.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.halo.expressassistant.R
import com.halo.expressassistant.data.Store

class ExpressWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_express)
            val items = Store.items(context)
            val text = if (items.isEmpty()) {
                "打开快递助手添加快递"
            } else {
                items.take(6).joinToString("\n") {
                    "${it.companyName} ${it.stateLabel()}\n${it.latestText.take(24)}"
                }
            }
            views.setTextViewText(R.id.widget_list, text)
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}
