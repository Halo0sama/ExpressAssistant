package com.halo.expressassistant.api

import android.content.Context
import com.halo.expressassistant.data.ExpressItem
import com.halo.expressassistant.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object XiaomiSync {

    suspend fun sync(context: Context): String = withContext(Dispatchers.IO) {
        val token = Store.xiaomiToken(context)
        val cUser = Store.xiaomiCUser(context)
        val accountId = Store.xiaomiAccountId(context)
        if (token.isEmpty() || cUser.isEmpty() || accountId.isEmpty()) {
            throw IllegalStateException("还没有小米登录态，请先点“小米同步”扫码登录")
        }
        val phones = Store.xiaomiPhones(context)
        val body = XiaomiApi.getListBody(phones)
        val raw = XiaomiApi.fetchList(
            context,
            token,
            cUser,
            body,
            accountId,
            Store.xiaomiOaid(context),
            Store.xiaomiVaid(context)
        )
        val root = JSONObject(raw)
        if (root.optInt("code") != 0) {
            throw IllegalStateException("小米返回错误: ${root.optString("message")}")
        }
        val dataStr = root.optString("data")
        val data = JSONObject(dataStr)
        val list = data.optJSONArray("expressList") ?: JSONArray()
        val incoming = ArrayList<ExpressItem>()
        for (i in 0 until list.length()) {
            val e = list.getJSONObject(i)
            val details = e.optJSONArray("details")
            val latest = if (details != null && details.length() > 0) details.getJSONObject(details.length() - 1) else null
            val detailTexts = if (details != null) {
                (0 until details.length()).joinToString(" ") { details.getJSONObject(it).optString("desc") }
            } else {
                ""
            }
            incoming.add(
                ExpressItem(
                    id = e.optString("mailNo"),
                    companyCode = e.optString("cpCode"),
                    companyName = e.optString("name"),
                    mailNo = e.optString("mailNo"),
                    latestText = latest?.optString("desc").orEmpty().ifBlank { e.optString("state") },
                    latestTime = latest?.optString("time").orEmpty(),
                    state = stateToInt(e.optString("state"), e.optInt("stateNum")),
                    stateName = e.optString("state"),
                    phone = e.optString("phone").ifEmpty { null },
                    provider = e.optString("provider"),
                    stateNum = e.optInt("stateNum"),
                    queryChannel = e.optString("queryChannel"),
                    iconUrl = e.optString("iconUrl"),
                    originalName = e.optString("name"),
                    eta = EtaParser.extract(detailTexts, e.optString("state"))
                )
            )
        }
        val hidden = Store.xiaomiHidden(context).map { it.mailNo }.toSet()
        incoming.removeAll { it.mailNo in hidden }
        val existing = Store.items(context).toMutableList()
        for (item in incoming) {
            val old = existing.firstOrNull { it.mailNo == item.mailNo }
            val merged = if (old != null) {
                item.copy(
                    eta = if (item.eta.isBlank() && old.eta.isNotBlank()) old.eta else item.eta,
                    tracked = old.tracked,
                    companyName = old.companyName,
                    originalName = old.originalName
                )
            } else {
                item
            }
            val idx = existing.indexOfFirst { it.mailNo == item.mailNo }
            if (idx >= 0) existing[idx] = merged else existing.add(merged)
        }
        Store.saveItems(context, existing)
        "同步成功，共 ${incoming.size} 个快递"
    }

    private fun stateToInt(state: String, stateNum: Int): Int {
        return when (stateNum) {
            107 -> 3
            105, 106 -> 5
            103 -> 1
            else -> when {
                state.contains("签收") || state.contains("订单完成") || state.contains("已送达") -> 3
                state.contains("派送") || state.contains("配送") -> 5
                state.contains("揽收") || state.contains("揽件") -> 1
                else -> 0
            }
        }
    }
}
