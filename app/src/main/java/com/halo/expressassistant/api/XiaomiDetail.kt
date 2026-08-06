package com.halo.expressassistant.api

import android.content.Context
import com.halo.expressassistant.data.DetailPoint
import com.halo.expressassistant.data.ExpressDetail
import com.halo.expressassistant.data.ExpressItem
import com.halo.expressassistant.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object XiaomiDetail {

    suspend fun fetch(context: Context, item: ExpressItem): ExpressDetail = withContext(Dispatchers.IO) {
        val token = Store.xiaomiToken(context)
        val cUser = Store.xiaomiCUser(context)
        val accountId = Store.xiaomiAccountId(context)
        if (token.isEmpty() || cUser.isEmpty() || accountId.isEmpty()) {
            throw IllegalStateException("未登录小米")
        }
        val body = XiaomiApi.getDetailBody(
            phones = Store.xiaomiPhones(context),
            cpCode = item.companyCode,
            mailNo = item.mailNo,
            name = item.companyName,
            provider = item.provider,
            stateNum = item.stateNum,
            logisticsUpdateTime = toEpochMillis(item.latestTime),
            phone = item.phone,
            queryChannel = item.queryChannel.ifEmpty { null },
            channel = "1"
        )
        val raw = XiaomiApi.fetchDetail(
            context, token, cUser, "/cpa/express/v2/query", body,
            accountId, Store.xiaomiOaid(context), Store.xiaomiVaid(context)
        )
        val root = JSONObject(raw)
        if (root.optInt("code") != 0) {
            throw IllegalStateException("小米返回错误: ${root.optString("message")}")
        }
        val data = JSONObject(root.optString("data"))
        val details = data.optJSONArray("details") ?: org.json.JSONArray()
        val points = ArrayList<DetailPoint>()
        for (i in 0 until details.length()) {
            val d = details.getJSONObject(i)
            points.add(
                DetailPoint(
                    context = d.optString("desc"),
                    time = d.optString("time"),
                    formattedTime = d.optString("time")
                )
            )
        }
        val stateNum = data.optInt("stateNum", item.stateNum)
        val isReceived = stateNum == 107 || data.optString("state").contains("签收")
        ExpressDetail(
            mailNo = item.mailNo,
            companyName = item.companyName,
            state = when {
                isReceived -> 3
                stateNum == 105 || stateNum == 106 -> 5
                stateNum == 103 -> 1
                else -> 0
            },
            isReceived = isReceived,
            data = points
        )
    }

    private fun toEpochMillis(time: String): String {
        return try {
            val dt = LocalDateTime.parse(time, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli().toString()
        } catch (e: Throwable) {
            time
        }
    }
}
