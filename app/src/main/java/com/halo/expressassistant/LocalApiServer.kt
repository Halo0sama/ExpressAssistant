package com.halo.expressassistant

import android.content.Context
import android.util.Log
import com.halo.expressassistant.ai.AiClient
import com.halo.expressassistant.api.XiaomiDetail
import com.halo.expressassistant.api.XiaomiSync
import com.halo.expressassistant.api.XiaomiApi
import com.halo.expressassistant.data.Store
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

object ApiServer {
    const val PORT = 8765

    @Volatile
    var instance: LocalApiServer? = null
        private set

    fun isRunning(): Boolean = instance != null

    fun start(context: Context) {
        synchronized(this) {
            if (instance != null) return
            try {
                LocalApiServer(context.applicationContext).also {
                    it.start(500, false)
                    instance = it
                }
                Log.i("LocalApi", "server started on 127.0.0.1:$PORT")
            } catch (e: Throwable) {
                Log.w("LocalApi", "server start failed", e)
            }
        }
    }

    fun stop() {
        synchronized(this) {
            instance?.stop()
            instance = null
        }
    }
}

class LocalApiServer(private val app: Context) : NanoHTTPD("127.0.0.1", ApiServer.PORT) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.removeSuffix("/")
        return try {
            when {
                uri == "/api/health" && session.method == Method.GET ->
                    ok(JSONObject().put("ok", true).put("app", "express-assistant").toString())

                uri == "/api/express" && session.method == Method.GET ->
                    ok(listJson().toString())

                uri == "/api/raw/list" && session.method == Method.GET ->
                    ok(rawListJson())

                uri.startsWith("/api/express/") && session.method == Method.GET ->
                    detailJson(uri.removePrefix("/api/express/"))

                uri == "/api/sync" && session.method == Method.POST ->
                    ok(syncJson().toString())

                uri == "/api/track" && session.method == Method.POST ->
                    ok(trackJson(bodyOf(session)).toString())

                uri == "/api/rename" && session.method == Method.POST ->
                    ok(renameJson(bodyOf(session)).toString())

                uri == "/mcp" && session.method == Method.POST ->
                    ok(mcp(bodyOf(session)).toString())

                else -> error(Response.Status.NOT_FOUND, "not found")
            }
        } catch (e: Throwable) {
            Log.w("LocalApi", "serve error", e)
            error(Response.Status.INTERNAL_ERROR, e.message ?: "internal error")
        }
    }

    private fun listJson(): JSONArray {
        val arr = JSONArray()
        for (item in Store.items(app)) {
            arr.put(
                JSONObject()
                    .put("mailNo", item.mailNo)
                    .put("companyCode", item.companyCode)
                    .put("companyName", item.companyName)
                    .put("latestText", item.latestText)
                    .put("latestTime", item.latestTime)
                    .put("state", item.state)
                    .put("stateName", item.stateName.ifBlank { item.stateLabel() })
                    .put("eta", item.eta)
                    .put("tracked", item.tracked)
                    .put("provider", item.provider)
                    .put("phone", item.phone ?: "")
            )
        }
        return arr
    }

    private fun detailJson(mailNo: String): Response {
        val item = Store.items(app).firstOrNull { it.mailNo == mailNo }
            ?: return error(Response.Status.NOT_FOUND, "mailNo not found: $mailNo")
        val detail = runBlocking { XiaomiDetail.fetch(app, item) }
        val points = JSONArray()
        for (p in detail.data) {
            points.put(JSONObject().put("time", p.time).put("formattedTime", p.formattedTime).put("context", p.context))
        }
        return ok(
            JSONObject()
                .put("mailNo", detail.mailNo)
                .put("companyName", detail.companyName)
                .put("state", detail.state)
                .put("isReceived", detail.isReceived)
                .put("eta", item.eta)
                .put("points", points)
                .toString()
        )
    }

    private fun rawListJson(): String {
        val body = XiaomiApi.getListBody(Store.xiaomiPhones(app))
        return XiaomiApi.fetchList(
            app,
            Store.xiaomiToken(app),
            Store.xiaomiCUser(app),
            body,
            Store.xiaomiAccountId(app),
            Store.xiaomiOaid(app),
            Store.xiaomiVaid(app)
        )
    }

    private fun syncJson(): JSONObject {
        val message = runBlocking { XiaomiSync.sync(app) }
        return JSONObject().put("ok", true).put("message", message)
    }

    private fun trackJson(body: String): JSONObject {
        val root = JSONObject(body)
        val mailNo = root.optString("mailNo")
        val tracked = root.optBoolean("tracked")
        val items = Store.items(app).map {
            if (it.mailNo == mailNo) {
                if (tracked) {
                    it.copy(tracked = true, notifiedText = it.latestText, notifiedTime = it.latestTime)
                } else {
                    it.copy(tracked = false)
                }
            } else {
                it
            }
        }
        Store.saveItems(app, items)
        return JSONObject().put("ok", true).put("mailNo", mailNo).put("tracked", tracked)
    }

    private fun renameJson(body: String): JSONObject {
        val root = JSONObject(body)
        val mailNo = root.optString("mailNo")
        val name = root.optString("name").trim()
        if (name.isEmpty()) return JSONObject().put("ok", false).put("error", "name is empty")
        val items = Store.items(app).map {
            if (it.mailNo == mailNo) it.copy(companyName = name) else it
        }
        Store.saveItems(app, items)
        return JSONObject().put("ok", true).put("mailNo", mailNo).put("name", name)
    }

    private fun mcp(body: String): JSONObject {
        val req = JSONObject(body)
        val id = req.opt("id")
        val method = req.optString("method")
        val params = req.optJSONObject("params") ?: JSONObject()
        return try {
            when (method) {
                "tools/list" -> mcpResult(id, JSONObject().put("tools", mcpTools()))
                "tools/call" -> {
                    val name = params.optString("name")
                    val args = params.optJSONObject("arguments") ?: JSONObject()
                    mcpResult(id, JSONObject().put("content", JSONArray().put(
                        JSONObject().put("type", "text").put("text", mcpCall(name, args))
                    )))
                }
                else -> mcpError(id, -32601, "method not found: $method")
            }
        } catch (e: Throwable) {
            Log.w("LocalApi", "mcp error", e)
            mcpError(id, -32603, e.message ?: "internal error")
        }
    }

    private fun mcpTools(): JSONArray {
        fun tool(name: String, description: String, properties: JSONObject, required: JSONArray = JSONArray()): JSONObject =
            JSONObject()
                .put("name", name)
                .put("description", description)
                .put("inputSchema", JSONObject()
                    .put("type", "object")
                    .put("properties", properties)
                    .put("required", required))

        return JSONArray()
            .put(tool(
                "summarize",
                "仓管 AI 总结当前所有快递：按问题或默认汇总，返回精炼文字，避免把所有包裹原始数据交给调用方",
                JSONObject()
                    .put("question", JSONObject().put("type", "string").put("description", "可选的自然语言问题，例如“我的快递都到哪了”"))
                    .put("maxChars", JSONObject().put("type", "integer").put("description", "返回文本最大长度，默认 2000"))
            ))
            .put(tool(
                "list_packages",
                "返回全部快递的精简列表（公司、单号、状态、预计送达、是否跟踪），适合调用方需要结构化数据时使用",
                JSONObject()
            ))
            .put(tool(
                "package_detail",
                "查询某个快递的完整物流轨迹",
                JSONObject().put("mailNo", JSONObject().put("type", "string")),
                JSONArray().put("mailNo")
            ))
            .put(tool(
                "sync",
                "触发一次小米同步，刷新快递列表",
                JSONObject()
            ))
            .put(tool(
                "track",
                "开启或关闭某个快递的跟踪通知",
                JSONObject()
                    .put("mailNo", JSONObject().put("type", "string"))
                    .put("tracked", JSONObject().put("type", "boolean")),
                JSONArray().put("mailNo").put("tracked")
            ))
            .put(tool(
                "rename",
                "修改某个快递的显示名称",
                JSONObject()
                    .put("mailNo", JSONObject().put("type", "string"))
                    .put("name", JSONObject().put("type", "string")),
                JSONArray().put("mailNo").put("name")
            ))
    }

    private fun mcpCall(name: String, args: JSONObject): String = when (name) {
        "summarize" -> summarize(
            args.optString("question").ifBlank { "请用简洁的中文总结我当前的快递情况" },
            args.optInt("maxChars", 2000)
        )
        "list_packages" -> compactList()
        "package_detail" -> {
            val item = Store.items(app).firstOrNull { it.mailNo == args.optString("mailNo") }
                ?: throw IllegalArgumentException("mailNo not found: ${args.optString("mailNo")}")
            val detail = runBlocking { XiaomiDetail.fetch(app, item) }
            JSONObject()
                .put("mailNo", detail.mailNo)
                .put("companyName", detail.companyName)
                .put("state", detail.state)
                .put("isReceived", detail.isReceived)
                .put("eta", item.eta)
                .put("points", JSONArray().apply {
                    for (p in detail.data) put(JSONObject().put("time", p.time).put("context", p.context))
                })
                .toString(2)
        }
        "sync" -> runBlocking { XiaomiSync.sync(app) }
        "track" -> {
            val r = trackJson(args.toString())
            if (!r.optBoolean("ok")) throw IllegalArgumentException(r.optString("error"))
            "已${if (r.optBoolean("tracked")) "开启" else "关闭"}跟踪：${r.optString("mailNo")}"
        }
        "rename" -> {
            val r = renameJson(args.toString())
            if (!r.optBoolean("ok")) throw IllegalArgumentException(r.optString("error"))
            "已改名为：${r.optString("name")}"
        }
        else -> throw IllegalArgumentException("tool not found: $name")
    }

    private fun summarize(question: String, maxChars: Int): String {
        val items = Store.items(app)
        val fallback = buildString {
            val moving = items.count { it.state != 3 }
            val delivering = items.count { it.state == 5 || it.stateName.contains("派送") }
            append("当前共 ${items.size} 个快递，在途 $moving 个")
            if (delivering > 0) append("（派送中 $delivering）")
            append("\n")
            items.take(8).forEach {
                append("- ${it.companyName} ${it.mailNo}：${it.stateLabel()}；${it.latestText}\n")
            }
        }
        val text = if (Store.aiKey(app).isBlank()) fallback else {
            try {
                val answer = runBlocking { AiClient.ask(app, items, question) }
                if (answer.startsWith("出错：") || answer.startsWith("请求失败")) fallback else answer
            } catch (e: Throwable) {
                fallback
            }
        }
        return text.take(maxChars.coerceAtLeast(100))
    }

    private fun compactList(): String = buildString {
        val items = Store.items(app)
        if (items.isEmpty()) {
            append("暂无快递")
            return@buildString
        }
        items.forEach {
            append("${it.companyName} ${it.mailNo}｜${it.stateLabel()}")
            if (it.eta.isNotBlank()) append("｜预计 ${it.eta}")
            if (it.tracked) append("｜跟踪中")
            append("\n")
        }
    }.trim()

    private fun mcpResult(id: Any?, result: JSONObject): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)

    private fun mcpError(id: Any?, code: Int, message: String): JSONObject =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("error", JSONObject().put("code", code).put("message", message))

    private fun bodyOf(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return files["postData"] ?: ""
    }

    private fun ok(body: String): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body)

    private fun error(status: Response.Status, message: String): Response {
        val body = JSONObject().put("ok", false).put("error", message).toString()
        return newFixedLengthResponse(status, "application/json; charset=utf-8", body)
    }
}
