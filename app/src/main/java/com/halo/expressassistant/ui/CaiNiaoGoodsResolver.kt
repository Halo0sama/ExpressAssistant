package com.halo.expressassistant.ui

import android.content.Context
import android.util.Log
import com.halo.expressassistant.data.JdGoods
import com.halo.expressassistant.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * 菜鸟商品溯源（淘宝系快递，需淘宝登录态）：
 * 调用 mtop 接口 queryalltrace（appKey=12574478，wapSign=md5(token&t&appKey&data)），
 * 从响应 data.result[0].packageItems 提取商品名称/图片/数量。
 * 无需 WebView，纯 OkHttp 实现。
 */
object CaiNiaoGoodsResolver {

    private const val TAG = "CaiNiaoResolver"
    private const val APPKEY = "12574478"
    private const val BASE =
        "https://acs.m.taobao.com/h5/mtop.taobao.logisticstracedetailservice.queryalltrace/1.0/"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 12; M2102K1C) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/105.0.0.0 Mobile Safari/537.36 EdgA/105.0.1343.48"

    private val client = OkHttpClient.Builder().build()

    suspend fun resolve(context: Context, mailNo: String): JdGoods? =
        resolveWith(Store.tbCookies(context), mailNo)

    /** 多源绑定：按指定淘宝账号凭证解析商品 */
    suspend fun resolveWith(cookies: String, mailNo: String): JdGoods? = withContext(Dispatchers.IO) {
        val result = queryResultWith(cookies, mailNo) ?: return@withContext null
        parseGoodsFromResult(result)
    }

    /** 轨迹（供详情页时间线使用）——旧接口 */
    suspend fun fetchTraces(context: Context, mailNo: String): List<com.halo.expressassistant.data.DetailPoint>? =
        fetchTracesWith(Store.tbCookies(context), mailNo)

    /** 多源绑定：按指定淘宝账号凭证取轨迹 */
    suspend fun fetchTracesWith(cookies: String, mailNo: String): List<com.halo.expressassistant.data.DetailPoint>? =
        withContext(Dispatchers.IO) {
            val result = queryResultWith(cookies, mailNo) ?: return@withContext null
            try {
                val traces = result.optJSONArray("fullTraceDetail") ?: return@withContext null
                val points = ArrayList<com.halo.expressassistant.data.DetailPoint>()
                for (i in traces.length() - 1 downTo 0) {
                    val t = traces.getJSONObject(i)
                    points.add(
                        com.halo.expressassistant.data.DetailPoint(
                            context = t.optString("desc"),
                            time = t.optString("time"),
                            formattedTime = t.optString("time")
                        )
                    )
                }
                points
            } catch (e: Throwable) {
                Log.w(TAG, "fetchTraces parse fail: $e")
                null
            }
        }

    /** 查询并返回 result[0]（goods 与轨迹共用一次请求）——旧接口 */
    suspend fun queryResult(context: Context, mailNo: String): JSONObject? =
        queryResultWith(Store.tbCookies(context), mailNo)

    /** 多源绑定：指定淘宝账号凭证 */
    suspend fun queryResultWith(tbCookies: String, mailNo: String): JSONObject? = withContext(Dispatchers.IO) {
        if (tbCookies.isBlank()) return@withContext null
        try {
            // 1) 刷新 mtop token（从 Set-Cookie 拿新的 _m_h5_tk，手动管理 Cookie，避免 CookieJar 合并出双值）
            var cookiesToSend = tbCookies
            runCatching {
                val (_, setCookies) = call("${BASE}?appKey=$APPKEY", tbCookies)
                val newTk = Regex("_m_h5_tk=([^;]+)").find(setCookies)?.groupValues?.get(1)
                val newEnc = Regex("_m_h5_tk_enc=([^;]+)").find(setCookies)?.groupValues?.get(1)
                if (!newTk.isNullOrBlank()) {
                    cookiesToSend = cookiesToSend.replace(Regex("_m_h5_tk=[^;]*"), "_m_h5_tk=$newTk")
                }
                if (!newEnc.isNullOrBlank()) {
                    cookiesToSend = cookiesToSend.replace(Regex("_m_h5_tk_enc=[^;]*"), "_m_h5_tk_enc=$newEnc")
                }
            }
            val token = Regex("_m_h5_tk=([^;]+)").find(cookiesToSend)
                ?.groupValues?.get(1)?.substringBefore("_")
            if (token.isNullOrBlank()) {
                Log.w(TAG, "no _m_h5_tk token")
                return@withContext null
            }
            // 2) 组装 data + wapSign
            val dataObj = JSONObject()
                .put("mailNo", mailNo)
                .put("appName", "GUOGUO")
                .put("actor", "RECEIVER")
                .put("isAccoutOut", true)
                .put("isShowConsignDetail", true)
                .put("ignoreInvalidNode", true)
                .put("isUnique", true)
                .put("isStandard", true)
                .put("isShowItem", true)
                .put("isShowTemporalityService", true)
                .put("isShowCommonService", true)
                .put("isStandardActionCode", true)
                .put("isOrderByAction", true)
                .put("isShowExpressMan", true)
                .put("isShowProgressbar", true)
                .put("isShowLastOneService", true)
                .put("isShowServiceProvider", true)
                .put("isShowDeliveryProgress", true)
            val dataRaw = dataObj.toString()
            val t = System.currentTimeMillis().toString()
            val sign = md5("$token&$t&$APPKEY&$dataRaw")
            val url = BASE + "?jsv=2.3.18&appKey=$APPKEY&t=$t&sign=$sign" +
                    "&type=originaljson&data=" + URLEncoder.encode(dataRaw, "UTF-8")
            val (body, _) = call(url, cookiesToSend)
            Log.i(TAG, "resp len=${body.length}")
            try {
                val root = JSONObject(body)
                val result = root.getJSONObject("data").getJSONArray("result")
                if (result.length() == 0) null else result.getJSONObject(0)
            } catch (e: Throwable) {
                Log.w(TAG, "queryResult parse fail: $e")
                null
            }
        } catch (e: Throwable) {
            Log.w(TAG, "queryResult fail: $e")
            null
        }
    }

    private fun parseGoodsFromResult(r0: JSONObject): JdGoods? {
        return try {
            val items = r0.optJSONArray("packageItems") ?: return null
            if (items.length() == 0) return null
            val it = items.getJSONObject(0)
            val name = it.optString("goodsName")
            val pic = it.optString("itemPic").ifBlank { it.optString("allPicUrl") }
            val qty = it.optInt("goodsQuantity", -1)
            if (name.isBlank() && pic.isBlank()) null
            else JdGoods(
                name = name,
                imageUrl = pic,
                count = if (qty > 0) "x$qty" else ""
            )
        } catch (e: Throwable) {
            Log.w(TAG, "parseGoodsFromResult fail: $e")
            null
        }
    }

    private fun call(url: String, cookies: String): Pair<String, String> {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Origin", "https://page.cainiao.com")
            .header("Referer", "https://page.cainiao.com/")
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Cookie", cookies)
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val setCookies = resp.headers("Set-Cookie").joinToString("\n")
            return body to setCookies
        }
    }

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
