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
            val updated = items.map { item ->
                if (!item.tracked) {
                    item
                } else if (item.notifiedText.isBlank() && item.notifiedTime.isBlank()) {
                    item.copy(notifiedText = item.latestText, notifiedTime = item.latestTime)
                } else if (item.latestTime.isNotEmpty() && item.latestTime > item.notifiedTime) {
                    TrackingNotifier.notify(ctx, item)
                    item.copy(notifiedText = item.latestText, notifiedTime = item.latestTime)
                } else {
                    item
                }
            }
            Store.saveItems(ctx, updated)
            Result.success()
        } catch (e: Throwable) {
            Result.retry()
        }
    }
}
