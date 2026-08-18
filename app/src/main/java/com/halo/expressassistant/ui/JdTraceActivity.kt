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
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import com.halo.expressassistant.data.Store

/**
 * 溯源实验页：带京东登录态加载 jingfen 溯源链接，
 * 抓取页面 DOM / 全局状态 / 网络请求，日志 tag=JdTrace。
 */
class JdTraceActivity : Activity() {

    companion object {
        private const val TAG = "JdTrace"
        private const val HOOK_JS = """
            (function(){
              var seq = 0;
              function report(tag, url, status, body){
                try {
                  var full = String(body);
                  var total = full.length;
                  var chunks = Math.ceil(total / 3000);
                  for (var i = 0; i < chunks; i++) {
                    var c = full.slice(i * 3000, (i + 1) * 3000);
                    console.log('JDHOOK ' + tag + ' ' + url.slice(0, 200) + ' STATUS=' + status + ' CHUNK=' + i + '/' + chunks + ' LEN=' + total + ' BODY=' + c);
                  }
                } catch(e){}
              }
              var origFetch = window.fetch;
              if (origFetch) {
                window.fetch = function(input, init){
                  var p = origFetch.apply(this, arguments);
                  if (p && p.then) {
                    p.then(function(resp){
                      try {
                        var url = typeof input === 'string' ? input : (input && input.url) || '';
                        resp.clone().text().then(function(t){ report('FETCH', url, resp.status, t); });
                      } catch(e){}
                    });
                  }
                  return p;
                };
              }
              var origOpen = XMLHttpRequest.prototype.open;
              var origSend = XMLHttpRequest.prototype.send;
              XMLHttpRequest.prototype.open = function(m, u){ this.__u = u; return origOpen.apply(this, arguments); };
              XMLHttpRequest.prototype.send = function(){
                var self = this;
                this.addEventListener('load', function(){
                  try { report('XHR', self.__u, self.status, self.responseText); } catch(e){}
                });
                return origSend.apply(this, arguments);
              };
              // JSONP 拦截：改写 callback 参数到我们自己的钩子
              window.__jdhookcb = function(data){
                try {
                  var s = JSON.stringify(data);
                  var total = s.length;
                  var chunks = Math.ceil(total / 3000);
                  for (var i = 0; i < chunks; i++) {
                    console.log('JDHOOK JSONP CHUNK=' + i + '/' + chunks + ' LEN=' + total + ' BODY=' + s.slice(i*3000, (i+1)*3000));
                  }
                } catch(e){}
              };
              var origCreate = document.createElement.bind(document);
              document.createElement = function(tag){
                var el = origCreate(tag);
                if (String(tag).toLowerCase() === 'script') {
                  var origSet = el.setAttribute.bind(el);
                  el.setAttribute = function(k, v){
                    if (k === 'src' && /callback=/.test(String(v))) {
                      v = String(v).replace(/callback=[a-zA-Z0-9_]+/, 'callback=__jdhookcb');
                    }
                    return origSet(k, v);
                  };
                }
                return el;
              };
            })();
        """
    }

    private var web: WebView? = null
    private lateinit var output: TextView
    private var dumped = false
    private val interceptClient = okhttp3.OkHttpClient.Builder().build()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(true)

        val url = intent.getStringExtra("url")
        val mailNo = intent.getStringExtra("mailNo") ?: "?"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        output = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(8, 8, 8, 8)
            text = "JdTrace mailNo=$mailNo\n$url\n\n"
            maxHeight = (120 * resources.displayMetrics.density).toInt()
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
        // 注入京东登录 cookie
        val jdCookies = Store.jdCookies(this)
        if (jdCookies.isBlank()) {
            append("⚠️ 没有京东登录态，先去设置里做「京东登录」\n")
        } else {
            for (part in jdCookies.split(";")) {
                val kv = part.trim().split("=", limit = 2)
                if (kv.size == 2 && kv[0].isNotBlank()) {
                    cm.setCookie("https://www.jd.com", "${kv[0]}=${kv[1]}")
                    cm.setCookie("https://jingfen.jd.com", "${kv[0]}=${kv[1]}")
                    cm.setCookie("https://u.jd.com", "${kv[0]}=${kv[1]}")
                    cm.setCookie("https://trade.m.jd.com", "${kv[0]}=${kv[1]}")
                    cm.setCookie("https://wqs.jd.com", "${kv[0]}=${kv[1]}")
                    cm.setCookie("https://api.m.jd.com", "${kv[0]}=${kv[1]}")
                }
            }
            cm.flush()
            append("已注入京东 cookie（${jdCookies.split(";").count { it.isNotBlank() }} 条）\n")
        }
        // 注入淘宝登录 cookie
        val tbCookies = Store.tbCookies(this)
        if (tbCookies.isBlank()) {
            append("⚠️ 没有淘宝登录态\n")
        } else {
            for (part in tbCookies.split(";")) {
                val kv = part.trim().split("=", limit = 2)
                if (kv.size == 2 && kv[0].isNotBlank()) {
                    cm.setCookie("https://h5.m.taobao.com", "${kv[0]}=${kv[1]}")
                    cm.setCookie("https://acs.m.taobao.com", "${kv[0]}=${kv[1]}")
                    cm.setCookie("https://h5api.m.taobao.com", "${kv[0]}=${kv[1]}")
                }
            }
            cm.flush()
            append("已注入淘宝 cookie（${tbCookies.split(";").count { it.isNotBlank() }} 条）\n")
        }

        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url2: String) {
                append("页面: $url2\n")
                Log.i(TAG, "page: $url2")
                if (!dumped) {
                    dumped = true
                    Handler(Looper.getMainLooper()).postDelayed({ dumpDom() }, 6000)
                    Handler(Looper.getMainLooper()).postDelayed({ dumpDom() }, 14000)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url.toString()
                if (u.startsWith("openapp.") || u.startsWith("jdmobile")) {
                    Log.i(TAG, "BLOCK_APP_JUMP $u")
                    append("已拦截拉起京东App\n")
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url.toString()
                val interesting = u.contains("api") || u.contains("track") || u.contains("order") ||
                        u.contains("item") || u.contains("json") || u.contains("jingfen")
                if (interesting) {
                    Log.i(TAG, "REQ ${request.method} ${u.take(800)}")
                    append("REQ ${request.method} ${u.take(120)}\n")
                }
                if (u.contains("api.m.jd.com") || u.contains("acs.m.taobao.com") || u.contains("h5api.m.taobao.com")) {
                    return try {
                        val builder = okhttp3.Request.Builder().url(u)
                        for ((name, value) in request.requestHeaders) {
                            builder.header(name, value)
                        }
                        val cookies = CookieManager.getInstance().getCookie(u)
                        if (cookies != null) builder.header("Cookie", cookies)
                        val resp = interceptClient.newCall(builder.build()).execute()
                        val body = resp.body?.string().orEmpty()
                        val fn = Regex("functionId=([a-zA-Z0-9_]+)").find(u)?.groupValues?.get(1)
                            ?: Regex("/mtop\\.([a-zA-Z0-9_.]+)/").find(u)?.groupValues?.get(1) ?: "?"
                        val file = java.io.File(filesDir, "ic_${fn}_${System.currentTimeMillis()}.json")
                        file.writeText(body)
                        val ct = resp.header("Content-Type") ?: "application/json"
                        val mime = ct.substringBefore(';').trim()
                        val enc = Regex("charset=([\\w-]+)").find(ct)?.groupValues?.get(1) ?: "utf-8"
                        Log.i(TAG, "INTERCEPT fn=$fn status=${resp.code} len=${body.length} mime=$mime saved=${file.absolutePath}")
                        WebResourceResponse(mime, enc, resp.code, resp.message, resp.headers.toMultimap().mapValues { it.value.joinToString(";") }, body.byteInputStream())
                    } catch (e: Throwable) {
                        Log.e(TAG, "intercept fail: $e")
                        null
                    }
                }
                return null
            }
        }

        w.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                if (msg.message().contains("JDHOOK")) {
                    Log.i(TAG, msg.message())
                }
                return true
            }
        }

        if (url == null) {
            append("缺 url 参数\n")
        } else {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                w, HOOK_JS,
                setOf("https://jingfen.jd.com", "https://trade.m.jd.com", "https://wqs.jd.com", "https://order.m.jd.com")
            )
            w.loadUrl(url)
        }
        val jsExtra = intent.getStringExtra("js")?.let {
            try { String(android.util.Base64.decode(it, android.util.Base64.NO_WRAP)) } catch (e: Throwable) { it }
        }
        if (jsExtra != null) {
            Handler(Looper.getMainLooper()).postDelayed({
                val loc = IntArray(2)
                web?.getLocationOnScreen(loc)
                Log.i(TAG, "WEB_ORIGIN ${loc[0]},${loc[1]} size=${web?.width}x${web?.height}")
                web?.evaluateJavascript(jsExtra) { value ->
                    Log.i(TAG, "JS_RESULT_START\n$value\nJS_RESULT_END")
                    append("JS 结果已输出到 logcat\n")
                }
            }, 8000)
        }
    }

    private fun dumpDom() {
        val w = web ?: return
        val js = """
            (function(){
              var out = {};
              out.title = document.title;
              out.url = location.href;
              out.bodyText = document.body ? document.body.innerText.slice(0, 4000) : '(no body)';
              var state = window.__INITIAL_STATE__ || window.__NUXT__ || window.__NEXT_DATA__ || null;
              out.state = state ? JSON.stringify(state).slice(0, 30000) : '(no state)';
              return JSON.stringify(out);
            })()
        """.trimIndent()
        w.evaluateJavascript(js) { value ->
            Log.i(TAG, "DOM_JSON_START\n$value\nDOM_JSON_END")
            append("\n已抓取 DOM，见 logcat JdTrace\n")
        }
    }

    private fun append(s: String) {
        runOnUiThread { output.append(s) }
    }

    override fun onDestroy() {
        web?.destroy()
        super.onDestroy()
    }
}
