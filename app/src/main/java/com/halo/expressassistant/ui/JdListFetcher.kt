package com.halo.expressassistant.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.halo.expressassistant.data.ExpressItem
import com.halo.expressassistant.data.JdGoods
import com.halo.expressassistant.data.Store
import org.json.JSONObject

/**
 * 京东包裹列表（无需小米登录）：隐藏 WebView 加载京东订单中心，
 * 网络层拦截 order_list_m 响应（页面 SDK 自动签名，绕开 h5st），
 * 滚动触发翻页，把订单转成 ExpressItem（source=jd）。
 */
object JdListFetcher {

    private const val TAG = "JdListFetcher"
    private const val ORDER_CENTER = "https://trade.m.jd.com/order/orderlist_jdm.shtml?orderType=all&source=m_outer_jx_order"

    private const val HOOK_JS = """
        (function(){
          function pushCap(s){
            try {
              var arr = [];
              if (window.name && window.name.charAt(0) === '[') { try { arr = JSON.parse(window.name); } catch(e){ arr = []; } }
              arr.push(s);
              window.name = JSON.stringify(arr);
            } catch(e) {}
          }
          window.__jdcb = function(data){ try{ pushCap(JSON.stringify(data)); }catch(e){} };
          function rewrite(v){ if (v && /callback=/.test(String(v))) { return String(v).replace(/callback=[a-zA-Z0-9_]+/, 'callback=__jdcb'); } return v; }
          var origCreate = document.createElement.bind(document);
          document.createElement = function(tag){
            var el = origCreate(tag);
            if (String(tag).toLowerCase() === 'script') {
              var origSet = el.setAttribute.bind(el);
              el.setAttribute = function(k, v){ if (k === 'src') { v = rewrite(v); } return origSet(k, v); };
            }
            return el;
          };
          try {
            var desc = Object.getOwnPropertyDescriptor(HTMLScriptElement.prototype, 'src');
            Object.defineProperty(HTMLScriptElement.prototype, 'src', {
              get: function(){ return desc.get.call(this); },
              set: function(v){ return desc.set.call(this, rewrite(v)); },
              configurable: true
            });
          } catch(e) {}
          var oo = XMLHttpRequest.prototype.open; var os = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.open = function(m,u){ this.__u=u; return oo.apply(this,arguments); };
          XMLHttpRequest.prototype.send = function(){ var self=this; this.addEventListener('load', function(){ try{ pushCap(self.responseText); }catch(e){} }); return os.apply(this,arguments); };
          var of = window.fetch; if (of) { window.fetch = function(input,init){ var p=of.apply(this,arguments); if(p&&p.then){p.then(function(r){try{ r.clone().text().then(function(t){ pushCap(t); }); }catch(e){}});} return p; }; }
        })();
    """

    private val handler = Handler(Looper.getMainLooper())
    private val interceptClient = okhttp3.OkHttpClient.Builder().build()
    private var scrollStarted = false
    private var challengeShown = false

    private fun tapAt(w: WebView, cssX: Float, cssY: Float) {
        val density = w.resources.displayMetrics.density
        val x = cssX * density
        val y = cssY * density
        val downTime = android.os.SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(downTime, downTime + 60, MotionEvent.ACTION_UP, x, y, 0)
        try {
            w.dispatchTouchEvent(down)
            w.dispatchTouchEvent(up)
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    fun fetch(
        act: Context,
        onDone: (List<ExpressItem>?, Map<String, JdGoods>, String?) -> Unit
    ) = fetchWith(act, Store.firstEnabledAccount(act, Store.CH_JD), onDone)

    /** 多源绑定：按指定京东账号凭证抓列表 */
    fun fetchWith(
        act: Context,
        account: com.halo.expressassistant.data.BoundAccount?,
        onDone: (List<ExpressItem>?, Map<String, JdGoods>, String?) -> Unit
    ) {
        handler.post { start(act, account, onDone) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun start(
        act: Context,
        account: com.halo.expressassistant.data.BoundAccount?,
        onDone: (List<ExpressItem>?, Map<String, JdGoods>, String?) -> Unit
    ) {
        var lastHintAt = 0L
        val captures = ArrayList<String>()
        var finished = false
        val w = WebView(act)
        WebView.setWebContentsDebuggingEnabled(true)
        w.setBackgroundColor(0x00000000)
        w.alpha = 0.01f
        w.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
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
        val jdCookies = account?.let { Store.cookieOf(it.payload) } ?: Store.jdCookies(act)
        for (part in jdCookies.split(";")) {
            val kv = part.trim().split("=", limit = 2)
            if (kv.size == 2 && kv[0].isNotBlank()) {
                cm.setCookie("https://www.jd.com", "${kv[0]}=${kv[1]}")
                cm.setCookie("https://wqs.jd.com", "${kv[0]}=${kv[1]}")
                cm.setCookie("https://trade.m.jd.com", "${kv[0]}=${kv[1]}")
                cm.setCookie("https://api.m.jd.com", "${kv[0]}=${kv[1]}")
                cm.setCookie("https://jingfen.jd.com", "${kv[0]}=${kv[1]}")
            }
        }
        cm.flush()
        try {
            val decor = (act as? Activity)?.window?.decorView as? ViewGroup
            decor?.addView(w, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } catch (e: Throwable) {
            Log.w(TAG, "attach fail: $e")
        }

        fun finish(result: List<ExpressItem>?, err: String?) {
            if (finished) return
            finished = true
            scrollStarted = false
            challengeShown = false
            handler.postDelayed({
                try {
                    (w.parent as? ViewGroup)?.removeView(w)
                    w.destroy()
                } catch (e: Throwable) {
                    Log.w(TAG, "cleanup fail: $e")
                }
                val (items, goods) = convert(captures, account)
                onDone(result ?: items, goods, err)
            }, 200)
        }

        /** 汇总京东主要域 Cookie（用于验证完成后回存，防每次刷新都脱线） */
        fun collectJdCookies(): String {
            val sb = StringBuilder()
            for (host in listOf(
                "https://www.jd.com", "https://wqs.jd.com", "https://trade.m.jd.com",
                "https://api.m.jd.com", "https://jingfen.jd.com", "https://plogin.m.jd.com"
            )) {
                val c = CookieManager.getInstance().getCookie(host) ?: continue
                if (sb.isNotEmpty()) sb.append("; ")
                sb.append(c)
            }
            return sb.toString()
        }

        fun saveCookies(cookies: String) {
            try {
                if (account != null) {
                    Store.updateAccount(act, Store.CH_JD, account.copy(payload = Store.cookiePayload(cookies)))
                } else {
                    Store.saveJdCookies(act, cookies)
                }
                Log.i(TAG, "jd cookies refreshed (len=${cookies.length})")
            } catch (e: Throwable) {
                Log.w(TAG, "save cookies fail: $e")
            }
        }

        w.webViewClient = object : WebViewClient() {            override fun onPageFinished(view: WebView, url: String) {
                Log.i(TAG, "page: $url")
                if (url.contains("plogin") || url.contains("nopasswordcmcc")) {
                    // 京东安全验证页：保持隐藏 WebView 静默等待（不弹窗打扰）；
                    // 页面内登录/一键登录完成后会继续跳回 orderlist_jdm，验证后回存新 Cookie 防再次脱线
                    Log.i(TAG, "login challenge (silent) -> wait page continue")
                    handler.postDelayed({
                        if (!finished) {
                            val cookies = collectJdCookies()
                            if (cookies.isNotBlank()) saveCookies(cookies)
                        }
                    }, 2500)
                    // 兜底：静默等待较久仍无数据 → 一次性提示（5 分钟节流），引导重新绑定而非反复弹窗
                    handler.postDelayed({
                        if (!finished && captures.isEmpty()) {
                            val now = System.currentTimeMillis()
                            if (now - lastHintAt > 5 * 60 * 1000L) {
                                lastHintAt = now
                                android.widget.Toast.makeText(
                                    act,
                                    "京东 H5 登录态已失效：本次保留旧数据；如需更新，请到 设置→京东登录 重新绑定",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }, 8000)
                    return
                }
                if (url.contains("orderlist_jdm.shtml") && !url.contains("orderType=search")) {
                    // 回到订单页（验证完成/直接进入）：回存最新 Cookie
                    val cookies = collectJdCookies()
                    if (cookies.isNotBlank()) saveCookies(cookies)
                    handler.postDelayed({
                        scrollAndCollect(w, captures, account, ::finish)
                    }, 4000)
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url.toString()
                if (u.contains("api.m.jd.com") && u.contains("order_list_m")) {
                    Log.i(TAG, "REQ order_list_m (passthrough)")
                    return null
                }
                return null
            }
        }
        // JS 级捕获钩子：改写 JSONP 回调，不动网络层
        androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
            w, HOOK_JS,
            setOf("https://trade.m.jd.com", "https://wqs.jd.com")
        )
        w.loadUrl(ORDER_CENTER)
        // 兜底超时
        handler.postDelayed({
            if (!finished) {
                Log.w(TAG, "timeout, finalize with ${captures.size} captures")
                finish(null, "京东列表超时（已用部分数据）")
            }
        }, 60000)
    }

    private fun scrollAndCollect(
        w: WebView,
        captures: ArrayList<String>,
        account: com.halo.expressassistant.data.BoundAccount?,
        finish: (List<ExpressItem>?, String?) -> Unit
    ) {
        if (scrollStarted) return
        scrollStarted = true
        var rounds = 0
        val runnable = object : Runnable {
            override fun run() {
                rounds++
                // 从 window.name（跨导航存活）读取捕获的响应
                w.evaluateJavascript(
                    "(function(){return window.name?window.name:'[]';})()"
                ) { v ->
                    try {
                        val arr = org.json.JSONArray(
                            v.trim().removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
                        )
                        synchronized(captures) {
                            for (i in 0 until arr.length()) {
                                val s = arr.getString(i)
                                if (s.contains("orderList") && captures.none { it == s }) {
                                    captures.add(s)
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "js capture poll fail: $e")
                    }
                }
                val (orders, _) = convert(captures, account)
                val total = totalNum(captures)
                Log.i(TAG, "round=$rounds orders=${orders.size} total=$total captures=${captures.size}")
                if ((total > 0 && orders.size >= total) || rounds > 14) {
                    finish(orders, null)
                    return
                }
                swipeUp(w)
                handler.postDelayed(this, 3000)
            }
        }
        handler.post(runnable)
    }

    private fun swipeUp(w: WebView) {
        val width = w.width.toFloat()
        val height = w.height.toFloat()
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
        val up = MotionEvent.obtain(downTime, t + 30, MotionEvent.ACTION_UP,
            x, height * 0.08f, 0)
        w.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    private fun totalNum(captures: List<String>): Int {
        for (body in captures) {
            runCatching {
                val total = JSONObject(body).getJSONObject("body").optInt("totalNum", 0)
                if (total > 0) return total
            }
        }
        return 0
    }

    private fun convert(
        captures: List<String>,
        account: com.halo.expressassistant.data.BoundAccount?
    ): Pair<List<ExpressItem>, Map<String, JdGoods>> {
        val byId = LinkedHashMap<String, ExpressItem>()
        val goodsMap = HashMap<String, JdGoods>()
        synchronized(captures) {
            for (body in captures) {
                runCatching {
                    val ol = JSONObject(body).getJSONObject("body").optJSONArray("orderList") ?: return@runCatching
                    for (i in 0 until ol.length()) {
                        val o = ol.getJSONObject(i)
                        val orderId = o.optString("orderId")
                        if (orderId.isBlank()) continue
                        val status = o.optJSONObject("orderStatusInfo")
                        val statusName = status?.optString("orderStatusName") ?: ""
                        if (statusName.contains("取消") || statusName.contains("关闭") ||
                            statusName.contains("退款") || statusName.contains("售后") ||
                            statusName.contains("待付款") || statusName.contains("未支付")) {
                            continue
                        }
                        val prog = o.optJSONObject("progressInfo")
                        val latestText = (prog?.optString("content") ?: "")
                            .ifBlank { o.optJSONObject("orderStatusInfo")?.optString("stateTip") ?: "" }
                            .ifBlank { statusName }
                        // 物流页完整参数（无 progressLink 的订单用订单字段拼装）
                        val trackLink = (prog?.optString("progressLink") ?: "").ifBlank {
                            val skuId = o.optJSONArray("wareInfoList")?.optJSONObject(0)?.optString("skuId") ?: ""
                            val shopId = o.optJSONObject("shopInfo")?.optString("shopId") ?: ""
                            val dealState = o.optJSONObject("orderStatusInfo")?.optString("originOrderStatus") ?: ""
                            "https://trade.m.jd.com/order/deal_wuliu_jdm.shtml?from=orderdetail" +
                                    "&dealState=$dealState&dealId=$orderId&orderType=${o.optString("orderType")}" +
                                    "&skuid=$skuId&shopid=$shopId&source=m_inner_orderList.track_orderTrack"
                        }
                        val latestTime = prog?.optString("tip") ?: ""
                        val state = when {
                            statusName.contains("完成") || statusName.contains("签收") -> 3
                            statusName.contains("待收货") || statusName.contains("等待收货") -> 5
                            statusName.contains("待发货") -> 1
                            else -> 0
                        }
                        val stateNum = when (state) {
                            3 -> 107
                            5 -> 105
                            1 -> 103
                            else -> 105
                        }
                        val item = ExpressItem(
                            id = orderId,
                            companyCode = "JDKD",
                            companyName = "京东商品快递",
                            mailNo = orderId,
                            latestText = latestText,
                            latestTime = latestTime,
                            state = state,
                            stateName = statusName,
                            provider = "JingDong",
                            stateNum = stateNum,
                            queryChannel = trackLink,
                            source = "jd",
                            accountId = account?.id ?: "",
                            accountLabel = account?.label ?: ""
                        )
                        byId[orderId] = item
                        val wares = o.optJSONArray("wareInfoList")
                        if (wares != null && wares.length() > 0) {
                            val w0 = wares.getJSONObject(0)
                            goodsMap[orderId] = JdGoods(
                                name = w0.optString("wareName"),
                                imageUrl = w0.optString("imageUrl"),
                                count = "x" + w0.optInt("num", 1)
                            )
                        }
                    }
                }
            }
        }
        return byId.values.toList() to goodsMap
    }
}
