package com.halo.expressassistant.ui

import android.app.Activity
import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.halo.expressassistant.data.JdGoods
import com.halo.expressassistant.data.Store

/**
 * 京东商品溯源解析器：用一个隐藏 WebView 加载京东订单中心，
 * 模拟真实触摸事件驱动“搜索我的订单”界面，把物流单号解析成订单商品（名称+图片）。
 *
 * 流程：订单中心 → 点搜索条 → 输入单号 → 点搜索 → 从 DOM 抓商品名/图 → 回调。
 * 不依赖 adb / root，事件经 WebView.dispatchTouchEvent 以真实触摸管道注入页面。
 */
object JdOrderResolver {

    private const val TAG = "JdResolver"
    private const val ORDER_CENTER = "https://wqs.jd.com/order/orderlist_merge.shtml"

    private data class QEntry(
        val act: Activity,
        val cookies: String,
        val mailNo: String,
        val cb: (JdGoods?) -> Unit
    )

    private val handler = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var busy = false
    private var started = false
    private var searchScraped = false
    private val queue = ArrayDeque<QEntry>()

    fun resolve(act: Activity, mailNo: String, callback: (JdGoods?) -> Unit) =
        resolveWith(act, Store.jdCookies(act), mailNo, callback)

    /** 多源绑定：按指定京东账号凭证解析商品 */
    fun resolveWith(act: Activity, jdCookies: String, mailNo: String, callback: (JdGoods?) -> Unit) {
        handler.post {
            queue.addLast(QEntry(act, jdCookies, mailNo, callback))
            next()
        }
    }

    private fun next() {
        if (busy || queue.isEmpty()) return
        val e = queue.removeFirst()
        busy = true
        start(e.act, e.cookies, e.mailNo, e.cb)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun start(act: Activity, jdCookies: String, mailNo: String, cb: (JdGoods?) -> Unit) {
        val w = WebView(act)
        web = w
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
        // 隐藏挂载到 decor 底部：拿到真实布局尺寸，但不遮挡界面、不抢触摸
        try {
            val decor = act.window.decorView as? ViewGroup
            decor?.addView(
                w, 0,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        } catch (e: Throwable) {
            Log.w(TAG, "attach fail: $e")
        }
        handler.postDelayed({ w.requestFocus() }, 500)
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                Log.i(TAG, "page: $url")
                if (url.contains("orderType=search")) {
                    if (searchScraped) return
                    searchScraped = true
                    handler.postDelayed({ scrapeResult(view, mailNo, cb) }, 4000)
                    return
                }
                if (started) return
                if (url.contains("orderlist_jdm.shtml")) {
                    started = true
                    // 直接导航到搜索页，用 URL 参数带单号，跳过输入框交互
                    handler.postDelayed({
                        val searchUrl = "https://trade.m.jd.com/order/orderlist_jdm.shtml?orderType=search&searchKey=$mailNo&sceneval="
                        Log.i(TAG, "goto search: $searchUrl")
                        view.loadUrl(searchUrl)
                    }, 1500)
                }
            }
        }
        w.loadUrl(ORDER_CENTER)
    }

    private fun scrapeResult(w: WebView, mailNo: String, cb: (JdGoods?) -> Unit) {
        w.evaluateJavascript(
            """(function(){
                var imgs=[].slice.call(document.querySelectorAll('img')).map(function(i){return i.src;}).filter(function(s){return s.indexOf('360buyimg')>=0;});
                var lines=document.body.innerText.split('\n').map(function(s){return s.trim();}).filter(function(s){return s.length>0;});
                return JSON.stringify({url:location.href,imgs:imgs,lines:lines});
            })()"""
        ) { value ->
            Log.i(TAG, "scrape: ${value.take(200)}")
            val goods = parseGoods(value, mailNo)
            finish(cb, goods)
        }
    }

    private fun jsJson(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length - 1)
            s = s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n")
        }
        return s
    }

    private fun parseGoods(raw: String, mailNo: String): JdGoods? {
        return try {
            val root = org.json.JSONObject(jsJson(raw))
            val url = root.optString("url")
            if (!url.contains(mailNo)) return null
            val lines = root.optJSONArray("lines") ?: return null
            val imgs = root.optJSONArray("imgs") ?: return null
            // 第一张订单卡：以第一个“实付”行为界，卡内最长行为商品名，xN 为数量
            var priceIdx = -1
            for (i in 0 until lines.length()) {
                if (lines.getString(i).contains("实付")) {
                    priceIdx = i
                    break
                }
            }
            if (priceIdx < 0) return null
            var name = ""
            var count = ""
            for (i in 0..priceIdx) {
                val line = lines.getString(i).trim()
                if (line.matches(Regex("x\\d+")) && count.isEmpty()) {
                    count = line
                    continue
                }
                if (line.length in 6..120 && line.length > name.length &&
                    !line.contains("实付") && !line.contains("¥") && !line.contains("￥")
                ) {
                    name = line
                }
            }
            val imgList = (0 until imgs.length()).map { imgs.getString(it) }
            val img = imgList.firstOrNull { it.contains("/n0/") || it.contains("/n1/") || it.contains("/n2/") }
                ?: imgList.firstOrNull { it.length > 40 && !it.contains("storage.") }
                ?: ""
            if (name.isBlank()) null else JdGoods(name = name, imageUrl = img, count = count)
        } catch (e: Throwable) {
            Log.w(TAG, "parse fail: $e")
            null
        }
    }





    private fun finish(cb: (JdGoods?) -> Unit, goods: JdGoods?) {
        handler.postDelayed({
            try {
                web?.let { (it.parent as? ViewGroup)?.removeView(it) }
                web?.destroy()
            } catch (e: Throwable) {
                Log.w(TAG, "cleanup fail: $e")
            }
            web = null
            busy = false
            started = false
            searchScraped = false
            cb(goods)
            next()
        }, 300)
    }
}
