package com.halo.expressassistant.api

import android.content.Context
import com.halo.expressassistant.data.DetailPoint
import com.halo.expressassistant.data.ExpressDetail
import com.halo.expressassistant.data.ExpressItem
import com.halo.expressassistant.data.Store
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.UUID

object KuaiDi100 {
    private const val MOBILE_SECRET = "L0Z1yKqPXseWi4ERAUFnxQmgHwhafITG"
    private val client = OkHttpClient.Builder().build()
    private val json = Json { ignoreUnknownKeys = true }

    data class Company(val comCode: String, val name: String)

    suspend fun detectCompany(mailNo: String): Company? {
        val url = "https://www.kuaidi100.com/autonumber/auto?num=${mailNo}"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 5.1.1; PCT-AL10 Build/LYZ28N)")
            .build()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    val arr = json.parseToJsonElement(text).jsonArray
                    if (arr.isEmpty()) null else {
                        val obj = arr[0].jsonObject
                        Company(obj["comCode"]?.jsonPrimitive?.content ?: "", obj["name"]?.jsonPrimitive?.content ?: "")
                    }
                }
            } catch (e: Throwable) {
                null
            }
        }
    }

    suspend fun fetchDetail(context: Context, item: ExpressItem): ExpressDetail {
        val key = Store.kdKey(context)
        val customer = Store.kdCustomer(context)
        if (key.isNotBlank() && customer.isNotBlank()) {
            return officialQuery(item, key, customer)
        }
        return mobileQuery(item)
    }

    private suspend fun officialQuery(item: ExpressItem, key: String, customer: String): ExpressDetail {
        val param = """{"com":"${item.companyCode}","num":"${item.mailNo}""" +
                (if (!item.phone.isNullOrEmpty()) ""","phone":"${item.phone}"""" else "") + "}"
        val sign = md5("$param$key$customer").uppercase()
        val form = FormBody.Builder()
            .add("param", param)
            .add("customer", customer)
            .add("sign", sign)
            .build()
        val request = Request.Builder()
            .url("https://poll.kuaidi100.com/poll/query.do")
            .post(form)
            .build()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                parseResponse(text, item)
            }
        }
    }

    private suspend fun mobileQuery(item: ExpressItem): ExpressDetail {
        val params = linkedMapOf<String, String?>(
            "type" to "detail",
            "appid" to "com.Kingdee.Express",
            "versionCode" to "693",
            "os_version" to "android5.1.1",
            "os_name" to "PCT-AL10",
            "t" to System.currentTimeMillis().toString(),
            "tra" to UUID.randomUUID().toString(),
            "uchannel" to "null",
            "nt" to "wifi",
            "deviceId" to UUID.randomUUID().hashCode().toString(),
            "apiversion" to "18",
            "phone" to item.phone,
            "num" to item.mailNo,
            "com" to item.companyCode
        )
        val paramJson = jsonMap(params)
        val hash = md5("$MOBILE_SECRET$paramJson").uppercase()
        val form = FormBody.Builder()
            .add("method", "query")
            .add("json", paramJson)
            .add("token", "")
            .add("hash", hash)
            .add("userid", "0")
            .build()
        val request = Request.Builder()
            .url("https://p.kuaidi100.com/mobile/mobileapi.do?method=query")
            .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 5.1.1; PCT-AL10 Build/LYZ28N)")
            .post(form)
            .build()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            client.newCall(request).execute().use { resp ->
                parseResponse(resp.body?.string().orEmpty(), item)
            }
        }
    }

    private fun parseResponse(text: String, item: ExpressItem): ExpressDetail {
        return try {
            val root = json.parseToJsonElement(text).jsonObject
            val last = root["lastResult"]?.jsonObject ?: root
            val state = last["state"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
            val isReceived = root["ischeck"]?.jsonPrimitive?.content == "1" ||
                    last["ischeck"]?.jsonPrimitive?.content == "1"
            val expTime = last["expTime"]?.jsonPrimitive?.content
                ?: root["expTime"]?.jsonPrimitive?.content
            val eta = if (!expTime.isNullOrBlank()) {
                try {
                    val date = expTime.trim().split(" ")[0].split("-")
                    if (date.size == 3) "${date[1].toInt()}月${date[2].toInt()}日" else EtaParser.extract(expTime)
                } catch (e: Throwable) {
                    EtaParser.extract(expTime)
                }
            } else {
                ""
            }
            val points = last["data"]?.jsonArray?.mapNotNull { el ->
                val obj = el.jsonObject
                DetailPoint(
                    context = obj["context"]?.jsonPrimitive?.content ?: "",
                    time = obj["time"]?.jsonPrimitive?.content ?: "",
                    formattedTime = obj["ftime"]?.jsonPrimitive?.content ?: ""
                )
            } ?: emptyList()
            ExpressDetail(item.mailNo, item.companyName, state, isReceived, points, eta)
        } catch (e: Throwable) {
            ExpressDetail(item.mailNo, item.companyName, -1, false, emptyList(), "")
        }
    }

    private fun jsonMap(map: LinkedHashMap<String, String?>): String {
        return map.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            val value = if (v == null) "null" else "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            "\"$k\":$value"
        }
    }

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
