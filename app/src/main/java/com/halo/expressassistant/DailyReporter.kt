package com.halo.expressassistant

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.halo.expressassistant.ai.AiClient
import com.halo.expressassistant.data.PendingReport
import com.halo.expressassistant.data.ReportSchedule
import com.halo.expressassistant.data.Store
import com.halo.expressassistant.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

object ReportScheduler {
    const val CHANNEL = "daily_report"
    private const val REQUEST_BASE = 70000

    fun requestCode(schedule: ReportSchedule): Int =
        REQUEST_BASE + (schedule.id % 1_000_000).toInt()

    fun scheduleAll(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (schedule in Store.reportSchedules(context)) {
            if (schedule.enabled) scheduleOne(context, schedule, am)
        }
    }

    fun scheduleOne(context: Context, schedule: ReportSchedule, am: AlarmManager? = null) {
        if (!schedule.enabled) return
        val am = am ?: context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, schedule)
        val trigger = nextTrigger(schedule)
        try {
            val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            } else {
                am.setWindow(AlarmManager.RTC_WAKEUP, trigger, 60_000, pi)
            }
        } catch (e: Exception) {
            try {
                am.setWindow(AlarmManager.RTC_WAKEUP, trigger, 60_000, pi)
            } catch (ignored: Exception) {
            }
        }
    }

    private fun nextTrigger(schedule: ReportSchedule): Long {
        val now = System.currentTimeMillis()
        val mask = when (schedule.repeat) {
            ReportSchedule.REPEAT_WEEKDAYS -> ReportSchedule.MASK_WEEKDAYS
            ReportSchedule.REPEAT_WEEKENDS -> ReportSchedule.MASK_WEEKENDS
            ReportSchedule.REPEAT_CUSTOM -> schedule.weekdays
            ReportSchedule.REPEAT_ONCE -> 0
            else -> ReportSchedule.MASK_ALL
        }
        for (offset in 0..7) {
            val base = Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.DAY_OF_YEAR, offset)
            }
            val dayBit = 1 shl ((base.get(Calendar.DAY_OF_WEEK) + 5) % 7)
            if (mask != 0 && (mask and dayBit) == 0) continue
            val t = Calendar.getInstance().apply {
                timeInMillis = base.timeInMillis
                set(Calendar.HOUR_OF_DAY, schedule.hour)
                set(Calendar.MINUTE, schedule.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (t.timeInMillis > now) return t.timeInMillis
        }
        return now + 86_400_000L
    }

    fun reschedule(context: Context, old: List<ReportSchedule>, new: List<ReportSchedule>) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (schedule in old) {
            am.cancel(pendingIntent(context, schedule))
        }
        Store.saveReportSchedules(context, new)
        for (schedule in new) {
            scheduleOne(context, schedule, am)
        }
    }

    fun cancelAll(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (schedule in Store.reportSchedules(context)) {
            am.cancel(pendingIntent(context, schedule))
        }
    }

    private fun pendingIntent(context: Context, schedule: ReportSchedule): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode(schedule),
            Intent(context, ReportReceiver::class.java).putExtra("id", schedule.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

class ReportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scheduleId = intent.getLongExtra("id", 0L)
        Log.d("ExpressReport", "onReceive id=$scheduleId")
        CoroutineScope(Dispatchers.Main).launch {
            val text = generateReport(context)
            if (text == null) {
                val schedule = Store.reportSchedules(context).firstOrNull { it.id == scheduleId }
                if (schedule != null && schedule.repeat != ReportSchedule.REPEAT_ONCE) {
                    ReportScheduler.scheduleOne(context, schedule)
                }
                return@launch
            }
            Store.savePendingReport(context, PendingReport(System.currentTimeMillis(), text))
            val schedule = Store.reportSchedules(context).firstOrNull { it.id == scheduleId }
            Log.d("ExpressReport", "generated fg=${MainActivity.isForeground}")
            if (MainActivity.isForeground) {
                MainActivity.onReportReady()
            } else {
                notify(context, scheduleId, text)
            }
            if (schedule != null && schedule.repeat == ReportSchedule.REPEAT_ONCE) {
                val schedules = Store.reportSchedules(context).map {
                    if (it.id == schedule.id) it.copy(enabled = false) else it
                }
                Store.saveReportSchedules(context, schedules)
            } else if (schedule != null) {
                ReportScheduler.scheduleOne(context, schedule)
            }
        }
    }

    private suspend fun generateReport(context: Context): String? {
        val items = Store.items(context)
        if (items.isEmpty()) return null
        val fallback = buildString {
            append("# 快递日报\n\n")
            val moving = items.filter { it.state != 3 }
            val done = items.filter { it.state == 3 }
            append("## 在途（${moving.size}）\n\n")
            for (item in moving) {
                append("- ${item.companyName} ${item.mailNo}：${item.stateLabel()}，${item.latestText}\n")
            }
            append("\n## 已签收（${done.size}）\n\n")
            for (item in done) {
                append("- ${item.companyName} ${item.mailNo}：${item.latestText}\n")
            }
        }
        if (Store.aiKey(context).isBlank()) return fallback
        return try {
            val answer = AiClient.ask(
                context,
                items,
                "现在是快递日报时间。请用 Markdown 生成一份简洁的中文日报：用 ## 分小节（在途、已签收、今日提醒），" +
                    "每件快递写公司、单号、当前状态和预计送达（如有），不要编造未给出的信息。"
            )
            if (answer.startsWith("出错：") || answer.startsWith("请求失败")) fallback else answer
        } catch (e: Throwable) {
            fallback
        }
    }

    private fun notify(context: Context, scheduleId: Long, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(ReportScheduler.CHANNEL, "快递日报", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val pi = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val items = Store.items(context)
        val inTransit = items.count { it.state != 3 }
        val delivering = items.count { it.state == 5 || it.stateName.contains("派送") }
        val done = items.count { it.state == 3 }
        val etas = items
            .filter { it.state != 3 && it.eta.isNotBlank() }
            .map { it.eta }
            .distinct()
            .take(2)
        val summary = buildString {
            append("在途 $inTransit 件")
            if (delivering > 0) append(" · 派送中 $delivering")
            if (done > 0) append(" · 已签收 $done")
            if (etas.isNotEmpty()) append("\n预计送达：${etas.joinToString("、")}")
        }
        val preview = text
            .replace(Regex("(?m)^#{1,6}\\s+"), "")
            .replace(Regex("[*_`#>]"), "")
            .trim()
            .lines()
            .filter { it.isNotBlank() }
            .take(4)
            .joinToString("\n")
        val bigText = "$summary\n\n$preview".trim()
        val n = NotificationCompat.Builder(context, ReportScheduler.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("快递日报")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify((scheduleId % 1_000_000).toInt(), n)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReportScheduler.scheduleAll(context)
    }
}
