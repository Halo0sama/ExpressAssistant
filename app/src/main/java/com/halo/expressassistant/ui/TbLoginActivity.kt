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
 * 淘宝 H5 登录：用户在 WebView 内完成登录，抓取 .taobao.com 登录 cookie 存本地，
 * 用于「菜鸟溯源」（物流单号 -> 订单商品名/图，需买家登录态）。
 */
class TbLoginActivity : Activity() {

    companion object {
        private const val TAG = "TbLogin"
        private const val LOGIN_URL = "https://login.m.taobao.com/login.htm"
    }

    private var web: WebView? = null
    private lateinit var output: TextView
    private var working = false
    private var pollHandler: Handler? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            val h = pollHandler ?: return
            if (working || isFinishing) return
            val cookies = CookieManager.getInstance().getCookie("https://h5.m.taobao.com") ?: ""
            val map = parseCookies(cookies)
            val loggedIn = !map["cookie2"].isNullOrBlank() && (!map["unb"].isNullOrBlank() || !map["cookie1"].isNullOrBlank())
            if (loggedIn) {
                tryLogin(cookies)
                return
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
            text = "正在打开淘宝登录页…\n请在页面内完成登录（密码 / 验证码 / 扫码均可）\n"
            maxHeight = (90 * resources.displayMetrics.density).toInt()
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

        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                append("页面: $url\n")
                Log.i(TAG, "page: $url")
                logCookieKeys()
            }
        }
        w.webChromeClient = object : WebChromeClient() {}

        pollHandler = Handler(Looper.getMainLooper()).also { it.postDelayed(pollRunnable, 2000) }
        w.loadUrl(LOGIN_URL)
    }

    private fun tryLogin(cookies: String) {
        if (working) return
        working = true
        Log.i(TAG, "tryLogin cookie keys: " + parseCookies(cookies).keys.joinToString(","))
        Store.saveTbCookies(this, cookies)
        runOnUiThread {
            Toast.makeText(this, "淘宝登录成功，已保存凭证", Toast.LENGTH_LONG).show()
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
        val cookies = CookieManager.getInstance().getCookie("https://h5.m.taobao.com") ?: ""
        val sb = StringBuilder("cookie keys:")
        for (part in cookies.split(";")) {
            val k = part.trim().split("=", limit = 2)[0]
            if (k.isNotEmpty()) sb.append(' ').append(k)
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
