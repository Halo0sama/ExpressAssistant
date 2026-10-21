package com.halo.expressassistant.ui

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import com.halo.expressassistant.data.Store
import org.json.JSONArray
import org.json.JSONObject

/**
 * 拼多多隐藏 WebView 捕获公共设施：注入通用 JS 钩子（XHR/fetch 全部响应进 window.name），
 * 把登录 cookie 写进 WebView CookieManager，以及读取/清理捕获结果的工具。
 *
 * 拼多多 H5 的 proxy 接口普遍要求 anti_content 等动态签名（由页面 JS 生成），
 * 因此仿照京东的做法：让 WebView 亲自加载页面并替我们完成签名，Kotlin 只读响应。
 */
object PddCapture {

    private const val TAG = "PddCapture"

    /** 拼多多 H5 相关域（cookie 注入与清理） */
    val COOKIE_HOSTS = listOf(
        "https://mobile.yangkeduo.com",
        "https://yangkeduo.com",
        "https://pinduoduo.com",
        "https://mobile.pinduoduo.com",
        "https://passport.pinduoduo.com"
    )

    /** 通用捕获 JS：把 /proxy/api|yangkeduo|pinduoduo 的 XHR/fetch 响应压入 window.name（含请求体 r） */
    const val HOOK_JS = """
        (function(){
          function pushCap(u, s, r){
            try {
              var arr = [];
              if (window.name && window.name.charAt(0) === '[') { try { arr = JSON.parse(window.name); } catch(e){ arr = []; } }
              var o = {u: String(u||'').slice(0,300), b: String(s||'')};
              if (r) o.r = String(r);
              arr.push(o);
              if (arr.length > 500) arr = arr.slice(arr.length - 500);
              window.name = JSON.stringify(arr);
            } catch(e) {}
          }
          var oo = XMLHttpRequest.prototype.open; var os = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.open = function(m,u){ this.__u=u; return oo.apply(this,arguments); };
          XMLHttpRequest.prototype.send = function(){
            var self=this;
            var args = arguments;
            if (self.__u && /proxy\/api|yangkeduo|pinduoduo/i.test(String(self.__u))) {
              var rb = '';
              try {
                if (args && args.length && args[0] != null) {
                  rb = (typeof args[0] === 'string') ? args[0] : (args[0] instanceof FormData ? '' : JSON.stringify(args[0]));
                }
              } catch(e){}
              this.addEventListener('load', function(){ try{ pushCap(self.__u, self.responseText, rb); }catch(e){} });
            }
            return os.apply(this,arguments);
          };
          var of = window.fetch;
          if (of) {
            window.fetch = function(input,init){
              var url = (typeof input === 'string') ? input : (input && input.url) || '';
              var rb = '';
              try {
                if (init && init.body != null) rb = (typeof init.body === 'string') ? init.body : (init.body instanceof FormData ? '' : JSON.stringify(init.body));
              } catch(e){}
              var p = of.apply(this,arguments);
              if (p && p.then) {
                p.then(function(r){
                  try {
                    if (/proxy\/api|yangkeduo|pinduoduo/i.test(url)) {
                      r.clone().text().then(function(t){ pushCap(url, t, rb); });
                    }
                  } catch(e){}
                });
              }
              return p;
            };
          }
        })();
    """

    /** Wait for cookie writes before navigation; replace only this PDD account's browser data. */
    fun setCookiesFor(pddCookies: String, cm: CookieManager, onDone: () -> Unit) {
        if (pddCookies.isBlank()) { onDone(); return }
        cm.setAcceptCookie(true)
        if (com.halo.expressassistant.data.PddSession.cookies(cm.getCookie(PddWeb.ORDERS_URL).orEmpty()) ==
            com.halo.expressassistant.data.PddSession.cookies(pddCookies)) { onDone(); return }
        clearCookies(cm) {
            val cookies = com.halo.expressassistant.data.PddSession.cookies(pddCookies)
            var remaining = COOKIE_HOSTS.size * cookies.size
            for (host in COOKIE_HOSTS) {
                for ((key, value) in cookies) {
                    cm.setCookie(host, "$key=$value; Path=/") {
                        remaining--
                        if (remaining == 0) { cm.flush(); onDone() }
                    }
                }
            }
            if (cookies.isEmpty()) onDone()
        }
    }

    /** Expire host-only and parent-domain PDD cookies; never clear another platform. */
    fun clearCookies(cm: CookieManager = CookieManager.getInstance(), onDone: () -> Unit = {}) {
        val writes = ArrayList<Pair<String, String>>()
        for (host in COOKIE_HOSTS) {
            val uri = android.net.Uri.parse(host)
            val name = uri.host.orEmpty()
            val root = if (name.endsWith("yangkeduo.com")) "yangkeduo.com" else "pinduoduo.com"
            for (key in com.halo.expressassistant.data.PddSession.cookies(cm.getCookie(host).orEmpty()).keys) {
                writes += host to "$key=; Path=/; Max-Age=0"
                for (domain in setOf(name, root)) writes += host to "$key=; Domain=$domain; Path=/; Max-Age=0"
            }
        }
        if (writes.isEmpty()) { onDone(); return }
        var remaining = writes.size
        writes.forEach { (host, value) -> cm.setCookie(host, value) {
            remaining--
            if (remaining == 0) { cm.flush(); onDone() }
        } }
    }

    /** Empty orders are valid too; error pages and unrelated JSON are not authentication. */
    fun authenticatedOrders(body: String): Boolean = authenticatedOrderCount(body) != null

    fun authenticatedOrderCount(body: String): Int? = runCatching {
        val root = JSONObject(body)
        if (root.optInt("error_code", 0) != 0 || root.optInt("errorCode", 0) != 0 || !root.optBoolean("success", true)) null
        else (root.optJSONArray("orders") ?: root.optJSONObject("data")?.optJSONArray("orders"))?.length()
    }.getOrNull()

    /** 把隐藏 WebView 挂到窗口最底层（不可见、无障碍隔离）；后台 Worker 环境无窗口时跳过 */
    fun attach(act: Context, w: WebView) {
        try {
            val decor = (act as? Activity)?.window?.decorView as? ViewGroup ?: return
            decor.addView(w, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } catch (e: Throwable) {
            Log.w(TAG, "attach fail: $e")
        }
    }

    fun detach(w: WebView) {
        try {
            (w.parent as? ViewGroup)?.removeView(w)
            w.destroy()
        } catch (e: Throwable) {
            Log.w(TAG, "detach fail: $e")
        }
    }

    /** 从 window.name 读取捕获列表：List<(url, body)>（只回传一次，随后清空避免重复） */
    fun readCaptures(w: WebView, cb: (List<Pair<String, String>>) -> Unit) {
        w.evaluateJavascript(
            "(function(){var s=window.name||'';window.name='';return s;})()"
        ) { v ->
            cb(decodeCaptures(v))
        }
    }

    internal fun decodeCaptures(value: String): List<Pair<String, String>> = runCatching {
        val text = org.json.JSONTokener(value).nextValue() as? String ?: "[]"
        val arr = JSONArray(text)
        (0 until arr.length()).mapNotNull { i ->
            val entry = arr.optJSONObject(i) ?: return@mapNotNull null
            entry.optString("b").takeIf { it.isNotBlank() }?.let { entry.optString("u") to it }
        }
    }.getOrDefault(emptyList())

    /** 读取并保留（调试用）：返回 JSON 数组字符串 */
    fun peekRaw(w: WebView, cb: (String) -> Unit) {
        w.evaluateJavascript("(function(){return window.name?window.name:'[]';})()") { v ->
            cb(v.orEmpty())
        }
    }

    /** 尝试点击「加载更多 / 查看更多 / 下一页」类按钮 */
    fun tryTapMore(w: WebView) {
        val js = """
            (function(){
              try {
                var els = document.querySelectorAll('div,span,button,a');
                for (var i = 0; i < els.length; i++) {
                  var t = (els[i].innerText || '').trim();
                  if (t === '加载更多' || t === '查看更多' || t === '下一页' || t === '查看全部') {
                    els[i].click(); return true;
                  }
                }
              } catch(e) {}
              return false;
            })()
        """.trimIndent()
        w.evaluateJavascript(js, null)
    }

    /** 滚动到底：页面 window + 所有可滚动容器（React 内层滚动） */
    fun scrollToBottom(w: WebView) {
        val js = """
            (function(){
              try { window.scrollTo(0, document.body ? document.body.scrollHeight : 0); } catch(e) {}
              try {
                var els = document.querySelectorAll('*');
                for (var i = 0; i < els.length; i++) {
                  var e = els[i];
                  if (e.scrollHeight > e.clientHeight + 100) e.scrollTop = e.scrollHeight;
                }
              } catch(e) {}
              return true;
            })()
        """.trimIndent()
        w.evaluateJavascript(js, null)
    }

    /** 尝试点击「全部」tab / 「查看全部」入口（订单页默认可能停在一个空 tab） */
    fun tryTapAllOrders(w: WebView) {
        val js = """
            (function(){
              try {
                var els = document.querySelectorAll('div,span,button,a,li');
                var candidates = [];
                for (var i = 0; i < els.length; i++) {
                  var t = (els[i].innerText || '').trim();
                  if (t === '全部' || t === '全部订单' || t === '全部商品' ||
                      t.indexOf('查看全部') >= 0 || t.indexOf('查看更多订单') >= 0 ||
                      t.indexOf('全部订单') >= 0) {
                    candidates.push(els[i]);
                  }
                }
                if (candidates.length) {
                  var el = candidates[0];
                  el.scrollIntoView();
                  el.click();
                  return candidates.length;
                }
              } catch(e) {}
              return -1;
            })()
        """.trimIndent()
        w.evaluateJavascript(js, null)
    }

    /** 解析 window.name 原始 JSON 数组（字符串）里 [{u,b}] 捕获项 */
    fun parseCaptures(rawJsonArray: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        runCatching {
            val arr = JSONArray(rawJsonArray)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val u = o.optString("u")
                val b = o.optString("b")
                if (b.isNotBlank()) out.add(u to b)
            }
        }
        return out
    }

    /** 捕获是否可能是拼多多业务数据（订单/物流类） */
    fun looksRelevant(url: String, body: String): Boolean {
        val u = url.lowercase()
        val b = body.lowercase()
        return u.contains("proxy/api") && (
            u.contains("order") || u.contains("logistic") || u.contains("express") ||
                u.contains("track") || u.contains("parcel") || u.contains("goods") ||
                b.contains("tracking_no") || b.contains("logistics_no") || b.contains("mailno") ||
                b.contains("order_sn") || b.contains("express")
            )
    }

    /* ─────────────── 候选键取值工具（拼多多字段随版本有差异，容错取值） ─────────────── */

    fun firstString(obj: JSONObject, keys: Set<String>): String {
        for (k in keys) {
            if (!obj.has(k)) continue
            val v = obj.opt(k)
            if (v is String && v.isNotBlank()) return v.trim()
            if (v is Number) return v.toString()
            if (v is JSONObject) {
                firstString(v, keys).takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return ""
    }

    fun firstStringDeep(obj: JSONObject, keys: Set<String>, depth: Int): String {
        if (depth <= 0) return ""
        for (k in keys) {
            if (obj.has(k)) {
                val v = obj.opt(k)
                if (v is String && v.isNotBlank()) return v.trim()
                if (v is JSONObject) {
                    firstStringDeep(v, keys, depth - 1).takeIf { it.isNotBlank() }?.let { return it }
                }
                if (v is JSONArray) {
                    for (i in 0 until v.length()) {
                        val e = v.opt(i)
                        if (e is JSONObject) firstStringDeep(e, keys, depth - 1).takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
            }
        }
        for (k in obj.keys()) {
            val v = obj.opt(k)
            when (v) {
                is JSONObject -> firstStringDeep(v, keys, depth - 1).takeIf { it.isNotBlank() }?.let { return it }
                is JSONArray -> for (i in 0 until v.length()) {
                    val e = v.opt(i)
                    if (e is JSONObject) firstStringDeep(e, keys, depth - 1).takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        return ""
    }

    fun firstValueDeep(obj: JSONObject, keys: Set<String>, depth: Int): Any? {
        if (depth <= 0) return null
        for (k in keys) {
            if (obj.has(k)) {
                val v = obj.opt(k)
                if (v is String && v.isNotBlank()) return v
                if (v is Number) return v
                if (v is JSONObject) {
                    firstValueDeep(v, keys, depth - 1)?.let { return it }
                }
                if (v is JSONArray && v.length() > 0) {
                    for (i in 0 until v.length()) {
                        val e = v.opt(i)
                        if (e is JSONObject) firstValueDeep(e, keys, depth - 1)?.let { return it }
                    }
                }
            }
        }
        for (k in obj.keys()) {
            val v = obj.opt(k)
            when (v) {
                is JSONObject -> firstValueDeep(v, keys, depth - 1)?.let { return it }
                is JSONArray -> for (i in 0 until v.length()) {
                    val e = v.opt(i)
                    if (e is JSONObject) firstValueDeep(e, keys, depth - 1)?.let { return it }
                }
            }
        }
        return null
    }

    fun timeString(v: Any?): String {
        if (v == null) return ""
        if (v is String) {
            val t = v.trim()
            if (t.matches(Regex("\\d{10,13}"))) return formatMillisSafe(t.toLong())
            return t.take(30)
        }
        if (v is Number) return formatMillisSafe(v.toLong())
        return ""
    }

    private fun formatMillisSafe(raw: Long): String {
        val ms = if (raw > 1_000_000_000_000L) raw else raw * 1000L
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(ms))
        } catch (e: Throwable) {
            raw.toString()
        }
    }
}
