package com.halo.expressassistant.ui;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.util.Log;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.halo.expressassistant.service.ShellClient;
import com.halo.expressassistant.service.DeviceIdHelper;
import com.halo.expressassistant.service.AdvertisingIdHelper;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class DebugShellActivity extends Activity {

    private TextView output;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Themes.INSTANCE.apply(this);
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 16, 16, 16);

        Button bind = new Button(this);
        bind.setText("绑定 Shizuku");
        bind.setOnClickListener(v -> bindShizuku());
        root.addView(bind);

        Button test = new Button(this);
        test.setText("probeAuth + probePass");
        test.setOnClickListener(v -> runTests());
        root.addView(test);

        Button mint = new Button(this);
        mint.setText("mintToken(PA)");
        mint.setOnClickListener(v -> runMint("com.miui.personalassistant"));
        root.addView(mint);

        Button mintShell = new Button(this);
        mintShell.setText("mintToken(shell)");
        mintShell.setOnClickListener(v -> runMint("com.android.shell"));
        root.addView(mintShell);

        Button fetch = new Button(this);
        fetch.setText("fetchList(empty phones)");
        fetch.setOnClickListener(v -> runFetch());
        root.addView(fetch);

        Button fetchWeb = new Button(this);
        fetchWeb.setText("fetchList(web token)");
        fetchWeb.setOnClickListener(v -> runFetchWeb());
        root.addView(fetchWeb);

        Button ids = new Button(this);
        ids.setText("probe advertising ids");
        ids.setOnClickListener(v -> append(AdvertisingIdHelper.probe(this)));
        root.addView(ids);

        Button saveLogin = new Button(this);
        saveLogin.setText("save web login to app");
        saveLogin.setOnClickListener(v -> runSaveLogin());
        root.addView(saveLogin);

        output = new TextView(this);
        output.setTextSize(12f);
        output.setTypeface(android.graphics.Typeface.MONOSPACE);
        root.addView(output);

        setContentView(new ScrollView(this) {{
            addView(root);
        }});
    }

    private void bindShizuku() {
        append("bind...\n");
        ShellClient.INSTANCE.bind(new Function1<Boolean, Unit>() {
            @Override
            public Unit invoke(Boolean ok) {
                append("bind=" + ok + " err=" + ShellClient.INSTANCE.getLastError() + "\n");
                return Unit.INSTANCE;
            }
        });
    }

    private void runTests() {
        append("tests...\n");
        new Thread(() -> {
            if (!waitBind()) {
                post("bind failed: " + ShellClient.INSTANCE.getLastError());
                return;
            }
            try {
                append("=== probeAuth ===\n" + ShellClient.INSTANCE.current().probeAuth() + "\n");
                append("=== probePass ===\n" + ShellClient.INSTANCE.current().probePass() + "\n");
            } catch (Throwable t) {
                java.io.StringWriter sw = new java.io.StringWriter();
                t.printStackTrace(new java.io.PrintWriter(sw));
                Log.i("DebugShell", "ERR " + sw);
                append("ERR: " + sw);
            }
        }).start();
    }

    private void runMint(String appName) {
        append("mint " + appName + "...\n");
        new Thread(() -> {
            if (!waitBind()) {
                post("bind failed: " + ShellClient.INSTANCE.getLastError());
                return;
            }
            try {
                String deviceId = null;
                try {
                    deviceId = DeviceIdHelper.get(DebugShellActivity.this);
                    append("deviceId=" + deviceId + "\n");
                } catch (Throwable t) {
                    append("deviceIdErr=" + t + "\n");
                }
                append("=== mint " + appName + " ===\n" + ShellClient.INSTANCE.current().mintToken(appName, deviceId) + "\n");
            } catch (Throwable t) {
                java.io.StringWriter sw = new java.io.StringWriter();
                t.printStackTrace(new java.io.PrintWriter(sw));
                Log.i("DebugShell", "ERR " + sw);
                append("ERR: " + sw);
            }
        }).start();
    }

    private void runFetch() {
        append("fetch...\n");
        new Thread(() -> {
            if (!waitBind()) {
                post("bind failed: " + ShellClient.INSTANCE.getLastError());
                return;
            }
            try {
                String body = "{\"info\":{\"limit\":29,\"phones\":[],\"deletedMailNos\":[],\"modifiedMailNos\":[]}}";
                append("=== fetch ===\n" + ShellClient.INSTANCE.current().getExpressList(body) + "\n");
            } catch (Throwable t) {
                java.io.StringWriter sw = new java.io.StringWriter();
                t.printStackTrace(new java.io.PrintWriter(sw));
                Log.i("DebugShell", "ERR " + sw);
                append("ERR: " + sw);
            }
        }).start();
    }

    private void runFetchWeb() {
        append("fetchWeb...\n");
        new Thread(() -> {
            if (!waitBind()) {
                post("bind failed: " + ShellClient.INSTANCE.getLastError());
                return;
            }
            try {
                String body = "{\"info\":{\"limit\":29,\"phones\":[],\"deletedMailNos\":[],\"modifiedMailNos\":[]}}";
                String ids = AdvertisingIdHelper.probe(this);
                String oaid = "";
                String vaid = "";
                for (String line : ids.split("\n")) {
                    if (line.startsWith("getOAID=") && !line.endsWith("=ERR")) oaid = line.substring(8);
                    if (line.startsWith("getVAID=") && !line.endsWith("=ERR")) vaid = line.substring(8);
                }
                append("ids: oaid=" + oaid + " vaid=" + vaid + "\n");
                append("=== fetchWeb ===\n" + ShellClient.INSTANCE.current().testWebToken(body, oaid, vaid) + "\n");
            } catch (Throwable t) {
                java.io.StringWriter sw = new java.io.StringWriter();
                t.printStackTrace(new java.io.PrintWriter(sw));
                Log.i("DebugShell", "ERR " + sw);
                append("ERR: " + sw);
            }
        }).start();
    }

    private void runSaveLogin() {
        append("saveLogin...\n");
        new Thread(() -> {
            if (!waitBind()) {
                post("bind failed: " + ShellClient.INSTANCE.getLastError());
                return;
            }
            try {
                String ids = AdvertisingIdHelper.probe(this);
                String oaid = "";
                String vaid = "";
                for (String line : ids.split("\n")) {
                    if (line.startsWith("getOAID=") && !line.contains("ERR")) oaid = line.substring(8);
                    if (line.startsWith("getVAID=") && !line.contains("ERR")) vaid = line.substring(8);
                }
                append("=== save ===\n" + ShellClient.INSTANCE.current().saveWebLogin(oaid, vaid) + "\n");
            } catch (Throwable t) {
                java.io.StringWriter sw = new java.io.StringWriter();
                t.printStackTrace(new java.io.PrintWriter(sw));
                Log.i("DebugShell", "ERR " + sw);
                append("ERR: " + sw);
            }
        }).start();
    }

    private boolean waitBind() {
        if (ShellClient.INSTANCE.isReady()) return true;
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean ok = new AtomicBoolean(false);
        new Handler(Looper.getMainLooper()).post(() -> ShellClient.INSTANCE.bind(new Function1<Boolean, Unit>() {
            @Override
            public Unit invoke(Boolean flag) {
                ok.set(flag);
                latch.countDown();
                return Unit.INSTANCE;
            }
        }));
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) return false;
        } catch (InterruptedException e) {
            return false;
        }
        if (!ok.get() && ShellClient.INSTANCE.getLastError().contains("等待授权")) {
            append("requesting Shizuku permission...\n");
            rikka.shizuku.Shizuku.requestPermission(1000);
        }
        return ok.get();
    }

    private void append(String s) {
        runOnUiThread(() -> output.append(s));
    }

    private void post(String s) {
        runOnUiThread(() -> output.setText(s));
    }
}
