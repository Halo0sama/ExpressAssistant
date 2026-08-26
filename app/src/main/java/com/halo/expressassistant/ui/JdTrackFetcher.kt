package com.halo.expressassistant.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.halo.expressassistant.data.DetailPoint
import com.halo.expressassistant.data.ExpressDetail
import com.halo.expressassistant.data.ExpressItem
import com.halo.expressassistant.data.Store
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 京东件轨迹（source=jd 时使用）：隐藏 WebView 加载京东物流详情页
 * deal_wuliu_jdm.shtml，从渲染后的 DOM 提取轨迹行（time + desc）。
 */
object JdTrackFetcher {

    private const val TAG = "JdTrackFetcher"
    private val handler = Handler(Looper.getMainLooper())

    suspend fun fetch(act: Activity, item: ExpressItem): ExpressDetail =
        fetchWith(act, item, Store.jdCookies(act))

    /** 多源绑定：按指定京东账号凭证取轨迹 */
    suspend fun fetchWith(act: Activity, item: ExpressItem, jdCookies: String): ExpressDetail =
        suspendCancellableCoroutine { cont ->
            handler.post {
                start(act, item, jdCookies) { detail, err ->
                    if (cont.isActive) {
                        if (detail != null) cont.resume(detail)
                        else cont.resume(
                            ExpressDetail(
                                mailNo = item.mailNo,
                                companyName = item.companyName,
                                state = item.state,
                                isReceived = item.state == 3,
                                data = emptyList(),
                                eta = item.eta
                            )
                        )
                    }
                }
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    private fun start(act: Activity, item: ExpressItem, jdCookies: String, cb: (ExpressDetail?, String?) -> Unit) {
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
        for (part in jdCookies.split(";")) {
            val kv = part.trim().split("=", limit = 2)
            if (kv.size == 2 && kv[0].isNotBlank()) {
                cm.setCookie("https://www.jd.com", "${kv[0]}=${kv[1]}")
                cm.setCookie("https://trade.m.jd.com", "${kv[0]}=${kv[1]}")
                cm.setCookie("https://api.m.jd.com", "${kv[0]}=${kv[1]}")
            }
        }
        cm.flush()
        try {
            val decor = act.window.decorView as? ViewGroup
            decor?.addView(w, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } catch (e: Throwable) {
            Log.w(TAG, "attach fail: $e")
        }

        fun finish(detail: ExpressDetail?, err: String?) {
            if (finished) return
            finished = true
            handler.postDelayed({
                try {
                    (w.parent as? ViewGroup)?.removeView(w)
                    w.destroy()
                } catch (e: Throwable) {
                    Log.w(TAG, "cleanup fail: $e")
                }
                cb(detail, err)
            }, 200)
        }

        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                Log.i(TAG, "page: $url")
                if (url.contains("deal_wuliu")) {
                    handler.postDelayed({
                        view.evaluateJavascript(
                            "(function(){var t=document.body?document.body.innerText:'';return JSON.stringify(t.slice(0,20000));})()"
                        ) { value ->
                            val detail = parseDetail(value, item)
                            Log.i(TAG, "scraped points=${detail.data.size}")
                            finish(detail, null)
                        }
                    }, 5000)
                }
            }
        }
        val url = if (item.queryChannel.contains("deal_wuliu")) {
            // 列表里带出的完整物流页链接
            if (item.queryChannel.startsWith("//")) "https:${item.queryChannel}" else item.queryChannel
        } else {
            "https://trade.m.jd.com/order/deal_wuliu_jdm.shtml?dealId=${item.mailNo}"
        }
        w.loadUrl(url)
        handler.postDelayed({
            if (!finished) finish(
                ExpressDetail(item.mailNo, item.companyName, item.state, item.state == 3, emptyList(), item.eta),
                "京东轨迹加载超时"
            )
        }, 20000)
    }

    private fun parseDetail(raw: String, item: ExpressItem): ExpressDetail {
        var text = raw.trim()
        if (text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length - 1).replace("\\n", "\n")
        }
        val points = ArrayList<DetailPoint>()
        val timeRe = Regex("""^(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}(?::\d{2})?)""")
        val noise = setOf("订单跟踪", "联系客服", "复制", "展开详细信息", "仓库", "签", "派", "等待收货", "已发货")
        val skipRe = Regex("""(订单编号|运单号|国内承运人|联系电话|^\d+件$|¥|评论|已售)""")
        val kw = listOf("快件", "包裹", "派件", "揽收", "签收", "发往", "到达", "转运", "投递", "取件", "出库", "配送", "妥投", "物流", "运输")
        fun looksLikeDesc(l: String) = l.contains("【") || kw.any { l.contains(it) }
        var pending: StringBuilder? = null
        for (line in text.split("\n")) {
            val l = line.trim()
            if (l.isEmpty()) continue
            val m = timeRe.find(l)
            if (m != null && l.length <= 25) {
                // 时间行：提交上一条 desc
                val t = m.groupValues[1]
                val d = pending?.toString()?.trim().orEmpty()
                if (d.isNotBlank() && d.length >= 4) {
                    points.add(DetailPoint(context = d, time = t, formattedTime = t))
                }
                pending = null
                continue
            }
            if (l in noise || skipRe.containsMatchIn(l)) continue
            if (pending == null) {
                if (looksLikeDesc(l)) pending = StringBuilder(l)
            } else {
                pending.append(" ").append(l)
            }
        }
        return ExpressDetail(
            mailNo = item.mailNo,
            companyName = item.companyName,
            state = item.state,
            isReceived = item.state == 3,
            data = points,
            eta = item.eta
        )
    }
}
