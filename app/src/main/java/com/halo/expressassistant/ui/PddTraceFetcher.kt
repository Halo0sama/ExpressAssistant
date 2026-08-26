package com.halo.expressassistant.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import com.halo.expressassistant.data.DetailPoint
import com.halo.expressassistant.data.ExpressItem
import com.halo.expressassistant.data.JdGoods
import com.halo.expressassistant.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * 拼多多物流轨迹 + 商品解析：隐藏 WebView 加载拼多多 H5 订单详情页，
 * JS 钩子捕获页面 SDK 自动签名后的物流接口响应（绕开 anti_content），
 * 容错解析出轨迹时间线（DetailPoint）与商品（JdGoods）。
 *
 * 轨迹/商品字段随拼多多版本有差异，解析器按「候选键 + 递归扫描」实现，
 * 真机验证阶段以 docs/API.md 记录的现场字段为准。
 */
object PddTraceFetcher {

    private const val TAG = "PddTraceFetcher"
    // 拼多多 H5 订单详情页（order_link_url 实测为 order.html?order_sn=…；物流/运单号/轨迹在该页接口里）
    private const val TRACE_PAGE = "https://mobile.yangkeduo.com/order.html?order_sn="

    private val TIME_KEYS = setOf(
        "time", "create_time", "update_time", "logistics_time", "tracking_time",
        "subtitle", "format_time", "formatTime", "gmt_create"
    )
    private val DESC_KEYS = setOf(
        "desc", "description", "context", "status_desc", "statusDesc", "text",
        "content", "track_desc", "logistics_desc", "logistics_status_desc",
        "sub_desc", "title"
    )

    private val GOODS_NAME_KEYS = setOf(
        "goods_name", "goodsName", "goods_title", "goods_brief", "product_name",
        "item_name", "sku_name", "goods_name_text"
    )
    private val GOODS_PIC_KEYS = setOf(
        "goods_image_url", "goodsImageUrl", "image_url", "thumbnail", "pic", "image",
        "goods_img", "goods_thumb_url"
    )
    private val GOODS_NUM_KEYS = setOf("goods_num", "goodsCount", "quantity", "num", "count")

    private val handler = Handler(Looper.getMainLooper())

    data class TraceResult(val points: List<DetailPoint>, val goods: JdGoods?)

    /** 详情页时间线（source=pdd）——旧接口：第一个启用拼多多账号 */
    suspend fun fetch(act: Activity, item: ExpressItem): List<DetailPoint>? =
        fetchWith(act, item, Store.pddCookies(act))

    /** 多源绑定：按指定拼多多账号凭证；silent=true 用于后台补抓（永不显示 WebView） */
    suspend fun fetchWith(act: Activity, item: ExpressItem, pddCookies: String, silent: Boolean = false): List<DetailPoint>? =
        run(act, orderIdOf(item), pddCookies, silent)?.points

    /** 完整结果（轨迹 + 商品，供详情页统一更新） */
    suspend fun fetchResult(act: Activity, item: ExpressItem): TraceResult? =
        run(act, orderIdOf(item), Store.pddCookies(act), false)

    /** 商品扫码解析（详情页「获取商品信息」按钮）——旧接口 */
    suspend fun resolveGoods(act: Activity, item: ExpressItem): JdGoods? =
        resolveGoodsWith(act, item, Store.pddCookies(act))

    /** 多源绑定：按指定拼多多账号凭证解析商品 */
    suspend fun resolveGoodsWith(act: Activity, item: ExpressItem, pddCookies: String): JdGoods? =
        run(act, orderIdOf(item), pddCookies, false)?.goods

    private fun orderIdOf(item: ExpressItem): String = item.queryChannel.ifBlank { item.mailNo }

    /**
     * 轨迹「双写」：① 存入 pdd_traces 缓存；② 回写卡片外显字段
     * latestText/latestTime/pickupCode（列表接口只有状态提示，真实最后轨迹在物流页）。
     */
    fun applyTraceToItem(act: Activity, item: ExpressItem, points: List<DetailPoint>, done: Boolean = item.state == 3) {
        if (points.isEmpty()) return
        Store.savePddTrace(act, item.mailNo, points, done)
        val last = points.last()
        val code = runCatching { GoodsPresentation.pickupCodeFrom(last.context) }.getOrNull()
        val items = Store.items(act).map {
            if (it.mailNo == item.mailNo && it.source == "pdd") {
                it.copy(
                    latestText = last.context.take(220).ifBlank { it.latestText },
                    latestTime = last.time.ifBlank { it.latestTime },
                    pickupCode = it.pickupCode.ifBlank { code ?: it.pickupCode }
                )
            } else {
                it
            }
        }
        Store.saveItems(act, items)
    }

    /** 已有缓存 → 回填卡片（处理历史数据：卡片轨迹外显此前未生效） */
    fun applyCachedTraceToItem(act: Activity, item: ExpressItem) {
        val cached = Store.pddTraces(act, item.mailNo) ?: return
        if (item.latestText.isBlank() || item.latestTime.isBlank()) {
            applyTraceToItem(act, item, cached, done = item.state == 3)
        }
    }

    private suspend fun run(act: Activity, orderId: String, pddCookies: String, silent: Boolean): TraceResult? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                start(act, orderId, pddCookies, silent) { r ->
                    if (cont.isActive) cont.resume(r)
                }
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    private fun start(act: Activity, orderId: String, pddCookies: String, silent: Boolean = false, onDone: (TraceResult?) -> Unit) {
        val captures = ArrayList<Pair<String, String>>()
        var finished = false
        var challengeShown = false
        var collectStarted = false
        var retriedLogin = false
        val pageHolder = arrayOf("")
        val w = WebView(act)
        WebView.setWebContentsDebuggingEnabled(true)
        w.setBackgroundColor(0x00000000)
        w.alpha = 0.01f
        w.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = "Mozilla/5.0 (Linux; Android 16; 25102RKBEC Build/BP2A.250605.031.A3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        }
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(w, true)
        PddCapture.setCookiesFor(act, pddCookies)
        PddCapture.attach(act, w)

        fun finish(result: TraceResult?) {
            if (finished) return
            finished = true
            handler.postDelayed({
                PddCapture.detach(w)
                val r = result ?: convert(captures)
                Log.i(TAG, "finish points=${r.points.size} goods=${r.goods?.name?.take(20)}")
                if (r.points.isNotEmpty()) {
                    Log.i(TAG, "TRACE_SAMPLE ${r.points.take(3).joinToString(" | ") { "${it.time} ${it.context.take(30)}" }}")
                }
                onDone(r)
            }, 200)
        }

        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                pageHolder[0] = url
                Log.i(TAG, "page: $url")
                // 真正的登录页才算（goods_express.html?…&needs_login=1 只是参数，不是登录页）
                val realLogin = url.contains("login.html") || url.contains("passport") || url.contains("/login") || url.contains("login?")
                if (realLogin) {
                    if (challengeShown) return
                    challengeShown = true
                    if (silent) {
                        // 后台补抓：静默；首次遇登录页重试一次（可能只是会话冷启动），再失败才放弃该单
                        Log.i(TAG, "login page during silent backfill -> retry once")
                        handler.post {
                            if (!retriedLogin) {
                                retriedLogin = true
                                w.reload()
                            } else {
                                finish(null)
                            }
                        }
                    } else {
                        handler.post {
                            w.alpha = 1f
                            w.bringToFront()
                            android.widget.Toast.makeText(
                                act, "拼多多需要登录或安全验证，请在页面中完成", android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    return
                }
                if (url.contains("order") || url.contains("logistic")) {
                    if (collectStarted) return
                    collectStarted = true
                    handler.postDelayed({ collect(w, captures, pageHolder, ::finish) }, 2500)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return blockScheme(request.url.toString())
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean {
                return blockScheme(url.orEmpty())
            }
        }
        WebViewCompat.addDocumentStartJavaScript(w, PddCapture.HOOK_JS, PddCapture.COOKIE_HOSTS.toSet())
        w.loadUrl(TRACE_PAGE + orderId)
        handler.postDelayed({
            if (!finished) {
                Log.w(TAG, "timeout, finalize with ${captures.size} captures")
                finish(null)
            }
        }, 45_000)
    }

    private fun collect(
        w: WebView,
        captures: ArrayList<Pair<String, String>>,
        pageHolder: Array<String>,
        finish: (TraceResult?) -> Unit
    ) {
        var rounds = 0
        var lastCount = -1
        var clicked = false
        val runnable = object : Runnable {
            override fun run() {
                rounds++
                // 点击「查看物流」进入物流页（物流是 DOM 渲染；一旦离开订单页就不再点击，避免钻进客服/聊天页）
                val cur = pageHolder[0]
                if (rounds >= 1 && (cur.contains("order.html") || cur.contains("orders.html") || cur.isBlank())) {
                    w.evaluateJavascript(
                        """(function(){
                          try {
                            var els = document.querySelectorAll('div,span,button,a');
                            var best = null;
                            for (var i = 0; i < els.length; i++) {
                              var t = (els[i].innerText || '').trim();
                              var r = els[i].getBoundingClientRect();
                              if ((t === '查看物流' || t === '查物流' || t === '物流详情' || t === '物流信息' ||
                                   (t.indexOf('物流') >= 0 && t.length <= 12)) && r.height > 0 && r.width > 0) {
                                if (!best || r.height < best.getBoundingClientRect().height) best = els[i];
                              }
                            }
                            if (best) { best.scrollIntoView(); best.click(); return best.innerText.slice(0,20); }
                          } catch(e) { return 'err:'+e; }
                          return 'notfound';
                        })()""",
                        null
                    )
                }
                // 每轮读一次 DOM 文本（物流轨迹线）与 JSON 捕获（商品等）
                w.evaluateJavascript(
                    "(function(){return document.body ? document.body.innerText.slice(0,30000) : 'no-body';})()"
                ) { v ->
                    val domPoints = parseDomPoints(v)
                    Log.i(TAG, "round=$rounds domPoints=${domPoints.size}")
                    if (domPoints.isNotEmpty()) {
                        val r = TraceResult(domPoints, null)
                        val stable = domPoints.size == lastCount
                        lastCount = domPoints.size
                        if ((stable && rounds >= 2) || rounds > 10) finish(r) else handler.postDelayed(this, 2000)
                        return@evaluateJavascript
                    }
                    val r = runCatching { convert(captures) }.getOrNull() ?: TraceResult(emptyList(), null)
                    Log.i(TAG, "round=$rounds jsonPoints=${r.points.size} captures=${captures.size}")
                    val stable = r.points.size == lastCount
                    lastCount = r.points.size
                    if ((r.points.isNotEmpty() && stable && rounds >= 2) || rounds > 10) {
                        finish(r)
                    } else {
                        handler.postDelayed(this, 2000)
                    }
                }
            }
        }
        handler.post(runnable)
    }

    /** DOM 文本 → 轨迹点：goods_express 物流页为「状态+时间 粘连行 + 描述行」结构 */
    private fun parseDomPoints(raw: String?): List<DetailPoint> {
        var text = raw?.trim() ?: return emptyList()
        if (text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length - 1).replace("\\n", "\n")
        }
        val points = ArrayList<DetailPoint>()
        val dateRe = Regex("""(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}(?::\d{2})?)""")
        val noise = setOf("复制", "展开", "物流服务", "在App打开", "顶部", "打赏快递员", "拨打电话", "货物跟踪", "包裹追踪")
        // 推荐流/价格杂线
        val recRe = Regex("""(即将恢复|本店已拼|全店总售|已抢|券后|立减|未发货秒退|24小时发货|限1件|退货包运费|\d+\.\d+$)""")
        val skipRe = Regex("""(订单编号|收货地址|快递员|网点电话|投诉电话|联系电话|微信公众号|^：$|^\d+$)""")
        val kw = listOf("快件", "包裹", "派件", "揽收", "签收", "发往", "到达", "转运", "投递", "取件",
            "出库", "配送", "妥投", "物流", "运输", "发出", "已投", "送达", "查询", "无轨迹", "拦截",
            "快递", "签收人", "配送至", "快递员", "网点")
        fun looksLikeDesc(l: String) = l.length >= 6 && kw.any { l.contains(it) }
        var pending: StringBuilder? = null
        var pendingTime = ""
        var inRec = false
        for (line in text.split("\n")) {
            val l = line.trim()
            if (l.isEmpty()) continue
            val dm = dateRe.find(l)
            if (dm != null) {
                val t = dm.groupValues[1]
                val statusPrefix = l.substring(0, dm.range.first).trim()
                val d = pending?.toString()?.trim().orEmpty()
                if (d.isNotBlank() && d.length >= 4 && pendingTime.isNotBlank()) {
                    points.add(DetailPoint(context = d.take(400), time = pendingTime, formattedTime = pendingTime))
                }
                pendingTime = t
                inRec = false
                pending = if (statusPrefix.isNotBlank() && statusPrefix !in noise) {
                    StringBuilder(statusPrefix)
                } else {
                    StringBuilder()
                }
                continue
            }
            if (l in noise) continue
            if (inRec) continue
            // 描述行优先（包含 快递/送达/签收 等）——即使里面混有电话/网点数字
            if (looksLikeDesc(l)) {
                if (pending == null) pending = StringBuilder()
                if (pending!!.length < 400) pending.append(" ").append(l)
                continue
            }
            if (recRe.containsMatchIn(l)) {
                // 进入推荐流：把已收集描述落点并停止追加
                val d = pending?.toString()?.trim().orEmpty()
                if (d.isNotBlank() && d.length >= 4 && pendingTime.isNotBlank()) {
                    points.add(DetailPoint(context = d.take(400), time = pendingTime, formattedTime = pendingTime))
                }
                pending = null
                inRec = true
                continue
            }
            if (skipRe.containsMatchIn(l)) continue
            if (pending == null) {
                if (l.length >= 4 && !l.all { it.isDigit() }) pending = StringBuilder(l)
            } else {
                // 续行追加仅接受带物流关键词的行（防止推荐流标题混入）
                val contKw = listOf("快递", "签收", "物流", "包裹", "快件", "网点", "电话", "服务", "反馈", "感谢", "送达", "配送", "运输", "预计")
                if (l.length >= 6 && l.contains("快递") || contKw.any { l.contains(it) }) {
                    if (pending!!.length < 400) pending.append(" ").append(l)
                }
            }
        }
        // 收尾：最后一条的「状态+时间」已在时间行记录，flush 剩余描述
        val d = pending?.toString()?.trim().orEmpty()
        if (d.isNotBlank() && d.length >= 4 && pendingTime.isNotBlank()) {
            points.add(DetailPoint(context = d.take(400), time = pendingTime, formattedTime = pendingTime))
        }
        return points
    }

    /** 拦截 pinduoduo:// App deep link（阻止跳 App，留在 H5 页继续捕获） */
    private fun blockScheme(u: String): Boolean {
        if (u.startsWith("pinduoduo://") || u.startsWith("xunmeng://")) {
            Log.i(TAG, "block app scheme: ${u.take(80)}")
            return true
        }
        return false
    }

    /* ─────────────── 容错解析 ─────────────── */

    private fun convert(captures: List<Pair<String, String>>): TraceResult {
        val pointsMap = LinkedHashMap<String, DetailPoint>()
        var goods: JdGoods? = null
        for ((url, body) in captures) {
            if (!PddCapture.looksRelevant(url, body)) continue
            val root = runCatching { JSONObject(body) }.getOrNull() ?: continue
            collectPoints(root, pointsMap)
            // 商品只从列表接口取（详情页 order_detail_group.goods_list 是推荐流，不要误采）
            if (goods == null && url.contains("order_list_v4")) goods = extractGoods(root)
        }
        val points = pointsMap.values.toList()
        if (points.isNotEmpty()) Log.i(TAG, "trace points=${points.size} first=${points.first().context.take(24)}")
        return TraceResult(points, goods)
    }

    private fun collectPoints(node: Any?, out: MutableMap<String, DetailPoint>) {
        when (node) {
            is JSONObject -> {
                // 轨迹点对象：同时有时间与描述
                val time = PddCapture.firstString(node, TIME_KEYS)
                val desc = PddCapture.firstString(node, DESC_KEYS)
                if (time.isNotBlank() && desc.isNotBlank() && desc != time) {
                    val key = "$time|$desc"
                    if (!out.containsKey(key)) {
                        out[key] = DetailPoint(context = desc, time = time, formattedTime = time)
                    }
                    return
                }
                for (k in node.keys()) {
                    val v = node.opt(k)
                    if (v is JSONObject || v is JSONArray) collectPoints(v, out)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val v = node.opt(i)
                    if (v is JSONObject || v is JSONArray) collectPoints(v, out)
                }
            }
        }
    }

    private fun extractGoods(obj: JSONObject): JdGoods? {
        val name = PddCapture.firstStringDeep(obj, GOODS_NAME_KEYS, 6)
        if (name.isBlank()) return null
        var pic = PddCapture.firstStringDeep(obj, GOODS_PIC_KEYS, 6)
        if (pic.startsWith("//")) pic = "https:$pic"
        val count = PddCapture.firstStringDeep(obj, GOODS_NUM_KEYS, 6)
        return JdGoods(
            name = name.take(200),
            imageUrl = pic,
            count = if (count.isNotBlank()) "x$count" else ""
        )
    }
}
