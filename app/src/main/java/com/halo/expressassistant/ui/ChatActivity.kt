package com.halo.expressassistant.ui

import android.app.AlarmManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.halo.expressassistant.R
import com.halo.expressassistant.ReportScheduler
import com.halo.expressassistant.ai.AiClient
import com.halo.expressassistant.ai.Markdown
import com.halo.expressassistant.api.XiaomiDetail
import com.halo.expressassistant.api.XiaomiSync
import com.halo.expressassistant.data.ChatMessage
import com.halo.expressassistant.data.ExpressItem
import com.halo.expressassistant.data.ReportSchedule
import com.halo.expressassistant.data.Store
import com.halo.expressassistant.databinding.ActivityChatBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.apply(this, binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.inputBar) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val base = max(ime, nav)
            val lp = v.layoutParams as ViewGroup.MarginLayoutParams
            lp.bottomMargin = base + dp(10)
            v.layoutParams = lp
            val lpPreset = binding.btnPreset.layoutParams as ViewGroup.MarginLayoutParams
            lpPreset.bottomMargin = base + dp(96)
            binding.btnPreset.layoutParams = lpPreset
            insets
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnInfo.setOnClickListener { showIntroDialog() }
        binding.btnSchedule.setOnClickListener { showScheduleDialog() }
        binding.btnClear.setOnClickListener { confirmClear() }
        binding.btnPreset.setOnClickListener { togglePresetPopup() }
        binding.scroll.post {
            val extra = dp(220)
            val viewport = binding.scroll.height
            val pad = max(extra, viewport - binding.messages.height + extra)
            binding.messages.setPadding(
                binding.messages.paddingLeft,
                binding.messages.paddingTop,
                binding.messages.paddingRight,
                pad
            )
        }

        renderHistory()

        binding.send.setOnClickListener {
            val question = binding.input.text?.toString().orEmpty().trim()
            if (question.isEmpty()) return@setOnClickListener
            sendQuestion(question)
        }

        if (intent?.getBooleanExtra("open_schedule", false) == true) {
            binding.toolbar.post { showScheduleDialog() }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun onSurfaceVariant(): Int =
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)

    private fun surfaceLow(): Int =
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerLow, 0)

    private fun surfaceHigh(): Int =
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHigh, 0)

    private fun primary(): Int =
        MaterialColors.getColor(this, android.R.attr.colorPrimary, 0)

    private fun onPrimary(): Int =
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary, 0)

    private fun renderHistory() {
        binding.messages.removeAllViews()
        val history = Store.chatHistory(this)
        if (history.isEmpty()) {
            addWelcome()
        } else {
            for (msg in history) addMessage(msg.role, msg.content)
        }
        scrollToBottom()
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle("清空对话")
            .setMessage("将删除所有对话记录")
            .setPositiveButton("继续") { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("确认清空")
                    .setMessage("删除后无法恢复，确定要清空吗？")
                    .setPositiveButton("清空") { _, _ ->
                        Store.clearChatHistory(this)
                        renderHistory()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun sendQuestion(question: String) {
        val history = Store.chatHistory(this).toMutableList()
        history.add(ChatMessage("user", question))
        Store.saveChatHistory(this, history)
        binding.input.text?.clear()
        binding.messages.removeAllViews()
        for (msg in history) addMessage(msg.role, msg.content)
        val thinking = addThinking()
        scrollToBottom()

        scope.launch {
            val answer = try {
                AiClient.askWithTools(
                    this@ChatActivity,
                    Store.items(this@ChatActivity),
                    question,
                    aiTools()
                ) { name, args -> executeTool(name, args) }
            } catch (e: Throwable) {
                "出错：$e"
            }
            binding.messages.removeView(thinking)
            val updated = (Store.chatHistory(this@ChatActivity) + ChatMessage("assistant", answer))
            Store.saveChatHistory(this@ChatActivity, updated)
            addMessage("assistant", answer)
            scrollToBottom()
        }
    }

    private fun scrollToBottom() {
        binding.scroll.post { binding.scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private var presetPopup: PopupWindow? = null

    private fun togglePresetPopup() {
        val existing = presetPopup
        if (existing?.isShowing == true) {
            existing.dismiss()
            presetPopup = null
            return
        }
        val content = layoutInflater.inflate(R.layout.popup_presets, null)
        val buttons = listOf(
            content.findViewById<com.google.android.material.button.MaterialButton>(R.id.preset_1),
            content.findViewById<com.google.android.material.button.MaterialButton>(R.id.preset_2),
            content.findViewById<com.google.android.material.button.MaterialButton>(R.id.preset_3),
            content.findViewById<com.google.android.material.button.MaterialButton>(R.id.preset_4)
        )
        val popup = PopupWindow(content, dp(240), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(8).toFloat()
            setOnDismissListener { presetPopup = null }
        }
        for (btn in buttons) {
            btn.setOnClickListener {
                val question = btn.text?.toString()?.trim().orEmpty()
                if (question.isNotEmpty()) sendQuestion(question)
                popup.dismiss()
            }
        }
        presetPopup = popup
        content.measure(
            View.MeasureSpec.makeMeasureSpec(dp(240), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        popup.showAsDropDown(
            binding.btnPreset,
            -(dp(240) - binding.btnPreset.width),
            -(content.measuredHeight + binding.btnPreset.height)
        )
    }

    private fun addWelcome() {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(48), 0, dp(24))
        }
        wrap.addView(
            TextView(this).apply {
                text = "云雀"
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
        )
        wrap.addView(
            TextView(this).apply {
                text = "你的私人快递仓管\n问点什么，或点上面的预制问题试试"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(onSurfaceVariant())
                setPadding(0, dp(6), 0, 0)
            }
        )
        binding.messages.addView(wrap)
    }

    private fun addThinking(): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(0, dp(8), 0, dp(2))
        }
        val card = bubbleCard(false)
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inner.addView(
            TextView(this).apply {
                text = "思考中…"
                textSize = 14f
                setTextColor(onSurfaceVariant())
            }
        )
        card.addView(inner)
        wrap.addView(card)
        binding.messages.addView(wrap)
        return wrap
    }

    private fun addMessage(role: String, content: String) {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (role == "user") Gravity.END else Gravity.START
            setPadding(0, dp(8), 0, dp(2))
        }
        val card = bubbleCard(role == "user")
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (role == "user") {
            inner.addView(
                TextView(this).apply {
                    text = content
                    textSize = 15f
                    setTextColor(
                        MaterialColors.getColor(
                            this@ChatActivity,
                            com.google.android.material.R.attr.colorOnPrimaryContainer,
                            0
                        )
                    )
                }
            )
        } else {
            renderAiContent(inner, content)
        }
        card.addView(inner)
        wrap.addView(card)
        binding.messages.addView(wrap)
    }

    private fun bubbleCard(user: Boolean): MaterialCardView = MaterialCardView(this).apply {
        radius = dp(18).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(
            if (user) {
                MaterialColors.getColor(this@ChatActivity, com.google.android.material.R.attr.colorPrimaryContainer, 0)
            } else {
                surfaceLow()
            }
        )
        setContentPadding(dp(14), dp(10), dp(14), dp(10))
    }

    private fun renderAiContent(container: ViewGroup, content: String) {
        val pattern = Regex("\\[\\[card:([^\\]]+)\\]\\]")
        var last = 0
        for (m in pattern.findAll(content)) {
            addParagraphs(container, content.substring(last, m.range.first))
            val item = Store.items(this).firstOrNull { it.mailNo == m.groupValues[1].trim() }
            if (item != null) container.addView(expressCard(item))
            last = m.range.last + 1
        }
        addParagraphs(container, content.substring(last))
    }

    private fun addParagraphs(container: ViewGroup, text: String) {
        for (p in text.split(Regex("\\n\\s*\\n"))) {
            val t = p.trim()
            if (t.isEmpty()) continue
            val tv = markdownText(t)
            (tv.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(3)
            container.addView(tv)
        }
    }

    private fun markdownText(content: String): TextView = TextView(this).apply {
        text = Markdown.render(this@ChatActivity, content)
        textSize = 15f
        setTextColor(MaterialColors.getColor(this@ChatActivity, com.google.android.material.R.attr.colorOnSurface, 0))
        setLineSpacing(dp(3).toFloat(), 1.3f)
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun expressCard(item: ExpressItem): View {
        val card = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(surfaceHigh())
            isClickable = true
            setOnClickListener {
                startActivity(
                    Intent(this@ChatActivity, DetailActivity::class.java)
                        .putExtra("item", Store.json.encodeToString(item))
                )
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(
            TextView(this).apply {
                text = item.companyName
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
            }
        )
        texts.addView(
            TextView(this).apply {
                text = item.mailNo
                textSize = 12f
                setTextColor(onSurfaceVariant())
            }
        )
        val etaText = item.eta.ifBlank { item.aiEta }
        val state = item.stateLabel() + if (etaText.isNotBlank()) " · 预计 $etaText" else ""
        texts.addView(
            TextView(this).apply {
                text = state
                textSize = 13f
                setTextColor(primary())
            }
        )
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(
            TextView(this).apply {
                text = "查看 ›"
                textSize = 13f
                setTextColor(primary())
            }
        )
        card.addView(row)
        return card
    }

    private fun aiTools(): List<JSONObject> {
        fun tool(name: String, description: String, properties: JSONObject, required: JSONArray = JSONArray()): JSONObject =
            JSONObject()
                .put("type", "function")
                .put("function", JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("parameters", JSONObject()
                        .put("type", "object")
                        .put("properties", properties)
                        .put("required", required)))

        return listOf(
            tool(
                "list_packages",
                "列出所有快递的精简信息",
                JSONObject()
            ),
            tool(
                "package_detail",
                "查询某件快递的完整物流轨迹",
                JSONObject().put("mailNo", JSONObject().put("type", "string")),
                JSONArray().put("mailNo")
            ),
            tool(
                "rename_package",
                "修改某件快递的显示名称",
                JSONObject()
                    .put("mailNo", JSONObject().put("type", "string"))
                    .put("name", JSONObject().put("type", "string")),
                JSONArray().put("mailNo").put("name")
            ),
            tool(
                "set_package_status",
                "修改某件快递的状态文字（如：已签收、派送中、异常）",
                JSONObject()
                    .put("mailNo", JSONObject().put("type", "string"))
                    .put("status", JSONObject().put("type", "string")),
                JSONArray().put("mailNo").put("status")
            ),
            tool(
                "set_package_section",
                "把某件快递移动到分区，可选：派送中、已发货、未发货、完成、异常",
                JSONObject()
                    .put("mailNo", JSONObject().put("type", "string"))
                    .put("section", JSONObject().put("type", "string")),
                JSONArray().put("mailNo").put("section")
            ),
            tool(
                "set_package_track",
                "开启或关闭某件快递的跟踪通知",
                JSONObject()
                    .put("mailNo", JSONObject().put("type", "string"))
                    .put("tracked", JSONObject().put("type", "boolean")),
                JSONArray().put("mailNo").put("tracked")
            ),
            tool(
                "sync_packages",
                "触发一次小米同步刷新快递列表",
                JSONObject()
            )
        )
    }

    private fun executeTool(name: String, args: JSONObject): String {
        val items = Store.items(this)
        return when (name) {
            "list_packages" -> JSONArray().apply {
                for (it in items) put(
                    JSONObject()
                        .put("mailNo", it.mailNo)
                        .put("companyName", it.companyName)
                        .put("state", it.stateLabel())
                        .put("eta", it.eta)
                        .put("tracked", it.tracked)
                )
            }.toString()

            "package_detail" -> {
                val item = items.firstOrNull { it.mailNo == args.optString("mailNo") }
                    ?: return JSONObject().put("error", "未找到该快递").toString()
                val detail = runBlocking { XiaomiDetail.fetch(this@ChatActivity, item) }
                JSONObject()
                    .put("mailNo", detail.mailNo)
                    .put("companyName", detail.companyName)
                    .put("state", detail.state)
                    .put("eta", item.eta)
                    .put("points", JSONArray().apply {
                        for (p in detail.data) put(JSONObject().put("time", p.time).put("context", p.context))
                    })
                    .toString()
            }

            "rename_package" -> {
                val mailNo = args.optString("mailNo")
                val name = args.optString("name").trim()
                if (name.isEmpty()) return JSONObject().put("error", "名称不能为空").toString()
                Store.saveItems(
                    this,
                    items.map { if (it.mailNo == mailNo) it.copy(companyName = name) else it }
                )
                JSONObject().put("ok", true).put("mailNo", mailNo).put("name", name).toString()
            }

            "set_package_status" -> {
                val mailNo = args.optString("mailNo")
                val status = args.optString("status").trim()
                if (status.isEmpty()) return JSONObject().put("error", "状态不能为空").toString()
                Store.saveItems(
                    this,
                    items.map { if (it.mailNo == mailNo) it.copy(stateOverride = status) else it }
                )
                JSONObject().put("ok", true).put("mailNo", mailNo).put("status", status).toString()
            }

            "set_package_section" -> {
                val mailNo = args.optString("mailNo")
                val key = when (args.optString("section")) {
                    "派送中" -> "delivering"
                    "已发货" -> "shipped"
                    "未发货" -> "notshipped"
                    "完成" -> "done"
                    "异常" -> "abnormal"
                    else -> return JSONObject().put("error", "分区只能是：派送中、已发货、未发货、完成、异常").toString()
                }
                Store.saveItems(
                    this,
                    items.map { if (it.mailNo == mailNo) it.copy(partitionOverride = key) else it }
                )
                JSONObject().put("ok", true).put("mailNo", mailNo).put("section", key).toString()
            }

            "set_package_track" -> {
                val mailNo = args.optString("mailNo")
                val tracked = args.optBoolean("tracked")
                Store.saveItems(
                    this,
                    items.map {
                        if (it.mailNo == mailNo) {
                            if (tracked) it.copy(tracked = true, notifiedText = it.latestText, notifiedTime = it.latestTime)
                            else it.copy(tracked = false)
                        } else it
                    }
                )
                JSONObject().put("ok", true).put("mailNo", mailNo).put("tracked", tracked).toString()
            }

            "sync_packages" -> JSONObject().put("ok", true).put("message", runBlocking { XiaomiSync.sync(this@ChatActivity) }).toString()

            else -> JSONObject().put("error", "未知工具 $name").toString()
        }
    }

    private fun showIntroDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        content.addView(
            TextView(this).apply {
                text = "为你提供 快递早报·私人仓管 服务"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(MaterialColors.getColor(this@ChatActivity, com.google.android.material.R.attr.colorOnSurface, 0))
                setPadding(0, dp(6), 0, dp(12))
            }
        )
        content.addView(
            introMarkdown(
                "在英语文化中，“lark”是清晨的经典象征。俗话说“as happy as a lark”（像云雀一样快乐），" +
                    "云雀在天亮前就开始高歌，因此被称为“**黎明的信使**”。用它来命名一份早报，寓意“一日之晨的新闻信使”。" +
                "这只“云雀”（快递员）在各地“驿站”（新闻采集点）之间穿梭，最终将消息汇集为一份“早报”。"
            ).apply {
                setPadding(0, dp(16), 0, 0)
            }
        )
        content.addView(
            TextView(this).apply {
                text = "我能做到"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(MaterialColors.getColor(this@ChatActivity, com.google.android.material.R.attr.colorOnSurface, 0))
                setPadding(0, dp(22), 0, dp(6))
            }
        )
        content.addView(
            introMarkdown(
                "- 汇总快递、生成早报与日报\n" +
                    "- 直接修改快递名称、状态和所属分区\n" +
                    "- 开关跟踪通知、触发同步\n" +
                    "- 在回答里直接贴出快递卡片\n" +
                    "- 结合轨迹预测更精准的运输进度与预计送达"
            )
        )
        val scroll = ScrollView(this)
        scroll.addView(content)
        MaterialAlertDialogBuilder(this)
            .setTitle("云雀")
            .setView(scroll)
            .setPositiveButton("好的", null)
            .show()
    }

    private fun introMarkdown(content: String): TextView = TextView(this).apply {
        text = Markdown.render(this@ChatActivity, content)
        textSize = 15f
        setTextColor(MaterialColors.getColor(this@ChatActivity, com.google.android.material.R.attr.colorOnSurface, 0))
        setLineSpacing(dp(3).toFloat(), 1.25f)
    }

    private fun sheetHeader(title: String, subtitle: String, sheet: BottomSheetDialog): LinearLayout {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(
            TextView(this).apply {
                text = title
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
            }
        )
        texts.addView(
            TextView(this).apply {
                text = subtitle
                textSize = 13f
                setTextColor(onSurfaceVariant())
            }
        )
        header.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(
            ImageButton(this).apply {
                setImageResource(R.drawable.ic_close)
                setBackgroundResource(borderlessBackground())
                contentDescription = "关闭"
                setOnClickListener { sheet.dismiss() }
            }
        )
        return header
    }

    private fun borderlessBackground(): Int {
        val typed = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, typed, true)
        return typed.resourceId
    }

    private fun showScheduleDialog() {
        val sheet = BottomSheetDialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), dp(20))
        }
        content.addView(sheetHeader("定时日报", "到点后 AI 自动生成并推送", sheet))

        if (Build.VERSION.SDK_INT >= 31) {
            val am = getSystemService(AlarmManager::class.java)
            if (!am.canScheduleExactAlarms()) {
                content.addView(
                    TextView(this).apply {
                        text = "系统未允许精确闹钟，日报可能延迟几分钟"
                        textSize = 12f
                        setTextColor(onSurfaceVariant())
                    }
                )
                content.addView(
                    TextView(this).apply {
                        text = "去开启精确闹钟 ›"
                        textSize = 13f
                        setTextColor(primary())
                        setPadding(0, 2, 0, 8)
                        isClickable = true
                        setOnClickListener {
                            try {
                                startActivity(
                                    Intent(
                                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                        Uri.parse("package:$packageName")
                                    )
                                )
                            } catch (e: Throwable) {
                                Toast.makeText(this@ChatActivity, "无法打开系统设置", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }
        }

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun refresh() {
            list.removeAllViews()
            val schedules = Store.reportSchedules(this@ChatActivity)
            if (schedules.isEmpty()) {
                list.addView(
                    TextView(this@ChatActivity).apply {
                        text = "还没有定时，点下方添加"
                        textSize = 14f
                        setTextColor(onSurfaceVariant())
                        setPadding(0, dp(14), 0, dp(10))
                    }
                )
            }
            for (schedule in schedules) {
                val card = buildScheduleRow(schedule) { refresh() }
                list.addView(
                    card,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(10) }
                )
            }
        }
        refresh()

        content.addView(list)
        content.addView(
            com.google.android.material.button.MaterialButton(this).apply {
                text = "添加定时"
                setIconResource(R.drawable.ic_schedule)
                setOnClickListener { showEditDialog(null) { refresh() } }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        )

        sheet.setContentView(content)
        sheet.show()
    }

    private fun buildScheduleRow(schedule: ReportSchedule, onChanged: () -> Unit): View {
        val card = MaterialCardView(this).apply {
            setCardBackgroundColor(surfaceHigh())
            radius = dp(18).toFloat()
            cardElevation = 0f
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(8), dp(8))
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setOnClickListener { showEditDialog(schedule, onChanged) }
        }
        left.addView(
            TextView(this).apply {
                text = String.format("%02d:%02d", schedule.hour, schedule.minute)
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
            }
        )
        left.addView(
            TextView(this).apply {
                text = repeatLabel(schedule) + if (schedule.label.isNotBlank()) " · ${schedule.label}" else ""
                textSize = 13f
                setTextColor(onSurfaceVariant())
            }
        )
        row.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(
            SwitchMaterial(this).apply {
                isChecked = schedule.enabled
                setOnCheckedChangeListener { _, checked ->
                    val old = Store.reportSchedules(this@ChatActivity)
                    val new = old.map { if (it.id == schedule.id) it.copy(enabled = checked) else it }
                    ReportScheduler.reschedule(this@ChatActivity, old, new)
                    onChanged()
                }
            }
        )
        row.addView(
            ImageButton(this).apply {
                setImageResource(R.drawable.ic_delete)
                setBackgroundResource(borderlessBackground())
                contentDescription = "删除"
                setOnClickListener {
                    val old = Store.reportSchedules(this@ChatActivity)
                    ReportScheduler.reschedule(
                        this@ChatActivity,
                        old,
                        old.filterNot { it.id == schedule.id }
                    )
                    onChanged()
                }
            }
        )
        card.addView(row)
        if (!schedule.enabled) card.alpha = 0.5f
        return card
    }

    private fun repeatLabel(schedule: ReportSchedule): String = when (schedule.repeat) {
        ReportSchedule.REPEAT_ONCE -> "仅一次"
        ReportSchedule.REPEAT_DAILY -> "每天"
        ReportSchedule.REPEAT_WEEKDAYS -> "工作日"
        ReportSchedule.REPEAT_WEEKENDS -> "周末"
        ReportSchedule.REPEAT_CUSTOM -> {
            val names = listOf("一", "二", "三", "四", "五", "六", "日")
            val days = (0..6).filter { schedule.weekdays and (1 shl it) != 0 }
            if (days.isEmpty()) "自定义" else "周" + days.joinToString("、") { names[it] }
        }
        else -> "每天"
    }

    private fun styledChip(text: String, checked: Boolean, compact: Boolean = false): Chip = Chip(this).apply {
        this.text = text
        textSize = if (compact) 11f else 13f
        isCheckable = true
        isChecked = checked
        chipBackgroundColor = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(primary(), surfaceLow())
        )
        setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(onPrimary(), onSurfaceVariant())
            )
        )
        chipStrokeWidth = 0f
        chipMinHeight = if (compact) dp(30).toFloat() else dp(36).toFloat()
        chipStartPadding = if (compact) dp(3).toFloat() else dp(6).toFloat()
        chipEndPadding = if (compact) dp(3).toFloat() else dp(6).toFloat()
    }

    private fun showEditDialog(existing: ReportSchedule?, onSaved: () -> Unit) {
        val sheet = BottomSheetDialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), dp(20))
        }
        content.addView(
            sheetHeader(
                if (existing == null) "添加定时" else "编辑定时",
                "选择时间、重复规则和备注",
                sheet
            )
        )

        var hour = existing?.hour ?: Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        var minute = existing?.minute ?: Calendar.getInstance().get(Calendar.MINUTE)
        var repeat = existing?.repeat ?: ReportSchedule.REPEAT_DAILY
        var weekdays = existing?.weekdays?.takeIf { it != 0 } ?: ReportSchedule.MASK_WEEKDAYS

        val labelLayout = layoutInflater.inflate(R.layout.view_input_outlined, null) as TextInputLayout
        labelLayout.hint = "备注（可选）"
        val labelInput = labelLayout.editText!!.apply {
            setText(existing?.label ?: "")
            setSingleLine(true)
        }
        content.addView(
            labelLayout,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )

        val timeCard = MaterialCardView(this).apply {
            setCardBackgroundColor(surfaceLow())
            radius = dp(18).toFloat()
            cardElevation = 0f
            isClickable = true
        }
        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        timeRow.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_schedule)
                setColorFilter(primary())
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
            }
        )
        val timeCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        timeCol.addView(
            TextView(this).apply {
                text = "时间"
                textSize = 13f
                setTextColor(onSurfaceVariant())
            }
        )
        val timeText = TextView(this).apply {
            text = String.format("%02d:%02d", hour, minute)
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
        }
        timeCol.addView(timeText)
        timeRow.addView(
            timeCol,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            }
        )
        timeRow.addView(
            TextView(this).apply {
                text = "修改 ›"
                textSize = 14f
                setTextColor(primary())
            }
        )
        timeCard.addView(timeRow)
        timeCard.setOnClickListener {
            showTimePicker(hour, minute) { h, m ->
                hour = h
                minute = m
                timeText.text = String.format("%02d:%02d", hour, minute)
            }
        }
        content.addView(
            timeCard,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        )

        content.addView(
            TextView(this).apply {
                text = "重复"
                textSize = 16f
                setPadding(0, dp(16), 0, dp(6))
            }
        )
        val repeatGroup = ChipGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
            chipSpacingHorizontal = dp(4)
        }
        val options = listOf(
            ReportSchedule.REPEAT_ONCE to "仅一次",
            ReportSchedule.REPEAT_DAILY to "每天",
            ReportSchedule.REPEAT_WEEKDAYS to "工作日",
            ReportSchedule.REPEAT_WEEKENDS to "周末",
            ReportSchedule.REPEAT_CUSTOM to "自定义"
        )
        for ((value, label) in options) {
            repeatGroup.addView(
                styledChip(label, value == repeat).apply { tag = value }
            )
        }
        content.addView(repeatGroup)

        val weekGroup = ChipGroup(this).apply {
            isSingleSelection = false
            chipSpacingHorizontal = dp(2)
        }
        val dayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        for (i in 0..6) {
            weekGroup.addView(
                styledChip(dayNames[i], weekdays and (1 shl i) != 0, compact = true)
            )
        }
        weekGroup.visibility = if (repeat == ReportSchedule.REPEAT_CUSTOM) View.VISIBLE else View.GONE
        content.addView(weekGroup)

        repeatGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            repeat = (repeatGroup.findViewById<Chip>(checkedId).tag as? Int) ?: repeat
            weekGroup.visibility = if (repeat == ReportSchedule.REPEAT_CUSTOM) View.VISIBLE else View.GONE
        }
        weekGroup.setOnCheckedStateChangeListener { _, _ ->
            weekdays = 0
            for (i in 0..6) {
                if ((weekGroup.getChildAt(i) as Chip).isChecked) {
                    weekdays = weekdays or (1 shl i)
                }
            }
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(18), 0, 0)
        }
        if (existing != null) {
            val delete = com.google.android.material.button.MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = "删除"
                setIconResource(R.drawable.ic_delete)
                setTextColor(Color.rgb(179, 38, 30))
                setOnClickListener {
                    val old = Store.reportSchedules(this@ChatActivity)
                    ReportScheduler.reschedule(this@ChatActivity, old, old.filterNot { it.id == existing.id })
                    sheet.dismiss()
                    onSaved()
                }
            }
            buttons.addView(
                delete,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(10)
                }
            )
        }
        val save = com.google.android.material.button.MaterialButton(this).apply {
            text = "保存"
            setIconResource(R.drawable.ic_checklist)
            setOnClickListener {
                val label = labelInput.text?.toString()?.trim().orEmpty()
                if (repeat == ReportSchedule.REPEAT_CUSTOM && weekdays == 0) {
                    Toast.makeText(this@ChatActivity, "请至少选择一天", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val schedule = ReportSchedule(
                    existing?.id ?: System.currentTimeMillis(),
                    hour,
                    minute,
                    label,
                    true,
                    repeat,
                    weekdays
                )
                val old = Store.reportSchedules(this@ChatActivity)
                val new = if (existing == null) {
                    old + schedule
                } else {
                    old.map { if (it.id == existing.id) schedule else it }
                }
                ReportScheduler.reschedule(this@ChatActivity, old, new)
                sheet.dismiss()
                onSaved()
            }
        }
        buttons.addView(save, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(buttons)

        sheet.setContentView(content)
        sheet.show()
    }

    private fun showTimePicker(hour: Int, minute: Int, onSet: (Int, Int) -> Unit) {
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(hour)
            .setMinute(minute)
            .setTitleText("选择时间")
            .build()
        picker.addOnPositiveButtonClickListener {
            onSet(picker.hour, picker.minute)
        }
        picker.show(supportFragmentManager, "report_time_picker")
    }
}
