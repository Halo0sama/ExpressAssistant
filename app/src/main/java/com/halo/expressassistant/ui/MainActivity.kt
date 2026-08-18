package com.halo.expressassistant.ui

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import coil.load
import coil.transform.CircleCropTransformation
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputLayout
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import com.halo.expressassistant.TrackingNotifier
import com.halo.expressassistant.ai.AiClient
import com.halo.expressassistant.api.KuaiDi100
import com.halo.expressassistant.api.XiaomiApi
import com.halo.expressassistant.api.XiaomiSync
import com.halo.expressassistant.ai.Markdown
import com.halo.expressassistant.data.ExpressItem
import com.halo.expressassistant.data.PendingReport
import com.halo.expressassistant.data.Store
import com.halo.expressassistant.data.displayProgress
import com.halo.expressassistant.data.sectionKeyOf
import com.halo.expressassistant.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ExpressAdapter
    private var reportDialog: Dialog? = null
    private var appliedTheme: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        appliedTheme = Themes.current(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.apply(this, binding.root)
        Paper.apply(this, binding.root, binding.toolbar)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3001)
        }

        adapter = ExpressAdapter(
            onClick = { item -> openDetail(item) },
            onLongClick = { item -> showPackageSheet(item) }
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.toolbar.navigationIcon = null
        binding.btnAdd.setOnClickListener { showAddDialog() }
        binding.tabGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val page = when (checkedId) {
                com.halo.expressassistant.R.id.tab_done -> 1
                com.halo.expressassistant.R.id.tab_abnormal -> 2
                else -> 0
            }
            adapter.setPage(page)
        }
        binding.swipeRefresh.setOnRefreshListener {
            if (Store.xiaomiToken(this).isEmpty() &&
                Store.jdCookies(this).isBlank() &&
                Store.tbCookies(this).isBlank()
            ) {
                binding.swipeRefresh.isRefreshing = false
                android.widget.Toast.makeText(this, "请先在设置中登录任一渠道（小米 / 京东 / 淘宝）", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                syncAll()
            }
        }
        binding.btnAi.setOnClickListener { startActivity(Intent(this, ChatActivity::class.java)) }
        binding.btnCalendar.setOnClickListener { showCalendarSheet() }
        binding.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        reload()
        maybeAutoSync()
        if (intent.getBooleanExtra("add", false)) {
            binding.root.post { showAddDialog() }
        }
        binding.list.post {
            val extra = dp(160)
            val pad = if (adapter.itemCount == 0) {
                binding.list.height + dp(120)
            } else {
                max(extra, binding.list.height / 2)
            }
            binding.list.setPadding(
                binding.list.paddingLeft,
                binding.list.paddingTop,
                binding.list.paddingRight,
                pad
            )
        }
        binding.root.post { reportFullyDrawn() }
    }

    override fun onResume() {
        super.onResume()
        val theme = Themes.current(this)
        if (appliedTheme != theme) {
            appliedTheme = theme
            recreate()
            return
        }
        Log.d("ExpressReport", "onResume fg=true")
        isForeground = true
        reportReady = { showPendingReport() }
        showPendingReport()
        reload()
    }

    override fun onPause() {
        super.onPause()
        Log.d("ExpressReport", "onPause fg=false")
        isForeground = false
        reportReady = null
    }

    private fun reload() {
        adapter.submit(Store.items(this))
        val hasDone = adapter.hasDone()
        val hasAbnormal = adapter.hasAbnormal()
        binding.tabDone.visibility = if (hasDone) android.view.View.VISIBLE else android.view.View.GONE
        binding.tabAbnormal.visibility = if (hasAbnormal) android.view.View.VISIBLE else android.view.View.GONE
        if ((adapter.currentPage() == 1 && !hasDone) || (adapter.currentPage() == 2 && !hasAbnormal)) {
            binding.tabTransport.isChecked = true
            adapter.setPage(0)
        }
    }

    private fun arrivalDateOf(item: ExpressItem): Pair<Int, Int>? {
        val eta = item.eta.ifBlank { item.aiEta }
        if (eta.isBlank()) return null
        val m = Regex("(\\d{1,2})月(\\d{1,2})日").find(eta) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    private fun showCalendarSheet() {
        val sheet = BottomSheetDialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(24))
        }
        val items = Store.items(this)
        val arrivalsByDate = HashMap<Pair<Int, Int>, MutableList<ExpressItem>>()
        for (item in items) {
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
            typeface = android.graphics.Typeface.DEFAULT_BOLD
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
            val header = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            for (name in listOf("日", "一", "二", "三", "四", "五", "六")) {
                header.addView(
                    TextView(this@MainActivity).apply {
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
                    grid.addView(LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL })
                }
                val row = grid.getChildAt(grid.childCount - 1) as LinearLayout
                if (idx < leading || day > daysInMonth) {
                    row.addView(TextView(this@MainActivity), LinearLayout.LayoutParams(0, dp(46), 1f))
                } else {
                    val d = day++
                    val arrivals = arrivalsByDate[(month + 1) to d] ?: emptyList()
                    val isToday = year == now.get(Calendar.YEAR) &&
                        month == now.get(Calendar.MONTH) &&
                        d == now.get(Calendar.DAY_OF_MONTH)
                    val cell = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        isClickable = true
                        setOnClickListener {
                            val c = Calendar.getInstance().apply { set(year, month, d) }
                            showArrivalsSheet(c, arrivals)
                        }
                    }
                    cell.addView(
                        TextView(this@MainActivity).apply {
                            text = d.toString()
                            textSize = 15f
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            gravity = Gravity.CENTER
                            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
                            if (isToday) {
                                setTextColor(
                                    MaterialColors.getColor(
                                        this@MainActivity,
                                        com.google.android.material.R.attr.colorOnPrimaryContainer,
                                        0
                                    )
                                )
                                setBackgroundResource(com.halo.expressassistant.R.drawable.bg_icon_circle)
                            } else {
                                setTextColor(
                                    MaterialColors.getColor(this@MainActivity, android.R.attr.textColorPrimary, 0)
                                )
                            }
                        }
                    )
                    cell.addView(
                        View(this@MainActivity).apply {
                            layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply { topMargin = dp(2) }
                            background = android.graphics.drawable.GradientDrawable().apply {
                                shape = android.graphics.drawable.GradientDrawable.OVAL
                                setColor(
                                    if (arrivals.isNotEmpty()) {
                                        MaterialColors.getColor(this@MainActivity, android.R.attr.colorPrimary, 0)
                                    } else {
                                        android.graphics.Color.TRANSPARENT
                                    }
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
            if (month < 0) {
                month = 11
                year--
            }
            render()
        }
        next.setOnClickListener {
            month++
            if (month > 11) {
                month = 0
                year++
            }
            render()
        }
        render()

        val scroll = ScrollView(this)
        scroll.addView(content)
        sheet.setContentView(scroll)
        sheet.show()
    }

    private fun showArrivalsSheet(c: Calendar, arrivals: List<ExpressItem>) {
        val sheet = BottomSheetDialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(24))
        }
        content.addView(
            TextView(this).apply {
                text = "${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日 到达"
                textSize = 22f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
        )
        content.addView(
            TextView(this).apply {
                text = if (arrivals.isEmpty()) "当天暂无快递到达" else "共 ${arrivals.size} 件"
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
                setOnClickListener { openDetail(item) }
            }
            row.addView(
                TextView(this).apply {
                    text = item.companyName
                    textSize = 16f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
            )
            row.addView(
                TextView(this).apply {
                    text = "${item.mailNo} · 预计 ${item.eta.ifBlank { item.aiEta }}"
                    textSize = 13f
                    setTextColor(onSurfaceVariant())
                }
            )
            content.addView(row)
        }
        sheet.setContentView(content)
        sheet.show()
    }

    private fun syncAll() {
        Store.setLastAutoSync(this, System.currentTimeMillis())
        // 调试用：intent extra "skip_channels"（逗号分隔 xiaomi/jd/taobao）模拟未登录组合
        val skip = intent.getStringExtra("skip_channels")
            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val report = SyncEngine.sync(this@MainActivity, skip)
                val parts = report.statuses.joinToString(" · ") { s ->
                    if (s.error != null) "${s.channel} 失败" else "${s.channel} ${s.count} 件"
                }
                val updated = refreshAllDetails()
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "同步完成：$parts（更新 $updated 件）",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                reload()
                // 后台优化卡片短名，完成后刷新
                CoroutineScope(Dispatchers.Main).launch {
                    SyncEngine.optimizeShortNames(this@MainActivity)
                    reload()
                }
            } catch (e: Throwable) {
                android.widget.Toast.makeText(this@MainActivity, e.message ?: "同步失败", android.widget.Toast.LENGTH_LONG).show()
            } finally {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private suspend fun refreshAllDetails(): Int {
        val items = Store.items(this@MainActivity).toMutableList()
        var updated = 0
        for ((i, item) in items.withIndex()) {
            try {
                // 京东/淘宝源的数据在列表同步时已是最新，无需再逐件拉详情
                if (item.source == "jd" || item.source == "taobao") continue
                val detail = com.halo.expressassistant.api.XiaomiDetail.fetch(this@MainActivity, item)
                val last = detail.data.firstOrNull { it.context.isNotBlank() } ?: detail.data.firstOrNull()
                if (last != null) {
                    val newText = last.context
                    val newTime = last.formattedTime.ifBlank { last.time }
                    var notifiedText = item.notifiedText
                    var notifiedTime = item.notifiedTime
                    if (item.tracked) {
                        if (notifiedText.isBlank() && notifiedTime.isBlank()) {
                            notifiedText = newText
                            notifiedTime = newTime
                        } else if (newTime.isNotEmpty() && newTime > notifiedTime) {
                            TrackingNotifier.notify(this@MainActivity, item.copy(latestText = newText, latestTime = newTime))
                            notifiedText = newText
                            notifiedTime = newTime
                        }
                    }
                    items[i] = item.copy(
                        latestText = newText,
                        latestTime = newTime,
                        notifiedText = notifiedText,
                        notifiedTime = notifiedTime,
                        state = detail.state,
                        eta = com.halo.expressassistant.api.EtaParser.extract(
                            detail.data.joinToString(" ") { it.context }
                        ).ifBlank { item.eta }
                    )
                    updated++
                }
            } catch (e: Throwable) {
                if (Store.kd100Fallback(this@MainActivity)) {
                    try {
                        val detail = KuaiDi100.fetchDetail(this@MainActivity, item)
                        val last = detail.data.firstOrNull { it.context.isNotBlank() } ?: detail.data.firstOrNull()
                        if (last != null) {
                            val newText = last.context
                            val newTime = last.formattedTime.ifBlank { last.time }
                            var notifiedText = item.notifiedText
                            var notifiedTime = item.notifiedTime
                            if (item.tracked) {
                                if (notifiedText.isBlank() && notifiedTime.isBlank()) {
                                    notifiedText = newText
                                    notifiedTime = newTime
                                } else if (newTime.isNotEmpty() && newTime > notifiedTime) {
                                    TrackingNotifier.notify(this@MainActivity, item.copy(latestText = newText, latestTime = newTime))
                                    notifiedText = newText
                                    notifiedTime = newTime
                                }
                            }
                            items[i] = item.copy(
                                latestText = newText,
                                latestTime = newTime,
                                notifiedText = notifiedText,
                                notifiedTime = notifiedTime,
                                state = detail.state,
                                eta = com.halo.expressassistant.api.EtaParser.extract(
                                    detail.data.joinToString(" ") { it.context }
                                ).ifBlank { item.eta }
                            )
                            updated++
                        }
                    } catch (ignored: Throwable) {
                    }
                }
            }
        }
        Store.saveItems(this@MainActivity, items)
        return updated
    }

    private fun maybeAutoSync() {
        if (Store.xiaomiToken(this).isEmpty() &&
            Store.jdCookies(this).isBlank() &&
            Store.tbCookies(this).isBlank()
        ) return
        val now = System.currentTimeMillis()
        if (now - Store.lastAutoSync(this) < 60_000) return
        Store.setLastAutoSync(this, now)
        CoroutineScope(Dispatchers.Main).launch {
            try {
                SyncEngine.sync(this@MainActivity)
                refreshAllDetails()
                reload()
            } catch (e: Throwable) {
                // 静默失败，用户手动同步时会看到提示
            }
        }
    }

    private fun openDetail(item: ExpressItem) {
        startActivity(Intent(this, DetailActivity::class.java).putExtra("item", Store.json.encodeToString(item)))
    }

    private fun showAddDialog() {
        val view = layoutInflater.inflate(com.halo.expressassistant.R.layout.dialog_add_express, null)
        val input = view.findViewById<EditText>(com.halo.expressassistant.R.id.input)
        MaterialAlertDialogBuilder(this)
            .setView(view)
            .setPositiveButton("添加") { _, _ ->
                val mailNo = input.text.toString().trim()
                if (mailNo.isNotEmpty()) {
                    val items = Store.items(this).toMutableList()
                    CoroutineScope(Dispatchers.Main).launch {
                        val matched = withContext(Dispatchers.IO) {
                            if (Store.xiaomiToken(this@MainActivity).isNotEmpty()) {
                                XiaomiApi.matchCompany(
                                    this@MainActivity,
                                    Store.xiaomiToken(this@MainActivity),
                                    Store.xiaomiCUser(this@MainActivity),
                                    Store.xiaomiAccountId(this@MainActivity),
                                    Store.xiaomiOaid(this@MainActivity),
                                    Store.xiaomiVaid(this@MainActivity),
                                    mailNo
                                )?.let { Pair(it.first, it.second) }
                            } else {
                                null
                            }
                        }
                        val company = matched?.let { KuaiDi100.Company(it.first, it.second) }
                            ?: if (Store.kd100Fallback(this@MainActivity)) {
                                KuaiDi100.detectCompany(mailNo)
                            } else {
                                null
                            }
                        items.add(
                            ExpressItem(
                                System.currentTimeMillis().toString(),
                                company?.comCode ?: "auto",
                                company?.name ?: "自动识别",
                                mailNo,
                                originalName = company?.name ?: "自动识别"
                            )
                        )
                        Store.saveItems(this@MainActivity, items)
                        reload()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun primary(): Int =
        MaterialColors.getColor(this, android.R.attr.colorPrimary, 0)

    private fun onSurfaceVariant(): Int =
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)

    private fun selectableBackground(): Int {
        val typed = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, typed, true)
        return typed.resourceId
    }

    private fun showPackageSheet(item: ExpressItem) {
        val sheet = BottomSheetDialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(24))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(14) }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundResource(com.halo.expressassistant.R.drawable.bg_status_chip)
            load(item.iconUrl) {
                crossfade(true)
                placeholder(com.halo.expressassistant.R.drawable.ic_package)
                error(com.halo.expressassistant.R.drawable.ic_package)
                transformations(CircleCropTransformation())
            }
            if (item.iconUrl.isBlank()) setImageResource(com.halo.expressassistant.R.drawable.ic_package)
        }
        header.addView(icon)
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(
            TextView(this).apply {
                text = item.companyName
                textSize = 20f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
        )
        texts.addView(
            TextView(this).apply {
                text = item.mailNo
                textSize = 13f
                setTextColor(onSurfaceVariant())
            }
        )
        header.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(header)

        val progress = displayProgress(item)
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, dp(6))
        }
        statusRow.addView(
            TextView(this).apply {
                text = item.stateLabel()
                textSize = 15f
                setTextColor(primary())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        val progressText = TextView(this).apply {
            text = if (item.stateNum in 108..111) "已终止"
            else if (progress >= 0) "运输进度：$progress%"
            else "运输进度：计算中…"
            textSize = 14f
            setTextColor(primary())
        }
        statusRow.addView(progressText)
        content.addView(statusRow)
        var etaText: TextView? = null
        if (item.stateNum !in 108..111) {
            val progressBar = LinearProgressIndicator(this).apply {
                max = 100
                isIndeterminate = progress < 0
                if (progress >= 0) setProgressCompat(progress, true)
                trackThickness = dp(6)
                setIndicatorColor(primary())
                setTrackColor(MaterialColors.getColor(this@MainActivity, android.R.attr.colorControlNormal, 0))
            }
            content.addView(progressBar)
            val eta = item.eta.ifBlank { item.aiEta }
            if (eta.isNotBlank()) {
                val etaView = TextView(this).apply {
                    text = "预计送达：$eta"
                    textSize = 13f
                    setTextColor(primary())
                    setPadding(0, dp(6), 0, 0)
                }
                etaText = etaView
                content.addView(etaView)
            }
            val statusChanged = item.aiProgressAt.isEmpty() || item.aiProgressAt != item.latestTime
            if (progress < 0 || statusChanged) {
                computeAiProgressForSheet(item, progressText, progressBar, etaText)
            }
        }

        val trackRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(18), 0, dp(6))
        }
        val trackTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        trackTexts.addView(
            TextView(this).apply {
                text = "跟踪快递"
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
        )
        trackTexts.addView(
            TextView(this).apply {
                text = "有新动态时通知我"
                textSize = 13f
                setTextColor(onSurfaceVariant())
            }
        )
        trackRow.addView(trackTexts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        trackRow.addView(
            SwitchMaterial(this).apply {
                isChecked = item.tracked
                setOnCheckedChangeListener { _, checked ->
                    updateTracked(item, checked)
                }
            }
        )
        content.addView(trackRow)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, 0)
        }
        buttons.addView(
            MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = "修改名称"
                setIconResource(com.halo.expressassistant.R.drawable.ic_edit)
                setOnClickListener {
                    sheet.dismiss()
                    showRenameSheet(item)
                }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(10)
            }
        )
        buttons.addView(
            MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = "移除快递"
                setIconResource(com.halo.expressassistant.R.drawable.ic_delete)
                setTextColor(Color.rgb(179, 38, 30))
                setOnClickListener {
                    sheet.dismiss()
                    removeItem(item)
                }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        content.addView(buttons)

        sheet.setContentView(content)
        sheet.show()
    }

    private fun computeAiProgressForSheet(
        item: ExpressItem,
        progressText: TextView,
        bar: LinearProgressIndicator,
        etaText: TextView?
    ) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val detail = withContext(Dispatchers.IO) {
                    com.halo.expressassistant.api.XiaomiDetail.fetch(this@MainActivity, item)
                }
                val trajectory = detail.data.joinToString("\n") { "${it.time} ${it.context}" }
                val (progress, eta) = AiClient.computeProgress(this@MainActivity, item, trajectory)
                if (progress >= 0) {
                    val items = Store.items(this@MainActivity).map {
                        if (it.mailNo == item.mailNo) {
                            it.copy(
                                aiProgress = progress,
                                aiEta = eta,
                                aiProgressAt = it.latestTime
                            )
                        } else {
                            it
                        }
                    }
                    Store.saveItems(this@MainActivity, items)
                    progressText.text = "运输进度：$progress%"
                    bar.isIndeterminate = false
                    bar.setProgressCompat(progress, true)
                    if (eta.isNotBlank()) {
                        etaText?.text = "预计送达：$eta"
                        etaText?.visibility = View.VISIBLE
                    }
                    reload()
                } else {
                    progressText.text = "运输进度：--"
                    bar.isIndeterminate = false
                }
            } catch (e: Throwable) {
                progressText.text = "运输进度：--"
                bar.isIndeterminate = false
            }
        }
    }

    private fun updateTracked(item: ExpressItem, tracked: Boolean) {
        val items = Store.items(this).map {
            if (it.mailNo == item.mailNo) {
                if (tracked) {
                    it.copy(tracked = true, notifiedText = it.latestText, notifiedTime = it.latestTime)
                } else {
                    it.copy(tracked = false)
                }
            } else {
                it
            }
        }
        Store.saveItems(this, items)
        android.widget.Toast.makeText(
            this,
            if (tracked) "已开启跟踪，有新动态会通知你" else "已关闭跟踪",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun showRenameSheet(item: ExpressItem) {
        val sheet = BottomSheetDialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(24))
        }
        content.addView(
            TextView(this).apply {
                text = "修改名称"
                textSize = 22f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(14))
            }
        )
        val labelLayout = layoutInflater.inflate(com.halo.expressassistant.R.layout.view_input_outlined, null) as TextInputLayout
        labelLayout.hint = "自定义名称"
        val input = labelLayout.editText!!.apply {
            setText(item.companyName)
            setSingleLine(true)
        }
        content.addView(labelLayout)
        content.addView(
            TextView(this).apply {
                text = "重置为原名 ›"
                textSize = 13f
                setTextColor(primary())
                setPadding(0, dp(10), 0, 0)
                isClickable = true
                setOnClickListener {
                    val original = item.originalName.ifBlank { item.companyName }
                    input.setText(original)
                }
            }
        )
        content.addView(
            MaterialButton(this).apply {
                text = "保存"
                setIconResource(com.halo.expressassistant.R.drawable.ic_checklist)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(16) }
                setOnClickListener {
                    val name = input.text?.toString()?.trim().orEmpty()
                    if (name.isNotEmpty()) {
                        val items = Store.items(this@MainActivity).map {
                            if (it.mailNo == item.mailNo) it.copy(companyName = name) else it
                        }
                        Store.saveItems(this@MainActivity, items)
                        reload()
                        sheet.dismiss()
                    }
                }
            }
        )
        sheet.setContentView(content)
        sheet.show()
    }

    private fun removeItem(item: ExpressItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle("移除快递")
            .setMessage("将 ${item.companyName}（${item.mailNo}）移除？可在设置里的“删除的快递”中恢复。")
            .setPositiveButton("移除") { _, _ ->
                val all = Store.items(this)
                Store.addHidden(this, item)
                Store.saveItems(this, all.filterNot { it.mailNo == item.mailNo })
                reload()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2001 && resultCode == RESULT_OK) {
            syncAll()
        }
    }

    private fun showPendingReport() {
        val pending = Store.pendingReport(this)
        Log.d("ExpressReport", "showPendingReport pending=${pending != null}")
        if (pending == null) return
        val hasInTransit = Store.items(this).any {
            sectionKeyOf(it) in setOf("delivering", "shipped", "notshipped")
        }
        if (!hasInTransit) {
            Store.clearPendingReport(this)
            return
        }
        showReportCard(pending)
    }

    private fun showReportCard(pending: PendingReport) {
        Log.d("ExpressReport", "showReportCard dialog=${reportDialog != null}")
        if (reportDialog != null) return
        val overlay = layoutInflater.inflate(com.halo.expressassistant.R.layout.report_overlay, null)
        Paper.styleTree(this, overlay)
        overlay.findViewById<TextView>(com.halo.expressassistant.R.id.report_dateline).text =
            reportDateline(pending.time, pending.issue)
        renderReportSections(overlay, pending.text)
        val card = overlay.findViewById<MaterialCardView>(com.halo.expressassistant.R.id.report_card)
        card.isClickable = true
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(overlay)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        reportDialog = dialog
        dialog.setOnDismissListener {
            if (reportDialog === dialog) reportDialog = null
        }
        dialog.show()

        card.alpha = 0f
        card.rotationY = -90f
        card.translationY = 180f
        card.scaleX = 0.85f
        card.scaleY = 0.85f
        card.animate()
            .alpha(1f)
            .rotationY(0f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(500)
            .setInterpolator(DecelerateInterpolator(2f))
            .start()

        fun dismiss() {
            Log.d("ExpressReport", "dismiss")
            Store.clearPendingReport(this)
            card.animate()
                .alpha(0f)
                .translationY(140f)
                .scaleX(0.9f)
                .scaleY(0.9f)
                .setDuration(200)
                .withEndAction {
                    if (dialog.isShowing) dialog.dismiss()
                    if (reportDialog === dialog) reportDialog = null
                }
                .start()
        }

        overlay.setOnClickListener { dismiss() }
        overlay.findViewById<View>(com.halo.expressassistant.R.id.report_close).setOnClickListener { dismiss() }
    }

    companion object {
        @Volatile
        var isForeground = false
            private set

        @Volatile
        private var reportReady: (() -> Unit)? = null

        @JvmStatic
        fun onReportReady() {
            reportReady?.invoke()
            }
    }

    private fun reportDateline(time: Long, issue: Int): String {
        val date = SimpleDateFormat("yyyy年M月d日 · EEEE", Locale.CHINA).format(Date(time))
        if (issue <= 0) return date
        val first = Store.reportFirstDate(this)
        val firstText = if (first > 0) {
            " · 创刊于 " + SimpleDateFormat("M月d日", Locale.CHINA).format(Date(first))
        } else {
            ""
        }
        return "$date$firstText · 第 $issue 期"
    }

    private fun renderReportSections(overlay: View, raw: String) {
        val container = overlay.findViewById<LinearLayout>(com.halo.expressassistant.R.id.report_sections)
        container.removeAllViews()
        container.addView(
            reportMarkdown(raw),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun reportMarkdown(content: String): TextView = TextView(this).apply {
        text = Markdown.render(this@MainActivity, content)
        textSize = 14.5f
        setTextColor(
            MaterialColors.getColor(
                this@MainActivity,
                com.google.android.material.R.attr.colorOnSurface,
                0
            )
        )
        setLineSpacing(dp(2).toFloat(), 1.3f)
        setTextIsSelectable(true)
    }
}
