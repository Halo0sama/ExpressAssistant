package com.halo.expressassistant.service;

import android.accounts.Account;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

import com.halo.expressassistant.api.XiaomiApi;
import com.halo.expressassistant.data.Store;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.lang.reflect.Proxy;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class ExpressShellService extends IExpressShell.Stub {

    private Context context;

    public ExpressShellService() {
    }

    public ExpressShellService(Context context) {
        this.context = context;
    }

    @Override
    public void destroy() {
        System.exit(0);
    }

    @Override
    public void exit() {
        destroy();
    }

    @Override
    public String probeAuth() throws RemoteException {
        if (context == null) return "ERR: no context";
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("uid=").append(Process.myUid()).append('\n');
            sb.append("paVersion=").append(XiaomiApi.INSTANCE.paVersionCode(context)).append('\n');
            Account[] accounts = directAccounts();
            sb.append("accounts=");
            for (Account a : accounts) {
                sb.append(a.type).append(':').append(a.name).append(' ');
            }
            sb.append('\n');
            Account xiaomi = null;
            for (Account a : accounts) {
                if (a.type.contains("xiaomi")) {
                    xiaomi = a;
                    break;
                }
            }
            if (xiaomi == null) {
                sb.append("no xiaomi account");
                return sb.toString();
            }
            String[] auth = directAuth(xiaomi);
            sb.append("token=").append(auth[0].substring(0, Math.min(48, auth[0].length()))).append('\n');
            sb.append("userId=").append(auth[1]).append('\n');
            sb.append("userDataN=").append(getUserData(xiaomi, "n")).append('\n');
            sb.append("userDataEncrypted=").append(getUserData(xiaomi, "encrypted_user_id")).append('\n');
            return sb.toString();
        } catch (Throwable t) {
            return "ERR: " + t;
        }
    }

    @Override
    public String probePass() throws RemoteException {
        if (context == null) return "ERR: no context";
        try {
            StringBuilder sb = new StringBuilder();
            Account xiaomi = null;
            for (Account a : directAccounts()) {
                if (a.type.contains("xiaomi")) {
                    xiaomi = a;
                    break;
                }
            }
            if (xiaomi == null) return "no xiaomi account";
            Object am = getIAccountManager();
            Object password;
            try {
                password = am.getClass().getMethod("getPassword", Account.class).invoke(am, xiaomi);
            } catch (Throwable t) {
                java.io.StringWriter sw = new java.io.StringWriter();
                t.printStackTrace(new java.io.PrintWriter(sw));
                sb.append("getPasswordErr=").append(sw).append('\n');
                password = null;
            }
            sb.append("passwordLen=").append(password == null ? 0 : password.toString().length()).append('\n');
            sb.append("passwordHead=").append(password == null ? "null" : mask(password.toString(), 80)).append('\n');
            for (String key : new String[]{"encrypted_user_id", "n", "acc_udevid", "has_local_channel", "serviceToken"}) {
                Object val;
                try {
                    val = am.getClass().getMethod("getUserData", Account.class, String.class).invoke(am, xiaomi, key);
                } catch (Throwable t) {
                    java.io.StringWriter sw = new java.io.StringWriter();
                    t.printStackTrace(new java.io.PrintWriter(sw));
                    sb.append("userDataErr ").append(key).append('=').append(sw).append('\n');
                    val = null;
                }
                sb.append(key).append('=').append(val == null ? "null" : mask(val.toString(), 120)).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return "ERR: " + t;
        }
    }

    @Override
    public String getExpressList(String infoJson) throws RemoteException {
        if (context == null) return "ERR: no context";
        try {
            String[] auth = auth();
            if (auth == null) return "ERR: no auth";
            if (infoJson.startsWith("__BAD__")) {
                return XiaomiApi.INSTANCE.fetchList(context, "BAD_TOKEN", "BAD_UID", infoJson.substring(7));
            }
            return XiaomiApi.INSTANCE.fetchList(context, auth[0], auth[1], infoJson, accountName());
        } catch (Throwable t) {
            return "ERR: " + t;
        }
    }

    @Override
    public String testToken(String token, String cUserId, String infoJson) throws RemoteException {
        if (context == null) return "ERR: no context";
        try {
            return XiaomiApi.INSTANCE.fetchList(context, token, cUserId, infoJson);
        } catch (Throwable t) {
            return "ERR: " + t;
        }
    }

    @Override
    public String testWebToken(String infoJson, String oaid, String vaid) throws RemoteException {
        if (context == null) return "ERR: no context";
        try {
            BufferedReader r = new BufferedReader(new FileReader("/data/local/tmp/web_token.txt"));
            String token = r.readLine();
            String cuser = r.readLine();
            String accountId = r.readLine();
            r.close();
            if (token == null || cuser == null) return "missing file content";
            String result = XiaomiApi.INSTANCE.fetchList(context, token.trim(), cuser.trim(), infoJson,
                    accountId == null ? null : accountId.trim(), oaid, vaid);
            try {
                java.io.FileWriter w = new java.io.FileWriter("/data/local/tmp/pa_resp.json");
                w.write(result);
                w.close();
            } catch (Throwable ignored) {
            }
            return result.length() > 300 ? result.substring(0, 300) + "...(len=" + result.length() + ")" : result;
        } catch (Throwable t) {
            return "ERR: " + t;
        }
    }

    @Override
    public String saveWebLogin(String oaid, String vaid) throws RemoteException {
        if (context == null) return "ERR: no context";
        try {
            BufferedReader r = new BufferedReader(new FileReader("/data/local/tmp/web_token.txt"));
            String token = r.readLine();
            String cuser = r.readLine();
            String accountId = r.readLine();
            r.close();
            if (token == null || cuser == null) return "missing file content";
            Store.INSTANCE.saveXiaomiLogin(context, token.trim(), cuser.trim(),
                    accountId == null ? "" : accountId.trim(), oaid, vaid,
                    java.util.Collections.emptyList());
            return "saved";
        } catch (Throwable t) {
            return "ERR: " + t;
        }
    }

    @Override
    public String mintToken(String appName, String externalDeviceId) throws RemoteException {
        if (context == null) return "ERR: no context";
        try {
            Account xiaomi = null;
            for (Account a : directAccounts()) {
                if (a.type.contains("xiaomi")) {
                    xiaomi = a;
                    break;
                }
            }
            if (xiaomi == null) return "no xiaomi account";
            Object am = getIAccountManager();
            Object password = am.getClass().getMethod("getPassword", Account.class).invoke(am, xiaomi);
            if (password == null) return "no password";
            String[] parts = password.toString().split(",");
            if (parts.length < 2) return "password format unexpected, parts=" + parts.length;
            String passToken = parts[0];
            String userId = xiaomi.name;
            String uDevId = String.valueOf(am.getClass().getMethod("getUserData", Account.class, String.class)
                    .invoke(am, xiaomi, "acc_udevid"));

            StringBuilder log = new StringBuilder();
            String serviceLoginUrl = "https://account.xiaomi.com/pass/serviceLogin"
                    + "?sid=assistant&_json=true&_appName=" + urlEncode(appName)
                    + "&_locale=zh_CN";
            String deviceId = externalDeviceId;
            if (deviceId == null || deviceId.isEmpty()) deviceId = "android_" + java.util.UUID.randomUUID();
            log.append("STEP0 deviceId=").append(deviceId).append('\n');
            String cookie = "userId=" + urlEncode(userId) + "; passToken=" + urlEncode(passToken)
                    + "; deviceId=" + urlEncode(deviceId)
                    + (uDevId != null && !uDevId.equals("null") ? "; uDevId=" + urlEncode(uDevId) : "");
            String loginBody = httpGet(serviceLoginUrl, cookie);
            log.append("STEP1 code=").append(firstLine(loginBody)).append('\n');
            log.append("STEP1 body=").append(truncate(loginBody, 400)).append('\n');

            String json = stripStart(loginBody);
            org.json.JSONObject obj = new org.json.JSONObject(json);
            int code = obj.optInt("code", -1);
            log.append("STEP1 jsonCode=").append(code).append('\n');
            if (code != 0) {
                return log.toString() + "LOGIN_FAIL desc=" + obj.optString("desc");
            }
            String location = obj.optString("location");
            String ssecurity = obj.optString("ssecurity");
            String nonce = obj.optString("nonce");
            String newPassToken = obj.optString("passToken", passToken);
            String cUserId = obj.optString("cUserId", "");
            log.append("STEP1 location=").append(truncate(location, 300)).append('\n');
            log.append("STEP1 ssecurityLen=").append(ssecurity.length()).append(" nonceLen=").append(nonce.length()).append('\n');
            if (location.isEmpty() || ssecurity.isEmpty() || nonce.isEmpty()) {
                return log.toString() + "MISSING_FIELDS keys=" + obj.keys();
            }

            String clientSign = sha1Base64("nonce=" + nonce + "&" + ssecurity);
            String stsUrl = location + (location.contains("?") ? "&" : "?")
                    + "clientSign=" + urlEncode(clientSign) + "&_userIdNeedEncrypt=true";
            String stsHeaders = httpGetHeaders(stsUrl, "Cookie: " + cookie);
            log.append("STEP2 url=").append(truncate(stsUrl, 300)).append('\n');
            log.append("STEP2 headers=").append(truncate(stsHeaders, 600)).append('\n');

            String token = extractCookie(stsHeaders, "assistant_serviceToken");
            if (token == null) token = extractCookie(stsHeaders, "serviceToken");
            log.append("STEP2 tokenLen=").append(token == null ? 0 : token.length()).append('\n');
            if (token == null) {
                return log.toString() + "NO_TOKEN";
            }
            log.append("STEP2 tokenHead=").append(mask(token, 60)).append('\n');
            log.append("STEP2 cUserId=").append(mask(cUserId, 80)).append('\n');
            if (cUserId.isEmpty()) {
                cUserId = String.valueOf(am.getClass().getMethod("getUserData", Account.class, String.class)
                        .invoke(am, xiaomi, "encrypted_user_id"));
            }
            return log.toString() + "RESULT token,cUserId=" + token + "," + cUserId;
        } catch (Throwable t) {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            return "ERR: " + sw;
        }
    }

    @Override
    public String getExpressDetail(String infoJson) throws RemoteException {
        if (context == null) return "ERR: no context";
        try {
            String[] auth = auth();
            if (auth == null) return "ERR: no auth";
            return XiaomiApi.INSTANCE.fetchDetail(context, auth[0], auth[1], "/cpa/express/v2/query", infoJson);
        } catch (Throwable t) {
            return "ERR: " + t;
        }
    }

    private String[] auth() throws Exception {
        Account account = null;
        for (Account a : directAccounts()) {
            if (a.type.contains("xiaomi")) {
                account = a;
                break;
            }
        }
        if (account == null) return null;
        return directAuth(account);
    }

    private String accountName() throws Exception {
        for (Account a : directAccounts()) {
            if (a.type.contains("xiaomi")) return a.name;
        }
        return null;
    }

    private Object getIAccountManager() throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) sm.getMethod("getService", String.class).invoke(null, "account");
        Class<?> stub = Class.forName("android.accounts.IAccountManager$Stub");
        return stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    private Account[] directAccounts() throws Exception {
        Object am = getIAccountManager();
        Object result = am.getClass().getMethod("getAccountsAsUser", String.class, int.class, String.class)
                .invoke(am, new Object[]{null, 0, "com.android.shell"});
        return (Account[]) result;
    }

    private String[] directAuth(Account account) throws Exception {
        Object am = getIAccountManager();
        Bundle options = new Bundle();
        options.putString("androidPackageName", "com.android.shell");
        Class<?> respCls = Class.forName("android.accounts.IAccountManagerResponse");
        final AccountResponseBinder rb = new AccountResponseBinder();
        Object response = Proxy.newProxyInstance(
                respCls.getClassLoader(),
                new Class<?>[]{respCls},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "onResult":
                            rb.result = (Bundle) args[0];
                            synchronized (rb) {
                                rb.done = true;
                                rb.notifyAll();
                            }
                            return null;
                        case "onError":
                            rb.error = (String) args[1];
                            synchronized (rb) {
                                rb.done = true;
                                rb.notifyAll();
                            }
                            return null;
                        case "asBinder":
                            return rb;
                        default:
                            return null;
                    }
                });
        am.getClass()
                .getMethod("getAuthToken", respCls, Account.class, String.class, boolean.class, boolean.class, Bundle.class)
                .invoke(am, response, account, "assistant", true, false, options);
        synchronized (rb) {
            long deadline = System.currentTimeMillis() + 20000;
            while (!rb.done && System.currentTimeMillis() < deadline) {
                rb.wait(1000);
            }
        }
        if (rb.error != null) throw new RuntimeException("auth error: " + rb.error);
        if (rb.result == null) throw new RuntimeException("no response");
        String rawToken = rb.result.getString("authtoken");
        String token = rawToken == null ? null : rawToken.split(",")[0];
        String userId = rb.result.getString("encrypted_user_id");
        if (userId == null) userId = rb.result.getString("n");
        if (userId == null) userId = getUserData(account, "encrypted_user_id");
        if (userId == null) userId = getUserData(account, "n");
        if (userId == null) userId = rb.result.getString("userId");
        if (userId == null) userId = account.name;
        if (token == null || userId == null) {
            throw new RuntimeException("missing token fields: " + rb.result.keySet());
        }
        return new String[]{token, userId};
    }

    private String getUserData(Account account, String key) throws Exception {
        Object am = getIAccountManager();
        Object result = am.getClass().getMethod("getUserData", Account.class, String.class)
                .invoke(am, account, key);
        return result == null ? null : result.toString();
    }

    private String mask(String value, int max) {
        if (value == null) return "null";
        if (value.length() <= max) return value;
        return value.substring(0, max) + "...(len=" + value.length() + ")";
    }

    private String urlEncode(String value) throws Exception {
        return URLEncoder.encode(value == null ? "" : value, "UTF-8");
    }

    private String firstLine(String s) {
        if (s == null) return "null";
        int i = s.indexOf('\n');
        return i >= 0 ? s.substring(0, i) : s;
    }

    private String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "...(len=" + s.length() + ")";
    }

    private String stripStart(String s) {
        int i = s.indexOf("{");
        return i >= 0 ? s.substring(i) : s;
    }

    private String sha1Base64(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        return Base64.getEncoder().encodeToString(md.digest(input.getBytes(StandardCharsets.UTF_8)));
    }

    private String httpGet(String url, String cookie) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent", "PassportSDK/2.0");
        if (cookie != null) conn.setRequestProperty("Cookie", cookie);
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = readAll(in);
        conn.disconnect();
        return "HTTP " + code + "\n" + body;
    }

    private String httpGetHeaders(String url, String extraHeader) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent", "PassportSDK/2.0");
        if (extraHeader != null && extraHeader.startsWith("Cookie: ")) {
            conn.setRequestProperty("Cookie", extraHeader.substring(8));
        }
        int code = conn.getResponseCode();
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP ").append(code).append('\n');
        for (Map.Entry<String, java.util.List<String>> e : conn.getHeaderFields().entrySet()) {
            if (e.getKey() == null) continue;
            for (String v : e.getValue()) {
                sb.append(e.getKey()).append(": ").append(v).append('\n');
            }
        }
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (in != null) {
            String body = readAll(in);
            if (!body.isEmpty()) sb.append("BODY: ").append(truncate(body, 300)).append('\n');
        }
        conn.disconnect();
        return sb.toString();
    }

    private String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        return sb.toString();
    }

    private String extractCookie(String headers, String name) {
        Pattern p = Pattern.compile("(?i)^set-cookie:\\s*" + Pattern.quote(name) + "=([^;]+)", Pattern.MULTILINE);
        Matcher m = p.matcher(headers);
        return m.find() ? m.group(1) : null;
    }


    private static class AccountResponseBinder extends android.os.Binder {
        static final String DESCRIPTOR = "android.accounts.IAccountManagerResponse";
        volatile Bundle result;
        volatile String error;
        volatile boolean done;

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == 1) {
                data.enforceInterface(DESCRIPTOR);
                Bundle b = data.readTypedObject(Bundle.CREATOR);
                synchronized (this) {
                    result = b;
                    done = true;
                    notifyAll();
                }
                reply.writeNoException();
                return true;
            }
            if (code == 2) {
                data.enforceInterface(DESCRIPTOR);
                int errCode = data.readInt();
                String msg = data.readString();
                synchronized (this) {
                    error = errCode + ": " + msg;
                    done = true;
                    notifyAll();
                }
                reply.writeNoException();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }
}
