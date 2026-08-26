package com.halo.expressassistant.api

import android.content.Context
import android.util.Log
import com.halo.expressassistant.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * 淘宝系包裹列表：无需小米登录。
 * 1) mtop.taobao.order.queryboughtlistv2 拿订单列表（orderId + orderStatus）
 * 2) 对每个有物流的订单请求 SSR 物流详情页 pages-g.m.taobao.com .../logisticsV2/h5-detail，
 *    从 __ICE_SUSPENSE_LOADER__ 里解析出承运商 + 运单号 + 轨迹 + 状态。
 */
object TbOrders {

    private const val TAG = "TbOrders"
    private const val APPKEY = "12574478"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 12; M2102K1C) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/105.0.0.0 Mobile Safari/537.36 EdgA/105.0.1343.48"

    data class TbOrder(
        val orderId: String,
        val tradeStatus: String,
        val statusText: String,
        val goodsName: String = "",
        val goodsPic: String = ""
    )

    data class TbParcel(
        val mailNo: String,
        val cpName: String,
        val stateLabel: String,
        val latestText: String,
        val latestTime: String,
        val orderId: String
    )

    private val client = OkHttpClient.Builder().build()

    /** 淘宝订单列表（第一页，可翻页）——旧接口：使用第一个启用淘宝账号 */
    suspend fun fetchBoughtList(context: Context, page: Int = 1): List<TbOrder> =
        fetchBoughtListWith(Store.tbCookies(context), page)

    /** 多源绑定：按指定淘宝账号凭证取订单列表 */
    suspend fun fetchBoughtListWith(cookies: String, page: Int = 1): List<TbOrder> =
        withContext(Dispatchers.IO) {
            if (cookies.isBlank()) return@withContext emptyList()
            try {
                val dataObj = JSONObject()
                    .put("tabCode", "all")
                    .put("page", page)
                    .put("OrderType", "OrderList")
                    .put("templateConfigVersion", "0")
                    .put("appName", "tborder")
                    .put("appVersion", "3.0")
                    .put("condition", "{\"version\":\"1.0.0\",\"appChannel\":\"\"}")
                    .put("ttid", "201200@taobao_h5_9.18.0")
                    .put("requestIdentity", "#t#ip#h5")
                val body = mtopCall(
                    cookies,
                    "mtop.taobao.order.queryboughtlistv2", "1.0",
                    dataObj.toString(),
                    host = "h5api.m.taobao.com"
                )
                Log.i(TAG, "boughtlist len=${body.length} head=${body.take(150)}")
                parseBoughtList(body)
            } catch (e: Throwable) {
                Log.w(TAG, "fetchBoughtList fail: $e")
                emptyList()
            }
        }

    /** 订单的 SSR 物流详情 -> 包裹信息（含全轨迹，供详情页时间线）——旧接口 */
    suspend fun fetchSsrDetail(context: Context, orderId: String): TbParcel? =
        fetchSsrDetailWith(Store.tbCookies(context), orderId)

    /** 多源绑定：指定淘宝账号凭证 */
    suspend fun fetchSsrDetailWith(cookies: String, orderId: String): TbParcel? =
        withContext(Dispatchers.IO) {
            if (cookies.isBlank()) return@withContext null
            try {
                val url = "https://pages-g.m.taobao.com/wow/z/app/mtb/logisticsV2/h5-detail" +
                        "?x-ssr=true&bizOrderId=$orderId"
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .header("Cookie", cookies)
                    .build()
                val html = client.newCall(req).execute().use { it.body?.string().orEmpty() }
                parseSsrDetail(html, orderId)
            } catch (e: Throwable) {
                Log.w(TAG, "fetchSsrDetail fail for $orderId: $e")
                null
            }
        }

    /** SSR 轨迹（供详情页；需订单号，淘宝源 item 的 queryChannel 里存了 orderId）——旧接口 */
    suspend fun fetchSsrTraces(context: Context, orderId: String): List<com.halo.expressassistant.data.DetailPoint>? =
        fetchSsrTracesWith(Store.tbCookies(context), orderId)

    /** 多源绑定：指定淘宝账号凭证 */
    suspend fun fetchSsrTracesWith(cookies: String, orderId: String): List<com.halo.expressassistant.data.DetailPoint>? =
        withContext(Dispatchers.IO) {
            if (cookies.isBlank()) return@withContext null
            try {
                val url = "https://pages-g.m.taobao.com/wow/z/app/mtb/logisticsV2/h5-detail" +
                        "?x-ssr=true&bizOrderId=$orderId"
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .header("Cookie", cookies)
                    .build()
                val html = client.newCall(req).execute().use { it.body?.string().orEmpty() }
                parseSsrTraces(html)
            } catch (e: Throwable) {
                Log.w(TAG, "fetchSsrTraces fail for $orderId: $e")
                null
            }
        }

    /* ───────────── 解析 ───────────── */

    private fun parseBoughtList(body: String): List<TbOrder> {
        val json = body.substringAfter("(", "").substringBeforeLast(")")
        if (json.isBlank()) return emptyList()
        val root = JSONObject(json)
        val data = root.optJSONObject("data") ?: return emptyList()
        // 新格式：data.result 是字符串，内层 JSON 有 mainOrders；旧格式：data.data.Main_*
        val inner: JSONObject? = runCatching {
            JSONObject(data.optString("result"))
        }.getOrNull() ?: data.optJSONObject("data")
        val orders = ArrayList<TbOrder>()
        val mainOrders = inner?.optJSONArray("mainOrders")
        if (mainOrders != null) {
            for (i in 0 until mainOrders.length()) {
                val o = mainOrders.getJSONObject(i)
                val id = o.optString("id")
                val tradeStatus = o.optJSONObject("extra")?.optString("tradeStatus") ?: ""
                val statusText = o.optJSONObject("statusInfo")?.optString("text") ?: ""
                val item = o.optJSONArray("subOrders")?.optJSONObject(0)?.optJSONObject("itemInfo")
                orders.add(
                    TbOrder(
                        orderId = id,
                        tradeStatus = tradeStatus,
                        statusText = statusText,
                        goodsName = item?.optString("title") ?: "",
                        goodsPic = item?.optString("pic") ?: ""
                    )
                )
            }
            return orders
        }
        // 旧格式兜底
        val keys = inner?.keys() ?: return emptyList()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.startsWith("Main_")) {
                val fields = inner.optJSONObject(k)?.optJSONObject("fields") ?: continue
                val id = fields.optString("orderId").ifBlank { k.removePrefix("Main_") }
                val status = fields.optString("orderStatus")
                if (id.isNotBlank()) orders.add(TbOrder(id, "", status))
            }
        }
        return orders
    }

    private fun parseSsrDetail(html: String, orderId: String): TbParcel? {
        val logistics = ssrLogistics(html) ?: return null
        return try {
            val company = logistics.optJSONObject("logisticCompany") ?: return null
            val mailNo = logistics.optString("mailNo").ifBlank { company.optString("mailNo") }
            val cpName = company.optString("name")
            if (mailNo.isBlank()) return null
            val stages = logistics.optJSONArray("multiStage") ?: JSONArray()
            var stateLabel = ""
            var latestText = ""
            var latestTime = ""
            if (stages.length() > 0) {
                val first = stages.getJSONObject(0)
                stateLabel = first.optString("title")
                latestTime = first.optString("subtitle").ifBlank { first.optString("time") }
                latestText = stageText(first)
            }
            TbParcel(mailNo, cpName, stateLabel, latestText, latestTime, orderId)
        } catch (e: Throwable) {
            Log.w(TAG, "parseSsrDetail fail: $e")
            null
        }
    }

    private fun ssrLogistics(html: String): JSONObject? {
        val marker = "__ICE_SUSPENSE_LOADER__']['undefined'] = "
        val i = html.indexOf(marker)
        if (i < 0) return null
        return try {
            val obj = org.json.JSONTokener(html.substring(i + marker.length)).nextValue() as JSONObject
            val data = obj.optJSONObject("result")?.optJSONObject("data") ?: return null
            data.optJSONObject("newLogistics")?.optJSONObject("fields")
                ?: data.optJSONObject("logisticsDetailH5")?.optJSONObject("fields")
        } catch (e: Throwable) {
            null
        }
    }

    private fun stageText(stage: JSONObject): String {
        val sb = StringBuilder()
        val ld = stage.optJSONObject("labelDesc")
        if (ld != null) {
            val rich = ld.optJSONArray("richContent")
            if (rich != null) {
                for (r in 0 until rich.length()) {
                    sb.append(rich.optJSONObject(r)?.optString("text") ?: "")
                }
            } else {
                sb.append(ld.optString("text"))
            }
        }
        if (sb.isEmpty()) {
            val raw = stage.optString("labelDesc")
            if (!raw.startsWith("{")) sb.append(raw)
        }
        if (sb.isEmpty()) sb.append(stage.optString("text"))
        return sb.toString()
    }

    private fun parseSsrTraces(html: String): List<com.halo.expressassistant.data.DetailPoint>? {
        val logistics = ssrLogistics(html) ?: return null
        val stages = logistics.optJSONArray("multiStage") ?: return null
        val points = ArrayList<com.halo.expressassistant.data.DetailPoint>()
        for (i in 0 until stages.length()) {
            val st = stages.getJSONObject(i)
            val time = st.optString("subtitle").ifBlank { st.optString("time") }
            val desc = stageText(st)
            if (desc.isNotBlank()) {
                points.add(
                    com.halo.expressassistant.data.DetailPoint(
                        context = desc,
                        time = time,
                        formattedTime = time
                    )
                )
            }
        }
        return points
    }

    /* ───────────── mtop 通用调用（wapSign） ───────────── */

    private fun mtopCall(cookies: String, api: String, v: String, dataRaw: String, host: String): String {
        val base = "https://$host/h5/$api/$v/"
        // 1) 刷新 token
        val headers = mapOf(
            "User-Agent" to UA,
            "Origin" to "https://h5.m.taobao.com",
            "Referer" to "https://h5.m.taobao.com/",
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "zh-CN,zh;q=0.9",
            "Cookie" to cookies
        )
        var ck = cookies
        runCatching {
            val req = Request.Builder().url("${base}?appKey=$APPKEY").apply {
                headers.forEach { (k, vv) -> header(k, vv) }
            }.build()
            client.newCall(req).execute().use { resp ->
                val setCookies = resp.headers("Set-Cookie").joinToString("\n")
                val newTk = Regex("_m_h5_tk=([^;]+)").find(setCookies)?.groupValues?.get(1)
                val newEnc = Regex("_m_h5_tk_enc=([^;]+)").find(setCookies)?.groupValues?.get(1)
                if (!newTk.isNullOrBlank()) ck = ck.replace(Regex("_m_h5_tk=[^;]*"), "_m_h5_tk=$newTk")
                if (!newEnc.isNullOrBlank()) ck = ck.replace(Regex("_m_h5_tk_enc=[^;]*"), "_m_h5_tk_enc=$newEnc")
            }
        }
        val token = Regex("_m_h5_tk=([^;]+)").find(ck)?.groupValues?.get(1)?.substringBefore("_")
            ?: throw IllegalStateException("no mtop token")
        val t = System.currentTimeMillis().toString()
        val sign = md5("$token&$t&$APPKEY&$dataRaw")
        val url = base + "?jsv=2.3.18&appKey=$APPKEY&t=$t&sign=$sign" +
                "&type=jsonp&dataType=jsonp&data=" + URLEncoder.encode(dataRaw, "UTF-8")
        val req = Request.Builder().url(url).apply {
            headers.forEach { (k, vv) -> header(k, vv) }
            header("Cookie", ck)
        }.build()
        return client.newCall(req).execute().use { it.body?.string().orEmpty() }
    }

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
