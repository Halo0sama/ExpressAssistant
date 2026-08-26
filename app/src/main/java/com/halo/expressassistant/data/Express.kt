package com.halo.expressassistant.data

import kotlinx.serialization.Serializable

@Serializable
data class JdGoods(
    val name: String = "",
    val imageUrl: String = "",
    val count: String = "",
    /** AI 优化后的短名（厂商+产品名，卡片一行） */
    val shortName: String = ""
)

/**
 * 多源绑定：某一平台的绑定账号（一组登录凭证 + 展示名 + 启停状态）。
 * payload 为平台私有 JSON：xiaomi={token,cUser,accountId,oaid,vaid,phones[]}；jd/tb/pdd={cookie}。
 */
@Serializable
data class BoundAccount(
    val id: String,
    val label: String = "",
    val enabled: Boolean = true,
    val payload: String = ""
)

/** 多地址管理：一条收件地址（label 用于显示，address 为具体地址） */
@Serializable
data class HomeAddress(
    val id: String,
    val label: String = "地址",
    val address: String = ""
)

@Serializable
data class ExpressItem(
    val id: String,
    val companyCode: String,
    val companyName: String,
    val mailNo: String,
    val latestText: String = "",
    val latestTime: String = "",
    val state: Int = -1,
    val stateName: String = "",
    val phone: String? = null,
    val provider: String = "",
    val stateNum: Int = 0,
    val queryChannel: String = "",
    val iconUrl: String = "",
    val originalName: String = "",
    val eta: String = "",
    val tracked: Boolean = false,
    val notifiedText: String = "",
    val notifiedTime: String = "",
    val stateOverride: String = "",
    val partitionOverride: String = "",
    val aiProgress: Int = -1,
    val aiEta: String = "",
    val aiProgressAt: String = "",
    val jumpLinks: String = "",
    /** 数据来源渠道：xiaomi / jd / taobao / pdd */
    val source: String = "xiaomi",
    /** 多源绑定：该件来自哪个账号（BoundAccount.id） */
    val accountId: String = "",
    /** 多源绑定：归属账号展示名（详情页标注） */
    val accountLabel: String = "",
    /** 指定收件地址（HomeAddress.id；空 = 使用全局当前地址） */
    val addressId: String = "",
    /** 取件码（从轨迹文本解析，聚合显示） */
    val pickupCode: String = ""
) {
    fun stateLabel(): String {
        if (stateOverride.isNotBlank()) return stateOverride
        if (stateName.isNotEmpty()) return stateName
        return when (state) {
            0 -> "运输中"
            1 -> "已揽件"
            3 -> "已签收"
            4 -> "已拒签"
            5 -> "派送中"
            else -> "未知"
        }
    }
}

fun sectionKeyOf(item: ExpressItem): String {
    if (item.partitionOverride.isNotBlank()) return item.partitionOverride
    return when {
        item.stateNum == 105 || item.stateNum == 106 || item.state == 5 -> "delivering"
        item.stateNum in 106..107 || item.state == 3 -> "done"
        item.stateNum in 108..111 || item.state == 4 -> "abnormal"
        item.stateNum == 101 || item.stateNum == 103 || item.state == 1 -> "notshipped"
        else -> "shipped"
    }
}



fun progressFor(item: ExpressItem): Int = when (item.stateNum) {
    101 -> 5
    102 -> 15
    103 -> 25
    104 -> 55
    105 -> 85
    106 -> 95
    107 -> 100
    108, 109, 110, 111 -> 0
    else -> when (item.state) {
        1 -> 25
        3 -> 100
        5 -> 85
        0 -> 55
        else -> 0
    }
}

/**
 * 展示用进度：AI 结果 0% 只在“未发货/已揽收”阶段合理，
 * 一旦已经开始运输（状态码 104 及以上）就视为无效，走重算或默认映射。
 */
fun displayProgress(item: ExpressItem): Int {
    val p = item.aiProgress
    if (p < 0) return -1
    if (p == 0 && item.stateNum !in 101..103) return -1
    return p
}

@Serializable
data class ExpressDetail(
    val mailNo: String,
    val companyName: String,
    val state: Int,
    val isReceived: Boolean,
    val data: List<DetailPoint> = emptyList(),
    val eta: String = ""
)

@Serializable
data class DetailPoint(
    val context: String,
    val time: String,
    val formattedTime: String = ""
)

/** 拼多多轨迹缓存：抓取一次存 N 小时，避免每次进详情重新走 WebView 流程 */
@Serializable
data class PddTraceCache(
    val fetchedAt: Long = 0L,
    val points: List<DetailPoint> = emptyList(),
    val done: Boolean = false
)
