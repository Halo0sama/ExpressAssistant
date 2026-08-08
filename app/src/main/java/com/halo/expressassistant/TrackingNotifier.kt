package com.halo.expressassistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.halo.expressassistant.data.ExpressItem
import com.halo.expressassistant.ui.MainActivity

object TrackingNotifier {
    const val CHANNEL = "express_tracking"

    fun notify(context: Context, item: ExpressItem) {
        Log.d("ExpressTracking", "notify ${item.mailNo} ${item.latestText}")
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "快递跟踪", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val pi = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = item.latestText.ifBlank { "快递状态已更新" }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("${item.companyName} 有新动态")
            .setContentText(text.take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(item.mailNo.hashCode(), n)
    }
}
