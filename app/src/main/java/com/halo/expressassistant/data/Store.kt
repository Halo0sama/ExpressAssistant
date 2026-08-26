package com.halo.expressassistant.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object Store {
    private const val PREFS = "express_store"
    private const val KEY_LIST = "items"
    private const val KEY_AI_BASE = "ai_base"
    private const val KEY_AI_KEY = "ai_key"
    private const val KEY_AI_MODEL = "ai_model"
    private const val KEY_DASHSCOPE_KEY = "dashscope_key"
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
    private const val KEY_ADDRESSES = "addresses"
    private const val KEY_ACTIVE_ADDRESS_ID = "active_address_id"
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
    private const val KEY_PDD_COOKIES = "pdd_cookies"
    private const val KEY_XIAOMI_ACCOUNTS = "xiaomi_accounts"
    private const val KEY_JD_ACCOUNTS = "jd_accounts"
    private const val KEY_TB_ACCOUNTS = "tb_accounts"
    private const val KEY_PDD_ACCOUNTS = "pdd_accounts"
    private const val KEY_PDD_TRACES = "pdd_traces"
    private const val KEY_JD_TRACES = "jd_traces"
    private const val KEY_TB_TRACES = "tb_traces"
    private const val KEY_POLL_MIN = "poll_interval_min"

    /** 后台轮询间隔（分钟）。0 = 关闭轮询（默认不开启）；开启快递跟踪时自动设为 15 */
    fun pollIntervalMin(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_POLL_MIN, 0)

    fun savePollIntervalMin(context: Context, minutes: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_POLL_MIN, minutes.coerceIn(0, 720)).apply()
    }

    /** 开启跟踪时调用：若轮询从未来过（=0/未配置）则自动默认 15 分钟并开启 */
    fun ensurePollingDefault(context: Context) {
        if (pollIntervalMin(context) <= 0) savePollIntervalMin(context, 15)
    }
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

    fun dashScopeKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DASHSCOPE_KEY, "") ?: ""

    fun saveDashScopeKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_DASHSCOPE_KEY, key.trim())
        }
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

    /** 从删除记录中永久清除指定单号（用于「清除已移除账号的快递」） */
    fun removeHiddenItems(context: Context, mailNos: Set<String>) {
        if (mailNos.isEmpty()) return
        val hidden = xiaomiHidden(context).filterNot { it.mailNo in mailNos }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_XIAOMI_HIDDEN, json.encodeToString(hidden))
        }
    }

    fun clearHidden(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            remove(KEY_XIAOMI_HIDDEN)
        }
    }

    /* ─────────────── 多源绑定：每平台账号列表（可任意数量） ─────────────── */

    const val CH_XIAOMI = "xiaomi"
    const val CH_JD = "jd"
    const val CH_TAOBAO = "taobao"
    const val CH_PDD = "pdd"
    val CHANNELS = listOf(CH_XIAOMI, CH_JD, CH_TAOBAO, CH_PDD)

    /** 小米登录凭证（payload 编解码） */
    @Serializable
    data class XiaomiCred(
        val token: String = "",
        val cUser: String = "",
        val accountId: String = "",
        val oaid: String = "",
        val vaid: String = "",
        val phones: List<String> = emptyList()
    )

    fun xiaomiPayload(cred: XiaomiCred): String = json.encodeToString(cred)
    fun parseXiaomiCred(payload: String): XiaomiCred =
        try { json.decodeFromString<XiaomiCred>(payload) } catch (e: Throwable) { XiaomiCred() }

    fun cookiePayload(cookie: String): String = json.encodeToString(mapOf("cookie" to cookie))
    fun cookieOf(payload: String): String =
        try { json.decodeFromString<Map<String, String>>(payload)["cookie"] ?: "" } catch (e: Throwable) { "" }

    fun accounts(context: Context, channel: String): List<BoundAccount> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(accountKey(channel), null)
        if (raw.isNullOrBlank()) {
            val migrated = migrateLegacy(context, channel)
            if (migrated != null) {
                saveAccounts(context, channel, listOf(migrated))
                return listOf(migrated)
            }
            return emptyList()
        }
        return try { json.decodeFromString<List<BoundAccount>>(raw) } catch (e: Throwable) { emptyList() }
    }

    fun saveAccounts(context: Context, channel: String, list: List<BoundAccount>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(accountKey(channel), json.encodeToString(list))
        }
    }

    fun addAccount(context: Context, channel: String, account: BoundAccount) {
        saveAccounts(context, channel, accounts(context, channel).filterNot { it.id == account.id } + account)
    }

    fun removeAccount(context: Context, channel: String, id: String) {
        saveAccounts(context, channel, accounts(context, channel).filterNot { it.id == id })
    }

    fun updateAccount(context: Context, channel: String, account: BoundAccount) {
        saveAccounts(context, channel, accounts(context, channel).map { if (it.id == account.id) account else it })
    }

    fun firstEnabledAccount(context: Context, channel: String): BoundAccount? =
        accounts(context, channel).firstOrNull { it.enabled }

    fun hasAnyAccount(context: Context): Boolean =
        CHANNELS.any { ch -> accounts(context, ch).any { it.enabled } }

    fun accountById(context: Context, channel: String, id: String): BoundAccount? =
        accounts(context, channel).firstOrNull { it.id == id }

    /** item 归属账号（找不到时回退该平台第一个启用账号） */
    fun accountForItem(context: Context, item: ExpressItem): BoundAccount? {
        if (item.accountId.isNotBlank()) {
            accountById(context, item.source, item.accountId)?.let { if (it.enabled) return it }
        }
        return firstEnabledAccount(context, item.source)
    }

    private fun accountKey(channel: String): String = when (channel) {
        CH_XIAOMI -> KEY_XIAOMI_ACCOUNTS
        CH_JD -> KEY_JD_ACCOUNTS
        CH_TAOBAO -> KEY_TB_ACCOUNTS
        CH_PDD -> KEY_PDD_ACCOUNTS
        else -> KEY_XIAOMI_ACCOUNTS
    }

    /** 旧版单账号凭证 → 首个绑定账号（一次性迁移 + 清理旧键） */
    private fun migrateLegacy(context: Context, channel: String): BoundAccount? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val e = prefs.edit()
        val account = when (channel) {
            CH_XIAOMI -> {
                val token = prefs.getString(KEY_XIAOMI_TOKEN, "") ?: ""
                if (token.isBlank()) null else {
                    val phones = runCatching {
                        json.decodeFromString<List<String>>(prefs.getString(KEY_XIAOMI_PHONES, "[]") ?: "[]")
                    }.getOrDefault(emptyList())
                    BoundAccount(
                        id = java.util.UUID.randomUUID().toString(),
                        label = "小米账号" + (phones.firstOrNull()?.let { " · ${maskPhone(it)}" } ?: ""),
                        payload = xiaomiPayload(
                            XiaomiCred(
                                token = token,
                                cUser = prefs.getString(KEY_XIAOMI_CUSER, "") ?: "",
                                accountId = prefs.getString(KEY_XIAOMI_ACCOUNT_ID, "") ?: "",
                                oaid = prefs.getString(KEY_XIAOMI_OAID, "") ?: "",
                                vaid = prefs.getString(KEY_XIAOMI_VAID, "") ?: "",
                                phones = phones
                            )
                        )
                    ).also {
                        e.remove(KEY_XIAOMI_TOKEN).remove(KEY_XIAOMI_CUSER).remove(KEY_XIAOMI_ACCOUNT_ID)
                            .remove(KEY_XIAOMI_OAID).remove(KEY_XIAOMI_VAID).remove(KEY_XIAOMI_PHONES)
                    }
                }
            }
            CH_JD -> {
                val c = prefs.getString(KEY_JD_COOKIES, "") ?: ""
                if (c.isBlank()) null else BoundAccount(
                    id = java.util.UUID.randomUUID().toString(),
                    label = "京东账号 · ${jdLabelOf(c)}",
                    payload = cookiePayload(c)
                ).also { e.remove(KEY_JD_COOKIES) }
            }
            CH_TAOBAO -> {
                val c = prefs.getString(KEY_TB_COOKIES, "") ?: ""
                if (c.isBlank()) null else BoundAccount(
                    id = java.util.UUID.randomUUID().toString(),
                    label = "淘宝账号 · ${tbLabelOf(c)}",
                    payload = cookiePayload(c)
                ).also { e.remove(KEY_TB_COOKIES) }
            }
            CH_PDD -> {
                val c = prefs.getString(KEY_PDD_COOKIES, "") ?: ""
                if (c.isBlank()) null else BoundAccount(
                    id = java.util.UUID.randomUUID().toString(),
                    label = "拼多多账号 · ${pddLabelOf(c)}",
                    payload = cookiePayload(c)
                ).also { e.remove(KEY_PDD_COOKIES) }
            }
            else -> null
        }
        if (account != null) e.commit()
        return account
    }

    fun maskPhone(p: String): String =
        if (p.length >= 7) p.replaceRange(3, 7, "****") else p

    fun jdLabelOf(cookies: String): String {
        val m = Regex("[;\\s]pt_pin=([^;]+)").find(cookies)?.groupValues?.get(1)
        return if (!m.isNullOrBlank()) java.net.URLDecoder.decode(m, "UTF-8").take(16) else "账号"
    }

    fun tbLabelOf(cookies: String): String {
        val unb = Regex("[;\\s]unb=([^;]+)").find(cookies)?.groupValues?.get(1)
        val cookie1 = Regex("[;\\s]cookie1=([^;]+)").find(cookies)?.groupValues?.get(1)
        val key = unb?.takeIf { it.isNotBlank() } ?: cookie1?.take(6) ?: ""
        return if (key.isNotBlank()) "尾号${key.takeLast(6)}" else "账号"
    }

    fun pddLabelOf(cookies: String): String {
        val uid = Regex("[;\\s]pdd_user_id=([^;]+)").find(cookies)?.groupValues?.get(1)
            ?: Regex("[;\\s]pdduid=([^;]+)").find(cookies)?.groupValues?.get(1)
        return if (!uid.isNullOrBlank()) "账号${uid.takeLast(6)}" else "账号"
    }

    /* ─────────────── 小米凭证（旧接口：回退为第一个启用账号） ─────────────── */

    fun xiaomiCred(context: Context): XiaomiCred =
        firstEnabledAccount(context, CH_XIAOMI)?.let { parseXiaomiCred(it.payload) } ?: XiaomiCred()

    fun xiaomiToken(context: Context): String = xiaomiCred(context).token

    fun xiaomiCUser(context: Context): String = xiaomiCred(context).cUser

    fun xiaomiAccountId(context: Context): String = xiaomiCred(context).accountId

    fun xiaomiOaid(context: Context): String = xiaomiCred(context).oaid

    fun xiaomiVaid(context: Context): String = xiaomiCred(context).vaid

    fun xiaomiPhones(context: Context): List<String> = xiaomiCred(context).phones

    fun addXiaomiAccount(context: Context, cred: XiaomiCred) {
        // 同账号（userId 相同）重新登录 = 更新凭证，不新增重复账号
        val existing = if (cred.accountId.isNotBlank()) {
            accounts(context, CH_XIAOMI).firstOrNull {
                Store.parseXiaomiCred(it.payload).accountId == cred.accountId
            }
        } else null
        if (existing != null) {
            updateAccount(
                context, CH_XIAOMI,
                existing.copy(payload = xiaomiPayload(cred))
            )
        } else {
            addAccount(
                context, CH_XIAOMI,
                BoundAccount(
                    id = java.util.UUID.randomUUID().toString(),
                    label = "小米账号" + (cred.phones.firstOrNull()?.let { " · ${maskPhone(it)}" } ?: ""),
                    payload = xiaomiPayload(cred)
                )
            )
        }
    }

    /** 旧接口（兼容）：写回为第一个启用账号的凭证 */
    fun saveXiaomiLogin(
        context: Context,
        token: String,
        cUser: String,
        accountId: String,
        oaid: String,
        vaid: String,
        phones: List<String>
    ) {
        val cred = XiaomiCred(token, cUser, accountId, oaid, vaid, phones)
        val first = firstEnabledAccount(context, CH_XIAOMI)
        if (first == null) addXiaomiAccount(context, cred)
        else updateAccount(context, CH_XIAOMI, first.copy(payload = xiaomiPayload(cred)))
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

    /* ─────────────── 京东登录 / 商品溯源（账号列表化） ─────────────── */

    fun jdCookies(context: Context): String =
        firstEnabledAccount(context, CH_JD)?.let { cookieOf(it.payload) } ?: ""

    fun addJdAccount(context: Context, cookies: String) {
        // 去重：同账号（标签=pt_key/尾号）已存在 → 更新凭证而非重复新增（防“自动登录重复绑定”）
        val label = "京东账号 · ${jdLabelOf(cookies)}"
        val exists = accounts(context, CH_JD).firstOrNull { it.label == label }
        if (exists != null) {
            updateAccount(context, CH_JD, exists.copy(payload = cookiePayload(cookies), enabled = true))
            return
        }
        addAccount(
            context, CH_JD,
            BoundAccount(
                id = java.util.UUID.randomUUID().toString(),
                label = label,
                payload = cookiePayload(cookies)
            )
        )
    }

    /** 旧接口（兼容）：保存为第一个启用账号 */
    fun saveJdCookies(context: Context, cookies: String) {
        val first = firstEnabledAccount(context, CH_JD)
        if (first == null) addJdAccount(context, cookies)
        else updateAccount(context, CH_JD, first.copy(payload = cookiePayload(cookies)))
    }

    fun clearJdLogin(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            remove(KEY_JD_COOKIES)
            remove(KEY_JD_GOODS)
        }
    }

    /* ─────────────── 淘宝登录 / 菜鸟溯源（账号列表化） ─────────────── */

    fun tbCookies(context: Context): String =
        firstEnabledAccount(context, CH_TAOBAO)?.let { cookieOf(it.payload) } ?: ""

    fun addTbAccount(context: Context, cookies: String) {
        // 去重：同账号（unb/cookie1 尾号）已存在 → 更新凭证而非重复新增
        val label = "淘宝账号 · ${tbLabelOf(cookies)}"
        val exists = accounts(context, CH_TAOBAO).firstOrNull { it.label == label }
        if (exists != null) {
            updateAccount(context, CH_TAOBAO, exists.copy(payload = cookiePayload(cookies), enabled = true))
            return
        }
        addAccount(
            context, CH_TAOBAO,
            BoundAccount(
                id = java.util.UUID.randomUUID().toString(),
                label = label,
                payload = cookiePayload(cookies)
            )
        )
    }

    /** 旧接口（兼容）：保存为第一个启用账号 */
    fun saveTbCookies(context: Context, cookies: String) {
        val first = firstEnabledAccount(context, CH_TAOBAO)
        if (first == null) addTbAccount(context, cookies)
        else updateAccount(context, CH_TAOBAO, first.copy(payload = cookiePayload(cookies)))
    }

    fun clearTbLogin(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            remove(KEY_TB_COOKIES)
        }
    }

    /* ─────────────── 拼多多登录 / 拼多多快递（账号列表化） ─────────────── */

    fun pddCookies(context: Context): String =
        firstEnabledAccount(context, CH_PDD)?.let { cookieOf(it.payload) } ?: ""

    fun addPddAccount(context: Context, cookies: String) {
        // 去重：同账号（pdd_user_id 尾号）已存在 → 更新凭证而非重复新增
        val label = "拼多多账号 · ${pddLabelOf(cookies)}"
        val exists = accounts(context, CH_PDD).firstOrNull { it.label == label }
        if (exists != null) {
            updateAccount(context, CH_PDD, exists.copy(payload = cookiePayload(cookies), enabled = true))
            return
        }
        addAccount(
            context, CH_PDD,
            BoundAccount(
                id = java.util.UUID.randomUUID().toString(),
                label = label,
                payload = cookiePayload(cookies)
            )
        )
    }

    /** 旧接口（兼容）：保存为第一个启用账号 */
    fun savePddCookies(context: Context, cookies: String) {
        val first = firstEnabledAccount(context, CH_PDD)
        if (first == null) addPddAccount(context, cookies)
        else updateAccount(context, CH_PDD, first.copy(payload = cookiePayload(cookies)))
    }

    fun clearPddLogin(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            remove(KEY_PDD_COOKIES)
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

    /** 旧接口兼容：手机号清单写入第一个启用的小米账号 payload（旧键 `xiaomi_phones` 已在 v0.5.0 迁移中弃用） */
    fun saveXiaomiPhones(context: Context, phones: List<String>) {
        val first = firstEnabledAccount(context, CH_XIAOMI)
        if (first != null) {
            updateXiaomiPhones(context, first.id, phones)
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putString(KEY_XIAOMI_PHONES, json.encodeToString(phones))
            }
        }
    }

    /** 多源绑定：按账号 id 更新该账号的手机号清单（写入 XiaomiCred.phones payload） */
    fun updateXiaomiPhones(context: Context, accountId: String, phones: List<String>) {
        val target = accountById(context, CH_XIAOMI, accountId)
            ?: firstEnabledAccount(context, CH_XIAOMI)
            ?: return
        val cred = parseXiaomiCred(target.payload)
        updateAccount(context, CH_XIAOMI, target.copy(payload = xiaomiPayload(cred.copy(phones = phones))))
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

    /* ─────────────── 多地址管理（可新增/编辑/删除/切换当前） ─────────────── */

    fun addresses(context: Context): List<HomeAddress> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ADDRESSES, null)
        if (raw == null || raw.isBlank()) {
            // 迁移：旧单地址 home_address → 默认地址（label=默认地址, active）
            val legacy = prefs.getString(KEY_HOME_ADDRESS, "") ?: ""
            if (legacy.isNotBlank()) {
                val id = java.util.UUID.randomUUID().toString()
                val list = listOf(HomeAddress(id = id, label = "默认地址", address = legacy))
                saveAddresses(context, list)
                prefs.edit().putString(KEY_ACTIVE_ADDRESS_ID, id).remove(KEY_HOME_ADDRESS).commit()
                return list
            }
            return emptyList()
        }
        return try { json.decodeFromString<List<HomeAddress>>(raw) } catch (e: Throwable) { emptyList() }
    }

    fun saveAddresses(context: Context, list: List<HomeAddress>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_ADDRESSES, json.encodeToString(list))
        }
    }

    fun addAddress(context: Context, label: String, address: String): HomeAddress {
        val a = HomeAddress(id = java.util.UUID.randomUUID().toString(), label = label, address = address)
        saveAddresses(context, addresses(context) + a)
        return a
    }

    fun updateAddress(context: Context, id: String, label: String, address: String) {
        saveAddresses(context, addresses(context).map {
            if (it.id == id) it.copy(label = label, address = address) else it
        })
    }

    fun removeAddress(context: Context, id: String) {
        val rest = addresses(context).filterNot { it.id == id }
        saveAddresses(context, rest)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_ACTIVE_ADDRESS_ID, null) == id) {
            prefs.edit().putString(
                KEY_ACTIVE_ADDRESS_ID,
                rest.firstOrNull()?.id ?: ""
            ).commit()
        }
    }

    fun activeAddressId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_ADDRESS_ID, "") ?: ""

    fun setActiveAddressId(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_ACTIVE_ADDRESS_ID, id)
        }
        // 地址切换后让 AI 进度失效重算
        val items = items(context).map { it.copy(aiProgress = -1, aiEta = "", aiProgressAt = "") }
        saveItems(context, items)
    }

    fun addressById(context: Context, id: String): HomeAddress? =
        addresses(context).firstOrNull { it.id == id }

    /** 当前地址字符串（AI 计算用）；无则回落第一条 */
    fun homeAddress(context: Context): String {
        val list = addresses(context)
        if (list.isEmpty()) return ""
        val activeId = activeAddressId(context)
        return (list.firstOrNull { it.id == activeId } ?: list.first()).address
    }

    fun saveHomeAddress(context: Context, address: String) {
        val list = addresses(context)
        if (list.isEmpty()) {
            val a = addAddress(context, "默认地址", address)
            setActiveAddressId(context, a.id)
        } else {
            val activeId = activeAddressId(context).ifBlank { list.first().id }
            updateAddress(context, activeId, list.first { it.id == activeId }.label, address)
        }
    }

    /** 件的收件地址（指定地址优先，未指定回退全局当前地址） */
    fun addressForItem(context: Context, item: ExpressItem): String {
        if (item.addressId.isNotBlank()) {
            addressById(context, item.addressId)?.let { return it.address }
        }
        return homeAddress(context)
    }

    fun addressLabelForItem(context: Context, item: ExpressItem): String? {
        if (item.addressId.isNotBlank()) {
            return addressById(context, item.addressId)?.label
        }
        val activeId = activeAddressId(context)
        val l = addresses(context)
        return if (l.isEmpty()) null else (l.firstOrNull { it.id == activeId } ?: l.first()).label
    }

    /* ─────────────── 拼多多轨迹缓存（始终保存；TTL 只决定「是否该重抓」） ─────────────── */

    /** 读取缓存轨迹（只要抓过就返回——**始终保存**，过期不影响展示） */
    fun pddTraces(context: Context, mailNo: String): List<DetailPoint>? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PDD_TRACES, null) ?: return null
        val map = runCatching { json.decodeFromString<Map<String, PddTraceCache>>(raw) }.getOrNull() ?: return null
        val e = map[mailNo] ?: return null
        return e.points.takeIf { it.isNotEmpty() }
    }

    /** 是否需要重抓：无缓存；已完成单**只抓一次**（之后永远用已保存的）；活跃单超 1h 重抓 */
    fun pddTraceNeedsRefresh(context: Context, mailNo: String, done: Boolean): Boolean {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PDD_TRACES, null) ?: return true
        val map = runCatching { json.decodeFromString<Map<String, PddTraceCache>>(raw) }.getOrNull() ?: return true
        val e = map[mailNo] ?: return true
        if (e.points.isEmpty()) return true
        if (done || e.done) return false // 已完成：轨迹不会再变，始终用已保存的
        return System.currentTimeMillis() - e.fetchedAt > 3600 * 1000L
    }

    fun pddTraceLastFetch(context: Context, mailNo: String): Long {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PDD_TRACES, null) ?: return 0L
        val map = runCatching { json.decodeFromString<Map<String, PddTraceCache>>(raw) }.getOrNull() ?: return 0L
        return map[mailNo]?.fetchedAt ?: 0L
    }

    fun savePddTrace(
        context: Context,
        mailNo: String,
        points: List<DetailPoint>,
        done: Boolean = false,
        fetchedAt: Long = System.currentTimeMillis()
    ) {
        if (points.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val map = runCatching {
            json.decodeFromString<Map<String, PddTraceCache>>(
                prefs.getString(KEY_PDD_TRACES, null) ?: "{}"
            )
        }.getOrElse { emptyMap() }.toMutableMap()
        map[mailNo] = PddTraceCache(fetchedAt = fetchedAt, points = points, done = done)
        // 始终保存：仅做超大安全上限（2000 单），正常量级不淘汰
        val trimmed = map.entries.sortedByDescending { it.value.fetchedAt }.take(2000).associate { it.key to it.value }
        prefs.edit().putString(KEY_PDD_TRACES, json.encodeToString(trimmed)).commit()
    }

    /* ─────────────── 京东轨迹缓存（活跃单 1h / 已完成 24h；详情秒开） ─────────────── */

    fun jdTraces(context: Context, mailNo: String, done: Boolean = false): List<DetailPoint>? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_JD_TRACES, null) ?: return null
        val map = runCatching { json.decodeFromString<Map<String, PddTraceCache>>(raw) }.getOrNull() ?: return null
        val e = map[mailNo] ?: return null
        if (e.points.isEmpty()) return null
        val ttl = if (done || e.done) 24 * 3600 * 1000L else 3600 * 1000L
        if (System.currentTimeMillis() - e.fetchedAt > ttl) return null
        return e.points
    }

    fun saveJdTrace(context: Context, mailNo: String, points: List<DetailPoint>, done: Boolean = false) {
        if (points.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val map = runCatching {
            json.decodeFromString<Map<String, PddTraceCache>>(
                prefs.getString(KEY_JD_TRACES, null) ?: "{}"
            )
        }.getOrElse { emptyMap() }.toMutableMap()
        map[mailNo] = PddTraceCache(fetchedAt = System.currentTimeMillis(), points = points, done = done)
        val trimmed = map.entries.sortedByDescending { it.value.fetchedAt }.take(2000).associate { it.key to it.value }
        prefs.edit().putString(KEY_JD_TRACES, json.encodeToString(trimmed)).commit()
    }

    /* ─────────────── 淘宝（菜鸟）轨迹缓存：活跃单 1h / 已完成 24h ─────────────── */

    fun tbTraces(context: Context, mailNo: String, done: Boolean = false): List<DetailPoint>? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TB_TRACES, null) ?: return null
        val map = runCatching { json.decodeFromString<Map<String, PddTraceCache>>(raw) }.getOrNull() ?: return null
        val e = map[mailNo] ?: return null
        if (e.points.isEmpty()) return null
        val ttl = if (done || e.done) 24 * 3600 * 1000L else 3600 * 1000L
        if (System.currentTimeMillis() - e.fetchedAt > ttl) return null
        return e.points
    }

    fun saveTbTrace(context: Context, mailNo: String, points: List<DetailPoint>, done: Boolean = false) {
        if (points.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val map = runCatching {
            json.decodeFromString<Map<String, PddTraceCache>>(
                prefs.getString(KEY_TB_TRACES, null) ?: "{}"
            )
        }.getOrElse { emptyMap() }.toMutableMap()
        map[mailNo] = PddTraceCache(fetchedAt = System.currentTimeMillis(), points = points, done = done)
        val trimmed = map.entries.sortedByDescending { it.value.fetchedAt }.take(2000).associate { it.key to it.value }
        prefs.edit().putString(KEY_TB_TRACES, json.encodeToString(trimmed)).commit()
    }

    /**
     * 主题。默认 **lark（温暖纸感 · 衬线）**——纸感衬线是云雀的主打视觉身份，
     * 新装用户第一眼就该看到它（配色默认 warm、字体默认 serif，与之一致）。
     * 想要跟随系统壁纸取色的用户在设置里切「莫奈取色」。
     */
    fun theme(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME, "lark") ?: "lark"

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
