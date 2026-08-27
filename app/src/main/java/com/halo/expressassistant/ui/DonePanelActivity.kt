package com.halo.expressassistant.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.Gravity
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import com.halo.expressassistant.data.ExpressItem
import com.halo.expressassistant.data.Store
import com.halo.expressassistant.databinding.ActivityDonePanelBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.util.Calendar

/**
 * 已完成/异常 二级面板（右下角「已完成」胶囊进入）：
 * 顶部：搜索 / 日历 / 异常包裹（内外隔离：只作用于完成+异常集合）
 * 中间：完成列表（可切到异常包裹）
 * 底部：悬浮分页条（每页 4 条 + 点击页数弹跳页菜单），与首页翻页功能一致
 */
class DonePanelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDonePanelBinding
    private lateinit var adapter: ExpressAdapter

    private companion object {
        const val MODE_DONE = 1
        const val MODE_ABNORMAL = 2
    }

    private var mode = MODE_DONE
    private var windowPage = 0
    /** 单页自适应：按列表可用高度与卡片实测高度计算单屏可同时显示的最大件数（≥4） */
    private var pageSize = 4

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun onSurfaceVariant(): Int =
        com.google.android.material.color.MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityDonePanelBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ExpressAdapter(
            onClick = { item ->
                startActivity(Intent(this, DetailActivity::class.java).putExtra("item", Store.json.encodeToString(item)))
            },
            onLongClick = {}
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnPanelAbnormal.setOnClickListener { switchMode(MODE_ABNORMAL) }
        binding.btnPanelSearch.setOnClickListener { showPanelSearch() }
        binding.btnPanelCalendar.setOnClickListener { showPanelCalendar() }
        binding.btnPagePrev.setOnClickListener { windowPage = (windowPage - 1).coerceAtLeast(0); applyPaging() }
        binding.btnPageNext.setOnClickListener { windowPage++; applyPaging() }
        binding.pageInfo.setOnClickListener { showPageJumpDialog() }

        // 首帧后按实际卡片高度自适应「单页件数」（手机支持的同一屏最高同时显示量）
        binding.list.post {
            try {
                val probe = com.halo.expressassistant.databinding.ItemExpressBinding.inflate(layoutInflater)
                // 与面板卡片视觉一致：轨迹区三条（分隔线/最新/时间）都是 GONE
                probe.latest.visibility = View.GONE
                probe.time.visibility = View.GONE
                probe.traceDivider.visibility = View.GONE
                val wSpec = android.view.View.MeasureSpec.makeMeasureSpec(binding.list.width, android.view.View.MeasureSpec.EXACTLY)
                val hSpec = android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
                probe.root.measure(wSpec, hSpec)
                val cardH = probe.root.measuredHeight
                if (cardH > 0 && binding.list.height > 0) {
                    // 底部悬浮分页条约占 56dp：可用高 = 列表高 - 该留白 - 少量余量；
                    // 自适应张数再减 1——个别机型最后一卡会被悬浮条/手势条吃掉一截
                    val usable = binding.list.height - dp(56) - dp(8)
                    val cap = ((usable / cardH).toInt() - 1).coerceIn(3, 8)
                    if (cap != pageSize) {
                        pageSize = cap
                        windowPage = 0
                        applyPaging()
                    }
                }
            } catch (e: Throwable) {
                Log.w("DonePanel", "adapt pageSize fail: $e")
            }
        }

        switchMode(MODE_DONE)
    }

    override fun onResume() {
        super.onResume()
        // 每次回来（详情/新批次到达后）刷新数据
        adapter.submit(Store.items(this))
        windowPage = 0
        applyPaging()
    }

    private fun itemsForMode(m: Int): List<ExpressItem> = Store.items(this).filter {
        when (m) {
            MODE_ABNORMAL -> it.state == 4 || it.stateNum in setOf(108, 109, 110, 111) || it.partitionOverride == "abnormal"
            else -> it.state == 3 || it.stateNum in setOf(106, 107) || it.partitionOverride == "done"
        }
    }

    private fun switchMode(m: Int) {
        mode = m
        windowPage = 0
        val set = itemsForMode(m)
        // 包裹总数跟在标题后面：已完成 · N 件 / 异常包裹 · N 件
        binding.toolbar.title = (if (m == MODE_ABNORMAL) "异常包裹" else "已完成") + " · ${set.size} 件"
        binding.btnPanelAbnormal.isVisible = m == MODE_DONE
        adapter.submit(Store.items(this))
        applyPaging()
    }

    /** 分页：单页自适应件数（pageSize），底部留白防遮挡 */
    private fun applyPaging() {
        val set = itemsForMode(mode)
        if (set.size > pageSize) {
            val pages = (set.size + pageSize - 1) / pageSize
            if (windowPage >= pages) windowPage = pages - 1
            if (windowPage < 0) windowPage = 0
            adapter.setPage(mode)
            adapter.setWindow(windowPage * pageSize, pageSize)
            binding.pagerBar.visibility = View.VISIBLE
            val full = "第 ${windowPage + 1}/$pages 页 · 共 ${set.size} 件"
            val seg = "${windowPage + 1}/$pages"
            val sp = android.text.SpannableString(full)
            val idx = full.indexOf(seg)
            if (idx >= 0) sp.setSpan(android.text.style.UnderlineSpan(), idx, idx + seg.length, 0)
            binding.pageInfo.text = sp
            binding.btnPagePrev.isEnabled = windowPage > 0
            binding.btnPageNext.isEnabled = windowPage < pages - 1
            binding.list.setPadding(binding.list.paddingLeft, binding.list.paddingTop, binding.list.paddingRight, dp(56))
        } else {
            if (set.isEmpty()) {
                adapter.setPage(mode)
                adapter.clearWindow()
                binding.pagerBar.visibility = View.GONE
                binding.list.setPadding(binding.list.paddingLeft, binding.list.paddingTop, binding.list.paddingRight, dp(8))
                // 空态
                adapter.setWindow(0, 0)
            } else {
                adapter.setPage(mode)
                adapter.clearWindow()
                binding.pagerBar.visibility = View.GONE
                binding.list.setPadding(binding.list.paddingLeft, binding.list.paddingTop, binding.list.paddingRight, dp(8))
            }
        }
    }

    /** 点击页数 → 底部跳页菜单（与其他二级菜单统一） */
    private fun showPageJumpDialog() {
        val set = itemsForMode(mode)
        if (set.size <= pageSize) return
        val pages = (set.size + pageSize - 1) / pageSize
        val (sheet, container) = Sheets.create(this, "跳转到第几页")
        container.addView(
            TextView(this).apply {
                text = "当前共 $pages 页，输入要跳转的页数"
                textSize = 13f
                setTextColor(onSurfaceVariant())
                setPadding(0, dp(2), 0, dp(12))
            }
        )
        val input = layoutInflater.inflate(com.halo.expressassistant.R.layout.view_input_outlined, null) as TextInputLayout
        input.hint = "1 - $pages"
        input.editText?.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        container.addView(input)
        container.addView(
            MaterialButton(this).apply {
                text = "跳转"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(14) }
                setOnClickListener {
                    val n = input.editText?.text?.toString()?.trim()?.toIntOrNull()
                    sheet.dismiss()
                    if (n != null && n in 1..pages) {
                        windowPage = n - 1
                        applyPaging()
                    }
                }
            }
        )
        container.post { input.editText?.requestFocus() }
        sheet.show()
    }

    /** 面板内搜索：只搜当前模式（完成/异常）集合——内外隔离 */
    private fun showPanelSearch() {
        val (sheet, container) = Sheets.create(this, if (mode == MODE_ABNORMAL) "搜索异常包裹" else "搜索已完成")
        val input = layoutInflater.inflate(com.halo.expressassistant.R.layout.view_input_outlined, null) as TextInputLayout
        input.hint = "单号 / 快递公司 / 商品名 / 状态"
        container.addView(input)
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(results)

        fun row(item: ExpressItem): View {
            val r = LinearLayout(this@DonePanelActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                setBackgroundResource(selectableBackground())
                isClickable = true
                setOnClickListener {
                    sheet.dismiss()
                    startActivity(Intent(this@DonePanelActivity, DetailActivity::class.java)
                        .putExtra("item", Store.json.encodeToString(item)))
                }
            }
            val goods = Store.jdGoods(this@DonePanelActivity)[item.mailNo]
            r.addView(TextView(this@DonePanelActivity).apply {
                text = goods?.name?.takeIf { it.isNotBlank() } ?: item.companyName
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(com.google.android.material.color.MaterialColors.getColor(
                    this@DonePanelActivity, android.R.attr.textColorPrimary, 0))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            r.addView(TextView(this@DonePanelActivity).apply {
                text = "${item.companyName} ${item.mailNo} · ${item.stateName}" +
                    if (item.accountLabel.isNotBlank()) " · ${item.accountLabel}" else ""
                textSize = 12f
                setTextColor(onSurfaceVariant())
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            return r
        }

        fun render(q: String) {
            results.removeAllViews()
            if (q.isBlank()) {
                results.addView(TextView(this@DonePanelActivity).apply {
                    text = "输入关键字搜索（单号/快递公司/商品/状态）"
                    textSize = 13f
                    setTextColor(onSurfaceVariant())
                    setPadding(0, dp(4), 0, 0)
                })
                return
            }
            val goods = Store.jdGoods(this@DonePanelActivity)
            val matched = itemsForMode(mode).filter {
                it.mailNo.contains(q, true) || it.companyName.contains(q, true) ||
                    it.stateName.contains(q, true) || it.accountLabel.contains(q, true) ||
                    it.latestText.contains(q, true) ||
                    (goods[it.mailNo]?.name?.contains(q, true) ?: false)
            }.take(30)
            if (matched.isEmpty()) {
                results.addView(TextView(this@DonePanelActivity).apply {
                    text = "没有匹配的包裹"
                    textSize = 13f
                    setTextColor(onSurfaceVariant())
                    setPadding(0, dp(4), 0, 0)
                })
                return
            }
            matched.forEach { results.addView(row(it)) }
        }
        input.editText?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                render(s?.toString()?.trim() ?: "")
            }
        })
        sheet.show()
    }

    /** 面板内日历：同首页「真日历」（月网格/今日标记/到达圆点/点击日期看当日件）——作用于当前模式集合 */
    private fun showPanelCalendar() {
        val set = itemsForMode(mode)
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(24))
        }
        content.addView(
            TextView(this).apply {
                text = if (mode == MODE_ABNORMAL) "异常包裹日历（共 ${set.size} 件）" else "已完成日历（共 ${set.size} 件）"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(com.google.android.material.color.MaterialColors.getColor(
                    this@DonePanelActivity, android.R.attr.textColorPrimary, 0))
                setPadding(0, 0, 0, dp(6))
            }
        )
        val arrivalsByDate = HashMap<Pair<Int, Int>, MutableList<ExpressItem>>()
        for (item in set) {
            arrivalDateOf(item)?.let { d ->
                arrivalsByDate.getOrPut(d) { mutableListOf() }.add(item)
            }
        }
        val now = Calendar.getInstance()
        var year = now.get(Calendar.YEAR)
        var month = now.get(Calendar.MONTH)

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        val title = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        }
        val prev = ImageButton(this).apply {
            setImageResource(com.halo.expressassistant.R.drawable.ic_chevron_left)
            setBackgroundResource(selectableBackground())
            contentDescription = "上个月"
        }
        val next = ImageButton(this).apply {
            setImageResource(com.halo.expressassistant.R.drawable.ic_chevron_right)
            setBackgroundResource(selectableBackground())
            contentDescription = "下个月"
        }
        nav.addView(prev, LinearLayout.LayoutParams(dp(44), dp(44)))
        nav.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        nav.addView(next, LinearLayout.LayoutParams(dp(44), dp(44)))
        content.addView(nav)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(grid)

        fun render() {
            title.text = "${year}年${month + 1}月"
            grid.removeAllViews()
            val header = LinearLayout(this@DonePanelActivity).apply { orientation = LinearLayout.HORIZONTAL }
            for (name in listOf("日", "一", "二", "三", "四", "五", "六")) {
                header.addView(
                    TextView(this@DonePanelActivity).apply {
                        text = name
                        gravity = Gravity.CENTER
                        textSize = 12f
                        setTextColor(onSurfaceVariant())
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
            }
            grid.addView(header)
            val first = Calendar.getInstance().apply { set(year, month, 1) }
            val leading = first.get(Calendar.DAY_OF_WEEK) - 1
            val daysInMonth = Calendar.getInstance().apply { set(year, month + 1, 0) }.get(Calendar.DAY_OF_MONTH)
            var day = 1
            val totalCells = ((leading + daysInMonth + 6) / 7) * 7
            for (idx in 0 until totalCells) {
                if (idx % 7 == 0) {
                    grid.addView(LinearLayout(this@DonePanelActivity).apply { orientation = LinearLayout.HORIZONTAL })
                }
                val row = grid.getChildAt(grid.childCount - 1) as LinearLayout
                if (idx < leading || day > daysInMonth) {
                    row.addView(TextView(this@DonePanelActivity), LinearLayout.LayoutParams(0, dp(46), 1f))
                } else {
                    val d = day++
                    val arrivals = arrivalsByDate[(month + 1) to d] ?: emptyList()
                    val isToday = year == now.get(Calendar.YEAR) &&
                        month == now.get(Calendar.MONTH) &&
                        d == now.get(Calendar.DAY_OF_MONTH)
                    val cell = LinearLayout(this@DonePanelActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        isClickable = true
                        setOnClickListener { showDayArrivals(arrivals, month + 1, d) }
                    }
                    cell.addView(
                        TextView(this@DonePanelActivity).apply {
                            text = d.toString()
                            textSize = 15f
                            typeface = Typeface.DEFAULT_BOLD
                            gravity = Gravity.CENTER
                            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
                            if (isToday) {
                                setTextColor(com.google.android.material.color.MaterialColors.getColor(
                                    this@DonePanelActivity,
                                    com.google.android.material.R.attr.colorOnPrimaryContainer, 0))
                                setBackgroundResource(com.halo.expressassistant.R.drawable.bg_icon_circle)
                            } else {
                                setTextColor(com.google.android.material.color.MaterialColors.getColor(
                                    this@DonePanelActivity, android.R.attr.textColorPrimary, 0))
                            }
                        }
                    )
                    cell.addView(
                        View(this@DonePanelActivity).apply {
                            layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply { topMargin = dp(2) }
                            background = android.graphics.drawable.GradientDrawable().apply {
                                shape = android.graphics.drawable.GradientDrawable.OVAL
                                setColor(
                                    if (arrivals.isNotEmpty()) {
                                        com.google.android.material.color.MaterialColors.getColor(
                                            this@DonePanelActivity, android.R.attr.colorPrimary, 0)
                                    } else android.graphics.Color.TRANSPARENT
                                )
                            }
                        }
                    )
                    row.addView(cell, LinearLayout.LayoutParams(0, dp(46), 1f))
                }
            }
        }
        prev.setOnClickListener {
            month--
            if (month < 0) { month = 11; year-- }
            render()
        }
        next.setOnClickListener {
            month++
            if (month > 11) { month = 0; year++ }
            render()
        }
        render()
        val scroll = ScrollView(this)
        scroll.addView(content)
        sheet.setContentView(scroll)
        sheet.show()
    }

    /** 日历某日到达/完成的件数列表（点击进详情） */
    private fun showDayArrivals(arrivals: List<ExpressItem>, m: Int, d: Int) {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(24))
        }
        content.addView(
            TextView(this).apply {
                text = "${m}月${d}日 · ${if (mode == MODE_ABNORMAL) "异常" else "完成"}数"
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
            }
        )
        content.addView(
            TextView(this).apply {
                text = if (arrivals.isEmpty()) "当天暂无" else "共 ${arrivals.size} 件"
                textSize = 13f
                setTextColor(onSurfaceVariant())
                setPadding(0, 2, 0, dp(10))
            }
        )
        for (item in arrivals) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setBackgroundResource(selectableBackground())
                setOnClickListener {
                    sheet.dismiss()
                    startActivity(Intent(this@DonePanelActivity, DetailActivity::class.java)
                        .putExtra("item", Store.json.encodeToString(item)))
                }
            }
            val goods = Store.jdGoods(this@DonePanelActivity)[item.mailNo]
            row.addView(TextView(this).apply {
                text = goods?.name?.takeIf { it.isNotBlank() } ?: item.companyName
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            row.addView(TextView(this).apply {
                text = "${item.mailNo} · ${item.stateName}"
                textSize = 12f
                setTextColor(onSurfaceVariant())
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            content.addView(row)
        }
        val scroll = ScrollView(this)
        scroll.addView(content)
        sheet.setContentView(scroll)
        sheet.show()
    }

    /** 到达日期解析（与首页一致）：eta → M月D日 */
    private fun arrivalDateOf(item: ExpressItem): Pair<Int, Int>? {
        val eta = item.eta.ifBlank { item.aiEta }
        if (eta.isBlank()) return null
        val m = Regex("(\\d{1,2})月(\\d{1,2})日").find(eta) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    private fun selectableBackground(): Int {
        val typed = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, typed, true)
        return typed.resourceId
    }
}
