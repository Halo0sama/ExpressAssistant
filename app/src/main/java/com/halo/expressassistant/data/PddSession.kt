package com.halo.expressassistant.data

/** Cookie headers have no leading semicolon; use the complete UID for identity. */
object PddSession {
    fun cookies(header: String): Map<String, String> = header.split(';').mapNotNull {
        val parts = it.trim().split('=', limit = 2)
        if (parts.size == 2 && parts[0].isNotBlank()) parts[0] to parts[1] else null
    }.toMap()

    fun userId(header: String): String = cookies(header).let {
        it["pdd_user_id"]?.takeIf(String::isNotBlank)
            ?: it["pdduid"]?.takeIf(String::isNotBlank).orEmpty()
    }

    fun hasCredentials(header: String): Boolean =
        !cookies(header)["PDDAccessToken"].isNullOrBlank() && userId(header).isNotBlank()
}
