package com.halo.expressassistant.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.halo.expressassistant.data.Store

/**
 * 拼多多 H5 登录：用户在 WebView 内完成登录（手机号验证码 / 微信授权），
 * 轮询 CookieManager 抓取 PDDAccessToken / pdduid 等拼多多登录态存本地，
 * 用于「拼多多快递」同步（订单/包裹列表、物流轨迹、商品名/图）。
 *
 * 逆向说明：与京东/淘宝同一模式，仅学习用途，遵守平台服务条款。
 */
class PddLoginActivity : Activity() {

    companion object {
        private const val TAG = "PddLogin"
        private const val LOGIN_URL = "https://mobile.yangkeduo.com/login.html"
        // 登录前要清空的拼多多域（避免复用旧死会话产生假登录）
        private val PDD_HOSTS = listOf(
            "https://mobile.yangkeduo.com",
            "https://yangkeduo.com",
            "https://pinduoduo.com",
            "https://mobile.pinduoduo.com",
            "https://passport.pinduoduo.com"
        )
    }

    private var web: WebView? = null
    private lateinit var output: TextView
    private var working = false
    private var pollHandler: Handler? = null
    private var pollTicks = 0

    private val pollRunnable = object : Runnable {
        override fun run() {
            val h = pollHandler ?: return
            if (working || isFinishing) return
            pollTicks++
            val cookies = CookieManager.getInstance().getCookie("https://mobile.yangkeduo.com") ?: ""
            val map = parseCookies(cookies)
            val loggedIn = !map["PDDAccessToken"].isNullOrBlank() &&
                    (!map["pdduid"].isNullOrBlank() ||
                        !map["PASS_ID"].isNullOrBlank() ||
                        !map["pdd_user_id"].isNullOrBlank() ||
                        !map["pdd_user_uin"].isNullOrBlank())
            if (loggedIn) {
                tryLogin(cookies)
                return
            }
            // 30 秒还没登录态时给出提示（拼多多有时要求先完成 App 内实名/验证）
            if (pollTicks == 15) {
                append("还没有检测到登录态…\n请确认已通过手机号验证码或微信完成授权；" +
                        "若页面一直无法登录，请在拼多多 App 内完成实名与安全验证后再试。\n")
            }
            append("等待登录态…（cookie 数 ${cookies.split(";").count { it.isNotBlank() }}）\n")
            h.postDelayed(this, 2000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        output = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(8, 8, 8, 8)
            text = "正在打开拼多多登录页…\n请在页面内完成登录（手机号验证码 / 微信授权均可）\n"
            maxHeight = (110 * resources.displayMetrics.density).toInt()
            movementMethod = ScrollingMovementMethod()
        }
        root.addView(output, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val w = WebView(this)
        web = w
        root.addView(w, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.userAgentString = "Mozilla/5.0 (Linux; Android 16; 25102RKBEC Build/BP2A.250605.031.A3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(w, true)
        // 统一根治：登录页打开即清全部 WebView Cookie（域级 cookie 按 host expire 删不掉，必须 removeAllCookies）
        cm.removeAllCookies(null)
        cm.flush()

        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                append("页面: $url\n")
                Log.i(TAG, "page: $url")
                logCookieKeys()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                return blockScheme(request.url.toString())
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean {
                return blockScheme(url.orEmpty())
            }
        }
        w.webChromeClient = object : WebChromeClient() {}

        pollHandler = Handler(Looper.getMainLooper()).also { it.postDelayed(pollRunnable, 2000) }
        w.loadUrl(LOGIN_URL)
    }

    /** 拦截 pinduoduo:// App deep link（页面 JS 强制跳 App；阻止后留在 H5 登录页） */
    private fun blockScheme(u: String): Boolean {
        if (u.startsWith("pinduoduo://") || u.startsWith("xunmeng://") || u.startsWith("weixin://mobile")) {
            Log.i(TAG, "block app scheme: ${u.take(80)}")
            return true
        }
        return false
    }

    private fun tryLogin(cookies: String) {
        if (working) return
        working = true
        Log.i(TAG, "tryLogin cookie keys: " + parseCookies(cookies).keys.joinToString(","))
        // 多源绑定：每次登录 = 追加一个可绑定的账号
        Store.addPddAccount(this, cookies)
        runOnUiThread {
            Toast.makeText(this, "拼多多登录成功，已绑定新账号", Toast.LENGTH_LONG).show()
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun parseCookies(cookieHeader: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (part in cookieHeader.split(";")) {
            val kv = part.trim().split("=", limit = 2)
            if (kv.size == 2) map[kv[0]] = kv[1]
        }
        return map
    }

    private fun logCookieKeys() {
        val cookies = CookieManager.getInstance().getCookie("https://mobile.yangkeduo.com") ?: ""
        val sb = StringBuilder("cookie keys:")
        for (part in cookies.split(";")) {
            val k = part.trim().split("=", limit = 2)[0]
            if (k.isNotEmpty()) {
                sb.append(' ').append(k).append(
                    if (k == "PDDAccessToken" || k == "PASS_ID") "(len=${part.substringAfter('=').length})" else ""
                )
            }
        }
        Log.i(TAG, sb.toString())
    }

    private fun append(s: String) {
        runOnUiThread { output.append(s) }
    }

    override fun onDestroy() {
        pollHandler?.removeCallbacks(pollRunnable)
        web?.destroy()
        super.onDestroy()
    }
}
