package com.halo.expressassistant.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.halo.expressassistant.api.XiaomiMintResult;
import com.halo.expressassistant.api.XiaomiPassport;
import com.halo.expressassistant.data.Store;
import com.halo.expressassistant.service.AdvertisingIdHelper;

import java.util.HashMap;
import java.util.Map;

public class XiaomiLoginActivity extends Activity {

    private WebView web;
    private TextView output;
    private boolean working;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        output = new TextView(this);
        output.setTextSize(12f);
        output.setTypeface(android.graphics.Typeface.MONOSPACE);
        output.setPadding(8, 8, 8, 8);
        output.setText("正在打开小米扫码登录…\n请用手机“设置 → 小米账号”扫码\n");
        root.addView(output, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        web = new WebView(this);
        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                append("页面: " + url + "\n");
                if (!working && (url.contains("fe/service/account") || url.contains("passInfo=login-end"))) {
                    tryLogin();
                }
            }
        });

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
        new Thread(() -> {
            try {
                String cookies = CookieManager.getInstance().getCookie("https://account.xiaomi.com");
                if (cookies == null) cookies = "";
                append("cookies: " + cookies + "\n");
                Map<String, String> map = parseCookies(cookies);
                String passToken = map.get("passToken");
                String userId = map.get("userId");
                String deviceId = map.get("deviceId");
                if (passToken == null || userId == null || deviceId == null) {
                    append("登录态不完整，请重试\n");
                    working = false;
                    return;
                }
                XiaomiMintResult result = XiaomiPassport.INSTANCE.mint(map, userId);
                String ids = AdvertisingIdHelper.probe(this);
                String oaid = "";
                String vaid = "";
                for (String line : ids.split("\n")) {
                    if (line.startsWith("getOAID=") && !line.contains("ERR")) oaid = line.substring(8);
                    if (line.startsWith("getVAID=") && !line.contains("ERR")) vaid = line.substring(8);
                }
                Store.INSTANCE.saveXiaomiLogin(
                        this, result.getToken(), result.getCUserId(), result.getAccountId(),
                        oaid, vaid, Store.INSTANCE.xiaomiPhones(this));
                runOnUiThread(() -> {
                    Toast.makeText(this, "小米登录成功，已保存令牌", Toast.LENGTH_LONG).show();
                    setResult(RESULT_OK);
                    finish();
                });
            } catch (Throwable t) {
                append("登录处理失败: " + t + "\n");
                working = false;
            }
        }).start();
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

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
