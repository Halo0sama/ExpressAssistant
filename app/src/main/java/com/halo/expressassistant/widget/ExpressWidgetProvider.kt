package com.halo.expressassistant.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import com.halo.expressassistant.R
import com.halo.expressassistant.api.XiaomiSync
import com.halo.expressassistant.data.ExpressItem
import com.halo.expressassistant.data.Store
import com.halo.expressassistant.data.sectionKeyOf
import com.halo.expressassistant.ui.DetailActivity
import com.halo.expressassistant.ui.MainActivity
import com.halo.expressassistant.ui.Themes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

class ExpressWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        renderAll(context, appWidgetManager, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle?
    ) {
        renderAll(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    Toast.makeText(context, "正在同步快递…", Toast.LENGTH_SHORT).show()
                    XiaomiSync.sync(context)
                    Toast.makeText(context, "同步完成", Toast.LENGTH_SHORT).show()
                } catch (e: Throwable) {
                    Toast.makeText(context, e.message ?: "同步失败", Toast.LENGTH_LONG).show()
                } finally {
                    updateAll(context)
                }
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.halo.expressassistant.WIDGET_REFRESH"

        // 物理布局的行优先格子编号：每行 8 格
        private val ORDER = IntArray(32) { it + 1 }

        private fun idOf(context: Context, name: String): Int =
            context.resources.getIdentifier(name, "id", context.packageName)

        fun updateAll(context: Context) {
            try {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(
                    ComponentName(context, ExpressWidgetProvider::class.java)
                )
                if (ids.isNotEmpty()) renderAll(context, manager, ids)
            } catch (_: Throwable) {
                // 小组件未安装或桌面未就绪时静默跳过
            }
        }

        private fun renderAll(
            context: Context,
            manager: AppWidgetManager,
            ids: IntArray
        ) {
            for (id in ids) renderOne(context, manager, id)
        }

        private fun renderOne(context: Context, manager: AppWidgetManager, id: Int) {
            val options = manager.getAppWidgetOptions(id)
            val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            Log.d("WidgetDebug", "options id=$id ${width}x${height}")
            val large = width >= 300 && height >= 250
            val medium = width >= 300
            val rows = if (large) 4 else 3
            val cols = 8

            val lark = Store.theme(context) == Themes.LARK || Store.colorScheme(context) == "warm"
            val views = RemoteViews(
                context.packageName,
                when {
                    large || medium ->
                        if (lark) R.layout.widget_express_list_lark else R.layout.widget_express_list
                    else ->
                        if (lark) R.layout.widget_express_small_lark else R.layout.widget_express_small
                }
            )
            val allItems = Store.items(context)
            val items = allItems.filter {
                when (sectionKeyOf(it)) {
                    "delivering" -> Store.widgetShowDelivering(context)
                    "shipped" -> Store.widgetShowShipped(context)
                    "notshipped" -> Store.widgetShowNotShipped(context)
                    else -> false
                }
            }
            if (large || medium) {
                val sorted = items.sortedBy { orderOf(it) }
                val grid = gridFor(items.size, if (large) 4 else 3)
                val gridCols = grid.first
                val gridRows = grid.second
                // 保守估算组件高度：宁可底部留白，保证每行等分、内容完整
                val density = context.resources.displayMetrics.density
                val widgetHeightPx = (height * density).toInt()
                val headerPx = (28 * density).toInt()
                val paddingPx = (10 * density).toInt()
                val dividerPx = (1 * density).toInt()
                val rowHeightPx = (widgetHeightPx - headerPx - paddingPx -
                    dividerPx * (gridRows - 1)) / gridRows
                val cap = gridCols * gridRows
                for (row in 0 until 4) {
                    for (c in 1 until 8) {
                        views.setViewVisibility(
                            idOf(context, "widget_v_${row}_$c"),
                            if (row < gridRows && c < gridCols &&
                                row * gridCols + c < cap
                            ) View.VISIBLE else View.GONE
                        )
                    }
                }
                for (d in 1..3) {
                    views.setViewVisibility(
                        idOf(context, "widget_hdiv_$d"),
                        if (d < gridRows) View.VISIBLE else View.GONE
                    )
                }
                for (n in 1..32) {
                    views.setViewVisibility(idOf(context, "widget_cell_$n"), View.GONE)
                }
                for (i in 0 until cap) {
                    val row = i / gridCols
                    val col = i % gridCols
                    val cellN = row * 8 + col + 1
                    val cellId = idOf(context, "widget_cell_$cellN")
                    views.setViewVisibility(cellId, View.VISIBLE)
                    if (rowHeightPx > 0) {
                        views.setInt(cellId, "setMinimumHeight", rowHeightPx)
                    }
                    val item = sorted.getOrNull(i)
                    if (item == null) {
                        bindPlaceholder(context, views, cellN, gridRows, gridCols)
                    } else {
                        bindCell(context, views, cellN, item, id, i, gridRows, gridCols)
                    }
                }
                views.setOnClickPendingIntent(R.id.widget_refresh, refreshPending(context))
                views.setOnClickPendingIntent(R.id.widget_open, openAppPending(context))
            } else {
                val counts = countsOf(items)
                views.setTextViewText(R.id.widget_small_summary, smallSummary(items, counts))
                views.setTextViewText(R.id.widget_small_detail, smallDetail(items))
                views.setOnClickPendingIntent(R.id.widget_root, openAppPending(context))
            }

            manager.updateAppWidget(id, views)
        }

        private fun bindCell(
            context: Context,
            views: RemoteViews,
            cellN: Int,
            item: ExpressItem?,
            widgetId: Int,
            index: Int,
            gridRows: Int,
            gridCols: Int
        ) {
            if (item == null) return
            val avatarId = idOf(context, "widget_cell_${cellN}_avatar")
            val stateId = idOf(context, "widget_cell_${cellN}_state")
            val timeId = idOf(context, "widget_cell_${cellN}_time")
            val big = gridRows == 1 && gridCols <= 4
            val medium = gridRows == 2 && gridCols <= 5
            val tiny = gridRows >= 4
            val small = !big && !medium && !tiny
            views.setTextViewTextSize(
                avatarId, TypedValue.COMPLEX_UNIT_SP,
                if (big) 18f else if (medium) 14f else if (tiny) 8f else 10f
            )
            views.setTextViewTextSize(
                stateId, TypedValue.COMPLEX_UNIT_SP,
                if (big) 13f else if (medium) 11f else if (tiny) 7f else 8f
            )
            views.setTextViewTextSize(
                timeId, TypedValue.COMPLEX_UNIT_SP,
                if (big) 11f else if (medium) 9f else if (tiny) 6f else 7f
            )
            if (small || tiny) {
                views.setInt(avatarId, "setMinimumHeight", 0)
                views.setInt(avatarId, "setMinimumWidth", 0)
                val pad2 = (2 * context.resources.displayMetrics.density).toInt()
                views.setViewPadding(avatarId, pad2, pad2, pad2, pad2)
            }
            views.setViewVisibility(stateId, View.VISIBLE)
            views.setViewVisibility(timeId, View.VISIBLE)
            views.setTextViewText(stateId, item.stateLabel())
            views.setTextViewText(avatarId, item.companyName.take(2).ifBlank { "快" })
            views.setTextViewText(timeId, timeOf(item))

            val kind = sectionKeyOf(item)
            views.setInt(avatarId, "setBackgroundResource", avatarBg(kind))
            views.setTextColor(stateId, context.getColor(chipText(kind)))

            val pi = PendingIntent.getActivity(
                context,
                widgetId * 100 + index + 1,
                Intent(context, DetailActivity::class.java)
                    .putExtra("item", Store.json.encodeToString(item)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(idOf(context, "widget_cell_$cellN"), pi)
        }

        private fun bindPlaceholder(
            context: Context,
            views: RemoteViews,
            cellN: Int,
            gridRows: Int,
            gridCols: Int
        ) {
            val avatarId = idOf(context, "widget_cell_${cellN}_avatar")
            val stateId = idOf(context, "widget_cell_${cellN}_state")
            val timeId = idOf(context, "widget_cell_${cellN}_time")
            val big = gridRows == 1 && gridCols <= 4
            val medium = gridRows == 2 && gridCols <= 5
            val tiny = gridRows >= 4
            val small = !big && !medium && !tiny
            views.setTextViewTextSize(
                avatarId, TypedValue.COMPLEX_UNIT_SP,
                if (big) 18f else if (medium) 14f else if (tiny) 8f else 10f
            )
            if (small || tiny) {
                views.setInt(avatarId, "setMinimumHeight", 0)
                views.setInt(avatarId, "setMinimumWidth", 0)
                val pad2 = (2 * context.resources.displayMetrics.density).toInt()
                views.setViewPadding(avatarId, pad2, pad2, pad2, pad2)
            }
            views.setViewVisibility(stateId, View.GONE)
            views.setViewVisibility(timeId, View.GONE)
            views.setTextViewText(avatarId, "+")
            views.setInt(avatarId, "setBackgroundResource", R.drawable.widget_avatar_grey)
            views.setTextColor(avatarId, context.getColor(R.color.widget_avatar_text))
            views.setOnClickPendingIntent(idOf(context, "widget_cell_$cellN"), addPending(context))
        }

        // 均衡网格：容量最小、行少优先；单行最多 4 格，避免过挤
        private fun gridFor(n: Int, maxRows: Int): Pair<Int, Int> {
            if (n <= 0) return 4 to 1
            var best = 4 to 1
            var bestScore = Long.MAX_VALUE
            for (rows in 1..maxRows) {
                val cols = (n + rows - 1) / rows
                if (cols > 8) continue
                if (rows == 1 && cols > 4) continue
                val cap = cols * rows
                val score = cap * 1000L + rows
                if (score < bestScore) {
                    bestScore = score
                    best = cols to rows
                }
            }
            return best
        }

        private fun addPending(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                3,
                Intent(context, MainActivity::class.java).putExtra("add", true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        private fun timeOf(item: ExpressItem): String {
            val eta = item.eta.ifBlank { item.aiEta }
            if (eta.isNotBlank()) {
                return Regex("(\\d{1,2}月\\d{1,2}日)").find(eta)?.groupValues?.get(1)
                    ?: eta.take(7)
            }
            val t = item.latestTime.trim()
            if (t.isBlank()) return ""
            return if (t.length >= 10 && t[4] == '-') t.substring(5, 10) else t.take(7)
        }

        private fun countsOf(items: List<ExpressItem>): Counts {
            var transit = 0
            var delivering = 0
            var abnormal = 0
            var done = 0
            for (item in items) {
                when (sectionKeyOf(item)) {
                    "delivering" -> { delivering++; transit++ }
                    "shipped", "notshipped" -> transit++
                    "abnormal" -> abnormal++
                    "done" -> done++
                }
            }
            return Counts(transit, delivering, abnormal, done)
        }

        private fun smallSummary(items: List<ExpressItem>, counts: Counts): String {
            if (items.isEmpty()) return "暂无快递"
            val sb = StringBuilder()
            if (counts.transit > 0) {
                sb.append("${counts.transit} 件在途")
                if (counts.delivering > 0) sb.append(" · ${counts.delivering} 派送中")
            } else {
                sb.append("暂无在途")
            }
            if (counts.abnormal > 0) sb.append(" · ${counts.abnormal} 异常")
            return sb.toString()
        }

        private fun smallDetail(items: List<ExpressItem>): String {
            val inTransit = items.sortedBy { orderOf(it) }.firstOrNull {
                sectionKeyOf(it) in setOf("delivering", "shipped", "notshipped")
            }
            if (inTransit == null) return "点击打开快递助手"
            val eta = inTransit.eta.ifBlank { inTransit.aiEta }
            return if (eta.isNotBlank()) {
                "${inTransit.companyName} · 预计 $eta"
            } else {
                "${inTransit.companyName} · ${inTransit.latestText.ifBlank { inTransit.stateLabel() }}"
            }
        }

        private fun orderOf(item: ExpressItem): Int = when (sectionKeyOf(item)) {
            "delivering" -> 0
            "shipped" -> 1
            "notshipped" -> 2
            "abnormal" -> 3
            else -> 4
        }

        private fun avatarBg(kind: String): Int = when (kind) {
            "delivering" -> R.drawable.widget_avatar_orange
            "done" -> R.drawable.widget_avatar_green
            "abnormal" -> R.drawable.widget_avatar_red
            "notshipped" -> R.drawable.widget_avatar_grey
            else -> R.drawable.widget_avatar_blue
        }

        private fun chipText(kind: String): Int = when (kind) {
            "delivering" -> R.color.widget_chip_orange_text
            "done" -> R.color.widget_chip_green_text
            "abnormal" -> R.color.widget_chip_red_text
            "notshipped" -> R.color.widget_chip_grey_text
            else -> R.color.widget_chip_blue_text
        }

        private fun openAppPending(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        private fun refreshPending(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                2,
                Intent(context, ExpressWidgetProvider::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        private data class Counts(
            val transit: Int,
            val delivering: Int,
            val abnormal: Int,
            val done: Int
        )
    }
}
