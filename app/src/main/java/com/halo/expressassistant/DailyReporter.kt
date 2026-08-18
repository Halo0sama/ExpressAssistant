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
import com.halo.expressassistant.data.sectionKeyOf
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
            val issue = Store.nextReportIssue(context)
            Store.savePendingReport(context, PendingReport(System.currentTimeMillis(), text, issue))
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
        val moving = items.filter { isInTransit(it) }
        if (moving.isEmpty()) return null
        val fallback = buildString {
            append("今日在途 ${moving.size} 件：\n\n")
            for (item in moving) {
                val eta = item.eta.ifBlank { item.aiEta }
                append("- ${item.companyName} ${item.mailNo}：${item.stateLabel()}")
                if (eta.isNotBlank()) append("，预计 $eta")
                append("\n  ${item.latestText}\n")
            }
        }
        if (Store.aiKey(context).isBlank()) return fallback
        return try {
            val answer = AiClient.ask(
                context,
                moving,
                "现在是晨报时间。请用你自己的文风写一篇完整的、像报纸短讯一样的小早报：" +
                    "把每件在途快递的当前状态、预计送达，和你的判断（结合发货地、收货地、快递公司、中转耗时、当前位置，" +
                    "哪件进展顺利、哪件可能延误或值得留意、整体节奏如何）自然融成一体，不要分硬性栏目，" +
                    "不要使用“在途”“云雀思考”这类小标题，也不要说明本早报的覆盖范围（例如“只报在途快递”之类的话）。" +
                    "全文保持极简，不要输出 [[card:单号]] 标记或“直通查询”之类的提示，" +
                    "不重复主界面已有的轨迹原文，不编造未给出的信息。"
            )
            val cleaned = answer
                .replace(Regex("\\[\\[card:[^\\]]+\\]\\]"), "")
                .replace(Regex("直通查询[:：]?"), "")
                .trim()
            if (cleaned.startsWith("出错：") || cleaned.startsWith("请求失败")) fallback else cleaned
        } catch (e: Throwable) {
            fallback
        }
    }

    private fun isInTransit(item: com.halo.expressassistant.data.ExpressItem): Boolean =
        sectionKeyOf(item) in setOf("delivering", "shipped", "notshipped")

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
        val moving = items.filter { isInTransit(it) }
        val inTransit = moving.size
        val delivering = moving.count { it.state == 5 || it.stateName.contains("派送") }
        val etas = moving
            .filter { it.eta.isNotBlank() }
            .map { it.eta }
            .distinct()
            .take(2)
        val summary = buildString {
            append("在途 $inTransit 件")
            if (delivering > 0) append(" · 派送中 $delivering")
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
