package com.halo.expressassistant.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.halo.expressassistant.data.ExpressItem
import com.halo.expressassistant.data.Store
import java.util.regex.Pattern

class ExpressImportService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!Store.accessibilityEnabled(this)) return
        val pkg = event?.packageName?.toString() ?: return
        val root = rootInActiveWindow ?: return
        if (pkg == "com.cainiao.wireless" || pkg == "com.taobao.taobao") {
            importCainiaoOrTaobao(root)
            return
        }
        if (pkg != "com.miui.personalassistant") return
        val texts = mutableListOf<String>()
        collectText(root, texts)
        if (texts.none { it.contains("商品单号") && it.contains("：") }) return

        val companies = mutableListOf<Pair<String, String>>()
        val numbers = mutableListOf<String>()
        val currentCompany = arrayOfNulls<String>(1)
        val known = mapOf(
            "京东" to ("jd" to "京东物流"),
            "申通" to ("shentong" to "申通快递"),
            "圆通" to ("yuantong" to "圆通速递"),
            "顺丰" to ("shunfeng" to "顺丰速运"),
            "中通" to ("zhongtong" to "中通快递"),
            "韵达" to ("yunda" to "韵达快递"),
            "极兔" to ("jtexpress" to "极兔速递"),
            "邮政" to ("youzhengguonei" to "中国邮政")
        )
        for (t in texts) {
            known.entries.firstOrNull { t.contains(it.key) }?.let { currentCompany[0] = it.key }
            val idx = t.indexOf("商品单号")
            if (idx >= 0) {
                val num = t.substringAfter("：").trim()
                val comp = currentCompany[0]
                if (num.isNotEmpty() && comp != null) {
                    companies.add(known.getValue(comp))
                    numbers.add(num)
                }
            }
        }
        if (numbers.isEmpty()) return

        val items = Store.items(this).toMutableList()
        val existing = items.map { it.mailNo }.toSet()
        for (i in numbers.indices) {
            val mailNo = numbers[i]
            if (mailNo !in existing) {
                val (code, name) = companies[i]
                items.add(ExpressItem(System.currentTimeMillis().toString() + i, code, name, mailNo, originalName = name))
            }
        }
        Store.saveItems(this, items)
    }

    private fun importCainiaoOrTaobao(root: AccessibilityNodeInfo) {
        val texts = mutableListOf<String>()
        collectText(root, texts)
        if (texts.none { it.contains("包裹") || it.contains("物流") || it.contains("单号") || it.contains("运单") }) return

        val known = mapOf(
            "京东" to ("jd" to "京东物流"),
            "申通" to ("shentong" to "申通快递"),
            "圆通" to ("yuantong" to "圆通速递"),
            "顺丰" to ("shunfeng" to "顺丰速运"),
            "中通" to ("zhongtong" to "中通快递"),
            "韵达" to ("yunda" to "韵达快递"),
            "极兔" to ("jtexpress" to "极兔速递"),
            "邮政" to ("youzhengguonei" to "中国邮政"),
            "菜鸟" to ("cainiao" to "菜鸟裹裹")
        )
        val companyEntry = known.entries.firstOrNull { (k, _) -> texts.any { it.contains(k) } }
        val (code, name) = companyEntry?.value ?: ("cainiao" to "菜鸟裹裹")

        val pattern = Pattern.compile("\\b(?:\\d{13,15}|[A-Za-z]{1,6}\\d{10,20})\\b")
        val numbers = linkedSetOf<String>()
        for (t in texts) {
            val m = pattern.matcher(t)
            while (m.find()) numbers.add(m.group().uppercase())
        }
        if (numbers.isEmpty()) return

        val latest = texts.firstOrNull {
            it.length in 6..90 &&
                !it.contains("单号") && !it.contains("包裹") && !it.contains("物流") &&
                !it.contains("订单") && !it.contains("扫码") && !it.contains("手机")
        } ?: "来自菜鸟导入"
        val time = texts.firstOrNull {
            it.contains("202") && (it.contains(":") || it.contains("-"))
        } ?: ""

        val items = Store.items(this).toMutableList()
        val existing = items.map { it.mailNo }.toSet()
        for (num in numbers) {
            if (num !in existing) {
                items.add(
                    ExpressItem(
                        System.currentTimeMillis().toString() + num,
                        code, name, num,
                        latestText = latest, latestTime = time, originalName = name
                    )
                )
            }
        }
        if (items.size != Store.items(this).size) {
            Store.saveItems(this, items)
        }
    }

    private fun collectText(node: AccessibilityNodeInfo, out: MutableList<String>) {
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectText(it, out) }
        }
    }

    override fun onInterrupt() {
    }
}
