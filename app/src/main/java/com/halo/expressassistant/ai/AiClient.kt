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
        // 记忆放在 system 尾部：persona 与规则不变 → 供应商前缀缓存在记忆不变时全程命中
        val memory = Store.aiMemory(context)
        val memoryBlock = if (memory.isEmpty()) "" else
            "\n关于用户的长期记忆（此前对话中你记下的关键事实）：\n" + memory.joinToString("\n") { "- $it" }
        return persona + "\n" +
            "你可以读取并总结快递数据，也可以修改快递名称、状态、所属分区，开关跟踪、触发同步；" +
            "用 memory_save / memory_forget 工具维护长期记忆（用户告知的偏好、习惯、背景等值得跨对话记住的事）。\n" +
            "回答默认用中文和 Markdown，小节用 ##；需要让用户直接查看某件快递时，在回答中输出 [[card:单号]] 标记，一行一个；" +
            "不要编造未给出的信息；涉及写操作时先调用工具，再如实报告结果；用户要求直说时先给准确答案。" +
            memoryBlock
    }

    suspend fun ask(context: Context, items: List<ExpressItem>, question: String): String =
        askWithTools(context, items, question, emptyList(), executor = { _, _ -> "" })

    /** omni/audio 系模型才吃音频输入 */
    fun isAudioModel(model: String): Boolean =
        model.contains("omni", ignoreCase = true) || model.contains("audio", ignoreCase = true)

    /**
     * 语音提问：录音 WAV → DashScope OSS（oss:// 私有桶 + OssResourceResolve 头，全程不出阿里云）
     * → qwen-omni 原生音频输入。路由：激活配置本身就是音频模型则用它，否则找第一个音频配置。
     */
    suspend fun askWithAudio(
        context: Context,
        items: List<ExpressItem>,
        wav: ByteArray
    ): String {
        val profiles = Store.aiProfileList(context)
        val target = run {
            val active = Store.activeProfile(context)
            if (isAudioModel(active.model)) active else profiles.firstOrNull { isAudioModel(it.model) }
        } ?: return "当前没有支持语音的 AI 配置（需要 qwen-omni 系模型），请在设置里添加"
        if (target.key.isBlank()) return "「${target.label}」还没填 API Key，请先在设置里填写"

        val audioRef = uploadVoice(context, target.key, wav)
            ?: return "语音上传失败，请检查网络后重试"

        val base = target.base.trimEnd('/')
        val home = Store.homeAddress(context).trim()
        val homeLine = if (home.isNotEmpty()) "收件地址：$home\n" else ""
        val packageSummary = homeLine + items.joinToString("\n") {
            "- ${it.companyName} ${it.mailNo}：${it.stateLabel()}，分区=${sectionLabelOf(it)}，最新：${it.latestText}（${it.latestTime}）"
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt(context)))
            .put(JSONObject().put("role", "user").put("content", "当前包裹：\n$packageSummary"))
            .put(JSONObject().put("role", "assistant").put("content", "包裹数据已读取。"))
        for (m in Store.chatHistory(context).takeLast(12)) {
            messages.put(JSONObject().put("role", m.role).put("content", m.content))
        }
        messages.put(
            JSONObject().put("role", "user").put(
                "content",
                JSONArray()
                    .put(JSONObject().put("type", "input_audio").put("input_audio", audioRef))
                    .put(
                        JSONObject().put("type", "text").put(
                            "text",
                            "（这是一条语音消息：先听清用户说了什么再回答。语音识别可能有误，" +
                                "涉及改名/改状态/改分区/删除等写操作时，先复述你听到的指令请求确认，不要直接执行）"
                        )
                    )
            )
        )
        return chatLoop(context, base, target.key, target.model, messages, emptyList()) { _, _ -> "" }
    }

    /** 上传 WAV 到 DashScope OSS，返回 oss:// 引用（带 5 分钟有效期的 policy，直接用） */
    private suspend fun uploadVoice(context: Context, key: String, wav: ByteArray): String? =
        withContext(Dispatchers.IO) {
            try {
                val policyReq = Request.Builder()
                    .url("https://dashscope.aliyuncs.com/api/v1/uploads?action=getPolicy&model=qwen3.8-omni-flash")
                    .header("Authorization", "Bearer $key")
                    .build()
                client.newCall(policyReq).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val data = JSONObject(resp.body?.string() ?: return@withContext null).getJSONObject("data")
                    val objectKey = data.getString("upload_dir") + "/voice_" + System.currentTimeMillis() + ".wav"
                    val multipart = okhttp3.MultipartBody.Builder()
                        .setType(okhttp3.MultipartBody.FORM)
                        .addFormDataPart("OSSAccessKeyId", data.getString("oss_access_key_id"))
                        .addFormDataPart("policy", data.getString("policy"))
                        .addFormDataPart("Signature", data.getString("signature"))
                        .addFormDataPart("key", objectKey)
                        .addFormDataPart("x-oss-object-acl", data.getString("x_oss_object_acl"))
                        .addFormDataPart("x-oss-forbid-overwrite", data.getString("x_oss_forbid_overwrite"))
                        .addFormDataPart("success_action_status", "200")
                        .addFormDataPart(
                            "file", "voice.wav",
                            wav.toRequestBody("audio/wav".toMediaType())
                        )
                        .build()
                    val upReq = Request.Builder().url(data.getString("upload_host")).post(multipart).build()
                    client.newCall(upReq).execute().use { up ->
                        if (!up.isSuccessful) return@withContext null
                    }
                    "oss://" + objectKey
                }
            } catch (e: Throwable) {
                null
            }
        }

    /**
     * 消息组装（对齐前缀缓存：稳定段在前，易变段在后，命中供应商的隐式上下文缓存）：
     *   [system：人设+规则+记忆（很少变）] → [user：包裹快照（会话内不变）] → [assistant：确认]
     *   → [历史轮次] → [user：本次问题]
     */
    suspend fun askWithTools(
        context: Context,
        items: List<ExpressItem>,
        question: String,
        tools: List<JSONObject>,
        executor: (String, JSONObject) -> String,
        withHistory: Boolean = false
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
            .put(
                JSONObject().put("role", "user")
                    .put("content", "当前包裹：\n$packageSummary\n\n（以上为本次会话的包裹数据快照，后续提问都基于它。）")
            )
            .put(JSONObject().put("role", "assistant").put("content", "包裹数据已读取。"))
        if (withHistory) {
            val history = Store.chatHistory(context)
            val previous = if (history.lastOrNull()?.let { it.role == "user" && it.content == question } == true)
                history.dropLast(1) else history
            for (m in previous.takeLast(12)) {
                messages.put(JSONObject().put("role", m.role).put("content", m.content))
            }
        }
        messages.put(JSONObject().put("role", "user").put("content", question))
        return chatLoop(context, base, key, model, messages, tools, executor)
    }

    /** 工具循环：发请求 → 执行工具 → 回填，直到给出最终文本（最多 6 轮） */
    private suspend fun chatLoop(
        context: Context,
        base: String,
        key: String,
        model: String,
        messages: JSONArray,
        tools: List<JSONObject>,
        executor: (String, JSONObject) -> String
    ): String {
        var answer = ""
        for (turn in 0 until 6) {
            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", 0.3)
            // 思考深度限 low：DeepSeek-V4 思考模式默认 effort=high、千问同样支持该参数——
            // low 档省钱、出结果快，问答与进度计算这类任务准度基本无损。
            // omni 多模态系不传（参数集不同，实测 omni 默认自带思考）
            if (!isAudioModel(model)) body.put("reasoning_effort", "low")
            if (model.contains("omni", ignoreCase = true)) body.put("modalities", JSONArray().put("text"))
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

    suspend fun computeProgress(
        context: Context,
        item: ExpressItem,
        trajectory: String,
        address: String? = null
    ): Pair<Int, String> {
        val home = (address ?: Store.addressForItem(context, item)).trim()
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
                val builder = Request.Builder()
                    .url("$base/chat/completions")
                    .header("Authorization", "Bearer $key")
                    .header("Content-Type", "application/json")
                // 消息里引用 oss:// 私有对象时，需要打开资源解析头
                if (body.toString().contains("oss://")) {
                    builder.header("X-DashScope-OssResourceResolve", "enable")
                }
                val request = builder
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
