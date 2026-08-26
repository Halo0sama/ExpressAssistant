package com.halo.expressassistant.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import com.halo.expressassistant.data.ExpressItem
import com.halo.expressassistant.data.JdGoods
import com.halo.expressassistant.data.Store
import org.json.JSONArray
import org.json.JSONObject

/**
 * 拼多多包裹列表（无需小米/京东/淘宝登录）：隐藏 WebView 加载拼多多 H5 订单中心，
 * JS 钩子捕获页面 SDK 自动签名后的 proxy 接口响应（绕开 anti_content），
 * 滚动 + 点击「加载更多」翻页，把订单/包裹转成 ExpressItem（source=pdd）。
 *
 * 说明：拼多多订单接口字段随版本有差异，解析器按「候选键 + 递归扫描」容错实现，
 * 真实字段样例在真机验证阶段记录（docs/API.md），以现场为准。
 */
object PddListFetcher {

    private const val TAG = "PddListFetcher"
    // 拼多多 H5 买家订单页（油猴脚本同源确认：orders.html，React 无限滚动 + 「没有更多了...」）
    private const val ORDER_PAGE = "https://mobile.yangkeduo.com/orders.html"

    /**
     * 纯数据抓取：在页面上下文直接 fetch order_list_v4（同源 + 站点 Cookie，接口仅需 pdduid）。
     * 自动检测常见分页字段（has_more/next_offset/cursor/offset/size）循环抓齐；结果经 HOOK_JS 落入捕获管道。
     */
    private val PURE_FETCH_JS = """
(async function(){
  try{
    var uid = '__PDDUID__';
    if(!uid){
      var m1 = location.href.match(/pdduid=([0-9]+)/);
      if(m1) uid = m1[1];
    }
    if(!uid){ var m2 = document.cookie.match(/(?:^|;\s*)pdduid=([0-9]+)/); if(m2) uid = m2[1]; }
    if(!uid){ window.__pddFetch = {done:true, error:'no-uid'}; return; }
    var pages = 0, bodies = 0, emptyStreak = 0, has = true;
    while(has && pages < 20){
      pages++;
      var qs = new URLSearchParams({pdduid: uid, page: String(pages), type: 'all'});
      var resp = await fetch('/proxy/api/api/aristotle/order_list_v4?' + qs.toString(), {credentials:'include'});
      if(!resp.ok){ window.__pddFetch = {done:true, error:'http-' + resp.status, pages:pages, bodies:bodies}; return; }
      var text = await resp.text(); bodies++;
      var j = null;
      try{ j = JSON.parse(text); }catch(e){}
      if(!j){ window.__pddFetch = {done:true, error:'json', pages:pages, bodies:bodies}; return; }
      var orders = (j.orders && j.orders.length) ? j.orders.length : 0;
      var hm = (j.has_more !== undefined) ? j.has_more : (j.hasMore !== undefined) ? j.hasMore : false;
      if(orders === 0){ emptyStreak++; if(emptyStreak >= 2) has = false; }
      else emptyStreak = 0;
      if(hm === false && orders === 0) has = false;
      if(orders === 0 && pages >= 3) has = false;
    }
    window.__pddFetch = {done:true, pages:pages, bodies:bodies};
  }catch(e){ window.__pddFetch = {done:true, error:String(e)}; }
})();
""".trimIndent()

    /**
     * JS 程序化滚动：找到滚动容器后逐步 scrollTop（每轮调用推进一次）。
     * 无任何触摸事件，由页面自身响应滚动并发起带签名的翻页请求。
     */
    private val SCROLL_JS = """
(function(){
  try {
    var all = document.querySelectorAll('*');
    var best = null, bestSh = 0;
    for (var i = 0; i < all.length; i++) {
      var n = all[i];
      var sh = n.scrollHeight || 0;
      if (sh > bestSh && sh > n.clientHeight + 100 && n.clientHeight > 100) { best = n; bestSh = sh; }
    }
    if (!best) best = document.scrollingElement || document.documentElement;
    window.__pddScroll = (window.__pddScroll || 0) + Math.floor((best.clientHeight || 900) * 0.85);
    var y = window.__pddScroll;
    try { if (typeof best.scrollTo === 'function') best.scrollTo(0, y); } catch(e){}
    try { best.scrollTop = y; } catch(e){}
    try { document.scrollingElement.scrollTop = y; } catch(e){}
    try { window.scrollTo(0, y); } catch(e){}
    try { best.dispatchEvent(new Event('scroll', {bubbles: true})); } catch(e){}
    var now = best ? (best.scrollTop || 0) : 0;
    return 'tag=' + (best ? best.tagName : '?') + ' sh=' + bestSh + ' ch=' + (best ? best.clientHeight : 0) + ' top=' + now + ' y=' + y;
  } catch(e) { return 'ERR:' + e; }
})();
""".trimIndent()

    /** 点击订单页顶部标签（全部/已完成/待评价…），每个标签页各滚一遍以抓全各分类订单 */
    private val TAP_TAB_JS = """
(function(){
  try {
    var target = '__TAB__';
    var els = document.querySelectorAll('div,span,button,a,li');
    var best = null;
    for (var i = 0; i < els.length; i++) {
      var e = els[i];
      var t = (e.innerText || '').trim();
      if (t === target && e.offsetParent !== null) { best = e; break; }
    }
    if (best) {
      window.__pddScroll = 0;
      best.click();
      return 'CLICKED ' + target;
    }
    return 'NO_TAB ' + target;
  } catch(e) { return 'ERR:' + e; }
})();
""".trimIndent()

    private val ORDER_KEYS = setOf(
        "order_sn", "orderSn", "order_id", "orderId", "mall_order_sn",
        "order_no", "order_list_sn", "orderSnList"
    )
    private val TRACK_KEYS = setOf(
        "tracking_no", "mailNo", "mail_no", "express_no", "logistics_no",
        "waybill_no", "waybillCode", "trackingNumber", "logistics_sn", "delivery_no"
    )
    private val COMPANY_KEYS = setOf(
        "logistics_company_short_name", "logisticsCompanyName", "logistics_company_name",
        "shipping_company", "express_company_name", "company_name", "express_company"
    )
    private val STATUS_KEYS = setOf(
        "order_status_prompt", "order_prompt", "status_prompt_text", "order_status_text",
        "combined_order_status_prompt", "status_desc", "statusDesc", "order_status_desc",
        "order_status", "orderStatus", "combined_order_status", "shipping_status",
        "status_text", "statusText", "logistics_status", "status"
    )
    private val TRACK_KEYS_TEXT = setOf(
        "order_status_prompt", "bottom_left_content", "logistics_desc", "tracking_desc",
        "logistics_desc_text", "status_desc", "statusDesc", "latest_status",
        "latest_status_text", "description", "desc", "text"
    )
    private val TRACK_KEYS_TIME = setOf(
        "logistics_update_time", "latest_time", "tracking_time", "update_time", "updateTime",
        "create_time", "gmt_modified", "status_change_time", "last_update_time"
    )
    private val GOODS_NAME_KEYS = setOf(
        "goods_name", "goodsName", "goods_title", "goods_brief", "product_name",
        "item_name", "sku_name", "goods_name_text"
    )
    private val GOODS_PIC_KEYS = setOf(
        "goods_image_url", "goodsImageUrl", "image_url", "thumbnail", "pic", "image",
        "goods_img", "goods_thumb_url", "thumb_url", "hd_thumb_url"
    )
    private val GOODS_NUM_KEYS = setOf("goods_num", "goodsCount", "quantity", "num", "count", "goods_number")

    private val handler = Handler(Looper.getMainLooper())
    private var scrolling = false
    private var orderSampled = false

    fun fetch(
        act: Context,
        onDone: (List<ExpressItem>?, Map<String, JdGoods>, String?) -> Unit
    ) = fetchWith(act, Store.firstEnabledAccount(act, Store.CH_PDD), onDone)

    /** 多源绑定：按指定拼多多账号凭证抓列表 */
    fun fetchWith(
        act: Context,
        account: com.halo.expressassistant.data.BoundAccount?,
        onDone: (List<ExpressItem>?, Map<String, JdGoods>, String?) -> Unit
    ) {
        handler.post { start(act, account, onDone) }
    }

    /** 流式抓取：每增量一批新订单+商品图就回调 onBatch（「获取一点加载一点」），最终列表经 onDone 回传 */
    fun fetchStream(
        act: Context,
        account: com.halo.expressassistant.data.BoundAccount?,
        onBatch: (List<ExpressItem>, Map<String, JdGoods>) -> Unit,
        onDone: (List<ExpressItem>?) -> Unit
    ) {
        handler.post {
            start(act, account, { items, _, _ -> onDone(items) }, onBatch)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun start(
        act: Context,
        account: com.halo.expressassistant.data.BoundAccount?,
        onDone: (List<ExpressItem>?, Map<String, JdGoods>, String?) -> Unit,
        onBatch: ((List<ExpressItem>, Map<String, JdGoods>) -> Unit)? = null
    ) {
        val captures = ArrayList<Pair<String, String>>()
        var finished = false
        var challengeShown = false
        orderSampled = false
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
        val pddCookies = account?.let { Store.cookieOf(it.payload) } ?: Store.pddCookies(act)
        PddCapture.setCookiesFor(act, pddCookies)
        PddCapture.attach(act, w)

        fun finish(result: List<ExpressItem>?, err: String?) {
            if (finished) return
            finished = true
            scrolling = false
            handler.postDelayed({
                PddCapture.detach(w)
                val (items, goods) = convert(captures, account)
                Log.i(TAG, "finish items=${items.size} goods=${goods.size} err=$err")
                onDone(result ?: items, goods, err)
            }, 200)
        }

        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                Log.i(TAG, "page: $url")
                if (url.contains("login.html") || url.contains("passport") || url.contains("/login") || url.contains("login?")) {
                    // 拼多多要求安全验证/重新登录：把 WebView 显示出来让用户完成，完成后自动继续
                    if (challengeShown) return
                    challengeShown = true
                    Log.i(TAG, "login challenge -> show webview for user verification")
                    handler.post {
                        w.alpha = 1f
                        w.bringToFront()
                        android.widget.Toast.makeText(
                            act, "拼多多需要登录或安全验证，请在页面中完成", android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                    return
                }
                if (url.contains("order") && !url.contains("login.html") && !url.contains("proxy")) {
                    handler.postDelayed({
                        collect(w, captures, account, pddCookies, onBatch, ::finish)
                    }, 4000)
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url.toString()
                if (u.contains("proxy/api") && (u.contains("order") || u.contains("logistic") || u.contains("express"))) {
                    Log.i(TAG, "REQ proxy (passthrough): ${u.take(160)}")
                }
                return null
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
        w.loadUrl(ORDER_PAGE)
        handler.postDelayed({
            if (!finished) {
                Log.w(TAG, "timeout, finalize with ${captures.size} captures")
                finish(null, "拼多多列表超时（已用部分数据）")
            }
        }, 75_000)
    }

    private fun collect(w: WebView, captures: ArrayList<Pair<String, String>>, account: com.halo.expressassistant.data.BoundAccount?, cookieForUid: String, onBatch: ((List<ExpressItem>, Map<String, JdGoods>) -> Unit)?, finish: (List<ExpressItem>?, String?) -> Unit) {
        if (scrolling) return
        scrolling = true
        var rounds = 0
        var lastCount = -1
        var stableStreak = 0
        val emitted = HashSet<String>()
        val emittedGoods = HashSet<String>()
        val runnable = object : Runnable {
            override fun run() {
                rounds++
                PddCapture.readCaptures(w) { batch ->
                    var loggedNew = 0
                    synchronized(captures) {
                        for ((url, body) in batch) {
                            if (PddCapture.looksRelevant(url, body) && captures.none { it.first == url && it.second == body }) {
                                captures.add(url to body)
                                if (loggedNew < 3) {
                                    loggedNew++
                                    Log.i(TAG, "CAP url=${url.take(120)} len=${body.length} head=${body.take(200)}")
                                }
                            }
                        }
                    }
                    val (orders, allGoods) = convert(captures, account)
                    Log.i(TAG, "round=$rounds orders=${orders.size} captures=${captures.size}")
                    // 流式：只推送本轮新增的订单 + 新增商品图（「获取一点加载一点」）
                    if (onBatch != null && orders.isNotEmpty()) {
                        val fresh = orders.filter { emitted.add(it.mailNo) }
                        val freshGoods = allGoods.filterKeys { emittedGoods.add(it) }
                        if (fresh.isNotEmpty()) {
                            onBatch(fresh, freshGoods)
                            Log.i(TAG, "STREAM +${fresh.size} goods +${freshGoods.size} (total=${orders.size})")
                        }
                    }
                    if (rounds == 1) {
                        w.evaluateJavascript("(function(){return document.body ? document.body.innerText.slice(0,1200) : 'no-body';})()") { v ->
                            Log.i(TAG, "PAGE_TEXT: $v")
                        }
                        // 纯数据抓取：单次 fetch 即全部（接口仅 pdduid）；HOOK_JS 会把响应落入 captures
                        val pddUid = Regex("[;\\s]pdd_user_id=([^;]+)").find(cookieForUid)?.groupValues?.get(1)
                            ?: Regex("[;\\s]pdduid=([^;]+)").find(cookieForUid)?.groupValues?.get(1).orEmpty()
                        w.evaluateJavascript(PURE_FETCH_JS.replace("__PDDUID__", pddUid)) { }
                    }
                    // 触摸时序滚动（PDD 虚拟列表以 touch 事件触发增量加载，JS scrollTop 无法触发完整 76 单）
                    if (rounds == 2 || rounds == 5) {
                        PddCapture.tryTapAllOrders(w)
                    }
                    PddCapture.tryTapMore(w)
                    swipeUp(w)
                    if (orders.size == lastCount) stableStreak++ else {
                        stableStreak = 0
                        lastCount = orders.size
                    }
                    if ((orders.isNotEmpty() && stableStreak >= 3 && rounds >= 6) || rounds > 18) {
                        finish(orders, null)
                    } else {
                        handler.postDelayed(this, 3000)
                    }
                }
            }
        }
        handler.post(runnable)
    }

    /** 拦截 pinduoduo:// App deep link（阻止跳 App，留在 H5 页继续捕获） */
    private fun blockScheme(u: String): Boolean {
        if (u.startsWith("pinduoduo://") || u.startsWith("xunmeng://")) {
            Log.i(TAG, "block app scheme: ${u.take(80)}")
            return true
        }
        return false
    }

    private fun swipeUp(w: WebView) {
        val width = w.width.toFloat()
        val height = w.height.toFloat()
        if (width <= 0 || height <= 0) return
        val x = width / 2f
        val downTime = android.os.SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, height * 0.9f, 0)
        w.dispatchTouchEvent(down)
        var t = downTime
        for (i in 1..8) {
            t += 30
            val move = MotionEvent.obtain(downTime, t, MotionEvent.ACTION_MOVE,
                x, height * 0.9f - height * 0.11f * i, 0)
            w.dispatchTouchEvent(move)
            move.recycle()
        }
        val up = MotionEvent.obtain(downTime, t + 30, MotionEvent.ACTION_UP, x, height * 0.08f, 0)
        w.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    /* ─────────────── 容错解析（候选键 + 递归扫描） ─────────────── */

    private fun convert(
        captures: List<Pair<String, String>>,
        account: com.halo.expressassistant.data.BoundAccount?
    ): Pair<List<ExpressItem>, Map<String, JdGoods>> {
        val byOrder = LinkedHashMap<String, ExpressItem>()
        val goodsMap = HashMap<String, JdGoods>()
        var sampled = false
        for ((url, body) in captures) {
            // 排除「状态码表」：express/track/status 只是 order_sn→GOT/SIGN/1 的映射，不是订单
            if (url.contains("track/status")) continue
            if (!PddCapture.looksRelevant(url, body)) continue
            val root = runCatching { JSONObject(body) }.getOrNull() ?: continue
            if (!sampled && url.contains("order_list_v4")) {
                sampled = true
                val firstOrder = firstOrderLike(root)
                if (firstOrder != null) {
                    Log.i(TAG, "ORDER_REAL_SAMPLE keys=${buildKeys(firstOrder)} json=${firstOrder.toString().take(1500)}")
                }
            }
            collectOrders(root, byOrder, goodsMap, account?.id ?: "", account?.label ?: "")
        }
        return byOrder.values.toList() to goodsMap
    }

    private fun firstOrderLike(node: Any?): JSONObject? {
        when (node) {
            is JSONObject -> {
                if (looksLikeOrder(node)) return node
                for (k in node.keys()) {
                    val inner = node.opt(k)
                    if (inner is JSONObject || inner is JSONArray) {
                        firstOrderLike(inner)?.let { return it }
                    }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) {
                val inner = node.opt(i)
                if (inner is JSONObject) firstOrderLike(inner)?.let { return it }
            }
        }
        return null
    }

    private fun buildKeys(obj: JSONObject): String {
        val sb = StringBuilder()
        for (k in obj.keys()) sb.append(k).append(' ')
        return sb.toString()
    }

    private fun collectOrders(
        node: Any?,
        byOrder: MutableMap<String, ExpressItem>,
        goodsMap: MutableMap<String, JdGoods>,
        accountId: String,
        accountLabel: String
    ) {
        when (node) {
            is JSONObject -> {
                if (looksLikeOrder(node)) {
                    parseOrder(node, byOrder, goodsMap, accountId, accountLabel)
                    return
                }
                for (k in node.keys()) {
                    val inner = node.opt(k)
                    if (inner is JSONObject || inner is JSONArray) collectOrders(inner, byOrder, goodsMap, accountId, accountLabel)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val inner = node.opt(i)
                    if (inner is JSONObject || inner is JSONArray) collectOrders(inner, byOrder, goodsMap, accountId, accountLabel)
                }
            }
        }
    }

    private fun looksLikeOrder(obj: JSONObject): Boolean {
        for (k in ORDER_KEYS) {
            if (obj.has(k)) {
                val v = obj.opt(k)
                if (v is String || v is Number) return true
            }
        }
        for (k in TRACK_KEYS) {
            if (obj.has(k)) {
                val v = obj.opt(k)
                if (v is String || v is Number) return true
            }
        }
        return false
    }

    private fun parseOrder(
        obj: JSONObject,
        byOrder: MutableMap<String, ExpressItem>,
        goodsMap: MutableMap<String, JdGoods>,
        accountId: String,
        accountLabel: String
    ) {
        if (!orderSampled) {
            orderSampled = true
            val keys = StringBuilder()
            for (k in obj.keys()) keys.append(k).append(' ')
            Log.i(TAG, "ORDER_SAMPLE keys=$keys json=${obj.toString().take(6000)}")
        }
        val orderId = PddCapture.firstString(obj, ORDER_KEYS)
        val mailNo = PddCapture.firstString(obj, TRACK_KEYS).ifBlank { orderId }
        if (mailNo.isBlank()) return
        val statusText = PddCapture.firstString(obj, STATUS_KEYS)
        val bad = listOf("取消", "关闭", "退款", "售后", "待付款", "未支付", "已取消", "交易关闭")
        if (bad.any { statusText.contains(it) }) {
            Log.i(TAG, "skip closed order: $orderId status=$statusText")
            return
        }
        val state = when {
            statusText.contains("签收") || statusText.contains("已完成") ||
                statusText.contains("交易成功") || statusText.contains("待评价") -> 3
            statusText.contains("派送") || statusText.contains("配送") || statusText.contains("派件") -> 5
            statusText.contains("待发货") || statusText.contains("未发货") ||
                statusText.contains("待成团") -> 1
            else -> 0
        }
        val stateNum = when (state) {
            3 -> 107
            5 -> 105
            1 -> 103
            else -> 105
        }
        val latestText = PddCapture.firstStringDeep(obj, TRACK_KEYS_TEXT, 4).ifBlank { statusText.trim() }
        val latestTime = PddCapture.timeString(PddCapture.firstValueDeep(obj, TRACK_KEYS_TIME, 4))
        val companyName = PddCapture.firstString(obj, COMPANY_KEYS).ifBlank { "拼多多快递" }
        val goods = extractGoods(obj)
        val item = ExpressItem(
            id = orderId.ifBlank { mailNo },
            companyCode = "PDD",
            companyName = companyName,
            mailNo = mailNo,
            latestText = latestText,
            latestTime = latestTime,
            state = state,
            stateName = statusText.trim().take(60),
            provider = "Pinduoduo",
            stateNum = stateNum,
            queryChannel = orderId.ifBlank { mailNo },
            source = "pdd",
            accountId = accountId,
            accountLabel = accountLabel
        )
        val key = orderId.ifBlank { mailNo }
        byOrder[key] = item
        if (goods.name.isNotBlank()) {
            goodsMap[mailNo] = goods
        }
        Log.i(TAG, "parsed order key=$key mailNo=$mailNo state=$state goods=${goods.name.take(20)}")
    }

    private fun extractGoods(obj: JSONObject): JdGoods {
        val name = PddCapture.firstStringDeep(obj, GOODS_NAME_KEYS, 6)
        if (name.isBlank()) return JdGoods()
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
