package com.halo.expressassistant.ui

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.halo.expressassistant.R
import com.halo.expressassistant.data.ExpressItem
import com.halo.expressassistant.data.Store
import com.halo.expressassistant.databinding.ItemExpressBinding
import com.halo.expressassistant.databinding.ItemSectionHeaderBinding

class ExpressAdapter(
    private val onClick: (ExpressItem) -> Unit,
    private val onLongClick: (ExpressItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        data class Header(val title: String) : Row()
        data class Item(val item: ExpressItem) : Row()
    }

    private val rows = mutableListOf<Row>()
    private var page = PAGE_TRANSPORT
    private var allItems: List<ExpressItem> = emptyList()
    private var windowStart = -1
    private var windowCount = -1

    fun submit(list: List<ExpressItem>) {
        allItems = list
        rebuild()
    }

    /** 分页窗口：只渲染 [start, start+count) 的条目（header 保留，显示总数） */
    fun setWindow(start: Int, count: Int) {
        windowStart = start
        windowCount = count
        rebuild()
    }

    /** 取消分页窗口（整列展示） */
    fun clearWindow() {
        windowStart = -1
        windowCount = -1
        rebuild()
    }

    fun setPage(newPage: Int) {
        page = newPage
        rebuild()
        notifyDataSetChanged()
    }

    fun currentPage(): Int = page

    /** 当前板块总数（分页条显示用）；page=在途 时为三节合计 */
    fun filteredFor(targetPage: Int): List<ExpressItem> = when (targetPage) {
        PAGE_DONE -> allItems.filter { bucket(it) == 3 }
        PAGE_ABNORMAL -> allItems.filter { bucket(it) == 4 }
        else -> allItems.filter { bucket(it) == 2 || bucket(it) == 0 || bucket(it) == 1 }
    }

    private fun rebuild() {
        rows.clear()
        when (page) {
            PAGE_TRANSPORT -> {
                val delivering = allItems.filter { bucket(it) == 2 }
                val after = allItems.filter { bucket(it) == 0 }
                val before = allItems.filter { bucket(it) == 1 }
                if (delivering.isNotEmpty()) {
                    rows.add(Row.Header("派送中 · ${delivering.size}"))
                    delivering.forEach { rows.add(Row.Item(it)) }
                }
                if (after.isNotEmpty()) {
                    rows.add(Row.Header("已发货 · ${after.size}"))
                    after.forEach { rows.add(Row.Item(it)) }
                }
                if (before.isNotEmpty()) {
                    rows.add(Row.Header("未发货 · ${before.size}"))
                    before.forEach { rows.add(Row.Item(it)) }
                }
            }
            PAGE_DONE -> {
                val done = allItems.filter { bucket(it) == 3 }
                if (done.isNotEmpty()) {
                    // 「完成 · N」头部去掉（数量信息在底部翻页条），直接分页渲染卡片
                    addWindowed(done)
                }
            }
            PAGE_ABNORMAL -> {
                val abnormal = allItems.filter { bucket(it) == 4 }
                if (abnormal.isNotEmpty()) {
                    addWindowed(abnormal)
                }
            }
        }
        notifyDataSetChanged()
    }

    /** 窗口切片：只挂载当前页的条目（其他页翻到才渲染） */
    private fun addWindowed(items: List<ExpressItem>) {
        if (windowStart < 0 || windowCount <= 0) {
            items.forEach { rows.add(Row.Item(it)) }
            return
        }
        val start = windowStart.coerceIn(0, items.size)
        val end = (start + windowCount).coerceAtMost(items.size)
        items.subList(start, end).forEach { rows.add(Row.Item(it)) }
    }

    private fun bucket(item: ExpressItem): Int {
        when (item.partitionOverride) {
            "delivering" -> return 2
            "shipped" -> return 0
            "notshipped" -> return 1
            "done" -> return 3
            "abnormal" -> return 4
        }
        return when (item.stateNum) {
            101, 103 -> 1
            102, 104 -> 0
            105 -> 2
            106, 107 -> 3
            108, 109, 110, 111 -> 4
            else -> when {
                item.state == 3 -> 3
                item.state == 1 -> 1
                item.state == 5 -> 2
                item.state == 4 -> 4
                item.state == 0 -> 0
                else -> 4
            }
        }
    }

    fun hasDone(): Boolean = allItems.any { bucket(it) == 3 }

    fun hasAbnormal(): Boolean = allItems.any { bucket(it) == 4 }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Header) TYPE_HEADER else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemSectionHeaderBinding.inflate(inflater, parent, false))
        } else {
            ItemHolder(ItemExpressBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).bind(row.title)
            is Row.Item -> (holder as ItemHolder).bind(row.item)
        }
    }

    override fun getItemCount(): Int = rows.size

    inner class HeaderHolder(private val binding: ItemSectionHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(title: String) {
            binding.section.text = title
        }
    }

    inner class ItemHolder(private val binding: ItemExpressBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ExpressItem) {
            Paper.styleCard(binding.root.context, binding.root)
            // 溯源商品替换卡片外显：标题=短名(或原名)，图标=商品图
            val goods = Store.jdGoods(binding.root.context)[item.mailNo]
            val goodsTitle = goods?.shortName?.ifBlank { goods?.name }.orEmpty()
            binding.company.text = goodsTitle.ifBlank { item.companyName }
            binding.mailNo.text = item.mailNo
            binding.latest.text = item.latestText.ifBlank { "点击查看详情" }
            binding.time.text = item.latestTime
            // 二级菜单（完成/异常）不再外显最末轨迹与时间（含分隔线）；仅在途首页显示
            val showTraceLine = page == PAGE_TRANSPORT
            binding.latest.visibility = if (showTraceLine) android.view.View.VISIBLE else android.view.View.GONE
            binding.time.visibility = if (showTraceLine) android.view.View.VISIBLE else android.view.View.GONE
            binding.traceDivider.visibility = if (showTraceLine) android.view.View.VISIBLE else android.view.View.GONE
            binding.reason.text = reasonText(item)
            binding.reason.visibility = if (page == PAGE_ABNORMAL) android.view.View.VISIBLE else android.view.View.GONE
            val etaText = item.eta.ifBlank { item.aiEta }
            binding.eta.text = if (etaText.isBlank()) "预计送达" else etaText
            binding.eta.visibility = if (etaText.isNotBlank()) android.view.View.VISIBLE else android.view.View.GONE
            val goodsPic = goods?.imageUrl.orEmpty()
            if (goodsPic.isNotBlank()) {
                binding.icon.load(goodsPic) {
                    crossfade(true)
                    placeholder(R.drawable.ic_package)
                    error(R.drawable.ic_package)
                }
            } else {
                binding.icon.load(item.iconUrl) {
                    crossfade(true)
                    placeholder(R.drawable.ic_package)
                    error(R.drawable.ic_package)
                    transformations(CircleCropTransformation())
                }
                if (item.iconUrl.isBlank()) {
                    binding.icon.setImageResource(R.drawable.ic_package)
                }
            }
            // 聚合取件码
            binding.pickup.text = if (item.pickupCode.isNotBlank()) "取件码 ${item.pickupCode}" else ""
            binding.pickup.visibility = if (item.pickupCode.isNotBlank()) android.view.View.VISIBLE else android.view.View.GONE
            binding.checkbox.visibility = android.view.View.GONE
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener {
                onLongClick(item)
                true
            }
            binding.root.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        binding.root.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).start()
                        false
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        binding.root.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                        false
                    }
                    else -> false
                }
            }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
        private const val PAGE_TRANSPORT = 0
        private const val PAGE_DONE = 1
        private const val PAGE_ABNORMAL = 2

        private fun reasonText(item: ExpressItem): String = when (item.stateNum) {
            108 -> "拒收"
            109 -> "派送失败"
            110 -> "弃件"
            111 -> "已取消"
            else -> item.stateName.ifBlank { "状态异常" }
        }
    }
}
