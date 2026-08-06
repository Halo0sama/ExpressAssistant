package com.halo.expressassistant.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class LoginActivity extends Activity {

    private WebView web;
    private TextView output;
    private boolean done;

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
        output.setText("loading login...\n");
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
                append("page=" + url + "\n");
                capture();
            }
        });

        new Thread(() -> {
            try {
                String location = fetchLocation();
                runOnUiThread(() -> {
                    append("loading location\n");
                    web.loadUrl(location);
                });
            } catch (Throwable t) {
                append("fetchLocationErr: " + t + "\n");
            }
        }).start();
    }

    private String fetchLocation() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(
                "https://account.xiaomi.com/pass/serviceLogin?sid=assistant&_json=true").openConnection();
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36");
        BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        String body = sb.toString();
        int i = body.indexOf("\"location\":\"");
        if (i < 0) throw new RuntimeException("no location in: " + body);
        String loc = body.substring(i + 12);
        int j = loc.indexOf('"');
        return loc.substring(0, j);
    }

    private void capture() {
        if (done) return;
        StringBuilder sb = new StringBuilder();
        for (String domain : new String[]{"https://api.assistant.miui.com", "https://account.xiaomi.com", "https://i.mi.com"}) {
            String cookies = CookieManager.getInstance().getCookie(domain);
            sb.append("COOKIE ").append(domain).append("=").append(cookies == null ? "null" : mask(cookies, 700)).append('\n');
            if (cookies != null) {
                for (String pair : cookies.split(";")) {
                    String[] kv = pair.trim().split("=", 2);
                    if (kv.length == 2) {
                        String k = kv[0];
                        String v = kv[1];
                        if (k.equals("serviceToken") || k.equals("assistant_serviceToken") || k.equals("passToken") || k.equals("cUserId") || k.equals("userId")) {
                            sb.append("  KEY ").append(k).append(" len=").append(v.length()).append(" head=").append(v.substring(0, Math.min(40, v.length()))).append('\n');
                            if (k.equals("serviceToken") || k.equals("assistant_serviceToken")) {
                                sb.append("  *** SERVICE_TOKEN_FOUND ***\n");
                                done = true;
                            }
                        }
                    }
                }
            }
        }
        if (done) {
            sb.append("LOGIN_DONE\n");
        }
        append(sb.toString());
    }

    private String mask(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...(len=" + s.length() + ")";
    }

    private void append(String s) {
        Log.i("LoginActivity", s.replace('\n', ' '));
        runOnUiThread(() -> output.append(s));
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
