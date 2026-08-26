package com.halo.expressassistant.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import coil.load
import coil.transform.CircleCropTransformation
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.halo.expressassistant.R
import com.halo.expressassistant.ai.AiClient
import com.halo.expressassistant.api.KuaiDi100
import com.halo.expressassistant.api.EtaParser
import com.halo.expressassistant.api.XiaomiDetail
import com.halo.expressassistant.data.ExpressDetail
import com.halo.expressassistant.data.ExpressItem
import com.halo.expressassistant.data.Store
import com.halo.expressassistant.databinding.ActivityDetailBinding
import com.halo.expressassistant.data.progressFor
import com.halo.expressassistant.data.displayProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString

class DetailActivity : AppCompatActivity() {
    private var headerProgressText: TextView? = null
    private var headerProgressBar: com.google.android.material.progressindicator.LinearProgressIndicator? = null
    private var headerEtaText: TextView? = null
    private var goodsHolder: LinearLayout? = null
    private val resolveAttempted = HashSet<String>()
    private var resolving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        val binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.apply(this, binding.root)
        Paper.apply(this, binding.root, binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        val rawItem = intent.getStringExtra("item")
        val mailNo = intent.getStringExtra("mailNo")
        val item = try {
            if (rawItem != null) {
                Store.json.decodeFromString<ExpressItem>(rawItem)
            } else if (mailNo != null) {
                Store.items(this).firstOrNull { it.mailNo == mailNo }
            } else {
                null
            }
        } catch (e: Throwable) {
            null
        }
        if (item == null) {
            binding.container.addView(text("数据错误"))
            return
        }
        binding.toolbar.inflateMenu(R.menu.detail_menu)
        val traceView = binding.toolbar.menu.findItem(R.id.action_trace).actionView
        if (traceView != null) {
            // 右移边距：按钮右边缘与下方卡片右边缘（屏幕右 12dp）齐平
            val container = traceView.parent as? ViewGroup
            (container?.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                it.marginEnd = dp(12)
                container.layoutParams = it
            }
            val bg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(MaterialColors.getColor(this@DetailActivity, android.R.attr.colorPrimary, Color.BLUE))
            }
            traceView.background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x2EFFFFFF),
                bg,
                null
            )
            (traceView as? TextView)?.setTextColor(
                MaterialColors.getColor(this@DetailActivity, com.google.android.material.R.attr.colorOnPrimary, Color.WHITE)
            )
            traceView.setOnClickListener { onTraceClicked(item) }
        } else {
            binding.toolbar.setOnMenuItemClickListener { mi ->
                if (mi.itemId == R.id.action_trace) {
                    onTraceClicked(item)
                    true
                } else {
                    false
                }
            }
        }
        binding.container.addView(text("加载详情中…"))
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // 轨迹按数据来源路由：京东件→京东物流页；淘宝件→菜鸟轨迹；拼多多件→H5 订单详情；小米件→小米详情
                // 多源绑定：按件归属账号取凭证（找不到回退该平台第一个启用账号）
                val account = Store.accountForItem(this@DetailActivity, item)
                val pddCookies = if (item.source == "pdd") account?.let { Store.cookieOf(it.payload) } ?: Store.pddCookies(this@DetailActivity) else ""
                val tbCookies = if (item.source == "taobao") account?.let { Store.cookieOf(it.payload) } ?: Store.tbCookies(this@DetailActivity) else ""
                val xiaomiCred = account?.let { Store.parseXiaomiCred(it.payload) } ?: Store.xiaomiCred(this@DetailActivity)
                var detail = when (item.source) {
                    "jd" -> {
                        // 京东轨迹缓存：有效期内直接显示（秒开），无缓存才走 WebView 抓取并回存
                        val cached = Store.jdTraces(this@DetailActivity, item.mailNo, done = item.state == 3)
                        if (cached != null) {
                            ExpressDetail(
                                mailNo = item.mailNo,
                                companyName = item.companyName,
                                state = item.state,
                                isReceived = item.state == 3,
                                data = cached,
                                eta = item.eta
                            )
                        } else {
                            val d = JdTrackFetcher.fetchWith(
                                this@DetailActivity, item,
                                account?.let { Store.cookieOf(it.payload) } ?: Store.jdCookies(this@DetailActivity)
                            )
                            if (d.data.isNotEmpty()) {
                                Store.saveJdTrace(this@DetailActivity, item.mailNo, d.data, done = item.state == 3)
                            }
                            d
                        }
                    }
                    "taobao" -> {
                        // 淘宝（菜鸟）轨迹缓存：有效期内直接显示（秒开），无缓存才抓取并回存
                        val cached = Store.tbTraces(this@DetailActivity, item.mailNo, done = item.state == 3)
                        if (cached != null) {
                            ExpressDetail(
                                mailNo = item.mailNo,
                                companyName = item.companyName,
                                state = item.state,
                                isReceived = item.state == 3,
                                data = cached,
                                eta = item.eta
                            )
                        } else {
                            val traces = if (item.queryChannel.isNotBlank()) {
                                com.halo.expressassistant.api.TbOrders.fetchSsrTracesWith(tbCookies, item.queryChannel)
                            } else {
                                null
                            } ?: CaiNiaoGoodsResolver.fetchTracesWith(tbCookies, item.mailNo)
                            val tracesOrEmpty = traces ?: emptyList()
                            if (tracesOrEmpty.isNotEmpty()) {
                                Store.saveTbTrace(this@DetailActivity, item.mailNo, tracesOrEmpty, done = item.state == 3)
                            }
                            ExpressDetail(
                                mailNo = item.mailNo,
                                companyName = item.companyName,
                                state = item.state,
                                isReceived = item.state == 3,
                                data = traces ?: emptyList(),
                                eta = item.eta
                            )
                        }
                    }
                    "pdd" -> {
                        // 轨迹缓存：3 小时内直接显示，不重新走 WebView 流程
                        val cached = Store.pddTraces(this@DetailActivity, item.mailNo)
                        val traces = cached ?: PddTraceFetcher.fetchWith(this@DetailActivity, item, pddCookies).also { r ->
                            if (r != null) PddTraceFetcher.applyTraceToItem(this@DetailActivity, item, r)
                        }
                        ExpressDetail(
                            mailNo = item.mailNo,
                            companyName = item.companyName,
                            state = item.state,
                            isReceived = item.state == 3,
                            data = traces ?: emptyList(),
                            eta = item.eta
                        )
                    }
                    else -> try {
                        if (Store.accounts(this@DetailActivity, Store.CH_XIAOMI).any { it.enabled }) {
                            XiaomiDetail.fetchWith(this@DetailActivity, item, xiaomiCred)
                        } else {
                            throw IllegalStateException("no xiaomi login")
                        }
                    } catch (e: Throwable) {
                        if (Store.kd100Fallback(this@DetailActivity)) {
                            KuaiDi100.fetchDetail(this@DetailActivity, item)
                        } else {
                            throw e
                        }
                    }
                }
                if (detail.data.isEmpty() && Store.kd100Fallback(this@DetailActivity) && item.source == "xiaomi") {
                    detail = KuaiDi100.fetchDetail(this@DetailActivity, item)
                }
                // 详情抓到真实轨迹 → 双写回卡片外显（京东/淘宝完成单列表只给「完成」占位）
                if ((item.source == "jd" || item.source == "taobao") && detail.data.isNotEmpty()) {
                    val last = detail.data.last()
                    val statusOnly = setOf("完成", "已完成", "交易成功", "已签收", "已送达", "签收", "送达")
                    if (last.context.isNotBlank() && last.context !in statusOnly) {
                        val items = Store.items(this@DetailActivity).map {
                            if (it.mailNo == item.mailNo) {
                                it.copy(
                                    latestText = last.context.take(220),
                                    latestTime = last.time.ifBlank { it.latestTime }
                                )
                            } else it
                        }
                        Store.saveItems(this@DetailActivity, items)
                    }
                }
                binding.container.removeAllViews()
                // 从完整轨迹解析取件码（列表文本里可能没有）
                if (item.pickupCode.isBlank()) {
                    val code = GoodsPresentation.pickupCodeFromParts(
                        detail.data.map { it.context } + item.latestText
                    )
                    if (!code.isNullOrBlank()) {
                        val items = Store.items(this@DetailActivity).map {
                            if (it.mailNo == item.mailNo) it.copy(pickupCode = code) else it
                        }
                        Store.saveItems(this@DetailActivity, items)
                    }
                }
                addHeader(binding.container, item)
                if (detail.data.isEmpty()) {
                    binding.container.addView(text("暂无物流轨迹"))
                } else {
                    addTimeline(binding.container, detail)
                    val eta = EtaParser.extract(detail.data.joinToString(" ") { it.context })
                    if (eta.isNotBlank() && eta != item.eta) {
                        val items = Store.items(this@DetailActivity).map {
                            if (it.mailNo == item.mailNo) it.copy(eta = eta) else it
                        }
                        Store.saveItems(this@DetailActivity, items)
                    }
                    val statusChanged = item.aiProgressAt.isEmpty() || item.aiProgressAt != item.latestTime
                    if ((item.aiProgress < 0 || statusChanged) && item.stateNum !in 108..111) {
                        computeAiProgress(
                            item,
                            detail.data.joinToString("\n") { "${it.time} ${it.context}" }
                        )
                    }
                }
            } catch (e: Throwable) {
                binding.container.removeAllViews()
                binding.container.addView(text("加载失败：${e.message}"))
            }
        }
    }

    private fun addHeader(container: LinearLayout, item: ExpressItem) {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            radius = dp(20).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(MaterialColors.getColor(this@DetailActivity, android.R.attr.colorBackground, Color.TRANSPARENT))
        }
        Paper.styleCard(this, card)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val links = try {
            org.json.JSONArray(item.jumpLinks)
        } catch (e: Throwable) {
            null
        }
        var appLink: String? = null
        var h5Link: String? = null
        if (links != null) {
            for (i in 0 until links.length()) {
                val o = links.optJSONObject(i)
                val link = o?.optString("link")
                when (o?.optString("type")) {
                    "app" -> if (appLink == null) appLink = link
                    "h5" -> if (h5Link == null) h5Link = link
                }
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(14) }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundResource(R.drawable.bg_status_chip)
            load(item.iconUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_package)
                error(R.drawable.ic_package)
                transformations(CircleCropTransformation())
            }
            if (item.iconUrl.isBlank()) setImageResource(R.drawable.ic_package)
        }
        row.addView(icon)
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = item.companyName
            textSize = 20f
            setTextColor(MaterialColors.getColor(this@DetailActivity, android.R.attr.textColorPrimary, Color.BLACK))
        })
        texts.addView(TextView(this).apply {
            text = item.mailNo
            textSize = 13f
            setTextColor(MaterialColors.getColor(this@DetailActivity, android.R.attr.textColorSecondary, Color.GRAY))
            setPadding(0, dp(4), 0, 0)
        })
        if (item.accountLabel.isNotBlank()) {
            texts.addView(TextView(this).apply {
                text = "来自绑定：${item.accountLabel}"
                textSize = 12f
                setTextColor(MaterialColors.getColor(this@DetailActivity, android.R.attr.textColorSecondary, Color.GRAY))
                setPadding(0, dp(2), 0, 0)
            })
        }
        val addrLabel = Store.addressLabelForItem(this, item)
        if (!addrLabel.isNullOrBlank()) {
            texts.addView(TextView(this).apply {
                text = "收件地址：$addrLabel" + if (item.addressId.isNotBlank()) "（已指定）" else "（全局默认）"
                textSize = 12f
                setTextColor(MaterialColors.getColor(this@DetailActivity, android.R.attr.textColorSecondary, Color.GRAY))
                setPadding(0, dp(2), 0, 0)
            })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (appLink != null || h5Link != null) {
            row.addView(ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(4) }
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(9), dp(9), dp(9), dp(9))
                setImageResource(R.drawable.ic_widget_open)
                setColorFilter(MaterialColors.getColor(this@DetailActivity, android.R.attr.colorPrimary, Color.BLUE))
                contentDescription = "这是什么快递 · 查看来源"
                setBackgroundResource(selectableItemBackground())
                isClickable = true
                isFocusable = true
                setOnClickListener { openSource(appLink, h5Link) }
            })
        }
        content.addView(row)
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        goodsHolder = holder
        content.addView(holder)
        refreshGoods(item)

        val progress = displayProgress(item)
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        statusRow.addView(TextView(this).apply {
            text = item.stateLabel()
            textSize = 15f
            setTextColor(MaterialColors.getColor(this@DetailActivity, android.R.attr.colorPrimary, Color.BLUE))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val progressText = TextView(this).apply {
            text = if (item.stateNum in 108..111) "已终止"
            else if (progress >= 0) "运输进度：$progress%"
            else "运输进度：计算中…"
            textSize = 14f
            setTextColor(MaterialColors.getColor(this@DetailActivity, android.R.attr.colorPrimary, Color.BLUE))
        }
        headerProgressText = progressText
        statusRow.addView(progressText)
        content.addView(statusRow)
        if (item.stateNum !in 108..111) {
            val bar = com.google.android.material.progressindicator.LinearProgressIndicator(this).apply {
                max = 100
                isIndeterminate = progress < 0
                if (progress >= 0) setProgressCompat(progress, true)
                trackThickness = dp(6)
                setIndicatorColor(MaterialColors.getColor(this@DetailActivity, android.R.attr.colorPrimary, Color.BLUE))
                setTrackColor(MaterialColors.getColor(this@DetailActivity, android.R.attr.colorControlNormal, Color.LTGRAY))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = dp(18)
                    rightMargin = dp(18)
                    bottomMargin = dp(18)
                }
            }
            headerProgressBar = bar
            content.addView(bar)
            val eta = item.eta.ifBlank { item.aiEta }
            if (eta.isNotBlank()) {
                val etaText = TextView(this).apply {
                    text = "预计送达：$eta"
                    textSize = 14f
                    setTextColor(MaterialColors.getColor(this@DetailActivity, android.R.attr.colorPrimary, Color.BLUE))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        leftMargin = dp(18)
                        rightMargin = dp(18)
                        bottomMargin = dp(18)
                    }
                }
                headerEtaText = etaText
                content.addView(etaText)
            }
        } else {
            content.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)).apply {
                    leftMargin = dp(18)
                    rightMargin = dp(18)
                    bottomMargin = dp(18)
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    setColor(MaterialColors.getColor(this@DetailActivity, android.R.attr.colorControlNormal, Color.LTGRAY))
                }
            })
        }
        card.addView(content)
        container.addView(card)
    }

    private fun refreshGoods(item: ExpressItem) {
        val holder = goodsHolder ?: return
        holder.removeAllViews()
        val cached = Store.jdGoods(this)[item.mailNo]
        if (cached != null && cached.name.isNotBlank()) {
            holder.addView(goodsRow(cached))
            return
        }
        val isJd = item.provider == "JingDong" || item.companyCode.startsWith("JD")
        val isPdd = item.source == "pdd"
        val canResolve = when {
            isJd -> Store.accounts(this, Store.CH_JD).any { it.enabled }
            isPdd -> Store.accounts(this, Store.CH_PDD).any { it.enabled }
            else -> Store.accounts(this, Store.CH_TAOBAO).any { it.enabled }
        }
        if (canResolve && resolveAttempted.add(item.mailNo)) {
            startResolve(item, silent = true)
        }
    }

    private fun startResolve(item: ExpressItem, silent: Boolean = false) {
        val isJd = item.provider == "JingDong" || item.companyCode.startsWith("JD")
        val isPdd = item.source == "pdd"
        if (isJd && Store.accounts(this, Store.CH_JD).none { it.enabled }) {
            if (!silent) Toast.makeText(this, "请先在 设置 → 京东登录 · 商品溯源 完成京东登录", Toast.LENGTH_LONG).show()
            return
        }
        if (isPdd && Store.accounts(this, Store.CH_PDD).none { it.enabled }) {
            if (!silent) Toast.makeText(this, "请先在 设置 → 拼多多登录 完成拼多多登录", Toast.LENGTH_LONG).show()
            return
        }
        if (!isJd && !isPdd && Store.accounts(this, Store.CH_TAOBAO).none { it.enabled }) {
            if (!silent) Toast.makeText(this, "请先在 设置 → 淘宝登录 · 菜鸟溯源 完成淘宝登录", Toast.LENGTH_LONG).show()
            return
        }
        if (resolving) {
            if (!silent) Toast.makeText(this, "正在解析商品信息，请稍候…", Toast.LENGTH_SHORT).show()
            return
        }
        resolving = true
        if (!silent) Toast.makeText(this, "开始溯源，稍候几秒…", Toast.LENGTH_SHORT).show()
        // 多源绑定：按件归属账号取凭证
        val account = Store.accountForItem(this, item)
        val jdCookies = account?.let { Store.cookieOf(it.payload) } ?: Store.jdCookies(this)
        val tbCookies = account?.let { Store.cookieOf(it.payload) } ?: Store.tbCookies(this)
        val pddCookies = account?.let { Store.cookieOf(it.payload) } ?: Store.pddCookies(this)
        val done: (com.halo.expressassistant.data.JdGoods?) -> Unit = { goods ->
            resolving = false
            if (goods != null && goods.name.isNotBlank()) {
                val map = Store.jdGoods(this).toMutableMap()
                map[item.mailNo] = goods
                Store.saveJdGoods(this, map)
                if (!silent) {
                    Toast.makeText(this, "已解析：${goods.name.take(20)}…", Toast.LENGTH_LONG).show()
                }
                // 长名即时优化成卡片一行短名
                if (goods.shortName.isBlank() && goods.name.length > 12) {
                    CoroutineScope(Dispatchers.Main).launch {
                        GoodsPresentation.shortNameOf(this@DetailActivity, item.mailNo, goods.name)
                        if (!isFinishing) refreshGoods(item)
                    }
                }
            } else if (!silent) {
                Toast.makeText(this, "没有解析到商品信息（该件可能没有商品数据，或登录已过期）", Toast.LENGTH_LONG).show()
            }
            if (!isFinishing) refreshGoods(item)
        }
        if (isJd) {
            JdOrderResolver.resolveWith(this, jdCookies, item.mailNo, done)
        } else if (isPdd) {
            kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                val goods = PddTraceFetcher.resolveGoodsWith(this@DetailActivity, item, pddCookies)
                done(goods)
            }
        } else {
            kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                val goods = CaiNiaoGoodsResolver.resolveWith(tbCookies, item.mailNo)
                done(goods)
            }
        }
    }

    private fun onTraceClicked(item: ExpressItem) {
        val cached = Store.jdGoods(this)[item.mailNo]
        if (cached != null && cached.name.isNotBlank()) {
            showGoodsSheet(item, cached)
        } else {
            startResolve(item)
        }
    }

    private fun showGoodsSheet(item: ExpressItem, goods: com.halo.expressassistant.data.JdGoods) {
        val (sheet, container) = Sheets.create(this, "商品溯源")
        container.addView(
            TextView(this).apply {
                text = "商品信息来自电商平台订单数据（京东 / 菜鸟 / 拼多多），已缓存在本地。"
                textSize = 13f
                setTextColor(MaterialColors.getColor(this@DetailActivity, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
                setPadding(0, 0, 0, dp(10))
            }
        )
        container.addView(goodsRow(goods))
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        buttons.addView(
            MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "重新解析"
                setOnClickListener {
                    sheet.dismiss()
                    startResolve(item)
                }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) }
        )
        buttons.addView(
            MaterialButton(this).apply {
                text = "好的"
                setOnClickListener { sheet.dismiss() }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        container.addView(buttons)
        sheet.show()
    }

    private fun goodsRow(goods: com.halo.expressassistant.data.JdGoods): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(2), dp(18), dp(12))
        }
        row.addView(ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(10) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundResource(R.drawable.bg_status_chip)
            load(goods.imageUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_package)
                error(R.drawable.ic_package)
            }
        })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = goods.name
            textSize = 15f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(MaterialColors.getColor(this@DetailActivity, android.R.attr.textColorPrimary, Color.BLACK))
        })
        if (goods.count.isNotBlank()) {
            texts.addView(TextView(this).apply {
                text = goods.count
                textSize = 12f
                setTextColor(MaterialColors.getColor(this@DetailActivity, android.R.attr.textColorSecondary, Color.GRAY))
                setPadding(0, dp(2), 0, 0)
            })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun selectableItemBackground(): Int {
        val typed = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, typed, true)
        return typed.resourceId
    }

    private fun openSource(appLink: String?, h5Link: String?) {
        fun tryOpen(link: String?): Boolean {
            if (link.isNullOrBlank()) return false
            return try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                true
            } catch (e: Throwable) {
                false
            }
        }
        if (!tryOpen(appLink) && !tryOpen(h5Link)) {
            Toast.makeText(this, "没有可打开的来源应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addTimeline(container: LinearLayout, detail: ExpressDetail) {
        val points = detail.data
        for (i in points.indices) {
            val p = points[i]
            val isLast = i == points.size - 1
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(2) }
            }
            val rail = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.MATCH_PARENT)
            }
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply {
                    topMargin = dp(6)
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(MaterialColors.getColor(this@DetailActivity, android.R.attr.colorPrimary, Color.BLUE))
                }
            }
            rail.addView(dot)
            if (!isLast) {
                rail.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(2), 0, 1f)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        setColor(MaterialColors.getColor(this@DetailActivity, android.R.attr.colorControlNormal, Color.LTGRAY))
                    }
                })
            }
            row.addView(rail)
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, dp(8), dp(20))
            }
            content.addView(TextView(this).apply {
                text = p.time
                textSize = 14f
                setTextColor(MaterialColors.getColor(this@DetailActivity, android.R.attr.colorPrimary, Color.BLUE))
            })
            content.addView(TextView(this).apply {
                text = p.context
                textSize = 16f
                setTextColor(MaterialColors.getColor(this@DetailActivity, android.R.attr.textColorPrimary, Color.BLACK))
                setLineSpacing(dp(4).toFloat(), 1f)
                setPadding(0, dp(6), 0, 0)
            })
            row.addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            container.addView(row)
        }
    }

    private fun text(content: String): TextView = TextView(this).apply {
        text = content
        setPadding(dp(12), dp(12), dp(12), dp(12))
        textSize = 15f
        setTextColor(MaterialColors.getColor(this@DetailActivity, android.R.attr.textColorSecondary, Color.GRAY))
    }

    private suspend fun computeAiProgress(item: ExpressItem, trajectory: String) {
        val (progress, eta) = AiClient.computeProgress(
            this, item, trajectory,
            Store.addressForItem(this, item)
        )
        if (progress < 0) {
            headerProgressText?.text = "运输进度：--"
            headerProgressBar?.isIndeterminate = false
            return
        }
        val items = Store.items(this).map {
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
        Store.saveItems(this, items)
        headerProgressText?.text = "运输进度：$progress%"
        headerProgressBar?.isIndeterminate = false
        headerProgressBar?.setProgressCompat(progress, true)
        if (eta.isNotBlank()) {
            val displayEta = if (item.eta.isNotBlank()) item.eta else eta
            headerEtaText?.text = "预计送达：$displayEta"
            headerEtaText?.visibility = View.VISIBLE
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

}
