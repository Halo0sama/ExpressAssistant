package com.halo.expressassistant

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.halo.expressassistant.api.XiaomiSync
import com.halo.expressassistant.data.Store

class TrackingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (Store.xiaomiToken(ctx).isBlank()) return Result.success()
        val tracked = Store.items(ctx).filter { it.tracked }
        if (tracked.isEmpty()) return Result.success()
        return try {
            XiaomiSync.sync(ctx)
            val items = Store.items(ctx)
            for (item in items) {
                if (!item.tracked) continue
                val old = tracked.firstOrNull { it.mailNo == item.mailNo } ?: continue
                if (item.latestText != old.latestText || item.latestTime != old.latestTime) {
                    TrackingNotifier.notify(ctx, item)
                }
            }
            Result.success()
        } catch (e: Throwable) {
            Result.retry()
        }
    }
}
