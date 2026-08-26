package com.halo.expressassistant.ui

import android.app.Activity
import android.content.Context
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
 * 四源融合同步：小米（优先级最高）/ 京东 / 淘宝（菜鸟）/ 拼多多。
 * 各渠道按登录态独立拉取，失败互不影响；按 mailNo 去重合并，
 * 小米同件覆盖京东/淘宝/拼多多；未登录渠道的旧数据在本次同步中移除。
 */
object SyncEngine {

    private const val TAG = "SyncEngine"

    data class ChannelStatus(val channel: String, val count: Int, val error: String?)

    data class Report(val statuses: List<ChannelStatus>, val total: Int)

    private val mutex = kotlinx.coroutines.sync.Mutex()

    suspend fun sync(act: Context, skip: Set<String> = emptySet(), onPddProgress: ((List<ExpressItem>, Map<String, JdGoods>) -> Unit)? = null): Report =
        mutex.withLock {
            syncInternal(act, skip, onPddProgress)
        }

    private suspend fun syncInternal(act: Context, skip: Set<String>, onPddProgress: ((List<ExpressItem>, Map<String, JdGoods>) -> Unit)? = null): Report = withContext(Dispatchers.IO) {
        val xiaomiOn = Store.accounts(act, Store.CH_XIAOMI).any { it.enabled } && "xiaomi" !in skip
        val jdOn = Store.accounts(act, Store.CH_JD).any { it.enabled } && "jd" !in skip
        val tbOn = Store.accounts(act, Store.CH_TAOBAO).any { it.enabled } && "taobao" !in skip
        val pddOn = Store.accounts(act, Store.CH_PDD).any { it.enabled } && "pdd" !in skip
        Log.i(TAG, "channels: xiaomi=$xiaomiOn jd=$jdOn tb=$tbOn pdd=$pddOn")

        val oldItems = Store.items(act).associateBy { it.mailNo }
        val statuses = ArrayList<ChannelStatus>()

        // ── 小米：逐账号顺序同步（内部合并到 items，先跑保优先级）──
        var xiaomiItems: List<ExpressItem>? = null
        if (xiaomiOn) {
            var err: String? = null
            for (account in Store.accounts(act, Store.CH_XIAOMI).filter { it.enabled }) {
                try {
                    XiaomiSync.syncAccount(act, account)
                } catch (e: Throwable) {
                    Log.w(TAG, "xiaomi sync fail: $e")
                    err = e.message ?: "小米同步失败"
                }
            }
            xiaomiItems = Store.items(act).filter { it.source == "xiaomi" }
            statuses.add(ChannelStatus("xiaomi", xiaomiItems.size, err))
        }

        // ── 京东（WebView）/ 淘宝（HTTP）/ 拼多多（WebView）三路并行；各自内部逐账号 ──
        val jdDeferred = if (jdOn) async { fetchJdAll(act) } else null
        val tbDeferred = if (tbOn) async { fetchTbAll(act) } else null
        val pddDeferred = if (pddOn) async { fetchPddAll(act, onPddProgress) } else null

        val jdResult = jdDeferred?.await()
        val tbResult = tbDeferred?.await()
        val pddResult = pddDeferred?.await()
        if (jdOn) statuses.add(ChannelStatus("jd", jdResult?.first?.size ?: 0, jdResult?.third))
        if (tbOn) {
            statuses.add(ChannelStatus("taobao", tbResult?.first?.size ?: 0, tbResult?.second))
        }
        if (pddOn) statuses.add(ChannelStatus("pdd", pddResult?.first?.size ?: 0, pddResult?.third))
        // 预缓存京东/拼多多订单商品（保留已优化 shortName）
        jdResult?.second?.takeIf { it.isNotEmpty() }?.let { saveGoods(act, it) }
        pddResult?.second?.takeIf { it.isNotEmpty() }?.let { saveGoods(act, it) }

        // ── 合并（小米 > 京东 > 淘宝 > 拼多多；同平台多账号时先绑定账号优先）──
        // 防误清：通道「成功但 0 件」或抓取失败时保留该通道旧数据（WebView 抓取偶发空页会清空列表）；
        // 新账号首次同步（无旧数据）才允许写入空结果。
        fun preserveOld(channel: String, fresh: List<ExpressItem>?): List<ExpressItem>? {
            if (fresh != null && fresh.isNotEmpty()) return fresh
            val old = oldItems.values.filter { it.source == channel }
            return if (old.isNotEmpty()) old else fresh
        }
        val oldPddCount = oldItems.values.count { it.source == "pdd" }
        if ((pddResult?.first ?: emptyList()).isEmpty() && pddOn && oldPddCount > 0) {
            Log.w(TAG, "pdd fetch empty/missing -> keep old $oldPddCount")
        }
        val merged = LinkedHashMap<String, ExpressItem>()
        (xiaomiItems ?: oldItems.values.filter { it.source == "xiaomi" })
            .forEach { merged[it.mailNo] = it }
        preserveOld("jd", jdResult?.first)?.forEach { if (!merged.containsKey(it.mailNo)) merged[it.mailNo] = it }
        preserveOld("taobao", tbResult?.first)?.forEach { if (!merged.containsKey(it.mailNo)) merged[it.mailNo] = it }
        // 拼多多「增量」合并：本次抓到的（新）与历史上抓过的（旧）取并集——总件数只增不减、实时上涨
        val pddUnion = (preserveOld("pdd", pddResult?.first).orEmpty() + oldItems.values.filter { it.source == "pdd" })
            .distinctBy { it.mailNo }
        pddUnion.forEach { if (!merged.containsKey(it.mailNo)) merged[it.mailNo] = it }

        val finalList = merged.values.map { item ->
            val old = oldItems[item.mailNo] ?: return@map item
            // 拼多多：列表最新文案只是状态提示（交易成功/待评价…），
            // 已被物流页回写的真实轨迹（latestText/latestTime）要在同步合并时保留，防止覆盖丢失
            val pddPromptOnly = item.source == "pdd" &&
                item.latestText in setOf("交易成功", "待评价", "待发货", "待收货", "待成团", "待付款", "已取消", "退款成功", "交易关闭")
            val latestText = if (pddPromptOnly) old.latestText.ifBlank { item.latestText } else item.latestText
            val latestTime = if (pddPromptOnly) old.latestTime.ifBlank { item.latestTime } else item.latestTime
            item.copy(
                tracked = old.tracked,
                notifiedText = old.notifiedText,
                notifiedTime = old.notifiedTime,
                stateOverride = old.stateOverride,
                partitionOverride = old.partitionOverride,
                aiProgress = old.aiProgress,
                aiEta = old.aiEta,
                aiProgressAt = old.aiProgressAt,
                latestText = latestText,
                latestTime = latestTime,
                addressId = old.addressId,
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
        // 同步合并后立即回填「已抓取」的 PDD 轨迹到卡片（列表接口只有状态提示，
        // 避免每次同步把物流页回写的 latestText 覆盖回「交易成功」）
        val pddPrompts = setOf("交易成功", "待评价", "待发货", "待收货", "待成团", "待付款", "已取消", "退款成功", "交易关闭")
        val withTrace = withPickup.map { item ->
            if (item.source != "pdd" || item.latestText !in pddPrompts) return@map item
            val pts = Store.pddTraces(act, item.mailNo) ?: return@map item
            if (pts.isEmpty()) return@map item
            val last = pts.last()
            item.copy(
                latestText = last.context.take(220).ifBlank { item.latestText },
                latestTime = last.time.ifBlank { item.latestTime },
                pickupCode = item.pickupCode.ifBlank { GoodsPresentation.pickupCodeFrom(last.context) ?: item.pickupCode }
            )
        }
        Store.saveItems(act, withTrace)
        Log.i(TAG, "merged total=${withTrace.size} statuses=$statuses")
        Report(statuses, withTrace.size)
    }

    /** 后台任务：商品短名批量优化（不阻塞同步），完成后建议 reload */
    suspend fun optimizeShortNames(act: Context) {
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

    /** 京东：逐账号抓取（一个失败不影响其它账号） */
    private suspend fun fetchJdAll(act: Context): Triple<List<ExpressItem>?, Map<String, JdGoods>, String?> {
        val accounts = Store.accounts(act, Store.CH_JD).filter { it.enabled }
        val allItems = ArrayList<ExpressItem>()
        val goodsAcc = HashMap<String, JdGoods>()
        var err: String? = null
        for (account in accounts) {
            try {
                val (items, goodsMap, e) = fetchJd(act, account)
                if (items == null) err = e ?: err
                else {
                    allItems.addAll(items)
                    goodsAcc.putAll(goodsMap)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "jd account ${account.label} fail: $e")
                err = e.message ?: err
            }
        }
        return Triple(if (allItems.isEmpty() && err != null) null else allItems, goodsAcc, err)
    }

    private suspend fun fetchJd(act: Context, account: com.halo.expressassistant.data.BoundAccount?): Triple<List<ExpressItem>?, Map<String, JdGoods>, String?> =
        suspendCancellableCoroutine { cont ->
            JdListFetcher.fetchWith(act, account) { items, goods, err ->
                if (cont.isActive) cont.resume(Triple(items, goods, err))
            }
        }

    /** 淘宝：逐账号抓取（订单列表 → 过滤 → SSR 详情 → 商品预缓存） */
    private suspend fun fetchTbAll(act: Context): Pair<List<ExpressItem>?, String?> {
        val accounts = Store.accounts(act, Store.CH_TAOBAO).filter { it.enabled }
        val allItems = ArrayList<ExpressItem>()
        var err: String? = null
        try {
            for (account in accounts) {
                val cookies = Store.cookieOf(account.payload)
                if (cookies.isBlank()) continue
                // 1) 订单列表（最多翻 3 页，按订单号去重）
                val orders = LinkedHashMap<String, TbOrders.TbOrder>()
                for (page in 1..3) {
                    val batch = TbOrders.fetchBoughtListWith(cookies, page)
                    batch.forEach { orders.putIfAbsent(it.orderId, it) }
                    if (batch.size < 10) break
                }
                // 2) 过滤有物流的订单（跳过交易关闭/待付款）
                val withParcels = orders.values.filter { o ->
                    !o.tradeStatus.contains("CLOSED") &&
                            o.statusText != "交易关闭" &&
                            o.statusText != "交易成功（未发货）"
                }
                Log.i(TAG, "tb(${account.label}) orders=${orders.size} withParcels=${withParcels.size}")
                // 3) 并发拉 SSR 详情
                val parcels = coroutineScope {
                    withParcels.chunked(4).flatMap { chunk ->
                        chunk.map { o -> async { TbOrders.fetchSsrDetailWith(cookies, o.orderId) } }.awaitAll().filterNotNull()
                    }
                }
                // 4) 商品预缓存：订单号 -> mailNo 重新映射（保留旧 shortName）
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
                allItems.addAll(parcels.mapNotNull { p -> toItem(p, account) })
            }
            return allItems to err
        } catch (e: Throwable) {
            Log.w(TAG, "tb sync fail: $e")
            return allItems to (e.message ?: "淘宝同步失败")
        }
    }

    /** 拼多多：逐账号**流式**抓取——每增量一批（订单+商品图）先上报（实时上涨），最终返回全量 */
    private suspend fun fetchPddAll(act: Context, onPddProgress: ((List<ExpressItem>, Map<String, JdGoods>) -> Unit)? = null): Triple<List<ExpressItem>?, Map<String, JdGoods>, String?> {
        val accounts = Store.accounts(act, Store.CH_PDD).filter { it.enabled }
        val allItems = LinkedHashMap<String, ExpressItem>()
        val goodsAcc = HashMap<String, JdGoods>()
        var err: String? = null
        for (account in accounts) {
            try {
                val (items, goodsMap, e) = fetchPddStream(act, account) { batch, goods ->
                    if (batch.isNotEmpty()) {
                        goodsAcc.putAll(goods)
                        val fresh = batch.filter { allItems.put(it.mailNo, it) == null }
                        if (fresh.isNotEmpty()) onPddProgress?.invoke(fresh, goods)
                    }
                }
                if (items == null) err = e ?: err
                else allItems.putAll(items.associateBy { it.mailNo })
            } catch (e: Throwable) {
                Log.w(TAG, "pdd account ${account.label} fail: $e")
                err = e.message ?: err
            }
        }
        return Triple(if (allItems.isEmpty() && err != null) null else allItems.values.toList(), goodsAcc, err)
    }

    private suspend fun fetchPdd(act: Context, account: com.halo.expressassistant.data.BoundAccount?): Triple<List<ExpressItem>?, Map<String, JdGoods>, String?> =
        suspendCancellableCoroutine { cont ->
            PddListFetcher.fetchWith(act, account) { items, goods, err ->
                if (cont.isActive) cont.resume(Triple(items, goods, err))
            }
        }

    private suspend fun fetchPddStream(
        act: Context,
        account: com.halo.expressassistant.data.BoundAccount?,
        onBatch: (List<ExpressItem>, Map<String, JdGoods>) -> Unit
    ): Triple<List<ExpressItem>?, Map<String, JdGoods>, String?> =
        suspendCancellableCoroutine { cont ->
            PddListFetcher.fetchStream(act, account, onBatch) { items ->
                if (cont.isActive) cont.resume(Triple(items, emptyMap(), if (items == null) "流式抓取失败" else null))
            }
        }

    /** 合并商品预缓存（保留旧 shortName） */
    private fun saveGoods(act: Context, goodsMap: Map<String, JdGoods>) {
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

    private fun toItem(p: TbOrders.TbParcel, account: com.halo.expressassistant.data.BoundAccount?): ExpressItem? {
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
            source = "taobao",
            accountId = account?.id ?: "",
            accountLabel = account?.label ?: ""
        )
    }
}
