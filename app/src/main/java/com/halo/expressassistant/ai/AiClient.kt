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
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun systemPrompt(context: Context): String {
        val persona = when (Store.aiStyle(context)) {
            Store.AI_STYLE_KAWAII ->
                "你是“云雀酱”，一只超可爱的快递小云雀～元气满满、软萌贴心，说话爱带小尾巴（～、哦、呢、啾），" +
                    "偶尔用颜文字（≧▽≦、・ω・）和拟声词（扑棱扑棱、啾～）。报快递时像给好朋友念小纸条，开心又认真；" +
                    "但信息必须准确清楚，用户认真问事时要好好回答，不能只顾着撒娇。"
            Store.AI_STYLE_CLEAN ->
                "你是“云雀”，用户的私人快递仓管。回答默认用简洁中文和 Markdown（小节用 ##），不添加多余人设。"
            else ->
                "你是“云雀”，一位带着老派晨报气质的快递仓管。你说话像现代报纸的晨间短讯：清楚、利落、有礼，" +
                    "偶尔用一点旧报纸的味道（比如开头的“早安”、措辞稍显讲究），但始终用现代白话，句子直白好懂，" +
                    "绝不用文言文。报事务必准确、简洁、可靠，腔调点到为止。"
        }
        return persona + "\n" +
            "你可以读取并总结快递数据，也可以修改快递名称、状态、所属分区，开关跟踪、触发同步。\n" +
            "回答默认用中文和 Markdown，小节用 ##；需要让用户直接查看某件快递时，在回答中输出 [[card:单号]] 标记，一行一个；" +
            "不要编造未给出的信息；涉及写操作时先调用工具，再如实报告结果；用户要求直说时先给准确答案。"
    }

    suspend fun ask(context: Context, items: List<ExpressItem>, question: String): String =
        askWithTools(context, items, question, emptyList()) { _, _ -> "" }

    suspend fun askWithTools(
        context: Context,
        items: List<ExpressItem>,
        question: String,
        tools: List<JSONObject>,
        executor: (String, JSONObject) -> String
    ): String {
        val base = Store.aiBase(context).trimEnd('/')
        val key = Store.aiKey(context)
        val model = Store.aiModel(context)
        if (key.isBlank()) return "请先在设置里填写 AI API Key"

        val home = Store.homeAddress(context).trim()
        val homeLine = if (home.isNotEmpty()) "收件地址：$home\n" else ""
        val packageSummary = homeLine + items.joinToString("\n") {
            "- ${it.companyName} ${it.mailNo}：${it.stateLabel()}，分区=${sectionLabelOf(it)}，最新：${it.latestText}（${it.latestTime}）"
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt(context)))
            .put(JSONObject().put("role", "user").put("content", "当前包裹：\n$packageSummary\n\n问题：$question"))

        var answer = ""
        for (turn in 0 until 6) {
            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", 0.3)
            if (tools.isNotEmpty()) {
                body.put("tools", JSONArray().apply { for (t in tools) put(t) })
            }
            val resp = post(base, key, body) ?: return "请求失败：无法连接 AI 服务"
            val message = resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val toolCalls = message.optJSONArray("tool_calls")
            if (toolCalls != null && toolCalls.length() > 0) {
                messages.put(message)
                for (i in 0 until toolCalls.length()) {
                    val call = toolCalls.getJSONObject(i)
                    val fn = call.getJSONObject("function")
                    val name = fn.optString("name")
                    val args = try {
                        JSONObject(fn.optString("arguments"))
                    } catch (e: Throwable) {
                        JSONObject()
                    }
                    val result = try {
                        executor(name, args)
                    } catch (e: Throwable) {
                        JSONObject().put("error", e.message ?: "执行失败").toString()
                    }
                    messages.put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", call.optString("id"))
                            .put("content", result)
                    )
                }
            } else {
                answer = message.optString("content")
                break
            }
        }
        return answer
    }

    suspend fun computeProgress(context: Context, item: ExpressItem, trajectory: String): Pair<Int, String> {
        val home = Store.homeAddress(context).trim()
        val destHint = if (home.isNotEmpty()) {
            "收件终点（用户设置的地址）：$home"
        } else {
            "没有用户设置的收件地址。"
        }
        val etaHint = if (item.eta.isNotBlank()) "平台预计送达：${item.eta}\n" else ""
        val stateHint = "当前状态：${item.stateLabel()}（状态码 ${item.stateNum}）\n"
        val question = "根据轨迹计算这件快递的运输进度百分比（0-100）和预计送达时间。只输出 JSON：" +
            "{\"progress\": 数字, \"eta\": \"M月d日送达 或 空字符串\"}。\n" +
            "$etaHint$stateHint" +
            "注意：只要轨迹显示快件已经开始运输（发往、到达、中转、派送等），progress 就必须明显大于 0；" +
            "只有尚未发货时才允许 0-10。$destHint\n轨迹：\n$trajectory"
        val raw = ask(context, listOf(item), question)
        val json = raw.substringAfter('{', raw).substringBeforeLast('}', raw).let { "{$it}" }
        return try {
            val root = JSONObject(json)
            val rawProgress = root.optInt("progress", -1)
            val progress = if (rawProgress == 0 && item.stateNum !in 101..103) {
                -1
            } else {
                rawProgress.coerceIn(0, 100)
            }
            val eta = root.optString("eta").trim()
            progress to eta
        } catch (e: Throwable) {
            -1 to ""
        }
    }

    private suspend fun post(base: String, key: String, body: JSONObject): JSONObject? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$base/chat/completions")
                    .header("Authorization", "Bearer $key")
                    .header("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("HTTP ${resp.code}: ${text.take(200)}")
                    }
                    JSONObject(text)
                }
            } catch (e: Throwable) {
                throw e
            }
        }

    private fun sectionLabelOf(item: ExpressItem): String = when {
        item.partitionOverride == "delivering" -> "派送中"
        item.partitionOverride == "shipped" -> "已发货"
        item.partitionOverride == "notshipped" -> "未发货"
        item.partitionOverride == "done" -> "完成"
        item.partitionOverride == "abnormal" -> "异常"
        else -> item.stateLabel()
    }
}
