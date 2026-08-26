package com.halo.expressassistant.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.app.AlertDialog;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.halo.expressassistant.api.XiaomiMintResult;
import com.halo.expressassistant.api.XiaomiPassport;
import com.halo.expressassistant.data.Store;
import com.halo.expressassistant.service.AdvertisingIdHelper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class XiaomiLoginActivity extends Activity {

    private static final String TAG = "XiaomiLogin";

    private WebView web;
    private TextView output;
    private boolean working;
    /* 记录最近一次页面 URL：手机号登录时 URL 带 _user=1xxxxxxxxxx，可直接提取 */
    private volatile String lastUrl = "";
    /* 任意页面 URL 中抓到的手机号（challenge 页出现过即记住，即使之后跳到 STS 页） */
    private volatile String phoneFromUrl = "";
    private Handler pollHandler;
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (working || isFinishing()) return;
            String cookies = CookieManager.getInstance().getCookie("https://account.xiaomi.com");
            String stsCookies = CookieManager.getInstance().getCookie("https://api.assistant.miui.com");
            if ((cookies != null && cookies.contains("passToken=")) ||
                    (stsCookies != null && (stsCookies.contains("serviceToken=") ||
                            stsCookies.contains("assistant_serviceToken=")))) {
                tryLogin();
                return;
            }
            if (pollHandler != null) pollHandler.postDelayed(this, 2000);
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Themes.INSTANCE.apply(this);
        super.onCreate(savedInstanceState);
        WebView.setWebContentsDebuggingEnabled(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        output = new TextView(this);
        output.setTextSize(12f);
        output.setTypeface(android.graphics.Typeface.MONOSPACE);
        output.setPadding(8, 8, 8, 8);
        output.setText("正在打开小米扫码登录…\n请用手机“设置 → 小米账号”扫码\n");
        output.setMaxHeight((int) (90 * getResources().getDisplayMetrics().density));
        output.setMovementMethod(new ScrollingMovementMethod());
        root.addView(output, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        web = new WebView(this);
        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        // 统一根治：登录页打开即清全部 WebView Cookie（域级 cookie 按 host expire 删不掉，必须 removeAllCookies）
        {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            cm.removeAllCookies(null);
            cm.flush();
            Log.i(TAG, "xiaomi login opened -> removed all webview cookies");
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                lastUrl = url;
                if (phoneFromUrl.isEmpty()) {
                    try {
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[?&](_user|user)=1(\\d{10})").matcher(url);
                        if (m.find()) phoneFromUrl = "1" + m.group(2);
                    } catch (Throwable ignored) {}
                }
                append("页面: " + url + "\n");
                Log.i(TAG, "page: " + url);
                logCookies();
                if (!working && url.contains("fe/service/identity/authStart")) {
                    append("请在页面内完成登录：输入手机号密码或验证码，并按提示完成安全验证。\n");
                }
                if (!working && (url.contains("fe/service/account") ||
                        url.contains("passInfo=login-end") ||
                        url.startsWith("https://api.assistant.miui.com/sts"))) {
                    tryLogin();
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(web);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(XiaomiLoginActivity.this)
                        .setMessage(message)
                        .setPositiveButton("确定", (d, w) -> result.confirm())
                        .setOnDismissListener(d -> result.cancel())
                        .show();
                return true;
            }
        });

        pollHandler = new Handler(Looper.getMainLooper());
        pollHandler.postDelayed(pollRunnable, 2000);

        new Thread(() -> {
            try {
                String location = XiaomiPassport.INSTANCE.getLoginUrl();
                runOnUiThread(() -> web.loadUrl(location));
            } catch (Throwable t) {
                append("打开登录页失败: " + t + "\n");
            }
        }).start();
    }

    private void tryLogin() {
        working = true;
        Log.i(TAG, "tryLogin start");
        logCookies();
        new Thread(() -> {
            try {
                String cookies = CookieManager.getInstance().getCookie("https://account.xiaomi.com");
                if (cookies == null) cookies = "";
                append("cookies: " + cookies + "\n");
                Map<String, String> map = parseCookies(cookies);
                String passToken = map.get("passToken");
                String userId = map.get("userId");
                String deviceId = map.get("deviceId");
                String cUserId = map.get("cUserId");

                // 优先使用页面 STS 跳转已经拿到的令牌（新版流程）
                String stsCookies = CookieManager.getInstance().getCookie("https://api.assistant.miui.com");
                String token = null;
                if (stsCookies != null) {
                    Map<String, String> stsMap = parseCookies(stsCookies);
                    token = stsMap.get("assistant_serviceToken");
                    if (token == null) token = stsMap.get("serviceToken");
                }
                Log.i(TAG, "stsToken=" + (token != null ? "len=" + token.length() : "none") +
                        " cUserId=" + (cUserId != null));

                if (token == null && (passToken == null || userId == null || deviceId == null)) {
                    Log.w(TAG, "incomplete: passToken=" + (passToken != null) +
                            " userId=" + (userId != null) + " deviceId=" + (deviceId != null));
                    append("登录态不完整，请重试\n");
                    working = false;
                    return;
                }

                if (token == null) {
                    // 旧版流程兜底
                    XiaomiMintResult result = XiaomiPassport.INSTANCE.mint(map, userId);
                    token = result.getToken();
                    cUserId = result.getCUserId();
                    Log.i(TAG, "mint ok tokenLen=" + token.length() +
                            " cUserLen=" + cUserId.length() + " accountIdLen=" + result.getAccountId().length());
                }

                String ids = AdvertisingIdHelper.probe(this);
                String oaid = "";
                String vaid = "";
                for (String line : ids.split("\n")) {
                    if (line.startsWith("getOAID=") && !line.contains("ERR")) oaid = line.substring(8);
                    if (line.startsWith("getVAID=") && !line.contains("ERR")) vaid = line.substring(8);
                }
                // 无手动绑手机号后：登录时自动探测手机号
                // ① 本机 SIM 号码 ② 小米账号资料接口里的绑定手机号（candidate URLs + 11 位号正则）
                List<String> detected = autoDetectPhones(passToken, userId, cUserId);
                List<String> phones = detected.isEmpty()
                        ? Store.INSTANCE.xiaomiPhones(this)
                        : detected;
                Log.i(TAG, "phones=" + phones + " (auto=" + detected.size() + ")");
                // 多源绑定：每次登录 = 追加一个可绑定的账号
                Store.INSTANCE.addXiaomiAccount(
                        this,
                        new Store.XiaomiCred(
                                token, cUserId == null ? "" : cUserId, userId == null ? "" : userId,
                                oaid, vaid, phones
                        )
                );
                Log.i(TAG, "saved login state");
                runOnUiThread(() -> {
                    Toast.makeText(this, "小米登录成功，已保存令牌", Toast.LENGTH_LONG).show();
                    setResult(RESULT_OK);
                    finish();
                });
            } catch (Throwable t) {
                Log.e(TAG, "login failed: " + t.getMessage(), t);
                append("登录处理失败: " + t.getMessage() + "\n");
                append("提示：若提示风控或令牌为空，请退出本页面后重新扫码，或稍等几分钟再试。\n");
                working = false;
            }
        }).start();
    }

    /** 自动探测手机号：① 登录 URL（手机号登录必带 _user=1xx…） ② 本机 SIM ③ 小米账号资料接口 */
    private java.util.List<String> autoDetectPhones(String passToken, String userId, String cUserId) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        // ① 登录 URL（任意页面出现过 _user=1xx… 即记住；手机号/验证码登录必带）
        try {
            if (!phoneFromUrl.isEmpty() && phoneFromUrl.matches("1\\d{10}")) {
                out.add(phoneFromUrl);
                Log.i(TAG, "phone from login url: " + phoneFromUrl);
            } else {
                String u = lastUrl == null ? "" : lastUrl;
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("[?&](_user|user)=1(\\d{10})").matcher(u);
                if (m.find()) out.add("1" + m.group(2));
            }
        } catch (Throwable t) {
            Log.w(TAG, "phone from url: " + t);
        }
        if (out.isEmpty()) {
            // ② 本机 SIM
            try {
                android.telephony.TelephonyManager tm =
                        (android.telephony.TelephonyManager) getSystemService(TELEPHONY_SERVICE);
                if (tm != null) {
                    String n = tm.getLine1Number();
                    if (n != null && n.trim().matches("1\\d{10}")) out.add(n.trim());
                }
            } catch (Throwable t) {
                Log.i(TAG, "phone from sim: " + t.getMessage());
            }
        }
        if (out.isEmpty()) {
            // ③ 小米账号资料接口（不同版本端点；响应里出现 1xxxxxxxxxx 即视为绑定号码）
            StringBuilder cookie = new StringBuilder();
            if (userId != null) cookie.append("userId=").append(userId).append("; ");
            if (passToken != null) cookie.append("passToken=").append(passToken).append("; ");
            if (cUserId != null) cookie.append("cUserId=").append(cUserId);
            String[] urls = {
                    "https://account.xiaomi.com/fe/service/account/profile",
                    "https://account.xiaomi.com/fe/api/account/profile",
                    "https://account.xiaomi.com/user/profile",
                    "https://api.account.xiaomi.com/user/profile"
            };
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("1\\d{10}");
            for (String u : urls) {
                try {
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                    conn.setConnectTimeout(6000);
                    conn.setReadTimeout(6000);
                    conn.setRequestProperty("Cookie", cookie.toString());
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36");
                    String body = readAll(conn.getInputStream());
                    if (body != null && body.length() > 0) {
                        java.util.regex.Matcher m = p.matcher(body);
                        if (m.find()) {
                            out.add(m.group());
                            Log.i(TAG, "profile phone via " + u);
                            break;
                        }
                    }
                } catch (Throwable t) {
                    Log.i(TAG, "profile probe " + u + " -> " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
                }
            }
        }
        // 去重
        java.util.List<String> uniq = new java.util.ArrayList<String>();
        for (String s : out) if (!uniq.contains(s)) uniq.add(s);
        return uniq;
    }

    private String readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        int total = 0;
        while ((line = r.readLine()) != null && total < 65536) {
            sb.append(line).append('\n');
            total += line.length();
        }
        r.close();
        return sb.toString();
    }

    private Map<String, String> parseCookies(String cookieHeader) {
        Map<String, String> map = new HashMap<>();
        if (cookieHeader == null) return map;
        for (String part : cookieHeader.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2) map.put(kv[0], kv[1]);
        }
        return map;
    }

    private void append(String s) {
        runOnUiThread(() -> output.append(s));
    }

    private void logCookies() {
        String cookies = CookieManager.getInstance().getCookie("https://account.xiaomi.com");
        if (cookies == null || cookies.isEmpty()) {
            Log.i(TAG, "cookies: <none>");
            return;
        }
        StringBuilder sb = new StringBuilder("cookies keys:");
        for (String part : cookies.split(";")) {
            String[] kv = part.trim().split("=", 2);
            String k = kv[0];
            String v = kv.length > 1 ? kv[1] : "";
            if (k.equals("passToken") || k.equals("userId") || k.equals("deviceId") ||
                    k.equals("serviceToken") || k.equals("assistant_serviceToken")) {
                sb.append(' ').append(k).append("(len=").append(v.length()).append(')');
            } else {
                sb.append(' ').append(k);
            }
        }
        Log.i(TAG, sb.toString());
    }

    @Override
    protected void onDestroy() {
        if (pollHandler != null) {
            pollHandler.removeCallbacks(pollRunnable);
        }
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
