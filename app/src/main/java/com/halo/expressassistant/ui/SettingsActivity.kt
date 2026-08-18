package com.halo.expressassistant.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.location.Geocoder
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.PackageInfoCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputLayout
import com.halo.expressassistant.ApiServer
import com.halo.expressassistant.R
import com.halo.expressassistant.ai.Markdown
import com.halo.expressassistant.api.XiaomiApi
import com.halo.expressassistant.data.Store
import com.halo.expressassistant.databinding.ActivitySettingsBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var addressInput: EditText? = null
    private val requestXiaomi = 100
    private val requestJd = 101
    private val requestTb = 102

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.apply(this, binding.root)
        Paper.apply(this, binding.root, binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.rowAi.setOnClickListener { showAiSheet() }
        binding.rowTheme.setOnClickListener { showThemeSheet() }
        binding.rowAddress.setOnClickListener { showAddressSheet() }
        binding.rowXiaomiLogin.setOnClickListener { showXiaomiSheet() }
        binding.rowJdLogin.setOnClickListener { showJdSheet() }
        binding.rowTbLogin.setOnClickListener { showTbSheet() }
        binding.rowPhone.setOnClickListener { showPhoneDialog() }
        binding.rowHidden.setOnClickListener { showHiddenDialog() }
        binding.rowWidget.setOnClickListener { showWidgetSheet() }
        binding.rowLocalApi.setOnClickListener { showLocalApiSheet() }
        binding.rowMore.setOnClickListener { showMoreSheet() }
        binding.rowAbout.setOnClickListener { showAboutSheet() }
        binding.rowUpdate.setOnClickListener { showUpdateSheet() }
        binding.btnTaobao.setOnClickListener { showTaobaoSheet() }

        refreshButtons()
    }

    private fun refreshButtons() {
        binding.aiSummary.text = "对话风格：${Store.aiStyleLabel(this)} · 接口 / 日报 / 对话"
        binding.themeSummary.text = themeLabel()
        binding.xiaomiLoginSummary.text = if (Store.xiaomiToken(this).isNotEmpty()) {
            val phones = Store.xiaomiPhones(this)
            if (phones.isEmpty()) "已登录 · 未绑定手机号" else "已登录 · ${phones.joinToString("、")}"
        } else {
            "未登录 · 扫码登录后同步"
        }
        binding.jdLoginSummary.text = if (Store.jdCookies(this).isNotBlank()) {
            "已登录 · 可查京东订单商品"
        } else {
            "未登录"
        }
        binding.tbLoginSummary.text = if (Store.tbCookies(this).isNotBlank()) {
            "已登录 · 可查淘宝件商品"
        } else {
            "未登录"
        }
        binding.phoneSummary.text = Store.xiaomiPhones(this).joinToString("、")
        binding.hiddenSummary.text = "${Store.xiaomiHidden(this).size} 个已删除"
        binding.localApiSummary.text = if (ApiServer.isRunning()) {
            "运行中 · 127.0.0.1:${ApiServer.PORT}"
        } else {
            "已关闭"
        }
        binding.addressSummary.text = Store.homeAddress(this).ifBlank { "未设置 · 用于 AI 计算进度" }
    }

    private fun themeLabel(): String = when (Store.theme(this)) {
        Themes.MONET -> "莫奈取色"
        Themes.CUSTOM -> {
            if (Store.customSeparate(this)) {
                "自定义 · 分昼夜设置"
            } else {
                val color = if (Store.colorScheme(this) == "monet") "莫奈" else "温暖"
                val font = if (Store.themeFont(this) == Themes.FONT_SERIF) "衬线" else "无衬线"
                "自定义 · $color / $font / 纸感 ${Store.paperIntensity(this)}%"
            }
        }
        else -> "温暖纸感"
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestXiaomi || requestCode == requestJd || requestCode == requestTb) {
            refreshButtons()
        }
    }

    /* ─────────────── 云雀（AI） ─────────────── */

    private fun showAiSheet() {
        val (sheet, container) = Sheets.create(this, "云雀", "对话风格、接口与对话")

        container.addView(Sheets.sectionTitle(this, "对话风格"))
        val styleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(styleBox)
        fun renderStyles() {
            styleBox.removeAllViews()
            fun add(v: String, label: String) {
                styleBox.addView(
                    Sheets.optionRow(this@SettingsActivity, label, null, Store.aiStyle(this@SettingsActivity) == v) {
                        Store.saveAiStyle(this@SettingsActivity, v)
                        renderStyles()
                        Toast.makeText(this@SettingsActivity, "已切换：${Store.aiStyleLabel(this@SettingsActivity)}", Toast.LENGTH_SHORT).show()
                    }
                )
            }
            add(Store.AI_STYLE_VICTORIAN, "维多利亚")
            add(Store.AI_STYLE_KAWAII, "可爱云雀")
            add(Store.AI_STYLE_CLEAN, "原本的模样")
        }
        renderStyles()

        container.addView(Sheets.sectionTitle(this, "接口设置"))
        val baseInput = outlinedInput("接口地址", Store.aiBase(this))
        val keyInput = outlinedInput("API Key", Store.aiKey(this), password = true)
        val modelInput = outlinedInput("模型", Store.aiModel(this))
        container.addView(baseInput)
        container.addView(keyInput)
        container.addView(modelInput)

        container.addView(Sheets.divider(this))
        container.addView(sheetNavRow("定时日报", "设置多个定时与重复规则", R.drawable.ic_schedule) {
            startActivity(Intent(this@SettingsActivity, ChatActivity::class.java).putExtra("open_schedule", true))
        })
        container.addView(sheetNavRow("清空对话记录", "删除云雀的所有聊天历史", R.drawable.ic_delete) {
            MaterialAlertDialogBuilder(this@SettingsActivity)
                .setTitle("清空对话记录")
                .setMessage("将删除云雀的所有聊天历史，无法恢复。")
                .setPositiveButton("清空") { _, _ ->
                    Store.clearChatHistory(this@SettingsActivity)
                    Toast.makeText(this@SettingsActivity, "已清空", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        })

        container.addView(
            MaterialButton(this).apply {
                text = "保存"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(16) }
                setOnClickListener {
                    Store.saveSettings(
                        this@SettingsActivity,
                        baseInput.editText?.text?.toString()?.trim().orEmpty(),
                        keyInput.editText?.text?.toString()?.trim().orEmpty(),
                        modelInput.editText?.text?.toString()?.trim().orEmpty(),
                        Store.kdKey(this@SettingsActivity),
                        Store.kdCustomer(this@SettingsActivity),
                        Store.reportTime(this@SettingsActivity).first,
                        Store.reportTime(this@SettingsActivity).second,
                        Store.kd100Fallback(this@SettingsActivity),
                        Store.accessibilityEnabled(this@SettingsActivity)
                    )
                    Toast.makeText(this@SettingsActivity, "已保存", Toast.LENGTH_SHORT).show()
                    sheet.dismiss()
                }
            }
        )

        sheet.show()
    }

    /* ─────────────── 主题外观 ─────────────── */

    private fun showThemeSheet() {
        val (sheet, container) = Sheets.create(this, "主题外观", "选择主题，或在自定义里自由混搭元素")
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(body)
        var changed = false
        sheet.setOnDismissListener {
            if (changed) recreate()
        }
        renderThemeBody(sheet, body) { changed = true }
        sheet.show()
    }

    private fun renderThemeBody(
        sheet: BottomSheetDialog,
        body: LinearLayout,
        markChanged: () -> Unit = {}
    ) {
        body.removeAllViews()
        val theme = Store.theme(this)
        body.addView(
            Sheets.optionRow(this, "莫奈取色", "跟随系统壁纸 · 无衬线 · 无纸纹", theme == Themes.MONET) {
                Store.saveTheme(this@SettingsActivity, Themes.MONET)
                Store.saveColorScheme(this@SettingsActivity, "monet")
                Store.saveThemeFont(this@SettingsActivity, Themes.FONT_SANS)
                Store.savePaperIntensity(this@SettingsActivity, 0)
                markChanged()
                renderThemeBody(sheet, body, markChanged)
            }
        )
        body.addView(
            Sheets.optionRow(this, "温暖纸感", "温暖配色 · 衬线 · 纸纹", theme == Themes.LARK) {
                Store.saveTheme(this@SettingsActivity, Themes.LARK)
                Store.saveColorScheme(this@SettingsActivity, "warm")
                Store.saveThemeFont(this@SettingsActivity, Themes.FONT_SERIF)
                Store.savePaperIntensity(this@SettingsActivity, 100)
                markChanged()
                renderThemeBody(sheet, body, markChanged)
            }
        )
        body.addView(
            Sheets.optionRow(this, "自定义", "混搭莫奈 / 温暖、衬线 / 无衬线、纸感", theme == Themes.CUSTOM) {
                Store.saveTheme(this@SettingsActivity, Themes.CUSTOM)
                markChanged()
                renderThemeBody(sheet, body, markChanged)
            }
        )
        if (theme == Themes.CUSTOM) {
            renderCustomTheme(sheet, body, markChanged)
        }
    }

    private fun renderCustomTheme(
        sheet: BottomSheetDialog,
        body: LinearLayout,
        markChanged: () -> Unit
    ) {
        body.addView(Sheets.sectionTitle(this, "昼夜设置"))
        body.addView(
            Sheets.optionRow(this, "白天 / 黑夜分别设置", "开启后可为白天和夜晚分别搭配", Store.customSeparate(this)) {
                Store.saveCustomSeparate(
                    this@SettingsActivity,
                    !Store.customSeparate(this@SettingsActivity)
                )
                markChanged()
                renderThemeBody(sheet, body, markChanged)
            }
        )
        if (Store.customSeparate(this)) {
            renderThemeMode(sheet, body, markChanged, "白天", true)
            renderThemeMode(sheet, body, markChanged, "夜间", false)
        } else {
            renderThemeMode(sheet, body, markChanged, null, null)
        }
    }

    private fun renderThemeMode(
        sheet: BottomSheetDialog,
        body: LinearLayout,
        markChanged: () -> Unit,
        label: String?,
        isDay: Boolean?
    ) {
        if (label != null) {
            body.addView(Sheets.sectionTitle(this, label))
        }
        val color = when (isDay) {
            true -> Store.colorSchemeDay(this)
            false -> Store.colorSchemeNight(this)
            null -> Store.colorScheme(this)
        }
        val font = when (isDay) {
            true -> Store.themeFontDay(this)
            false -> Store.themeFontNight(this)
            null -> Store.themeFont(this)
        }
        val paper = when (isDay) {
            true -> Store.paperIntensityDay(this)
            false -> Store.paperIntensityNight(this)
            null -> Store.paperIntensity(this)
        }

        body.addView(
            Sheets.optionRow(this, "配色 · 莫奈取色", null, color == "monet") {
                when (isDay) {
                    true -> Store.saveColorSchemeDay(this@SettingsActivity, "monet")
                    false -> Store.saveColorSchemeNight(this@SettingsActivity, "monet")
                    null -> Store.saveColorScheme(this@SettingsActivity, "monet")
                }
                markChanged()
                renderThemeBody(sheet, body, markChanged)
            }
        )
        body.addView(
            Sheets.optionRow(this, "配色 · 温暖", null, color == "warm") {
                when (isDay) {
                    true -> Store.saveColorSchemeDay(this@SettingsActivity, "warm")
                    false -> Store.saveColorSchemeNight(this@SettingsActivity, "warm")
                    null -> Store.saveColorScheme(this@SettingsActivity, "warm")
                }
                markChanged()
                renderThemeBody(sheet, body, markChanged)
            }
        )
        body.addView(
            Sheets.optionRow(this, "字体 · 衬线", null, font == Themes.FONT_SERIF) {
                when (isDay) {
                    true -> Store.saveThemeFontDay(this@SettingsActivity, Themes.FONT_SERIF)
                    false -> Store.saveThemeFontNight(this@SettingsActivity, Themes.FONT_SERIF)
                    null -> Store.saveThemeFont(this@SettingsActivity, Themes.FONT_SERIF)
                }
                markChanged()
                renderThemeBody(sheet, body, markChanged)
            }
        )
        body.addView(
            Sheets.optionRow(this, "字体 · 无衬线", null, font == Themes.FONT_SANS) {
                when (isDay) {
                    true -> Store.saveThemeFontDay(this@SettingsActivity, Themes.FONT_SANS)
                    false -> Store.saveThemeFontNight(this@SettingsActivity, Themes.FONT_SANS)
                    null -> Store.saveThemeFont(this@SettingsActivity, Themes.FONT_SANS)
                }
                markChanged()
                renderThemeBody(sheet, body, markChanged)
            }
        )

        body.addView(Sheets.sectionTitle(this, "纸感强度"))
        val valueText = TextView(this).apply {
            text = "$paper%"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(MaterialColors.getColor(this@SettingsActivity, android.R.attr.colorPrimary, 0))
            gravity = Gravity.END
        }
        body.addView(valueText)
        body.addView(
            SeekBar(this).apply {
                max = 200
                progress = paper
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        when (isDay) {
                            true -> Store.savePaperIntensityDay(this@SettingsActivity, progress)
                            false -> Store.savePaperIntensityNight(this@SettingsActivity, progress)
                            null -> Store.savePaperIntensity(this@SettingsActivity, progress)
                        }
                        valueText.text = "$progress%"
                        markChanged()
                        Paper.apply(this@SettingsActivity, binding.root, binding.toolbar)
                        refreshButtons()
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) {}

                    override fun onStopTrackingTouch(seekBar: SeekBar) {}
                })
            }
        )
        body.addView(
            TextView(this).apply {
                text = "0 为关闭纸纹"
                textSize = 12f
                setTextColor(onSurfaceVariant())
                setPadding(0, 2, 0, 0)
            }
        )
    }

    /* ─────────────── 小组件 ─────────────── */

    private fun showWidgetSheet() {
        val (sheet, container) = Sheets.create(this, "小组件", "选择显示哪些状态的快递，已完成和异常固定不显示")
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(box)

        fun render() {
            box.removeAllViews()
            fun add(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
                box.addView(
                    Sheets.optionRow(this@SettingsActivity, title, null, checked) {
                        onChange(!checked)
                        render()
                        refreshButtons()
                    }
                )
            }
            add("派送中", Store.widgetShowDelivering(this@SettingsActivity)) {
                Store.saveWidgetShowDelivering(this@SettingsActivity, it)
            }
            add("运输中", Store.widgetShowShipped(this@SettingsActivity)) {
                Store.saveWidgetShowShipped(this@SettingsActivity, it)
            }
            add("未发货", Store.widgetShowNotShipped(this@SettingsActivity)) {
                Store.saveWidgetShowNotShipped(this@SettingsActivity, it)
            }
        }
        render()
        sheet.show()
    }

    /* ─────────────── 更多连接方式 ─────────────── */

    private fun showMoreSheet() {
        val (sheet, container) = Sheets.create(this, "更多连接方式", "快递100 兜底与无障碍导入")
        val kdKey = outlinedInput("快递100 key（可留空）", Store.kdKey(this))
        val kdCustomer = outlinedInput("快递100 customer（可留空）", Store.kdCustomer(this))
        container.addView(kdKey)
        container.addView(kdCustomer)
        val swKd = SwitchMaterial(this).apply {
            isChecked = Store.kd100Fallback(this@SettingsActivity)
            text = "快递100兜底"
        }
        val swA11y = SwitchMaterial(this).apply {
            isChecked = Store.accessibilityEnabled(this@SettingsActivity)
            text = "无障碍导入（小米/菜鸟/淘宝）"
        }
        container.addView(swKd)
        container.addView(swA11y)
        container.addView(
            MaterialButton(this).apply {
                text = "保存"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(16) }
                setOnClickListener {
                    Store.saveSettings(
                        this@SettingsActivity,
                        Store.aiBase(this@SettingsActivity),
                        Store.aiKey(this@SettingsActivity),
                        Store.aiModel(this@SettingsActivity),
                        kdKey.editText?.text?.toString()?.trim().orEmpty(),
                        kdCustomer.editText?.text?.toString()?.trim().orEmpty(),
                        Store.reportTime(this@SettingsActivity).first,
                        Store.reportTime(this@SettingsActivity).second,
                        swKd.isChecked,
                        swA11y.isChecked
                    )
                    Toast.makeText(this@SettingsActivity, "已保存", Toast.LENGTH_SHORT).show()
                    sheet.dismiss()
                }
            }
        )
        sheet.show()
    }

    /* ─────────────── 关于云雀 ─────────────── */

    private fun showAboutSheet() {
        val (sheet, container) = Sheets.create(this, "关于云雀", "本地优先的三源快递聚合与私人仓管")
        val intro = """
            # 云雀 · 快递助手

            云雀是一个**本地优先**的 Android 快递聚合应用：它把散落在小米、京东、淘宝/菜鸟三个平台里的快递，合并成**属于你自己**的一份列表。它不只是一个查件工具，更是一位会说话的私人仓管。

            ## 三源融合

            - 小米智能助理、京东订单中心、淘宝/菜鸟，三套登录可任意组合
            - 按运单号合并去重，小米字段优先；任一渠道失败不影响其他渠道
            - 全部数据只保存在手机本地，不经过任何“云雀服务器”

            ## 商品溯源

            把“这是什么快递”回答到底：物流单号会被解析成**真实的商品名、图片和数量**，首页卡片直接显示商品缩略图与短名，而不是冰冷的承运商和单号。

            ## 聚合取件码

            从各渠道轨迹里自动提取取件码、取货码、提货码、驿站码，直接显示在卡片上。取件时不用再翻找各平台的短信和通知。

            ## 云雀 AI

            - 三种对话风格：维多利亚 / 可爱云雀 / 原本的模样
            - 可以读快递、改名称、改状态、移动分区、开关跟踪、触发同步
            - 结合轨迹与收件地址，计算运输进度和预计送达
            - 回答里可以直接贴出快递卡片

            ## 定时日报

            每天定时生成一份“报纸”风格早报：**有在途快递时才生成**，期数真实累计；没有在途的早晨，云雀不会打扰你。

            ## 小组件与主题

            - 桌面小组件：动态行列、状态筛选、暖纸主题
            - 主题：莫奈取色 / 温暖纸感 / 自定义，纸感强度与昼夜可分别设置

            ## 本地接口

            手机内提供 `127.0.0.1:8765` 的 CLI / MCP 接口，可让命令行或外部 AI 访问你的快递数据；钥匙只在你手里。

            ---

            > 数据是你的行李，不是平台的货物。
        """.trimIndent()
        container.addView(
            TextView(this).apply {
                text = Markdown.render(this@SettingsActivity, intro)
                textSize = 15f
                setTextColor(MaterialColors.getColor(this@SettingsActivity, com.google.android.material.R.attr.colorOnSurface, 0))
                setLineSpacing(dp(3).toFloat(), 1.3f)
                setPadding(0, dp(6), 0, 0)
                setTextIsSelectable(true)
            }
        )
        sheet.show()
    }

    /* ─────────────── 更新与 GitHub ─────────────── */

    private fun showUpdateSheet() {
        val (sheet, container) = Sheets.create(this, "更新与 GitHub")
        val version = try {
            val info = packageManager.getPackageInfo(packageName, 0)
            "${info.versionName} · versionCode ${PackageInfoCompat.getLongVersionCode(info)}"
        } catch (e: Throwable) {
            "未知"
        }
        container.addView(Sheets.sectionTitle(this, "当前版本"))
        container.addView(
            TextView(this).apply {
                text = version
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 2, 0, dp(8))
            }
        )
        container.addView(Sheets.divider(this))
        container.addView(
            sheetNavRow(
                "GitHub 仓库",
                "Halo0sama/ExpressAssistant",
                R.drawable.ic_widget_open
            ) {
                openUrl("https://github.com/Halo0sama/ExpressAssistant")
            }
        )
        container.addView(Sheets.divider(this))
        val checkButton = MaterialButton(this).apply {
            text = "检查更新"
            isEnabled = true
        }
        container.addView(
            checkButton,
            LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        )
        checkButton.setOnClickListener {
            checkButton.isEnabled = false
            checkButton.text = "检查中…"
            CoroutineScope(Dispatchers.Main).launch {
                val result = UpdateChecker.check(this@SettingsActivity)
                checkButton.isEnabled = true
                checkButton.text = "检查更新"
                when {
                    result.hasUpdate -> {
                        Toast.makeText(
                            this@SettingsActivity,
                            "发现新版本 v${result.latestTag}，正在打开下载…",
                            Toast.LENGTH_LONG
                        ).show()
                        sheet.dismiss()
                        openUrl(result.downloadUrl ?: "https://github.com/Halo0sama/ExpressAssistant/releases/latest")
                    }
                    result.latestTag != null -> {
                        Toast.makeText(
                            this@SettingsActivity,
                            "已是最新版本 v${result.latestTag}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    else -> {
                        Toast.makeText(
                            this@SettingsActivity,
                            "检查失败：${result.error ?: "未知错误"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
        sheet.show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Throwable) {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show()
        }
    }

    /* ─────────────── 小米登录 / 淘宝说明 ─────────────── */

    private fun showJdSheet() {
        val (sheet, container) = Sheets.create(this, "京东登录")
        val loggedIn = Store.jdCookies(this).isNotBlank()
        container.addView(
            TextView(this).apply {
                text = if (loggedIn) {
                    "当前已登录。登录后可用于京东订单查询与商品溯源。"
                } else {
                    "登录在 App 内完成，凭证仅保存在本地。"
                }
                textSize = 14f
                setLineSpacing(0f, 1.3f)
                setTextColor(onSurfaceVariant())
                setPadding(0, 0, 0, dp(14))
            }
        )
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        if (loggedIn) {
            buttons.addView(
                MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "退出登录"
                    setOnClickListener {
                        Store.clearJdLogin(this@SettingsActivity)
                        val cm = android.webkit.CookieManager.getInstance()
                        cm.removeAllCookies(null)
                        cm.flush()
                        refreshButtons()
                        sheet.dismiss()
                        Toast.makeText(this@SettingsActivity, "已退出京东登录", Toast.LENGTH_SHORT).show()
                    }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) }
            )
        }
        buttons.addView(
            MaterialButton(this).apply {
                text = if (loggedIn) "重新登录" else "去登录"
                setOnClickListener {
                    sheet.dismiss()
                    startActivityForResult(
                        Intent(this@SettingsActivity, JdLoginActivity::class.java),
                        requestJd
                    )
                }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        container.addView(buttons)
        sheet.show()
    }

    private fun showTbSheet() {
        val (sheet, container) = Sheets.create(this, "淘宝登录")
        val loggedIn = Store.tbCookies(this).isNotBlank()
        container.addView(
            TextView(this).apply {
                text = if (loggedIn) {
                    "当前已登录。登录后可用于淘宝件查询与商品溯源。"
                } else {
                    "登录在 App 内完成，凭证仅保存在本地。"
                }
                textSize = 14f
                setLineSpacing(0f, 1.3f)
                setTextColor(onSurfaceVariant())
                setPadding(0, 0, 0, dp(14))
            }
        )
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        if (loggedIn) {
            buttons.addView(
                MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "退出登录"
                    setOnClickListener {
                        Store.clearTbLogin(this@SettingsActivity)
                        val cm = android.webkit.CookieManager.getInstance()
                        cm.removeAllCookies(null)
                        cm.flush()
                        refreshButtons()
                        sheet.dismiss()
                        Toast.makeText(this@SettingsActivity, "已退出淘宝登录", Toast.LENGTH_SHORT).show()
                    }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) }
            )
        }
        buttons.addView(
            MaterialButton(this).apply {
                text = if (loggedIn) "重新登录" else "去登录"
                setOnClickListener {
                    sheet.dismiss()
                    startActivityForResult(
                        Intent(this@SettingsActivity, TbLoginActivity::class.java),
                        requestTb
                    )
                }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        container.addView(buttons)
        sheet.show()
    }

    private fun showXiaomiSheet() {
        val (sheet, container) = Sheets.create(this, "小米登录")
        val loggedIn = Store.xiaomiToken(this).isNotEmpty()
        container.addView(
            TextView(this).apply {
                text = if (loggedIn) {
                    "当前已登录" + Store.xiaomiPhones(this@SettingsActivity).joinToString("、") { " $it" }
                } else {
                    "未登录 · 扫码登录后同步"
                }
                textSize = 14f
                setTextColor(onSurfaceVariant())
                setPadding(0, 0, 0, dp(14))
            }
        )
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        if (loggedIn) {
            buttons.addView(
                MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "退出登录"
                    setOnClickListener {
                        Store.clearXiaomiLogin(this@SettingsActivity)
                        val cm = android.webkit.CookieManager.getInstance()
                        cm.removeAllCookies(null)
                        cm.flush()
                        refreshButtons()
                        sheet.dismiss()
                        Toast.makeText(this@SettingsActivity, "已退出小米登录", Toast.LENGTH_SHORT).show()
                    }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) }
            )
        }
        buttons.addView(
            MaterialButton(this).apply {
                text = if (loggedIn) "重新登录" else "扫码登录"
                setOnClickListener {
                    sheet.dismiss()
                    startActivityForResult(
                        Intent(this@SettingsActivity, XiaomiLoginActivity::class.java),
                        requestXiaomi
                    )
                }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        container.addView(buttons)
        sheet.show()
    }

    private fun showTaobaoSheet() {
        val (sheet, container) = Sheets.create(this, "淘宝快递同步")
        container.addView(
            TextView(this).apply {
                text = "若要同步淘宝的快递，需要在淘宝设置的隐私设置里关闭“订单号码保护”。"
                textSize = 14f
                setLineSpacing(0f, 1.3f)
                setTextColor(MaterialColors.getColor(this@SettingsActivity, com.google.android.material.R.attr.colorOnSurface, 0))
                setPadding(0, 0, 0, dp(16))
            }
        )
        container.addView(
            MaterialButton(this).apply {
                text = "好的"
                setOnClickListener { sheet.dismiss() }
            }
        )
        sheet.show()
    }

    /* ─────────────── 我的地址 ─────────────── */

    private fun showAddressSheet() {
        val sheet = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(24))
        }
        container.addView(
            TextView(this).apply {
                text = "我的地址"
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
            }
        )
        container.addView(
            TextView(this).apply {
                text = "用于 AI 计算运输进度与预计送达时间，也可以手动填写"
                textSize = 13f
                setTextColor(onSurfaceVariant())
                setPadding(0, 2, 0, dp(14))
            }
        )
        val inputLayout = layoutInflater.inflate(R.layout.view_input_outlined, null) as TextInputLayout
        inputLayout.hint = "收件地址"
        inputLayout.editText?.setText(Store.homeAddress(this))
        addressInput = inputLayout.editText
        container.addView(inputLayout)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, 0)
        }
        val locate = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "自动定位"
            setIconResource(R.drawable.ic_location)
            setOnClickListener {
                locateAddress(inputLayout.editText!!) {
                    Toast.makeText(this@SettingsActivity, "定位失败，请检查定位权限或手动填写", Toast.LENGTH_LONG).show()
                }
            }
        }
        buttons.addView(
            locate,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(10)
            }
        )
        buttons.addView(
            MaterialButton(this).apply {
                text = "保存"
                setOnClickListener {
                    val addr = inputLayout.editText?.text?.toString()?.trim().orEmpty()
                    Store.saveHomeAddress(this@SettingsActivity, addr)
                    val items = Store.items(this@SettingsActivity).map {
                        it.copy(aiProgress = -1, aiEta = "", aiProgressAt = "")
                    }
                    Store.saveItems(this@SettingsActivity, items)
                    refreshButtons()
                    Toast.makeText(this@SettingsActivity, "已保存", Toast.LENGTH_SHORT).show()
                    sheet.dismiss()
                }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        container.addView(buttons)
        sheet.setContentView(container)
        sheet.show()
    }

    private fun locateAddress(input: EditText, onFail: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQUEST_LOCATION
            )
            return
        }
        CoroutineScope(Dispatchers.Main).launch {
            val address = withContext(Dispatchers.IO) {
                try {
                    val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                        ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                        ?: return@withContext ""
                    val geocoder = Geocoder(this@SettingsActivity, Locale.CHINA)
                    val list = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
                    val a = list?.firstOrNull()
                    listOf(
                        a?.countryName,
                        a?.adminArea,
                        a?.locality,
                        a?.subLocality,
                        a?.thoroughfare,
                        a?.subThoroughfare
                    ).filterNotNull().joinToString("").ifBlank {
                        "${loc.latitude},${loc.longitude}"
                    }
                } catch (e: Throwable) {
                    ""
                }
            }
            if (address.isNotEmpty()) {
                input.setText(address)
            } else {
                onFail()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION) {
            val input = addressInput ?: return
            locateAddress(input) {
                Toast.makeText(this, "定位失败，请检查定位权限或手动填写", Toast.LENGTH_LONG).show()
            }
        }
    }

    /* ─────────────── 本地接口 ─────────────── */

    private fun showLocalApiSheet() {
        val (sheet, container) = Sheets.create(this, "本地接口（CLI / MCP）")
        container.addView(
            TextView(this).apply {
                text = "仅监听手机本机 127.0.0.1:${ApiServer.PORT}，通过 adb 端口转发后，可由命令行或 AI 调用。不会暴露到局域网。"
                textSize = 13f
                setTextColor(onSurfaceVariant())
                setPadding(0, 4, 0, dp(14))
                setLineSpacing(0f, 1.2f)
            }
        )
        container.addView(
            TextView(this).apply {
                text = "使用方式"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(6), 0, dp(6))
            }
        )
        val usage = "adb forward tcp:${ApiServer.PORT} tcp:${ApiServer.PORT}\n\n" +
            "python3 tools/express-cli.py list\n" +
            "python3 tools/express-cli.py mcp summarize '{\"question\":\"汇总我的快递\"}'"
        container.addView(
            TextView(this).apply {
                text = usage
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(MaterialColors.getColor(this@SettingsActivity, com.google.android.material.R.attr.colorOnSurfaceVariant, 0))
                setBackgroundColor(MaterialColors.getColor(this@SettingsActivity, com.google.android.material.R.attr.colorSurfaceContainerLow, 0))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setLineSpacing(0f, 1.2f)
            }
        )

        val switchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        switchRow.addView(
            TextView(this).apply {
                text = "启用本地接口"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        val sw = SwitchMaterial(this).apply {
            isChecked = Store.localApiEnabled(this@SettingsActivity)
        }
        switchRow.addView(sw)
        container.addView(switchRow)

        val status = TextView(this).apply {
            text = if (ApiServer.isRunning()) "状态：运行中" else "状态：已关闭"
            textSize = 13f
            setTextColor(onSurfaceVariant())
            setPadding(0, dp(4), 0, dp(10))
        }
        container.addView(status)

        sw.setOnCheckedChangeListener { _, checked ->
            Store.saveLocalApiEnabled(this@SettingsActivity, checked)
            if (checked) ApiServer.start(this@SettingsActivity) else ApiServer.stop()
            status.text = if (ApiServer.isRunning()) "状态：运行中" else "状态：已关闭"
            refreshButtons()
        }
        sheet.show()
    }

    /* ─────────────── 手机号管理 / 删除的快递 ─────────────── */

    private fun showPhoneDialog() {
        val (sheet, container) = Sheets.create(this, "手机号管理")
        container.addView(
            TextView(this).apply {
                text = "当前绑定：" + Store.xiaomiPhones(this@SettingsActivity).joinToString("、")
                textSize = 13f
                setTextColor(onSurfaceVariant())
                setPadding(0, 2, 0, dp(14))
            }
        )
        val phoneInput = outlinedInput("要绑定的手机号", number = true)
        container.addView(phoneInput)
        val sendBtn = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "发送验证码"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        container.addView(sendBtn)
        val codeInput = outlinedInput("短信验证码", number = true)
        container.addView(
            codeInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )
        val bindBtn = MaterialButton(this).apply {
            text = "确认绑定"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        }
        container.addView(bindBtn)
        sheet.show()

        fun loggedIn(): Boolean = Store.xiaomiToken(this@SettingsActivity).isNotEmpty()
        fun authParams(): Array<String?> = arrayOf(
            Store.xiaomiToken(this@SettingsActivity),
            Store.xiaomiCUser(this@SettingsActivity),
            Store.xiaomiAccountId(this@SettingsActivity),
            Store.xiaomiOaid(this@SettingsActivity),
            Store.xiaomiVaid(this@SettingsActivity)
        )

        sendBtn.setOnClickListener {
            val phone = phoneInput.editText?.text?.toString()?.trim().orEmpty()
            if (phone.isEmpty() || !loggedIn()) {
                Toast.makeText(this, "请先登录小米并输入手机号", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendBtn.isEnabled = false
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val a = authParams()
                    val raw = withContext(Dispatchers.IO) {
                        XiaomiApi.sendVerificationCode(
                            this@SettingsActivity, a[0]!!, a[1]!!, a[2], a[3], a[4], phone
                        )
                    }
                    val code = JSONObject(raw).optInt("code")
                    val msg = if (code == 0) "验证码已发送" else JSONObject(raw).optString("message")
                    Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
                } catch (e: Throwable) {
                    Toast.makeText(this@SettingsActivity, "发送失败：$e", Toast.LENGTH_LONG).show()
                } finally {
                    sendBtn.isEnabled = true
                }
            }
        }

        bindBtn.setOnClickListener {
            val phone = phoneInput.editText?.text?.toString()?.trim().orEmpty()
            val code = codeInput.editText?.text?.toString()?.trim().orEmpty()
            if (phone.isEmpty() || code.isEmpty() || !loggedIn()) {
                Toast.makeText(this, "请填写手机号和验证码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            bindBtn.isEnabled = false
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val a = authParams()
                    val checkRaw = withContext(Dispatchers.IO) {
                        XiaomiApi.checkVerificationCode(
                            this@SettingsActivity, a[0]!!, a[1]!!, a[2], a[3], a[4], phone, code
                        )
                    }
                    if (JSONObject(checkRaw).optInt("code") != 0) {
                        Toast.makeText(this@SettingsActivity, "验证码校验失败", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    val phones = (Store.xiaomiPhones(this@SettingsActivity) + phone).distinct()
                    val bindRaw = withContext(Dispatchers.IO) {
                        XiaomiApi.bindPhone(
                            this@SettingsActivity, a[0]!!, a[1]!!, a[2], a[3], a[4], phone, phones, true
                        )
                    }
                    if (JSONObject(bindRaw).optInt("code") == 0) {
                        Store.saveXiaomiPhones(this@SettingsActivity, phones)
                        Toast.makeText(this@SettingsActivity, "绑定成功", Toast.LENGTH_LONG).show()
                        refreshButtons()
                        sheet.dismiss()
                    } else {
                        Toast.makeText(
                            this@SettingsActivity,
                            "绑定失败：" + JSONObject(bindRaw).optString("message"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (e: Throwable) {
                    Toast.makeText(this@SettingsActivity, "绑定异常：$e", Toast.LENGTH_LONG).show()
                } finally {
                    bindBtn.isEnabled = true
                }
            }
        }
    }

    private fun showHiddenDialog() {
        val hidden = Store.xiaomiHidden(this)
        val (sheet, container) = Sheets.create(this, "删除的快递")
        container.addView(
            TextView(this).apply {
                text = if (hidden.isEmpty()) "暂无删除记录" else "点击恢复，可全部恢复"
                textSize = 13f
                setTextColor(onSurfaceVariant())
                setPadding(0, 2, 0, dp(8))
            }
        )
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(list)

        fun refresh() {
            list.removeAllViews()
            val items = Store.xiaomiHidden(this@SettingsActivity)
            for (item in items) {
                val row = LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    setBackgroundResource(selectableBackground())
                    isClickable = true
                    setOnClickListener {
                        val current = Store.items(this@SettingsActivity).toMutableList()
                        if (current.none { it.mailNo == item.mailNo }) current.add(item)
                        Store.saveItems(this@SettingsActivity, current)
                        Store.restoreHidden(this@SettingsActivity, item.mailNo)
                        refreshButtons()
                        Toast.makeText(this@SettingsActivity, "已恢复 ${item.companyName}", Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                }
                row.addView(
                    TextView(this@SettingsActivity).apply {
                        text = item.companyName
                        textSize = 16f
                        typeface = Typeface.DEFAULT_BOLD
                    }
                )
                row.addView(
                    TextView(this@SettingsActivity).apply {
                        text = item.mailNo
                        textSize = 13f
                        setTextColor(onSurfaceVariant())
                    }
                )
                list.addView(row)
            }
        }
        refresh()

        if (hidden.isNotEmpty()) {
            container.addView(
                MaterialButton(this).apply {
                    text = "全部恢复"
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(10) }
                    setOnClickListener {
                        val current = Store.items(this@SettingsActivity).toMutableList()
                        for (item in Store.xiaomiHidden(this@SettingsActivity)) {
                            if (current.none { it.mailNo == item.mailNo }) current.add(item)
                        }
                        Store.saveItems(this@SettingsActivity, current)
                        Store.clearHidden(this@SettingsActivity)
                        refreshButtons()
                        Toast.makeText(this@SettingsActivity, "已全部恢复", Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                }
            )
        }

        sheet.show()
    }

    /* ─────────────── 通用小工具 ─────────────── */

    private fun sheetNavRow(title: String, subtitle: String, icon: Int, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
            setBackgroundResource(selectableBackground())
            isClickable = true
            isFocusable = true

            addView(
                ImageView(this@SettingsActivity).apply {
                    setImageResource(icon)
                    setColorFilter(MaterialColors.getColor(this@SettingsActivity, android.R.attr.colorPrimary, 0))
                    layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(12) }
                }
            )
            val texts = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(
                TextView(this@SettingsActivity).apply {
                    text = title
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                }
            )
            texts.addView(
                TextView(this@SettingsActivity).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(onSurfaceVariant())
                }
            )
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                TextView(this@SettingsActivity).apply {
                    text = "›"
                    textSize = 22f
                    setTextColor(MaterialColors.getColor(this@SettingsActivity, com.google.android.material.R.attr.colorOutline, 0))
                }
            )
            setOnClickListener { onClick() }
        }

    private fun outlinedInput(
        hint: String,
        text: String = "",
        number: Boolean = false,
        password: Boolean = false
    ): TextInputLayout {
        val layout = layoutInflater.inflate(R.layout.view_input_outlined, null) as TextInputLayout
        layout.hint = hint
        layout.editText?.apply {
            setText(text)
            setSingleLine(true)
            if (number) inputType = android.text.InputType.TYPE_CLASS_NUMBER
            if (password) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        return layout
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun onSurfaceVariant(): Int =
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)

    private fun selectableBackground(): Int {
        val typed = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, typed, true)
        return typed.resourceId
    }

    companion object {
        private const val REQUEST_LOCATION = 4001
    }
}
