package com.halo.expressassistant.ui

import android.app.AlarmManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
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
import com.halo.expressassistant.data.ChatMessage
import com.halo.expressassistant.data.ReportSchedule
import com.halo.expressassistant.data.Store
import com.halo.expressassistant.databinding.ActivityChatBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val lp = v.layoutParams as ViewGroup.MarginLayoutParams
            lp.bottomMargin = nav + dp(10)
            v.layoutParams = lp
            insets
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnSchedule.setOnClickListener { showScheduleDialog() }
        binding.btnClear.setOnClickListener { confirmClear() }

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

    private fun formatHistory(history: List<ChatMessage>): String {
        if (history.isEmpty()) return "AI 可以读取你本地保存的快递数据。发送问题试试。"
        return history.joinToString("\n\n") { msg ->
            if (msg.role == "user") "你：${msg.content}" else "AI：${msg.content}"
        }
    }

    private fun renderHistory() {
        binding.output.text = Markdown.render(this, formatHistory(Store.chatHistory(this)))
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
        binding.output.text = Markdown.render(this, formatHistory(history) + "\n\n*思考中…*")

        scope.launch {
            val answer = AiClient.ask(this@ChatActivity, Store.items(this@ChatActivity), question)
            val updated = (Store.chatHistory(this@ChatActivity) + ChatMessage("assistant", answer))
            Store.saveChatHistory(this@ChatActivity, updated)
            binding.output.text = Markdown.render(this@ChatActivity, formatHistory(updated))
        }
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

        val labelLayout = TextInputLayout(this).apply {
            hint = "备注（可选）"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val labelInput = EditText(this).apply {
            setText(existing?.label ?: "")
            setSingleLine(true)
        }
        labelLayout.addView(labelInput)
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
