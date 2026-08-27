package com.halo.expressassistant.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.MaterialColors
import com.halo.expressassistant.data.Store

/**
 * 云雀内置浏览器：打开原平台订单页 + 通用网页浏览。
 * 登录态策略（为什么不会反复要求登录）：
 *  1. 启动时把「账号 Cookie（同步回存的）+ Cookie 罐（上次浏览结束时的页面会话）」注入 WebView；
 *  2. 每次页面加载完把当前渠道的全部 Cookie 收进罐里持久化（session cookie 本来活不过进程被杀，
 *     罐把它落盘，下次以 Max-Age 重新注入——等价于普通浏览器的 cookie 恢复）。
 * 京东特殊处理：会话过期跳 plogin 登录页 → 提示登录一次，登录成功自动回存全域 Cookie（抓取器同时复活）；
 * 单页被弹京东首页（快照参数校验失败）→ 纠偏一次转订单列表。
 */
class SourceWebActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_TITLE = "title"
        const val EXTRA_COOKIES = "cookies"
        const val EXTRA_ACCOUNT_ID = "accountId"
        private const val COOKIE_MAX_AGE = "Max-Age=7776000" // 90 天

        /** 各渠道需要注入/收集 Cookie 的域（与各抓取器的注入域保持一致） */
        fun domainsFor(source: String): List<String> = when (source) {
            "jd" -> listOf(
                "https://trade.m.jd.com", "https://wqs.jd.com", "https://api.m.jd.com",
                "https://www.jd.com", "https://m.jd.com", "https://plogin.m.jd.com",
                "https://tuihuan.jd.com", "https://afs.m.jd.com"
            )
            "taobao" -> listOf(
                "https://main.m.taobao.com", "https://h5api.m.taobao.com",
                "https://m.taobao.com", "https://www.taobao.com"
            )
            "pdd" -> listOf("https://mobile.yangkeduo.com", "https://yangkeduo.com")
            else -> emptyList()
        }
    }

    private lateinit var web: WebView
    private lateinit var urlBar: EditText
    private lateinit var progress: android.widget.ProgressBar
    private var source = ""
    private var accountId = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        source = intent.getStringExtra(EXTRA_SOURCE).orEmpty()
        accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID).orEmpty()
        if (url.isBlank()) {
            finish()
            return
        }
        val dp = resources.displayMetrics.density
        val iconColor = MaterialColors.getColor(this, android.R.attr.textColorPrimary, Color.BLACK)

        fun ripple(): Int {
            val tv = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            return tv.resourceId
        }

        fun barIcon(res: Int, desc: String, onClick: () -> Unit): ImageView = ImageView(this).apply {
            setImageResource(res)
            setColorFilter(iconColor)
            setBackgroundResource(ripple())
            setPadding((dp * 7).toInt(), (dp * 7).toInt(), (dp * 7).toInt(), (dp * 7).toInt())
            isClickable = true
            isFocusable = true
            contentDescription = desc
            setOnClickListener { onClick() }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(MaterialColors.getColor(this@SourceWebActivity, android.R.attr.colorBackground, Color.WHITE))
        }

        // ── 浏览器工具栏：关闭 | 后退 前进 刷新 | 地址栏 ──
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((dp * 6).toInt(), (dp * 8).toInt(), (dp * 6).toInt(), (dp * 8).toInt())
        }
        bar.addView(barIcon(com.halo.expressassistant.R.drawable.ic_close, "关闭") { finish() })
        bar.addView(barIcon(com.halo.expressassistant.R.drawable.ic_back, "后退") {
            if (web.canGoBack()) web.goBack()
        })
        bar.addView(barIcon(com.halo.expressassistant.R.drawable.ic_refresh, "刷新") {
            web.reload()
        })
        bar.addView(barIcon(com.halo.expressassistant.R.drawable.ic_chevron_right, "前进") {
            if (web.canGoForward()) web.goForward()
        })
        urlBar = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = (dp * 6).toInt() }
            textSize = 12f
            maxLines = 1
            imeOptions = EditorInfo.IME_ACTION_GO
            setSingleLine(true)
            setTextColor(iconColor)
            hint = "输入网址"
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_GO) {
                    val raw = text.toString().trim()
                    if (raw.isNotEmpty()) {
                        web.loadUrl(if (raw.startsWith("http")) raw else "https://$raw")
                    }
                    true
                } else {
                    false
                }
            }
        }
        bar.addView(urlBar)
        root.addView(bar)

        progress = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (dp * 3).toInt())
            max = 100
        }
        root.addView(progress)

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            // 与 JdListFetcher 同款干净 Chrome UA：默认 WebView UA 带 "; wv)" 尾巴，部分京东页面区别对待
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 16; 25102RKBEC Build/BP2A.250605.031.A3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            webViewClient = appClient()
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    this@SourceWebActivity.progress.progress = newProgress
                    this@SourceWebActivity.progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                }

                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    android.util.Log.i(
                        "SourceWebJS",
                        "[${m.messageLevel()}] ${m.message()} @${m.sourceId()}:${m.lineNumber()}"
                    )
                    return true
                }
            }
        }

        // ── 登录态恢复：优先只注入 Cookie 罐（上次浏览会话，自洽的一套）；
        // 账号 payload 是抓取器多次回存累积的大杂烩（200+ 条含跨会话风控残渣），
        // 整套注入会触发京东风控弹登录，只在无罐时作首次回退 ──
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)
        val primaryCookie = Store.browserCookieJar(this)[source].orEmpty()
            .ifBlank { intent.getStringExtra(EXTRA_COOKIES).orEmpty() }
        if (primaryCookie.isNotBlank()) {
            val merged = LinkedHashMap<String, String>()
            primaryCookie.split(";").forEach { part ->
                val kv = part.trim().split("=", limit = 2)
                if (kv.size == 2 && kv[0].isNotBlank()) merged[kv[0]] = kv[1]
            }
            domainsFor(source).forEach { domain ->
                merged.forEach { (k, v) ->
                    cm.setCookie(domain, "$k=$v; Path=/; $COOKIE_MAX_AGE")
                }
            }
            cm.flush()
            android.util.Log.i(
                "SourceWeb",
                "inject done: total=${merged.size} pt_key_back=${cm.getCookie("https://trade.m.jd.com")?.contains("pt_key")}"
            )
        }

        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        // 直开目标页：罐里有有效页面会话则一步直达；会话死则 plogin 兜底（Toast 提示登录，
        // 重定向循环由 onReceivedError 清 Cookie 恢复，被弹首页由 jdGuards 纠偏）。
        // 曾试过先开订单列表 warmup 再跳单页，实测列表页自身可能白屏卡住反成阻塞，弃用。
        web.loadUrl(url)
    }

    private fun appClient(): WebViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, visitUrl: String) {
            urlBar.setText(visitUrl)
            val t = view.title
            if (!t.isNullOrBlank()) title = t
            collectCookiesToJar()
            if (source == "jd") jdGuards(view, visitUrl)
        }

        override fun onReceivedError(
            view: WebView,
            request: android.webkit.WebResourceRequest,
            error: android.webkit.WebResourceError
        ) {
            // 半失效的残留 Cookie 会让登录页陷入重定向循环：清干净后直载标准登录页（带返回地址）
            if (source == "jd" &&
                (error.errorCode == -15 || // ERROR_TOO_MANY_REDIRECTS
                    error.description?.toString()?.contains("TOO_MANY_REDIRECTS") == true)
            ) {
                val cm = CookieManager.getInstance()
                cm.removeAllCookies(null)
                cm.flush()
                val ret = java.net.URLEncoder.encode("https://trade.m.jd.com/orderlist_jdm.shtml", "UTF-8")
                view.loadUrl("https://plogin.m.jd.com/login/login?appid=300&returnurl=$ret")
            }
        }
    }

    /** 页面加载完 → 把本渠道当前全部 Cookie 收进罐（持久化页面会话） */
    private fun collectCookiesToJar() {
        if (domainsFor(source).isEmpty()) return
        val cm = CookieManager.getInstance()
        val all = LinkedHashMap<String, String>()
        domainsFor(source).forEach { u ->
            cm.getCookie(u)?.split(";")?.forEach { part ->
                val kv = part.trim().split("=", limit = 2)
                if (kv.size == 2 && kv[0].isNotBlank()) all.putIfAbsent(kv[0], kv[1])
            }
        }
        if (all.isNotEmpty()) {
            Store.saveBrowserCookies(this, source, all.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }
    }

    private var sawLogin = false
    private var hinted = false
    private var homeCorrected = false

    private fun jdGuards(view: WebView, visitUrl: String) {
        val u = visitUrl.substringBefore('?')
        when {
            u.contains("plogin.m.jd.com") || u.endsWith("/login") || u.contains("nopasswordcmcc") -> {
                sawLogin = true
                if (!hinted) {
                    hinted = true
                    Toast.makeText(
                        this,
                        "京东会话已过期：在页面登录一次即可直达订单页（登录后同步也会恢复）",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            sawLogin && (u.contains("orderlist") || u.contains("deal_wuliu")) -> {
                sawLogin = false
                saveJdCookiesBack()
            }
            !homeCorrected && u.matches(Regex("https?://(www\\.)?m\\.jd\\.com/?")) -> {
                // 被弹回京东首页（快照参数校验失败等）：转跳订单列表兜底（只纠一次防循环）
                homeCorrected = true
                view.loadUrl("https://trade.m.jd.com/orderlist_jdm.shtml")
            }
        }
    }

    /** 京东登录成功后回存全域 Cookie：绑定账号优先，无账号落全局键 */
    private fun saveJdCookiesBack() {
        val cm = CookieManager.getInstance()
        val merged = LinkedHashMap<String, String>()
        listOf("https://www.jd.com", "https://trade.m.jd.com", "https://api.m.jd.com", "https://plogin.m.jd.com")
            .forEach { u ->
                cm.getCookie(u)?.split(";")?.forEach { part ->
                    val kv = part.trim().split("=", limit = 2)
                    if (kv.size == 2 && kv[0].isNotBlank()) merged.putIfAbsent(kv[0], kv[1])
                }
            }
        if (merged.isEmpty()) return
        val cookieStr = merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val account = accountId.takeIf { it.isNotBlank() }
            ?.let { id -> Store.accounts(this, Store.CH_JD).firstOrNull { it.id == id } }
        if (account != null) {
            Store.updateAccount(this, Store.CH_JD, account.copy(payload = Store.cookiePayload(cookieStr)))
        } else {
            Store.saveJdCookies(this, cookieStr)
        }
        Toast.makeText(this, "京东登录已保存，同步与订单页直达已恢复", Toast.LENGTH_SHORT).show()
    }

    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        collectCookiesToJar()
        super.onDestroy()
    }
}
