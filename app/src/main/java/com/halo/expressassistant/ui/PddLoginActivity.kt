package com.halo.expressassistant.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.halo.expressassistant.data.PddSession
import com.halo.expressassistant.data.Store
import org.json.JSONObject

/** SMS login in an independent browser profile; verify the session before binding. */
class PddLoginActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var profile: String
    private lateinit var web: WebView
    private lateinit var status: TextView
    private var checkingOrders = false
    private var saved = false
    private var destroyed = false

    private val poll = object : Runnable {
        override fun run() {
            if (destroyed || isFinishing || saved) return
            val cm = PddWeb.cookieManager(web)
            val cookie = cm.getCookie(PddWeb.ORDERS_URL).orEmpty()
            if (PddSession.hasCredentials(cookie)) {
                if (!checkingOrders) {
                    checkingOrders = true
                    status.text = "正在验证拼多多订单访问权限…"
                    web.loadUrl(PddWeb.ORDERS_URL)
                } else {
                    PddCapture.readCaptures(web) { captures ->
                        if (destroyed || isFinishing || saved) return@readCaptures
                        val valid = captures.any { (url, body) ->
                            url.contains("/aristotle/order_list") && PddCapture.authenticatedOrders(body)
                        }
                        if (valid) {
                            val fresh = cm.getCookie(PddWeb.ORDERS_URL).orEmpty()
                            if (PddSession.hasCredentials(fresh)) {
                                cm.flush()
                                Store.addPddAccount(this@PddLoginActivity, fresh, if (PddWeb.isolated) profile else "")
                                saved = true
                                android.util.Log.i("PddLogin", "verified order access; profileIsolation=${PddWeb.isolated}")
                                Toast.makeText(this@PddLoginActivity, "拼多多订单访问验证成功，已绑定账号", Toast.LENGTH_LONG).show()
                                setResult(RESULT_OK)
                                finish()
                            }
                        }
                    }
                }
            }
            handler.postDelayed(this, 1500)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        profile = savedInstanceState?.getString("pdd_profile")
            ?: PddWeb.PROFILE_PREFIX + java.util.UUID.randomUUID()
        WebView.setWebContentsDebuggingEnabled(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
        web = PddWeb.create(this, profile)
        status = TextView(this).apply {
            text = "请使用手机号和短信验证码登录拼多多。登录后会验证订单访问权限。"
            setPadding(24, 24, 24, 24)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(status)
            addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val uri = Uri.parse(url)
                android.util.Log.i("PddLogin", "page: ${uri.host}${uri.path}")
                if (checkingOrders && uri.path?.contains("login") == true) {
                    checkingOrders = false
                    status.text = "登录态未通过验证，请在页面内重新使用短信验证码登录。"
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest) =
                PddWeb.blockNavigation(request.url.toString())

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String?) =
                PddWeb.blockNavigation(url.orEmpty())
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            // Do not capture SMS requests, codes or login responses.
            WebViewCompat.addDocumentStartJavaScript(web,
                "if (location.pathname === '/orders.html') {" + PddCapture.HOOK_JS + "}",
                PddCapture.COOKIE_HOSTS.toSet())
        } else {
            status.text = "当前系统 WebView 不支持订单验证，请更新 Android System WebView 后重试。"
            return
        }
        val openLogin = {
            if (!destroyed && !isFinishing) {
                web.loadUrl("https://mobile.yangkeduo.com/login.html?_x_no_login_launch=1&from=" +
                    Uri.encode(PddWeb.ORDERS_URL))
                handler.postDelayed(poll, 1500)
            }
        }
        if (PddWeb.isolated) openLogin() else PddCapture.clearCookies(onDone = openLogin)
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        if (::web.isInitialized) {
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            web.destroy()
        }
        if (!saved && !isChangingConfigurations) PddWeb.clearProfile(profile)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pdd_profile", profile)
        super.onSaveInstanceState(outState)
    }
}
