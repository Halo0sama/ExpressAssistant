package com.halo.expressassistant.api

import java.util.regex.Pattern

object EtaParser {

    private val fullPattern = Pattern.compile(
        "(?:预计)?[【\\[]?\\s*(\\d{1,2})月(\\d{1,2})日\\s*[】\\]]?\\s*(?:[（(]?周[^)）]*[)）])?\\s*(前发货|发货|送达|到达|派送)?"
    )
    private val deliveryPattern = Pattern.compile(
        "(?:预计)?[【\\[]?\\s*(\\d{1,2})月(\\d{1,2})日\\s*[】\\]]?\\s*(?:[（(]?周[^)）]*[)）])?\\s*(送达|到达|派送)"
    )
    private val simplePattern = Pattern.compile(
        "(\\d{1,2})月(\\d{1,2})日(?:[（(]周[^)）]*[)）])?(前发货|发货|送达|到达|派送)"
    )
    private val todayPattern = Pattern.compile("预计(今天|明天|后天)(送达|到达|派送)")
    private val plainDatePattern = Pattern.compile("预计\\s*(\\d{1,2})月(\\d{1,2})日")

    fun extract(vararg texts: String?): String {
        val joined = texts.filterNotNull().joinToString(" ")
        if (joined.isBlank()) return ""

        // Prefer explicit delivery/arrival phrases.
        val delivery = deliveryPattern.matcher(joined)
        if (delivery.find()) {
            return "${delivery.group(1)}月${delivery.group(2)}日${delivery.group(3)}"
        }
        for (p in listOf(fullPattern, simplePattern, todayPattern)) {
            val m = p.matcher(joined)
            if (m.find()) {
                if (p == todayPattern) {
                    return "${m.group(1)}${m.group(2)}"
                }
                val month = m.group(1)
                val day = m.group(2)
                val suffix = if (m.groupCount() >= 3) m.group(3).orEmpty() else ""
                return "${month}月${day}日${suffix}"
            }
        }
        val m = plainDatePattern.matcher(joined)
        if (m.find()) {
            return "${m.group(1)}月${m.group(2)}日"
        }
        return ""
    }
}
