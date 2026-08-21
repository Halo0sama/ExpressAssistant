package com.halo.expressassistant.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import com.halo.expressassistant.App
import com.halo.expressassistant.R
import com.halo.expressassistant.data.ExpressItem
import com.halo.expressassistant.data.Store
import com.halo.expressassistant.data.sectionKeyOf
import com.halo.expressassistant.ui.DetailActivity
import com.halo.expressassistant.ui.MainActivity
import com.halo.expressassistant.ui.SyncEngine
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
                    val act = App.topActivity
                    if (act != null) {
                        // App 在前台：静默三源同步，不打断用户
                        Toast.makeText(context, "正在同步快递…", Toast.LENGTH_SHORT).show()
                        SyncEngine.sync(act)
                        Toast.makeText(context, "同步完成", Toast.LENGTH_SHORT).show()
                        updateAll(context)
                    } else {
                        // App 不在前台：拉起主界面自动同步（完成后自动回桌面）
                        val launch = Intent(context, MainActivity::class.java)
                            .putExtra("sync_now", true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launch)
                    }
                } catch (e: Throwable) {
                    Log.w("WidgetRefresh", "refresh fail", e)
                    Toast.makeText(context, e.message ?: "同步失败", Toast.LENGTH_LONG).show()
                    updateAll(context)
                }
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.halo.expressassistant.WIDGET_REFRESH"

        /** 头部高度：与 widget_express_list*.xml 的头部 LinearLayout 固定 28dp 对齐 */
        private const val HEADER_DP = 28

        /** 根布局纵向内边距：paddingTop 6dp + paddingBottom 4dp */
        private const val PADDING_DP = 10

        /** 行高够宽松（≥此值）用宽松版布局；低于它换紧凑版（字号/内边距更小，三行仍全显示） */
        private const val ROW_DP_ROOMY = 56

        /** 紧凑版一格三行约需 37dp；行高连这个都不到（极矮组件）才藏时间行 */
        private const val ROW_DP_MIN_3LINE = 34

        private fun idOf(context: Context, name: String): Int =
            context.resources.getIdentifier(name, "id", context.packageName)

        /** 组件可用尺寸（dp）。API 31+ 优先 SIZES 最大档，回退 MIN_*，再兜底大卡尺寸。 */
        private fun widgetSize(options: android.os.Bundle): Pair<Int, Int> {
            var width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            var height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (width <= 0 || height <= 0)) {
                val sizes: List<SizeF>? = if (Build.VERSION.SDK_INT >= 33) {
                    options.getParcelableArrayList(
                        AppWidgetManager.OPTION_APPWIDGET_SIZES,
                        SizeF::class.java
                    )
                } else {
                    @Suppress("DEPRECATION")
                    options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                }
                val max = sizes?.maxByOrNull { it.width * it.height }
                if (max != null) {
                    if (width <= 0) width = max.width.toInt()
                    if (height <= 0) height = max.height.toInt()
                }
            }
            if (width <= 0) width = 300
            if (height <= 0) height = 250
            return width to height
        }

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
            try {
                renderOneInner(context, manager, id)
            } catch (e: Throwable) {
                // 单实例渲染失败不吞异常：记录日志便于排查，其他实例不受影响
                Log.e("ExpressWidget", "render widget #$id failed", e)
            }
        }

        private fun renderOneInner(context: Context, manager: AppWidgetManager, id: Int) {
            val options = manager.getAppWidgetOptions(id)
            // 尺寸读取：Android 12+（API 31+）优先用 SIZES 取最大档，回退旧 MIN_*；
            // 新系统若两者皆缺（0），按大卡渲染兜底，保证格子数量不缩水
            val (width, height) = widgetSize(options)
            Log.d("WidgetDebug", "options id=$id ${width}x${height}")
            val large = width >= 300 && height >= 250
            val medium = width >= 300

            val lark = Store.theme(context) == Themes.LARK || Store.colorScheme(context) == "warm"
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
                val cap = gridCols * gridRows
                // 行高预算（dp）：行已用 layout_weight 均分卡片剩余高度，这里算出每行实得多少。
                // 宽松版一格约 53dp、紧凑版约 37dp；3 行时每行只有约 44dp，所以 3 行必须换紧凑版
                // ——否则三行内容放不下，末行会被卡片底边裁掉（实测 24 件时"预计今天送达"被切一半）。
                // 换布局资源是 0 action 代价（不像 setTextViewTextSize 是 32×3 个反射 action），
                // 且 layout id 一变桌面就重新 inflate，天然没有陈旧状态。
                val rowDp = (height - HEADER_DP - PADDING_DP - (gridRows - 1)) / gridRows
                val compact = rowDp < ROW_DP_ROOMY
                // 连紧凑版都塞不下三行的极矮组件（每行 < 34dp）才退让，藏掉时间行保住头像+状态
                val showTime = rowDp >= ROW_DP_MIN_3LINE
                val views = RemoteViews(
                    context.packageName,
                    when {
                        compact && lark -> R.layout.widget_express_list_lark_compact
                        compact -> R.layout.widget_express_list_compact
                        lark -> R.layout.widget_express_list_lark
                        else -> R.layout.widget_express_list
                    }
                )
                Log.d(
                    "WidgetDebug",
                    "RENDER id=$id large=$large all=${allItems.size} items=${items.size} " +
                        "grid=${gridCols}x${gridRows} cap=$cap rowDp=$rowDp " +
                        "compact=$compact showTime=$showTime " +
                        "themes=${Store.theme(context)}/${Store.colorScheme(context)}"
                )
                // 可见性必须"显式发全套"（VISIBLE/GONE 都发）：桌面 updateAppWidget 命中同一 layout 时走
                // reapply（复用旧视图树），只发 VISIBLE 不发 GONE 会残留上一次的格子——件数变少时
                // 桌面上会留下鬼格子（实测 24 件→4 件仍显示 24 格）。
                // 省 action 的办法：整行 GONE 会连带隐藏该行 8 个格子，所以只需处理"可见行"内部。
                for (row in 0 until 4) {
                    views.setViewVisibility(
                        idOf(context, "widget_row_${row + 1}"),
                        if (row < gridRows) View.VISIBLE else View.GONE
                    )
                }
                for (d in 1..3) {
                    views.setViewVisibility(
                        idOf(context, "widget_hdiv_$d"),
                        if (d < gridRows) View.VISIBLE else View.GONE
                    )
                }
                for (row in 0 until gridRows) {
                    for (c in 1 until 8) {
                        views.setViewVisibility(
                            idOf(context, "widget_v_${row}_$c"),
                            if (c < gridCols) View.VISIBLE else View.GONE
                        )
                    }
                    for (col in 0 until 8) {
                        views.setViewVisibility(
                            idOf(context, "widget_cell_${row * 8 + col + 1}"),
                            if (col < gridCols) View.VISIBLE else View.GONE
                        )
                    }
                }
                for (i in 0 until cap) {
                    val row = i / gridCols
                    val col = i % gridCols
                    val cellN = row * 8 + col + 1
                    val item = sorted.getOrNull(i)
                    if (item == null) {
                        bindPlaceholder(context, views, cellN, showTime, sorted.isNotEmpty())
                    } else {
                        bindCell(context, views, cellN, item, id, i, showTime)
                    }
                }
                views.setOnClickPendingIntent(R.id.widget_refresh, refreshPending(context))
                views.setOnClickPendingIntent(R.id.widget_open, openAppPending(context))
                manager.updateAppWidget(id, views)
            } else {
                val views = RemoteViews(
                    context.packageName,
                    if (lark) R.layout.widget_express_small_lark else R.layout.widget_express_small
                )
                val counts = countsOf(items)
                views.setTextViewText(R.id.widget_small_summary, smallSummary(items, counts))
                views.setTextViewText(R.id.widget_small_detail, smallDetail(items))
                views.setOnClickPendingIntent(R.id.widget_root, openAppPending(context))
                manager.updateAppWidget(id, views)
            }
        }

        private fun bindCell(
            context: Context,
            views: RemoteViews,
            cellN: Int,
            item: ExpressItem?,
            widgetId: Int,
            index: Int,
            showTime: Boolean
        ) {
            if (item == null) return
            val avatarId = idOf(context, "widget_cell_${cellN}_avatar")
            val stateId = idOf(context, "widget_cell_${cellN}_state")
            val timeId = idOf(context, "widget_cell_${cellN}_time")
            // 压缩 action：字号用布局默认值，不再动态 setTextSize（flutter 桌面 action 截断）
            // 状态行的 VISIBLE 不能省！同一个格子上一轮可能当过占位格（空列表时占位格把状态行设成
            // GONE），reapply 复用旧视图树不会自动复位，省掉这一条会让"曾当过占位格"的格子永久缺状态文字
            views.setViewVisibility(stateId, View.VISIBLE)
            views.setTextViewText(stateId, item.stateLabel())
            views.setTextViewText(avatarId, item.companyName.take(2).ifBlank { "快" })
            // 时间行按行高预算开关，且 GONE 也要显式发（reapply 会残留上一次的 VISIBLE）
            if (showTime) {
                views.setViewVisibility(timeId, View.VISIBLE)
                views.setTextViewText(timeId, timeOf(item))
            } else {
                views.setViewVisibility(timeId, View.GONE)
            }

            val kind = sectionKeyOf(item)
            views.setInt(avatarId, "setBackgroundResource", avatarBg(kind))
            views.setTextColor(stateId, context.getColor(chipText(kind)))

            // 点击传 mailNo 而非整件 JSON：压缩 RemoteViews 序列化体积，防大数量时触发桌面截断
            val pi = PendingIntent.getActivity(
                context,
                widgetId * 100 + index + 1,
                Intent(context, DetailActivity::class.java)
                    .putExtra("mailNo", item.mailNo),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(idOf(context, "widget_cell_$cellN"), pi)
        }

        private fun bindPlaceholder(
            context: Context,
            views: RemoteViews,
            cellN: Int,
            showTime: Boolean,
            align: Boolean
        ) {
            val avatarId = idOf(context, "widget_cell_${cellN}_avatar")
            val stateId = idOf(context, "widget_cell_${cellN}_state")
            val timeId = idOf(context, "widget_cell_${cellN}_time")
            views.setTextViewText(avatarId, "+")
            views.setInt(avatarId, "setBackgroundResource", R.drawable.widget_avatar_grey)
            views.setTextColor(avatarId, context.getColor(R.color.widget_avatar_text))
            // align：本次网格里还有真实快递格时，占位格要保持相同行结构（状态/时间留空但占位），
            // 否则 "+" 内容更矮、垂直居中后比同行邻格头像低一截（实测差 38px）。
            // 全空列表（一行全是 "+"）没有对齐对象，留空行反而把 "+" 顶上去，此时直接 GONE。
            // GONE/VISIBLE 都显式发：reapply 不会自动复位上一次的状态。
            if (align) {
                views.setViewVisibility(stateId, View.VISIBLE)
                views.setTextViewText(stateId, "")
                if (showTime) {
                    views.setViewVisibility(timeId, View.VISIBLE)
                    views.setTextViewText(timeId, "")
                } else {
                    views.setViewVisibility(timeId, View.GONE)
                }
            } else {
                views.setViewVisibility(stateId, View.GONE)
                views.setViewVisibility(timeId, View.GONE)
            }
            views.setOnClickPendingIntent(idOf(context, "widget_cell_$cellN"), addPending(context))
        }

        // 均衡网格：容量最小、行少优先；单行最多 4 格，避免过挤
        private fun gridFor(n: Int, maxRows: Int): Pair<Int, Int> {
            if (n <= 0) return 4 to 1
            var best: Pair<Int, Int>? = null
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
            // 件数超过最大容量（medium 3×8=24 / large 4×8=32）时没有候选：必须占满最大网格，
            // 否则会退化成初值 4×1——26 件在途只显示 4 件（静默丢件，实测踩过）
            return best ?: (8 to maxRows)
        }

        private fun addPending(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                3,
                Intent(context, MainActivity::class.java).putExtra("add", true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        /**
         * 时间行文案。优先 ETA（取"X月X日"），否则用轨迹时间的 MM-dd。
         * **统一去掉"预计"前缀**：最窄档（8 列列宽仅约 43dp）"预计今天送达"会被横向省略成
         * "预计今…"，而"今天送达"任何档位都放得下；宽松档也一并去掉，保持各件数文案一致。
         */
        private fun timeOf(item: ExpressItem): String {
            val eta = item.eta.ifBlank { item.aiEta }
            if (eta.isNotBlank()) {
                val hit = Regex("(\\d{1,2}月\\d{1,2}日)").find(eta)?.groupValues?.get(1)
                if (hit != null) return hit
                return etaBody(eta).take(7)
            }
            val t = item.latestTime.trim()
            if (t.isBlank()) return ""
            return if (t.length >= 10 && t[4] == '-') t.substring(5, 10) else t.take(7)
        }

        /** 去掉 ETA 文案里的"预计/预计于"前缀（"预计今天送达" → "今天送达"） */
        private fun etaBody(eta: String): String =
            eta.trim().removePrefix("预计于").removePrefix("预计").trim()

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
            val eta = etaBody(inTransit.eta.ifBlank { inTransit.aiEta })
            return if (eta.isNotBlank()) {
                // eta 本身多半自带"预计"，这里去前缀再拼，避免"预计 预计今天送达"
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
