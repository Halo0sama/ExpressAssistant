package com.halo.expressassistant.data

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

@Serializable
data class ReportSchedule(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val label: String = "",
    val enabled: Boolean = true,
    val repeat: Int = ReportSchedule.REPEAT_DAILY,
    val weekdays: Int = 0
) {
    companion object {
        const val REPEAT_ONCE = 0
        const val REPEAT_DAILY = 1
        const val REPEAT_WEEKDAYS = 2
        const val REPEAT_WEEKENDS = 3
        const val REPEAT_CUSTOM = 4

        const val MON = 1
        const val TUE = 1 shl 1
        const val WED = 1 shl 2
        const val THU = 1 shl 3
        const val FRI = 1 shl 4
        const val SAT = 1 shl 5
        const val SUN = 1 shl 6

        const val MASK_WEEKDAYS = MON or TUE or WED or THU or FRI
        const val MASK_WEEKENDS = SAT or SUN
        const val MASK_ALL = MASK_WEEKDAYS or MASK_WEEKENDS
    }
}

@Serializable
data class PendingReport(
    val time: Long,
    val text: String
)
