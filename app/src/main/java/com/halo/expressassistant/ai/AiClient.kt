package com.halo.expressassistant.ai

import android.content.Context
import com.halo.expressassistant.data.ExpressItem
import com.halo.expressassistant.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object AiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun ask(context: Context, items: List<ExpressItem>, question: String): String {
        val base = Store.aiBase(context).trimEnd('/')
        val key = Store.aiKey(context)
        val model = Store.aiModel(context)
        if (key.isBlank()) return "请先在设置里填写 AI API Key"

        val packageSummary = items.joinToString("\n") {
            "- ${it.companyName} ${it.mailNo}：${it.stateLabel()}，最新：${it.latestText}（${it.latestTime}）"
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", "你是用户的快递助手。请用简洁中文回答，结合给定包裹数据，不要编造未给出的信息。"))
                .put(JSONObject().put("role", "user").put("content", "当前包裹：\n$packageSummary\n\n问题：$question")))
            .put("temperature", 0.3)
            .toString()

        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$base/chat/completions")
                    .header("Authorization", "Bearer $key")
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext "请求失败 ${resp.code}: ${text.take(300)}"
                    val root = JSONObject(text)
                    root.getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content")
                }
            } catch (e: Throwable) {
                "出错：$e"
            }
        }
    }
}
