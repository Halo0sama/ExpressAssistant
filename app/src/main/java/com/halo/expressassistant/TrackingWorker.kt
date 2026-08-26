package com.halo.expressassistant

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.halo.expressassistant.data.ExpressItem
import com.halo.expressassistant.data.Store
import com.halo.expressassistant.ui.SyncEngine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * 全渠道后台轮询（规则版）：
 * - 设置里可配置间隔（分钟）；0=关闭（默认不开启）
 * - 开启跟踪时自动默认 15 分钟
 * - 只轮询「有在途且被跟踪包裹」的平台；跟踪件进入完成/异常（或关闭跟踪）后自动停止该平台轮询
 * - 轮询范围仅覆盖在途快递；变化 → 本地通知
 */
class TrackingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val interval = Store.pollIntervalMin(ctx)
        if (interval <= 0) return Result.success()
        val tracked = Store.items(ctx).filter { it.tracked && isTransport(it) }
        if (tracked.isEmpty()) return Result.success()
        val platforms = tracked.map { it.source }.filter { it.isNotBlank() }.toSet()
        return try {
            // 只同步在途跟踪件所属平台；京东/淘宝/拼多多 用 WebView 抓取（无窗口环境容错），小米走 API
            val skip = Store.CHANNELS.filter { it !in platforms }.toSet()
            SyncEngine.sync(ctx, skip)
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

    /** 在途判定（与首页一致）：完成/异常之外的都算在途轮询范围 */
    private fun isTransport(item: ExpressItem): Boolean {
        when (item.partitionOverride) {
            "delivering", "shipped", "notshipped" -> return true
            "done", "abnormal" -> return false
        }
        return when {
            item.state == 3 -> false
            item.state == 4 -> false
            item.stateNum in setOf(106, 107, 108, 109, 110, 111) -> false
            else -> true
        }
    }
}
