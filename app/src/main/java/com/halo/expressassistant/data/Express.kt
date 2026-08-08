package com.halo.expressassistant.data

import kotlinx.serialization.Serializable

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
    val aiProgressAt: String = ""
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
