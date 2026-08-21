package com.halo.expressassistant.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.halo.expressassistant.data.Store

/**
 * 衬线主题的跨机型可复现兜底。
 *
 * 问题：主题里写的是 `android:fontFamily="serif"`，这是**通用族名**。
 * 拉丁字形一定拿到 Noto Serif，但中文字形要靠系统的 fallback 链——
 * 装了 `NotoSerifCJK` 的 ROM 没问题（本机 HyperOS 有），
 * 而被精简过的 ROM 会悄悄回退成无衬线，「纸感衬线」在别人手机上就名不副实。
 *
 * 对策（不内置字体版，按产品决定）：
 *  1. 运行时**实测** `serif` 与 `sans-serif` 画同一个汉字是否得到不同位图；
 *     不同 → 系统确实给了中文衬线，什么都不用做（零风险、零开销的常见路径）。
 *  2. 相同 → 说明中文掉到了无衬线，再按候选表逐个试 ROM 里可能存在但没接进
 *     `serif` 族的中文衬线（如小米 MiSerif SC、思源宋体等），命中就显式套到 TextView。
 *  3. 一个都没命中 → 维持现状（拉丁仍是衬线），并打日志说明。
 *
 * 局限：ROM 完全不含中文衬线时，不内置字体就无法真正复现——那种情况只能打包字体文件。
 */
object SerifFont {

    /**
     * ROM 里可能存在、但未必接进 serif 族的中文衬线族名，按"最接近思源宋体"排序。
     * 注意族名要用**系统字体配置里真实的 family name**，不是文件名：
     * 例如小米的 MiSerifSCVF.ttf 注册在 `miclock-serif-sc-regular`
     * （见 /product/etc/mi_fonts_customization.xml），写成 "MiSerif SC" 是探测不到的。
     */
    private val CANDIDATES = listOf(
        "Noto Serif CJK SC", "NotoSerifCJK", "Noto Serif SC",
        "Source Han Serif SC", "Source Han Serif CN",
        "miclock-serif-sc-regular", "miclock-serif-sc",   // 小米系（HyperOS）
        "Songti SC", "STSong", "SimSun", "FangSong"
    )

    /** 用来试字形的汉字：常用且衬线/无衬线差别明显 */
    private const val PROBE = "国"

    @Volatile
    private var resolved = false

    /** null = 无需干预（系统 serif 已给中文衬线，或没有更好的候选） */
    private var override: Typeface? = null
    private var reason: String = "未探测"

    /** 把一个字画进小位图，返回像素签名；字形不同签名就不同 */
    private fun signature(tf: Typeface): Int {
        val size = 48
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf
            textSize = size * 0.8f
            color = Color.BLACK
        }
        canvas.drawText(PROBE, 2f, size * 0.82f, paint)
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            px[y * size + x] = bmp.getPixel(x, y)
        }
        bmp.recycle()
        return px.contentHashCode()
    }

    private fun resolve() {
        if (resolved) return
        resolved = true
        val serif = signature(Typeface.create("serif", Typeface.NORMAL))
        val sans = signature(Typeface.create("sans-serif", Typeface.NORMAL))
        if (serif != sans) {
            reason = "系统 serif 已提供中文衬线，无需干预"
            Log.i("SerifFont", reason)
            return
        }
        // 中文掉到了无衬线：找 ROM 里真实存在的中文衬线族
        for (name in CANDIDATES) {
            val tf = Typeface.create(name, Typeface.NORMAL)
            if (signature(tf) != sans) {
                override = tf
                reason = "系统 serif 未覆盖中文，改用 ROM 字体族「$name」"
                Log.i("SerifFont", reason)
                return
            }
        }
        reason = "ROM 未提供任何中文衬线，中文将保持无衬线（如需保证需内置字体文件）"
        Log.w("SerifFont", reason)
    }

    /** 当前生效策略的说明，设置页/排障可直接展示 */
    fun describe(): String {
        resolve()
        return reason
    }

    private fun serifThemeActive(activity: Activity): Boolean = when (Store.theme(activity)) {
        Themes.LARK -> true
        Themes.CUSTOM -> Store.themeFont(activity) == Themes.FONT_SERIF
        else -> false
    }

    /**
     * Activity 可见时调用（App 的 onActivityStarted 里统一挂）。
     * 只在"系统 serif 确实覆盖不了中文 + 找到了替代族"时才遍历视图树，
     * 常见机型是彻底的 no-op。
     */
    fun applyIfNeeded(activity: Activity) {
        if (!serifThemeActive(activity)) return
        resolve()
        val tf = override ?: return
        val root = activity.window?.decorView ?: return
        root.post { applyTo(root, tf) }
    }

    /** 递归套字体；保留原有 bold/italic，跳过等宽（调试页的终端文本） */
    fun applyTo(view: View, tf: Typeface? = override) {
        val face = tf ?: return
        when (view) {
            is TextView -> {
                val old = view.typeface
                if (old === Typeface.MONOSPACE) return
                view.typeface = Typeface.create(face, old?.style ?: Typeface.NORMAL)
            }
            is ViewGroup -> for (i in 0 until view.childCount) applyTo(view.getChildAt(i), face)
        }
    }
}
