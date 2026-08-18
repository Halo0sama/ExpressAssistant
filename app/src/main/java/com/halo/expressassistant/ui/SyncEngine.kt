package com.halo.expressassistant.ui

import android.app.Activity
import android.util.Log
import com.halo.expressassistant.api.EtaParser
import com.halo.expressassistant.api.TbOrders
import com.halo.expressassistant.api.XiaomiSync
import com.halo.expressassistant.data.ExpressItem
import com.halo.expressassistant.data.JdGoods
import com.halo.expressassistant.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 三源融合同步：小米（优先级最高）/ 京东 / 淘宝（菜鸟）。
 * 各渠道按登录态独立拉取，失败互不影响；按 mailNo 去重合并，
 * 小米同件覆盖京东/淘宝；未登录渠道的旧数据在本次同步中移除。
 */
object SyncEngine {

    private const val TAG = "SyncEngine"

    data class ChannelStatus(val channel: String, val count: Int, val error: String?)

    data class Report(val statuses: List<ChannelStatus>, val total: Int)

    private val mutex = kotlinx.coroutines.sync.Mutex()

    suspend fun sync(act: Activity, skip: Set<String> = emptySet()): Report =
        mutex.withLock {
            syncInternal(act, skip)
        }

    private suspend fun syncInternal(act: Activity, skip: Set<String>): Report = withContext(Dispatchers.IO) {
        val xiaomiOn = Store.xiaomiToken(act).isNotEmpty() && "xiaomi" !in skip
        val jdOn = Store.jdCookies(act).isNotBlank() && "jd" !in skip
        val tbOn = Store.tbCookies(act).isNotBlank() && "taobao" !in skip
        Log.i(TAG, "channels: xiaomi=$xiaomiOn jd=$jdOn tb=$tbOn")

        val oldItems = Store.items(act).associateBy { it.mailNo }
        val statuses = ArrayList<ChannelStatus>()

        // ── 小米（顺序执行：它内部读旧列表做字段合并，先跑保优先级）──
        var xiaomiItems: List<ExpressItem>? = null
        if (xiaomiOn) {
            try {
                XiaomiSync.sync(act)
                xiaomiItems = Store.items(act).filter { it.source == "xiaomi" }
                statuses.add(ChannelStatus("xiaomi", xiaomiItems.size, null))
            } catch (e: Throwable) {
                Log.w(TAG, "xiaomi sync fail: $e")
                statuses.add(ChannelStatus("xiaomi", 0, e.message ?: "小米同步失败"))
                xiaomiItems = null
            }
        }

        // ── 京东（隐藏 WebView）与淘宝（HTTP）并行 ──
        val jdDeferred = if (jdOn) async {
            try {
                val (items, goodsMap, err) = fetchJd(act)
                if (items == null) {
                    statuses.add(ChannelStatus("jd", 0, err))
                    null
                } else {
                    // 预缓存订单商品（保留已优化的 shortName）
                    if (goodsMap.isNotEmpty()) {
                        val merged = Store.jdGoods(act).toMutableMap()
                        goodsMap.forEach { (k, v) ->
                            val old = merged[k]
                            merged[k] = if (old != null && old.shortName.isNotBlank()) {
                                v.copy(shortName = old.shortName)
                            } else {
                                v
                            }
                        }
                        Store.saveJdGoods(act, merged)
                    }
                    statuses.add(ChannelStatus("jd", items.size, null))
                    items
                }
            } catch (e: Throwable) {
                Log.w(TAG, "jd sync fail: $e")
                statuses.add(ChannelStatus("jd", 0, e.message))
                null
            }
        } else null

        val tbDeferred = if (tbOn) async { fetchTb(act) } else null

        val jdItems = jdDeferred?.await()
        val tbResult = tbDeferred?.await()
        if (tbOn) {
            statuses.add(ChannelStatus("taobao", tbResult?.first?.size ?: 0, tbResult?.second))
        }

        // ── 合并（小米优先）──
        val merged = LinkedHashMap<String, ExpressItem>()
        xiaomiItems?.forEach { merged[it.mailNo] = it }
        jdItems?.forEach { if (!merged.containsKey(it.mailNo)) merged[it.mailNo] = it }
        tbResult?.first?.forEach { if (!merged.containsKey(it.mailNo)) merged[it.mailNo] = it }

        val finalList = merged.values.map { item ->
            val old = oldItems[item.mailNo] ?: return@map item
            item.copy(
                tracked = old.tracked,
                notifiedText = old.notifiedText,
                notifiedTime = old.notifiedTime,
                stateOverride = old.stateOverride,
                partitionOverride = old.partitionOverride,
                aiProgress = old.aiProgress,
                aiEta = old.aiEta,
                aiProgressAt = old.aiProgressAt,
                pickupCode = item.pickupCode.ifBlank { old.pickupCode }
            )
        }
        // 聚合取件码：从最新轨迹文本解析
        val withPickup = finalList.map { item ->
            if (item.pickupCode.isNotBlank()) item
            else {
                val code = GoodsPresentation.pickupCodeFrom(item.latestText)
                if (code.isNullOrBlank()) item else item.copy(pickupCode = code)
            }
        }
        Store.saveItems(act, withPickup)
        Log.i(TAG, "merged total=${withPickup.size} statuses=$statuses")
        Report(statuses, withPickup.size)
    }

    /** 后台任务：商品短名批量优化（不阻塞同步），完成后建议 reload */
    suspend fun optimizeShortNames(act: Activity) {
        val v2 = Store.shortOptV2(act)
        val goodsMap = Store.jdGoods(act)
        val need = goodsMap.filter { (_, g) ->
            g.name.length > 12 && (g.shortName.isBlank() || !v2)
        }
        if (need.isEmpty()) return
        val shorts = GoodsPresentation.batchShorten(act, need.mapValues { it.value.name })
        val merged = Store.jdGoods(act).toMutableMap()
        shorts.forEach { (k, v) ->
            merged[k] = (merged[k] ?: JdGoods(name = need[k]?.name ?: "")).copy(shortName = v)
        }
        if (shorts.isNotEmpty()) {
            Store.saveJdGoods(act, merged)
            Store.setShortOptV2(act, true)
            Log.i(TAG, "shortName optimized ${shorts.size}")
        }
    }

    private suspend fun fetchJd(act: Activity): Triple<List<ExpressItem>?, Map<String, JdGoods>, String?> =
        suspendCancellableCoroutine { cont ->
            JdListFetcher.fetch(act) { items, goods, err ->
                if (cont.isActive) cont.resume(Triple(items, goods, err))
            }
        }

    private suspend fun fetchTb(act: Activity): Pair<List<ExpressItem>?, String?> {
        return try {
            // 1) 订单列表（最多翻 3 页，按订单号去重）
            val orders = LinkedHashMap<String, TbOrders.TbOrder>()
            for (page in 1..3) {
                val batch = TbOrders.fetchBoughtList(act, page)
                batch.forEach { orders.putIfAbsent(it.orderId, it) }
                if (batch.size < 10) break
            }
            // 2) 过滤有物流的订单（跳过交易关闭/待付款）
            val withParcels = orders.values.filter { o ->
                !o.tradeStatus.contains("CLOSED") &&
                        o.statusText != "交易关闭" &&
                        o.statusText != "交易成功（未发货）"
            }
            Log.i(TAG, "tb orders=${orders.size} withParcels=${withParcels.size}")
            // 3) 并发拉 SSR 详情
            val parcels = coroutineScope {
                withParcels.chunked(4).flatMap { chunk ->
                    chunk.map { o -> async { TbOrders.fetchSsrDetail(act, o.orderId) } }.awaitAll().filterNotNull()
                }
            }
            // 4) 商品预缓存：订单号 -> mailNo 重新映射
            val goodsByOrder = withParcels.associateBy({ it.orderId }, { o ->
                JdGoods(
                    name = o.goodsName,
                    imageUrl = if (o.goodsPic.startsWith("//")) "https:${o.goodsPic}" else o.goodsPic,
                    count = ""
                )
            })
            val goodsByMail = HashMap<String, JdGoods>()
            parcels.forEach { p ->
                goodsByOrder[p.orderId]?.let { g ->
                    if (g.name.isNotBlank()) goodsByMail[p.mailNo] = g
                }
            }
            if (goodsByMail.isNotEmpty()) {
                val merged = Store.jdGoods(act).toMutableMap()
                goodsByMail.forEach { (k, v) ->
                    val old = merged[k]
                    merged[k] = if (old != null && old.shortName.isNotBlank()) {
                        v.copy(shortName = old.shortName)
                    } else {
                        v
                    }
                }
                Store.saveJdGoods(act, merged)
            }
            val items = parcels.mapNotNull { p -> toItem(p) }
            items to null
        } catch (e: Throwable) {
            Log.w(TAG, "tb sync fail: $e")
            null to (e.message ?: "淘宝同步失败")
        }
    }

    private fun toItem(p: TbOrders.TbParcel): ExpressItem? {
        if (p.mailNo.isBlank()) return null
        // 订单列表状态（交易成功 = 已签收等）优先，其次物流节点标题
        val label = p.stateLabel
        val state = when {
            label.contains("签收") || label.contains("成功") -> 3
            label.contains("派送") -> 5
            label.contains("发货") && !label.contains("待发货") -> 0
            label.contains("揽收") -> 1
            label.contains("下单") || label.contains("待发货") -> 1
            else -> 0
        }
        val stateNum = when (state) {
            3 -> 107
            5 -> 105
            1 -> 103
            else -> 105
        }
        val eta = EtaParser.extract(p.latestText, p.stateLabel)
        return ExpressItem(
            id = p.mailNo,
            companyCode = "",
            companyName = p.cpName.ifBlank { "淘宝快递" },
            mailNo = p.mailNo,
            latestText = p.latestText,
            latestTime = p.latestTime,
            state = state,
            stateName = p.stateLabel,
            provider = "CaiNiao",
            stateNum = stateNum,
            eta = eta,
            queryChannel = p.orderId,
            source = "taobao"
        )
    }
}
