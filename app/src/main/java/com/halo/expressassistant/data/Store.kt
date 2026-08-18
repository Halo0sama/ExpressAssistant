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
    private const val KEY_AI_STYLE = "ai_style"
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
    private const val KEY_REPORT_ISSUE = "report_issue"
    private const val KEY_REPORT_FIRST_DATE = "report_first_date"
    private const val KEY_LOCAL_API_ENABLED = "local_api_enabled"
    private const val KEY_HOME_ADDRESS = "home_address"
    private const val KEY_WIDGET_SHOW_DELIVERING = "widget_show_delivering"
    private const val KEY_WIDGET_SHOW_SHIPPED = "widget_show_shipped"
    private const val KEY_WIDGET_SHOW_NOTSHIPPED = "widget_show_notshipped"
    private const val KEY_THEME = "theme"
    private const val KEY_THEME_COLOR = "theme_color"
    private const val KEY_THEME_FONT = "theme_font"
    private const val KEY_THEME_COLOR_DAY = "theme_color_day"
    private const val KEY_THEME_COLOR_NIGHT = "theme_color_night"
    private const val KEY_THEME_FONT_DAY = "theme_font_day"
    private const val KEY_THEME_FONT_NIGHT = "theme_font_night"
    private const val KEY_PAPER_DAY = "paper_intensity_day"
    private const val KEY_PAPER_NIGHT = "paper_intensity_night"
    private const val KEY_CUSTOM_SEPARATE = "custom_separate"
    private const val KEY_PAPER_INTENSITY = "paper_intensity"
    private const val KEY_JD_COOKIES = "jd_cookies"
    private const val KEY_JD_GOODS = "jd_goods"
    private const val KEY_TB_COOKIES = "tb_cookies"
    private const val KEY_SHORT_OPT_V2 = "short_opt_v2"

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun items(context: Context): List<ExpressItem> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_LIST, null)
            ?: return seed().also { saveItems(context, it) }
        return try {
            val list = json.decodeFromString<List<ExpressItem>>(raw)
            val clean = list.filterNot { it.mailNo.startsWith("FAKETEST") }
            if (clean.size != list.size) saveItems(context, clean)
            clean
        } catch (e: Throwable) {
            seed().also { saveItems(context, it) }
        }
    }

    fun saveItems(context: Context, items: List<ExpressItem>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_LIST, json.encodeToString(items))
        }
        com.halo.expressassistant.widget.ExpressWidgetProvider.updateAll(context)
    }

    fun seed(): List<ExpressItem> = emptyList()

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

    const val AI_STYLE_VICTORIAN = "victorian"
    const val AI_STYLE_KAWAII = "kawaii"
    const val AI_STYLE_CLEAN = "clean"

    fun aiStyle(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_AI_STYLE, AI_STYLE_VICTORIAN) ?: AI_STYLE_VICTORIAN

    fun saveAiStyle(context: Context, style: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_AI_STYLE, style)
        }
    }

    fun aiStyleLabel(context: Context): String = when (aiStyle(context)) {
        AI_STYLE_KAWAII -> "可爱云雀"
        AI_STYLE_CLEAN -> "原本的模样"
        else -> "维多利亚"
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

    fun clearXiaomiLogin(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            remove(KEY_XIAOMI_TOKEN)
            remove(KEY_XIAOMI_CUSER)
            remove(KEY_XIAOMI_ACCOUNT_ID)
            remove(KEY_XIAOMI_OAID)
            remove(KEY_XIAOMI_VAID)
        }
        com.halo.expressassistant.widget.ExpressWidgetProvider.updateAll(context)
    }

    /* ─────────────── 京东登录 / 商品溯源 ─────────────── */

    fun jdCookies(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_JD_COOKIES, "") ?: ""

    fun saveJdCookies(context: Context, cookies: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_JD_COOKIES, cookies)
        }
    }

    fun clearJdLogin(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            remove(KEY_JD_COOKIES)
            remove(KEY_JD_GOODS)
        }
    }

    /* ─────────────── 淘宝登录 / 菜鸟溯源 ─────────────── */

    fun tbCookies(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TB_COOKIES, "") ?: ""

    fun saveTbCookies(context: Context, cookies: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_TB_COOKIES, cookies)
        }
    }

    fun clearTbLogin(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            remove(KEY_TB_COOKIES)
        }
    }

    fun shortOptV2(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHORT_OPT_V2, false)

    fun setShortOptV2(context: Context, v: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_SHORT_OPT_V2, v)
        }
    }

    fun jdGoods(context: Context): Map<String, JdGoods> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_JD_GOODS, null)
            ?: return emptyMap()
        return try {
            json.decodeFromString<Map<String, JdGoods>>(raw)
        } catch (e: Throwable) {
            emptyMap()
        }
    }

    fun saveJdGoods(context: Context, goods: Map<String, JdGoods>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_JD_GOODS, json.encodeToString(goods))
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

    fun reportIssue(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_REPORT_ISSUE, 0)

    fun reportFirstDate(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_REPORT_FIRST_DATE, 0L)

    /** 真正生成一篇日报时调用：期数 +1，并记录创刊日期。 */
    fun nextReportIssue(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = prefs.getInt(KEY_REPORT_ISSUE, 0) + 1
        val now = System.currentTimeMillis()
        val editor = prefs.edit()
        editor.putInt(KEY_REPORT_ISSUE, next)
        if (prefs.getLong(KEY_REPORT_FIRST_DATE, 0L) == 0L) {
            editor.putLong(KEY_REPORT_FIRST_DATE, now)
        }
        editor.commit()
        return next
    }

    fun localApiEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LOCAL_API_ENABLED, true)

    fun saveLocalApiEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_LOCAL_API_ENABLED, enabled)
        }
    }

    fun homeAddress(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HOME_ADDRESS, "") ?: ""

    fun saveHomeAddress(context: Context, address: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_HOME_ADDRESS, address)
        }
    }

    fun theme(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME, "monet") ?: "monet"

    fun saveTheme(context: Context, theme: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_THEME, theme)
        }
        com.halo.expressassistant.widget.ExpressWidgetProvider.updateAll(context)
    }

    fun colorScheme(context: Context): String =
        if (customSeparate(context)) {
            if (isNight(context)) colorSchemeNight(context) else colorSchemeDay(context)
        } else {
            colorSchemeCombined(context)
        }

    fun saveColorScheme(context: Context, scheme: String) {
        if (customSeparate(context)) {
            if (isNight(context)) saveColorSchemeNight(context, scheme) else saveColorSchemeDay(context, scheme)
        } else {
            saveColorSchemeCombined(context, scheme)
        }
    }

    private fun colorSchemeCombined(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME_COLOR, "warm") ?: "warm"

    fun colorSchemeDay(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME_COLOR_DAY, null) ?: colorSchemeCombined(context)

    fun colorSchemeNight(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME_COLOR_NIGHT, null) ?: colorSchemeCombined(context)

    fun saveColorSchemeDay(context: Context, scheme: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_THEME_COLOR_DAY, scheme)
        }
    }

    fun saveColorSchemeNight(context: Context, scheme: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_THEME_COLOR_NIGHT, scheme)
        }
    }

    private fun saveColorSchemeCombined(context: Context, scheme: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_THEME_COLOR, scheme)
        }
    }

    fun themeFont(context: Context): String =
        if (customSeparate(context)) {
            if (isNight(context)) themeFontNight(context) else themeFontDay(context)
        } else {
            themeFontCombined(context)
        }

    fun saveThemeFont(context: Context, font: String) {
        if (customSeparate(context)) {
            if (isNight(context)) saveThemeFontNight(context, font) else saveThemeFontDay(context, font)
        } else {
            saveThemeFontCombined(context, font)
        }
    }

    private fun themeFontCombined(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME_FONT, "serif") ?: "serif"

    fun themeFontDay(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME_FONT_DAY, null) ?: themeFontCombined(context)

    fun themeFontNight(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME_FONT_NIGHT, null) ?: themeFontCombined(context)

    fun saveThemeFontDay(context: Context, font: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_THEME_FONT_DAY, font)
        }
    }

    fun saveThemeFontNight(context: Context, font: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_THEME_FONT_NIGHT, font)
        }
    }

    private fun saveThemeFontCombined(context: Context, font: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_THEME_FONT, font)
        }
    }

    fun customSeparate(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CUSTOM_SEPARATE, false)

    fun saveCustomSeparate(context: Context, separate: Boolean) {
        val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (separate) {
            if (!customSeparate(context)) {
                val color = colorSchemeCombined(context)
                val font = themeFontCombined(context)
                val paper = paperIntensityCombined(context)
                e.putString(KEY_THEME_COLOR_DAY, color)
                e.putString(KEY_THEME_COLOR_NIGHT, color)
                e.putString(KEY_THEME_FONT_DAY, font)
                e.putString(KEY_THEME_FONT_NIGHT, font)
                e.putInt(KEY_PAPER_DAY, paper)
                e.putInt(KEY_PAPER_NIGHT, paper)
            }
        } else {
            e.putString(KEY_THEME_COLOR, colorScheme(context))
            e.putString(KEY_THEME_FONT, themeFont(context))
            e.putInt(KEY_PAPER_INTENSITY, paperIntensity(context))
        }
        e.putBoolean(KEY_CUSTOM_SEPARATE, separate)
        e.commit()
    }

    fun paperIntensity(context: Context): Int =
        if (customSeparate(context)) {
            if (isNight(context)) paperIntensityNight(context) else paperIntensityDay(context)
        } else {
            paperIntensityCombined(context)
        }

    fun paperIntensityDay(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_PAPER_DAY, paperIntensityCombined(context))

    fun paperIntensityNight(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_PAPER_NIGHT, paperIntensityCombined(context))

    fun savePaperIntensityDay(context: Context, intensity: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putInt(KEY_PAPER_DAY, intensity.coerceIn(0, 200))
        }
    }

    fun savePaperIntensityNight(context: Context, intensity: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putInt(KEY_PAPER_NIGHT, intensity.coerceIn(0, 200))
        }
    }

    private fun paperIntensityCombined(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_PAPER_INTENSITY, 100)

    private fun isNight(context: Context): Boolean {
        val mode = context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    fun savePaperIntensity(context: Context, intensity: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putInt(KEY_PAPER_INTENSITY, intensity.coerceIn(0, 200))
        }
    }

    fun widgetShowDelivering(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_WIDGET_SHOW_DELIVERING, true)

    fun widgetShowShipped(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_WIDGET_SHOW_SHIPPED, true)

    fun widgetShowNotShipped(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_WIDGET_SHOW_NOTSHIPPED, true)

    fun saveWidgetShowDelivering(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_WIDGET_SHOW_DELIVERING, enabled)
        }
        com.halo.expressassistant.widget.ExpressWidgetProvider.updateAll(context)
    }

    fun saveWidgetShowShipped(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_WIDGET_SHOW_SHIPPED, enabled)
        }
        com.halo.expressassistant.widget.ExpressWidgetProvider.updateAll(context)
    }

    fun saveWidgetShowNotShipped(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_WIDGET_SHOW_NOTSHIPPED, enabled)
        }
        com.halo.expressassistant.widget.ExpressWidgetProvider.updateAll(context)
    }
}
