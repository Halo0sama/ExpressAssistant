package com.halo.expressassistant.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors

object Sheets {

    /** 统一的“我的地址”式上弹卡片：返回弹窗和内容容器。 */
    fun create(
        context: Context,
        title: String,
        subtitle: String? = null,
        scrollable: Boolean = true
    ): Pair<BottomSheetDialog, LinearLayout> {
        val sheet = BottomSheetDialog(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 24), dp(context, 8), dp(context, 24), dp(context, 24))
        }
        container.addView(
            TextView(context).apply {
                text = title
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
            }
        )
        if (subtitle != null) {
            container.addView(
                TextView(context).apply {
                    text = subtitle
                    textSize = 13f
                    setTextColor(
                        MaterialColors.getColor(
                            context,
                            com.google.android.material.R.attr.colorOnSurfaceVariant,
                            0
                        )
                    )
                    setPadding(0, 2, 0, dp(context, 14))
                    setLineSpacing(0f, 1.2f)
                }
            )
        }
        if (scrollable) {
            val scroll = ScrollView(context)
            scroll.addView(container)
            sheet.setContentView(scroll)
        } else {
            sheet.setContentView(container)
        }
        return sheet to container
    }

    /** 统一的选项行：标题 + 说明 + 右侧勾选框。 */
    fun optionRow(
        context: Context,
        title: String,
        subtitle: String? = null,
        checked: Boolean,
        onClick: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 4), dp(context, 8), dp(context, 4), dp(context, 8))
            setBackgroundResource(selectableBackground(context))
            isClickable = true
            isFocusable = true
        }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        texts.addView(
            TextView(context).apply {
                text = title
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
            }
        )
        if (subtitle != null) {
            texts.addView(
                TextView(context).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(
                        MaterialColors.getColor(
                            context,
                            com.google.android.material.R.attr.colorOnSurfaceVariant,
                            0
                        )
                    )
                    setPadding(0, 2, 0, 0)
                }
            )
        }
        row.addView(
            texts,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            MaterialCheckBox(context).apply {
                isChecked = checked
                isClickable = false
                isFocusable = false
            }
        )
        row.setOnClickListener { onClick() }
        return row
    }

    /** 统一的小节标题。 */
    fun sectionTitle(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(
            MaterialColors.getColor(
                context,
                android.R.attr.colorPrimary,
                0
            )
        )
        setPadding(0, dp(context, 14), 0, dp(context, 4))
    }

    /** 统一的分隔线。 */
    fun divider(context: Context): android.view.View = android.view.View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(context, 1)
        ).apply { topMargin = dp(context, 12); bottomMargin = dp(context, 8) }
        setBackgroundColor(
            MaterialColors.getColor(
                context,
                com.google.android.material.R.attr.colorOutlineVariant,
                0x22000000
            )
        )
    }

    private fun selectableBackground(context: Context): Int {
        val typed = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, typed, true)
        return typed.resourceId
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
