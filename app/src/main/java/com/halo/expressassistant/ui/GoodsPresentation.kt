package com.halo.expressassistant.ui

import android.content.Context
import android.util.Log
import com.halo.expressassistant.ai.AiClient
import com.halo.expressassistant.data.ExpressItem
import com.halo.expressassistant.data.JdGoods
import com.halo.expressassistant.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 卡片外显辅助：取件码解析 + 商品短名优化（AI + 规则兜底）。
 */
object GoodsPresentation {

    private const val TAG = "GoodsPres"
    private const val MAX_NAME_LEN = 12

    private val pickupRe = Regex("""(取件码|取货码|提货码|自提码|驿站码|取件验证码)[:：\s]*([A-Za-z0-9][A-Za-z0-9\-]{1,12})""")

    /** 从文本中解析取件码；无则 null */
    fun pickupCodeFrom(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val m = pickupRe.find(text) ?: return null
        return m.groupValues[2]
    }

    /** 合并多段文本解析取件码（轨迹全文场景） */
    fun pickupCodeFromParts(parts: List<String?>): String? {
        for (p in parts) {
            val c = pickupCodeFrom(p)
            if (c != null) return c
        }
        return null
    }

    /** 规则兜底：去掉括号内容、压缩空格、截断 */
    fun ruleShorten(name: String): String {
        var s = name
            .replace(Regex("""【[^】]*】"""), " ")
            .replace(Regex("""（[^）]*）"""), " ")
            .replace(Regex("""\([^)]*\)"""), " ")
            .replace(Regex("""\[[^\]]*\]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .replace(Regex("""^(仅拆封|官方旗舰店|旗舰店|自营店)"""), "")
            .trim()
        if (s.length > MAX_NAME_LEN) {
            s = s.take(MAX_NAME_LEN)
        }
        return s
    }

    /** 商品短名：优先缓存，其次 AI 优化，失败规则兜底 */
    suspend fun shortNameOf(context: Context, mailNo: String, rawName: String): String =
        withContext(Dispatchers.IO) {
            val cached = Store.jdGoods(context)[mailNo]
            if (cached != null && cached.shortName.isNotBlank()) {
                return@withContext cached.shortName
            }
            val short = if (rawName.length > MAX_NAME_LEN) {
                optimize(context, rawName)
            } else {
                rawName
            }
            val map = Store.jdGoods(context).toMutableMap()
            map[mailNo] = (map[mailNo] ?: JdGoods(name = rawName)).copy(shortName = short)
            Store.saveJdGoods(context, map)
            short
        }

    /** 批量优化：分块（每批 8 个）一次 AI 调用，返回 原名->短名 映射 */
    suspend fun batchShorten(context: Context, names: Map<String, String>): Map<String, String> {
        if (names.isEmpty()) return emptyMap()
        if (Store.aiKey(context).isBlank()) {
            return names.mapValues { ruleShorten(it.value) }
        }
        val result = HashMap<String, String>()
        val entries = names.entries.toList()
        for (chunk in entries.chunked(8)) {
            try {
                val joined = chunk.joinToString("\n") { "${it.key} ||| ${it.value}" }
                val question = "把下面的商品名改写成简短的\"厂商/品牌+型号+产品名\"（去颜色/冗词/【】备注，保留型号如 MM3A、IN9），" +
                    "每一条不超过 $MAX_NAME_LEN 个汉字，保持可辨识度。只输出 JSON 对象，键为行号，值为短名：\n$joined"
                val raw = AiClient.ask(context, emptyList(), question)
                Log.i(TAG, "batchShorten ai raw: ${raw.take(120)}")
                val json = raw.substringAfter('{', raw).substringBeforeLast('}', raw).let { "{$it}" }
                val root = JSONObject(json)
                chunk.forEachIndexed { i, e ->
                    val v = root.optString((i + 1).toString()).ifBlank {
                        root.optString(e.key).ifBlank { ruleShorten(e.value) }
                    }
                    result[e.key] = v.trim().take(MAX_NAME_LEN + 4)
                }
            } catch (ex: Throwable) {
                Log.w(TAG, "batchShorten chunk fail: $ex")
                chunk.forEach { result[it.key] = ruleShorten(it.value) }
            }
        }
        return result
    }

    /** 单名优化（详情页溯源后即时用） */
    suspend fun optimize(context: Context, name: String): String {
        if (Store.aiKey(context).isBlank()) return ruleShorten(name)
        return try {
            val question = "把商品名\"$name\"改写成\"厂商/品牌+型号+产品名\"的简短形式，去掉颜色、冗余和【】备注，保留型号（如 MM3A），" +
                "不超过 $MAX_NAME_LEN 个汉字。只输出短名本身，不要引号、不要解释。"
            val raw = AiClient.ask(context, emptyList(), question)
            val s = raw.trim().trim('"').replace(Regex("""\s+"""), " ")
            if (s.isBlank() || s.length > MAX_NAME_LEN + 6) ruleShorten(name) else s
        } catch (e: Throwable) {
            ruleShorten(name)
        }
    }
}
