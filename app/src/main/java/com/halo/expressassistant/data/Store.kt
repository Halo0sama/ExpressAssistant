package com.halo.expressassistant.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object Store {
    private const val PREFS = "express_store"
    private const val KEY_LIST = "items"
    private const val KEY_AI_BASE = "ai_base"
    private const val KEY_AI_KEY = "ai_key"
    private const val KEY_AI_MODEL = "ai_model"
    private const val KEY_REPORT_HOUR = "report_hour"
    private const val KEY_REPORT_MINUTE = "report_minute"
    private const val KEY_KD_KEY = "kd_key"
    private const val KEY_KD_CUSTOMER = "kd_customer"
    private const val KEY_XIAOMI_TOKEN = "xiaomi_token"
    private const val KEY_XIAOMI_CUSER = "xiaomi_cuser"
    private const val KEY_XIAOMI_ACCOUNT_ID = "xiaomi_account_id"
    private const val KEY_XIAOMI_OAID = "xiaomi_oaid"
    private const val KEY_XIAOMI_VAID = "xiaomi_vaid"
    private const val KEY_XIAOMI_PHONES = "xiaomi_phones"
    private const val KEY_XIAOMI_HIDDEN = "xiaomi_hidden"
    private const val KEY_USE_KD100 = "use_kd100_fallback"
    private const val KEY_USE_ACCESSIBILITY = "use_accessibility"
    private const val KEY_LAST_AUTO_SYNC = "last_auto_sync"
    private const val KEY_CHAT_HISTORY = "chat_history"
    private const val KEY_REPORT_SCHEDULES = "report_schedules"
    private const val KEY_PENDING_REPORT = "pending_report"

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun items(context: Context): List<ExpressItem> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LIST, null)
            ?: return seed().also { saveItems(context, it) }
        return try {
            json.decodeFromString<List<ExpressItem>>(raw)
        } catch (e: Throwable) {
            seed().also { saveItems(context, it) }
        }
    }

    fun saveItems(context: Context, items: List<ExpressItem>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_LIST, json.encodeToString(items))
        }
    }

    fun seed(): List<ExpressItem> = listOf(
        ExpressItem("1", "jd", "京东物流", "DEMO0000000001", "【示例】快件已发往 示例转运中心", "08-05", 0, "运输中"),
        ExpressItem("2", "shentong", "申通快递", "DEMO0000000002", "快递状态已更新，点击查看>>", "08-05", 0, "运输中"),
        ExpressItem("3", "jd", "京东物流", "DEMO0000000003", "您的订单已送达至【家门口】", "08-05", 3, "已签收"),
        ExpressItem("4", "jd", "京东物流", "DEMO0000000004", "预计8月15日前发货，8月16日(周日)送达", "08-05", 1, "已揽件"),
        ExpressItem("5", "yuantong", "圆通速递", "DEMO0000000005", "运输中", "08-04", 0, "运输中"),
        ExpressItem("6", "jd", "京东物流", "DEMO0000000006", "【示例】快件已到达 上海浦西转运中心", "08-05", 0, "运输中"),
        ExpressItem("7", "jd", "京东物流", "DEMO0000000007", "您的订单已送达至【家门口】", "08-05", 3, "已签收"),
        ExpressItem("8", "shunfeng", "顺丰速运", "SF0000000000000", "待补充单号", "08-05", 0, "运输中")
    ).map { it.copy(originalName = it.companyName) }

    fun aiBase(context: Context): String {
        val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_AI_BASE, "")
        return if (v.isNullOrBlank() || v == "https://api.openai.com/v1") "https://api.deepseek.com" else v
    }

    fun aiKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_AI_KEY, "") ?: ""

    fun aiModel(context: Context): String {
        val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_AI_MODEL, "")
        return if (v.isNullOrBlank() || v == "gpt-4o-mini") "deepseek-v4-flash" else v
    }

    fun kdKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_KD_KEY, "") ?: ""

    fun kdCustomer(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_KD_CUSTOMER, "") ?: ""

    fun reportTime(context: Context): Pair<Int, Int> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getInt(KEY_REPORT_HOUR, 8) to p.getInt(KEY_REPORT_MINUTE, 30)
    }

    fun saveSettings(
        context: Context,
        aiBase: String,
        aiKey: String,
        aiModel: String,
        kdKey: String,
        kdCustomer: String,
        reportHour: Int,
        reportMinute: Int,
        kd100Fallback: Boolean,
        accessibilityEnabled: Boolean
    ) {
        val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        e.putString(KEY_AI_BASE, aiBase)
        e.putString(KEY_AI_KEY, aiKey)
        e.putString(KEY_AI_MODEL, aiModel)
        e.putString(KEY_KD_KEY, kdKey)
        e.putString(KEY_KD_CUSTOMER, kdCustomer)
        e.putInt(KEY_REPORT_HOUR, reportHour)
        e.putInt(KEY_REPORT_MINUTE, reportMinute)
        e.putBoolean(KEY_USE_KD100, kd100Fallback)
        e.putBoolean(KEY_USE_ACCESSIBILITY, accessibilityEnabled)
        e.commit()
    }

    fun kd100Fallback(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_USE_KD100, false)

    fun accessibilityEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_USE_ACCESSIBILITY, false)

    fun xiaomiHidden(context: Context): List<ExpressItem> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_XIAOMI_HIDDEN, null)
            ?: return emptyList()
        return try {
            json.decodeFromString<List<ExpressItem>>(raw)
        } catch (e: Throwable) {
            emptyList()
        }
    }

    fun addHidden(context: Context, item: ExpressItem) {
        val hidden = xiaomiHidden(context).toMutableList()
        hidden.removeAll { it.mailNo == item.mailNo }
        hidden.add(0, item)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_XIAOMI_HIDDEN, json.encodeToString(hidden))
        }
    }

    fun restoreHidden(context: Context, mailNo: String) {
        val hidden = xiaomiHidden(context).filterNot { it.mailNo == mailNo }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_XIAOMI_HIDDEN, json.encodeToString(hidden))
        }
    }

    fun clearHidden(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            remove(KEY_XIAOMI_HIDDEN)
        }
    }

    fun xiaomiToken(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_XIAOMI_TOKEN, "") ?: ""

    fun xiaomiCUser(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_XIAOMI_CUSER, "") ?: ""

    fun xiaomiAccountId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_XIAOMI_ACCOUNT_ID, "") ?: ""

    fun xiaomiOaid(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_XIAOMI_OAID, "") ?: ""

    fun xiaomiVaid(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_XIAOMI_VAID, "") ?: ""

    fun xiaomiPhones(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_XIAOMI_PHONES, null)
            ?: return emptyList()
        return try {
            json.decodeFromString<List<String>>(raw)
        } catch (e: Throwable) {
            emptyList()
        }
    }

    fun saveXiaomiLogin(
        context: Context,
        token: String,
        cUser: String,
        accountId: String,
        oaid: String,
        vaid: String,
        phones: List<String>
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_XIAOMI_TOKEN, token)
            putString(KEY_XIAOMI_CUSER, cUser)
            putString(KEY_XIAOMI_ACCOUNT_ID, accountId)
            putString(KEY_XIAOMI_OAID, oaid)
            putString(KEY_XIAOMI_VAID, vaid)
            putString(KEY_XIAOMI_PHONES, json.encodeToString(phones))
        }
    }

    fun saveXiaomiPhones(context: Context, phones: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_XIAOMI_PHONES, json.encodeToString(phones))
        }
    }

    fun lastAutoSync(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_AUTO_SYNC, 0L)

    fun setLastAutoSync(context: Context, time: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putLong(KEY_LAST_AUTO_SYNC, time)
        }
    }

    fun chatHistory(context: Context): List<ChatMessage> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CHAT_HISTORY, null)
            ?: return emptyList()
        return try {
            json.decodeFromString<List<ChatMessage>>(raw)
        } catch (e: Throwable) {
            emptyList()
        }
    }

    fun saveChatHistory(context: Context, messages: List<ChatMessage>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_CHAT_HISTORY, json.encodeToString(messages))
        }
    }

    fun clearChatHistory(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            remove(KEY_CHAT_HISTORY)
        }
    }

    fun reportSchedules(context: Context): List<ReportSchedule> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_REPORT_SCHEDULES, null)
            ?: return emptyList()
        return try {
            json.decodeFromString<List<ReportSchedule>>(raw)
        } catch (e: Throwable) {
            emptyList()
        }
    }

    fun saveReportSchedules(context: Context, schedules: List<ReportSchedule>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_REPORT_SCHEDULES, json.encodeToString(schedules))
        }
    }

    fun pendingReport(context: Context): PendingReport? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PENDING_REPORT, null)
            ?: return null
        return try {
            json.decodeFromString<PendingReport>(raw)
        } catch (e: Throwable) {
            null
        }
    }

    fun savePendingReport(context: Context, report: PendingReport) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_PENDING_REPORT, json.encodeToString(report))
        }
    }

    fun clearPendingReport(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            remove(KEY_PENDING_REPORT)
        }
    }
}
