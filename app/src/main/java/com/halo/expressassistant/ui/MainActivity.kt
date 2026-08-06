package com.halo.expressassistant.ui

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
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
import com.halo.expressassistant.api.KuaiDi100
import com.halo.expressassistant.api.XiaomiApi
import com.halo.expressassistant.api.XiaomiSync
import com.halo.expressassistant.ai.Markdown
import com.halo.expressassistant.data.ExpressItem
import com.halo.expressassistant.data.PendingReport
import com.halo.expressassistant.data.Store
import com.halo.expressassistant.data.progressFor
import com.halo.expressassistant.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ExpressAdapter
    private var reportDialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.apply(this, binding.root)

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
            if (Store.xiaomiToken(this).isEmpty()) {
                binding.swipeRefresh.isRefreshing = false
                android.widget.Toast.makeText(this, "请先登录小米再同步", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                xiaomiSync()
            }
        }
        binding.btnAi.setOnClickListener { startActivity(Intent(this, ChatActivity::class.java)) }
        binding.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        reload()
        maybeAutoSync()
    }

    override fun onResume() {
        super.onResume()
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

    private fun xiaomiSync() {
        Store.setLastAutoSync(this, System.currentTimeMillis())
        CoroutineScope(Dispatchers.Main).launch {
            try {
                XiaomiSync.sync(this@MainActivity)
                val updated = refreshAllDetails()
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "同步完成，已更新 $updated 个快递",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                reload()
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
                val detail = com.halo.expressassistant.api.XiaomiDetail.fetch(this@MainActivity, item)
                val last = detail.data.firstOrNull { it.context.isNotBlank() } ?: detail.data.firstOrNull()
                if (last != null) {
                    val newText = last.context
                    val newTime = last.formattedTime.ifBlank { last.time }
                    if (item.tracked && (newText != item.latestText || newTime != item.latestTime)) {
                        TrackingNotifier.notify(this@MainActivity, item.copy(latestText = newText, latestTime = newTime))
                    }
                    items[i] = item.copy(
                        latestText = newText,
                        latestTime = newTime,
                        state = detail.state,
                        eta = com.halo.expressassistant.api.EtaParser.extract(
                            detail.data.joinToString(" ") { it.context }
                        )
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
                            if (item.tracked && (newText != item.latestText || newTime != item.latestTime)) {
                                TrackingNotifier.notify(this@MainActivity, item.copy(latestText = newText, latestTime = newTime))
                            }
                            items[i] = item.copy(
                                latestText = newText,
                                latestTime = newTime,
                                state = detail.state,
                                eta = com.halo.expressassistant.api.EtaParser.extract(
                                    detail.data.joinToString(" ") { it.context }
                                )
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
        if (Store.xiaomiToken(this).isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - Store.lastAutoSync(this) < 60_000) return
        Store.setLastAutoSync(this, now)
        CoroutineScope(Dispatchers.Main).launch {
            try {
                XiaomiSync.sync(this@MainActivity)
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

        val progress = progressFor(item)
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
        statusRow.addView(
            TextView(this).apply {
                text = if (item.stateNum in 108..111) "已终止" else "运输进度：$progress%"
                textSize = 14f
                setTextColor(primary())
            }
        )
        content.addView(statusRow)
        if (item.stateNum !in 108..111) {
            content.addView(
                LinearProgressIndicator(this).apply {
                    max = 100
                    setProgressCompat(progress, true)
                    trackThickness = dp(6)
                    setIndicatorColor(primary())
                    setTrackColor(MaterialColors.getColor(this@MainActivity, android.R.attr.colorControlNormal, 0))
                }
            )
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

    private fun updateTracked(item: ExpressItem, tracked: Boolean) {
        val items = Store.items(this).map {
            if (it.mailNo == item.mailNo) it.copy(tracked = tracked) else it
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
        val labelLayout = TextInputLayout(this).apply {
            hint = "自定义名称"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val input = EditText(this).apply {
            setText(item.companyName)
            setSingleLine(true)
        }
        labelLayout.addView(input)
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
            xiaomiSync()
        }
    }

    private fun showPendingReport() {
        val pending = Store.pendingReport(this)
        Log.d("ExpressReport", "showPendingReport pending=${pending != null}")
        if (pending != null) showReportCard(pending)
    }

    private fun showReportCard(pending: PendingReport) {
        Log.d("ExpressReport", "showReportCard dialog=${reportDialog != null}")
        if (reportDialog != null) return
        val overlay = layoutInflater.inflate(com.halo.expressassistant.R.layout.report_overlay, null)
        overlay.findViewById<TextView>(com.halo.expressassistant.R.id.report_text).text =
            Markdown.render(this, pending.text)
        overlay.findViewById<TextView>(com.halo.expressassistant.R.id.report_time).text =
            "生成于 " + SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(pending.time))
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
}
