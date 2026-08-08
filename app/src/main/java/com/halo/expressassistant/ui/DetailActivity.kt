package com.halo.expressassistant.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import coil.load
import coil.transform.CircleCropTransformation
import com.google.android.material.card.MaterialCardView
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString

class DetailActivity : AppCompatActivity() {
    private var headerProgressText: TextView? = null
    private var headerProgressBar: com.google.android.material.progressindicator.LinearProgressIndicator? = null
    private var headerEtaText: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.apply(this, binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        val item = try {
            Store.json.decodeFromString<ExpressItem>(intent.getStringExtra("item") ?: "")
        } catch (e: Throwable) {
            null
        }
        if (item == null) {
            binding.container.addView(text("数据错误"))
            return
        }
        binding.container.addView(text("加载详情中…"))
        CoroutineScope(Dispatchers.Main).launch {
            try {
                var detail = try {
                    if (Store.xiaomiToken(this@DetailActivity).isNotEmpty()) {
                        XiaomiDetail.fetch(this@DetailActivity, item)
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
                if (detail.data.isEmpty() && Store.kd100Fallback(this@DetailActivity)) {
                    detail = KuaiDi100.fetchDetail(this@DetailActivity, item)
                }
                binding.container.removeAllViews()
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
                    val statusChanged = item.aiProgressAt.isNotEmpty() && item.aiProgressAt != item.latestTime
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
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
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
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(row)

        val progress = if (item.aiProgress in 0..100) item.aiProgress else -1
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
        val (progress, eta) = AiClient.computeProgress(this, item, trajectory)
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
