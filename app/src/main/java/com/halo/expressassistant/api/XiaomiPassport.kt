package com.halo.expressassistant.api

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

data class XiaomiMintResult(val token: String, val cUserId: String, val accountId: String)

object XiaomiPassport {
    private const val TAG = "XiaomiPassport"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    fun getLoginUrl(): String {
        val body = httpGet(
            "https://account.xiaomi.com/pass/serviceLogin?sid=assistant&_json=true",
            null
        )
        val json = body.substring(body.indexOf("{"))
        val obj = JSONObject(json)
        val location = obj.optString("location")
        if (location.isEmpty()) throw RuntimeException("no location: $json")
        return location
    }

    fun mint(cookies: Map<String, String>, accountId: String): XiaomiMintResult {
        val passToken = cookies["passToken"] ?: throw RuntimeException("no passToken cookie")
        val userId = cookies["userId"] ?: throw RuntimeException("no userId cookie")
        val deviceId = cookies["deviceId"] ?: throw RuntimeException("no deviceId cookie")

        val loginUrl = "https://account.xiaomi.com/pass/serviceLogin" +
            "?sid=assistant&_json=true&_appName=com.miui.personalassistant&_locale=zh_CN"
        val cookie = "userId=${urlEncode(userId)}; passToken=${urlEncode(passToken)}; deviceId=${urlEncode(deviceId)}"
        val loginBody = httpGet(loginUrl, cookie)
        val json = loginBody.substring(loginBody.indexOf("{"))
        val obj = JSONObject(json)
        if (obj.optInt("code") != 0) {
            throw RuntimeException("login failed: ${obj.optString("desc")} $json")
        }
        val ssecurity = obj.getString("ssecurity")
        val nonce = obj.getLong("nonce")
        val location = obj.getString("location")
        val cUserId = obj.optString("cUserId")

        val clientSign = sha1Base64("nonce=$nonce&$ssecurity")
        val sep = if (location.contains("?")) "&" else "?"
        val stsUrl = location + sep + "clientSign=" + urlEncode(clientSign) + "&_userIdNeedEncrypt=true"

        val (headers, stsBody) = httpGetWithHeaders(stsUrl, cookie)
        val token = extractCookie(headers, "serviceToken")
            ?: extractCookie(headers, "assistant_serviceToken")
            ?: throw RuntimeException("no serviceToken in STS response: $headers $stsBody")

        Log.i(TAG, "mint ok tokenLen=${token.length} cUser=$cUserId")
        return XiaomiMintResult(token, cUserId, accountId)
    }

    private fun httpGet(url: String, cookie: String?): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20000
            readTimeout = 20000
            setRequestProperty("User-Agent", UA)
            if (cookie != null) setRequestProperty("Cookie", cookie)
        }
        val code = conn.responseCode
        val input = if (code >= 400) conn.errorStream else conn.inputStream
        val text = input?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        conn.disconnect()
        return "HTTP $code\n$text"
    }

    private fun httpGetWithHeaders(url: String, cookie: String?): Pair<Map<String, String>, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20000
            readTimeout = 20000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", UA)
            if (cookie != null) setRequestProperty("Cookie", cookie)
        }
        val code = conn.responseCode
        val headers = HashMap<String, String>()
        for ((k, v) in conn.headerFields) {
            if (k == null) continue
            headers[k] = v.joinToString("\n")
        }
        val input = if (code >= 400) conn.errorStream else conn.inputStream
        val text = input?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        conn.disconnect()
        return headers to text
    }

    private fun extractCookie(headers: Map<String, String>, name: String): String? {
        for ((k, v) in headers) {
            if (!k.equals("Set-Cookie", ignoreCase = true)) continue
            for (line in v.split("\n")) {
                val trimmed = line.trim()
                if (trimmed.startsWith("$name=", ignoreCase = true)) {
                    val value = trimmed.substring(name.length + 1).substringBefore(";")
                    return value
                }
            }
        }
        return null
    }

    private fun sha1Base64(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(digest)
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
