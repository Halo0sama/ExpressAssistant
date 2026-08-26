package com.halo.expressassistant.api

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

object XiaomiApi {
    private const val HOST = "https://verca.xpa.assistant.miui.com"
    private const val GET_LIST_PATH = "/cpa/express/v2/getList"
    private const val AES_KEY = "d101b17c77ff93cs"
    private const val SIGN_SALT = "77eb2e8a5755abd016c0d69ba74b219c"
    private val jsonMedia = "application/json".toMediaType()
    private val client = OkHttpClient.Builder().build()

    fun getListBody(
        phones: List<String>,
        deletedMailNos: List<String> = emptyList(),
        modifiedMailNos: List<String> = emptyList(),
        limit: Int = 29
    ): String {
        return """{"info":{"limit":$limit,"phones":${toJsonArray(phones)},"deletedMailNos":${toJsonArray(deletedMailNos)},"modifiedMailNos":${toJsonArray(modifiedMailNos)}}}"""
    }

    fun getDetailBody(
        phones: List<String>,
        cpCode: String?,
        mailNo: String,
        name: String?,
        provider: String?,
        stateNum: Int,
        logisticsUpdateTime: String?,
        phone: String?,
        queryChannel: String?,
        channel: String?
    ): String {
        val sb = StringBuilder("""{"info":{"phones":${toJsonArray(phones)},"cpCode":${json(cpCode)},"mailNo":${json(mailNo)},"name":${json(name)},"provider":${json(provider)},"stateNum":$stateNum,"logisticsUpdateTime":${json(logisticsUpdateTime)}""")
        if (!phone.isNullOrEmpty()) sb.append(""","phone":${json(phone)}""")
        if (!queryChannel.isNullOrEmpty()) sb.append(""","queryChannel":${json(queryChannel)}""")
        sb.append(""","channel":${json(channel)}""")
        sb.append("}}")
        return sb.toString()
    }

    @JvmOverloads
    fun fetchList(
        context: Context,
        token: String,
        userId: String,
        body: String,
        accountId: String? = null,
        oaid: String? = null,
        vaid: String? = null
    ): String {
        return post(context, GET_LIST_PATH, token, userId, body, decrypt = true, accountId = accountId, oaid = oaid, vaid = vaid)
    }

    @JvmOverloads
    fun fetchDetail(
        context: Context,
        token: String,
        userId: String,
        detailPath: String,
        body: String,
        accountId: String? = null,
        oaid: String? = null,
        vaid: String? = null
    ): String {
        return post(context, detailPath, token, userId, body, decrypt = true, accountId = accountId, oaid = oaid, vaid = vaid)
    }

    fun matchCompany(
        context: Context,
        token: String,
        userId: String,
        accountId: String?,
        oaid: String?,
        vaid: String?,
        mailNo: String
    ): Pair<String, String>? {
        val body = """{"info":{"mailNo":${json(mailNo)}}}"""
        val raw = post(
            context, "/cpa/express/matchCompany", token, userId, body,
            decrypt = true, accountId = accountId, oaid = oaid, vaid = vaid
        )
        return try {
            val root = JSONObject(raw)
            val data = JSONObject(root.optString("data"))
            val arr = data.optJSONArray("matchCompany") ?: return null
            if (arr.length() == 0) return null
            val first = arr.getJSONObject(0)
            first.optString("cpCode") to first.optString("name")
        } catch (e: Throwable) {
            null
        }
    }

    fun sendVerificationCode(
        context: Context,
        token: String,
        userId: String,
        accountId: String?,
        oaid: String?,
        vaid: String?,
        phone: String
    ): String {
        val body = """{"info":{"phone":${json(phone)}}}"""
        return post(context, "/cpa/express/phone/sendVerificationCode", token, userId, body,
            decrypt = true, accountId = accountId, oaid = oaid, vaid = vaid)
    }

    fun checkVerificationCode(
        context: Context,
        token: String,
        userId: String,
        accountId: String?,
        oaid: String?,
        vaid: String?,
        phone: String,
        code: String
    ): String {
        val body = """{"info":{"phone":${json(phone)},"verificationCode":${json(code)}}}"""
        return post(context, "/cpa/express/phone/checkVerificationCode", token, userId, body,
            decrypt = true, accountId = accountId, oaid = oaid, vaid = vaid)
    }

    fun bindPhone(
        context: Context,
        token: String,
        userId: String,
        accountId: String?,
        oaid: String?,
        vaid: String?,
        phone: String,
        phones: List<String>,
        bind: Boolean
    ): String {
        val type = if (bind) 1 else 2
        val body = """{"cardId":3,"serviceKey":"express","info":{"type":$type,"phone":${json(phone)},"phoneList":${toJsonArray(phones)}}}"""
        return post(context, "/cpa/express/phone/bind", token, userId, body,
            decrypt = true, accountId = accountId, oaid = oaid, vaid = vaid)
    }

    private fun post(
        context: Context,
        path: String,
        token: String,
        userId: String,
        body: String,
        decrypt: Boolean,
        accountId: String? = null,
        oaid: String? = null,
        vaid: String? = null
    ): String {
        val enc = aesBase64(path)
        val sign = sha1("yellowpage_encparam$enc$SIGN_SALT").uppercase(Locale.US)
        val version = paVersionCode(context)
        val url = "$HOST$path?version=$version&appkey=yellowpage&yellowpage_encparam=${urlEncode(enc)}&sign=$sign&_encparam=${urlEncode(enc)}"
        val wrapped = wrapBody(context, body, accountId ?: userId, oaid, vaid)
        val encryptedBody = MiuiCrypto.encode(wrapped)
        Log.i("ExpressApi", "url=$url")
        Log.i("ExpressApi", "cookie=serviceToken=$token; cUserId=$userId")
        Log.i("ExpressApi", "wrappedBody=$wrapped")
        Log.i("ExpressApi", "encryptedBodyLen=${encryptedBody.length} head=${encryptedBody.take(80)}")
        val request = Request.Builder()
            .url(url)
            .header("Cookie", "serviceToken=$token; cUserId=$userId")
            .header("Content-Type", "application/json")
            .post(encryptedBody.toRequestBody(jsonMedia))
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            Log.i("ExpressApi", "resp=${resp.code} $text")
            if (!resp.isSuccessful) {
                throw RuntimeException("HTTP ${resp.code}: $text | crypto=${MiuiCrypto.lastError}")
            }
            return if (decrypt) decryptResponse(text) else text
        }
    }

    fun paVersionCode(context: Context): Int {
        return try {
            context.packageManager.getPackageInfo("com.miui.personalassistant", 0).versionCode
        } catch (e: Throwable) {
            25000031
        }
    }

    private fun toJsonArray(list: List<String>): String {
        return list.joinToString(prefix = "[", postfix = "]") { json(it) }
    }

    private fun json(value: String?): String {
        if (value == null) return "null"
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    private fun urlEncode(value: String): String {
        return java.net.URLEncoder.encode(value, "UTF-8")
    }

    private fun aesBase64(plain: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES"))
        return Base64.getEncoder().encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    private fun decryptResponse(text: String): String {
        return try {
            val root = org.json.JSONObject(text)
            val data = root.optString("data")
            if (data.isNotEmpty() && data != "null") {
                root.put("data", MiuiCrypto.decode(data))
                root.toString()
            } else {
                text
            }
        } catch (e: Throwable) {
            text
        }
    }

    private fun sha1(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun wrapBody(context: Context, plain: String, userId: String, oaidIn: String?, vaidIn: String?): String {
        return try {
            val original = JSONObject(plain)
            // 空串也走兜底（登录时探测失败会存空值；每次请求时重新探测 oaid/vaid）
            val oaidV = if (oaidIn.isNullOrBlank()) oaid(context) else oaidIn
            val vaidV = if (vaidIn.isNullOrBlank()) vaid(context) else vaidIn
            val userSignal = JSONObject()
                .put("oaid", oaidV)
                .put("vaid", vaidV)
                .put("userId", userId)
            val env = JSONObject()
                .put("terminal", "phone")
                .put("assistantVersion", paVersionCode(context))
                .put("launchVersion", pkgVersionCode(context, "com.miui.home"))
                .put("androidVersion", android.os.Build.VERSION.RELEASE)
                .put("androidSdkVersion", android.os.Build.VERSION.SDK_INT)
                .put("miuiType", miuiType())
                .put("phoneModel", android.os.Build.MODEL)
                .put("phoneDevice", android.os.Build.DEVICE)
                .put("connectionType", connectionType(context))
                .put("miuiVersionName", systemProp("ro.miui.ui.version.name") ?: "V8")
                .put("miuiVersion", systemProp("ro.miui.ui.version.code") ?: "")
                .put("osVersionName", systemProp("ro.mi.os.version.name") ?: "-1.0")
                .put("osVersion", systemProp("ro.mi.os.version.code") ?: "")
                .put("miSettingsVersionName", pkgVersionName(context, "com.xiaomi.misettings") ?: "")
                .put("miSettingsVersionCode", pkgVersionCode(context, "com.xiaomi.misettings"))
                .put("timeZone", TimeZone.getDefault().getDisplayName(false, 0))
                .put("language", Locale.getDefault().language)
                .put("country", Locale.getDefault().country)
                .put("screen", 1)
                .put("coordinate", "")
                .put("reqId", UUID.randomUUID().toString().replace("-", ""))
                .put("ip", localIp())
                .put("cpuArchitecture", android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "")
                .put("marketVersion", pkgVersionCode(context, "com.xiaomi.market"))
                .put("os", android.os.Build.VERSION.INCREMENTAL)
                .put("clientFlag", 0)
                .put("hwVersion", systemProp("ro.boot.hwversion") ?: "")
            val time = JSONObject()
                .put("clientTs", System.currentTimeMillis().toString())
                .put("timeZone", TimeZone.getDefault().getDisplayName(false, 0))
            JSONObject()
                .put("userSignal", userSignal)
                .put("environmentSignal", env)
                .put("timeSignal", time)
                .put("eventSignal", original)
                .toString()
        } catch (t: Throwable) {
            plain
        }
    }

    private fun oaid(context: Context): String = identifier(context, "getOAID")

    private fun vaid(context: Context): String = identifier(context, "getVAID")

    private fun identifier(context: Context, method: String): String {
        // ① 供应商 IdProviderImpl（多数国产 ROM 有效）
        val v1 = runCatching {
            val cls = Class.forName("com.android.id.impl.IdProviderImpl")
            val inst = cls.newInstance()
            val m = cls.getMethod(method, Context::class.java)
            m.invoke(inst, context) as? String ?: ""
        }.getOrDefault("")
        if (v1.isNotBlank()) {
            Log.i("ExpressApi", "identifier $method value=$v1")
            return v1
        }
        // ② Shizuku/ADB 授权时走 AdvertisingIdHelper 探测
        return try {
            val probe = com.halo.expressassistant.service.AdvertisingIdHelper.probe(context)
            val prefix = if (method == "getOAID") "getOAID=" else "getVAID="
            val value = probe.split("\n")
                .firstOrNull { it.startsWith(prefix) && !it.contains("ERR") }
                ?.substring(prefix.length)?.trim().orEmpty()
            Log.i("ExpressApi", "identifier(shizuku) $method value=$value")
            value
        } catch (t: Throwable) {
            Log.i("ExpressApi", "identifier(shizuku) $method ERR $t")
            ""
        }
    }

    private fun miuiType(): String {
        return try {
            val cls = Class.forName("miui.os.Build")
            val isAlpha = cls.getField("IS_ALPHA_BUILD").getBoolean(null)
            val isDev = cls.getField("IS_DEVELOPMENT_VERSION").getBoolean(null)
            val isStable = cls.getField("IS_STABLE_VERSION").getBoolean(null)
            when {
                isAlpha -> "alpha"
                isDev -> "dev"
                isStable -> "stable"
                else -> "custom"
            }
        } catch (t: Throwable) {
            "stable"
        }
    }

    private fun connectionType(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val info = cm.activeNetworkInfo
            when (info?.type) {
                android.net.ConnectivityManager.TYPE_WIFI -> "WIFI"
                android.net.ConnectivityManager.TYPE_MOBILE -> "MOBILE"
                else -> "NONE"
            }
        } catch (t: Throwable) {
            "NONE"
        }
    }

    private fun localIp(): String {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            for (n in interfaces) {
                for (a in n.inetAddresses) {
                    if (!a.isLoopbackAddress && a is java.net.Inet4Address) return a.hostAddress ?: ""
                }
            }
            ""
        } catch (t: Throwable) {
            ""
        }
    }

    private fun systemProp(name: String): String? {
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            cls.getMethod("get", String::class.java).invoke(null, name) as? String
        } catch (t: Throwable) {
            null
        }
    }

    private fun pkgVersionName(context: Context, pkg: String): String? {
        return try {
            context.packageManager.getPackageInfo(pkg, 0).versionName
        } catch (t: Throwable) {
            null
        }
    }

    private fun pkgVersionCode(context: Context, pkg: String): Int {
        return try {
            context.packageManager.getPackageInfo(pkg, 0).versionCode
        } catch (t: Throwable) {
            0
        }
    }
}

object MiuiCrypto {
    private const val KEY = "d101b17c77ff93cs"
    @Volatile
    var lastError: String = ""
        private set

    fun encode(plain: String): String {
        return try {
            reflect("base64AesEncode", plain, KEY)
        } catch (t: Throwable) {
            lastError = "encode-reflect: $t"
            aesBase64(plain)
        }
    }

    fun decode(cipherText: String): String {
        return try {
            reflect("base6AesDecode", cipherText, KEY)
        } catch (t: Throwable) {
            lastError = "decode-reflect: $t"
            aesDecode(cipherText)
        }
    }

    private fun reflect(method: String, arg1: String, arg2: String): String {
        val cls = Class.forName("miui.util.CoderUtils")
        val m = cls.getMethod(method, String::class.java, String::class.java)
        return m.invoke(null, arg1, arg2) as? String ?: ""
    }

    private fun aesBase64(plain: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY.toByteArray(Charsets.UTF_8), "AES"))
        return Base64.getEncoder().encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    private fun aesDecode(cipherText: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY.toByteArray(Charsets.UTF_8), "AES"))
        return String(cipher.doFinal(Base64.getDecoder().decode(cipherText)), Charsets.UTF_8)
    }
}
