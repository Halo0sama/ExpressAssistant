package com.halo.expressassistant.ui

import android.app.Activity
import com.halo.expressassistant.R
import com.halo.expressassistant.data.Store

object Themes {
    const val MONET = "monet"
    const val LARK = "lark"
    const val CUSTOM = "custom"
    const val FONT_SERIF = "serif"
    const val FONT_SANS = "sans"

    fun apply(activity: Activity) {
        val style = when (Store.theme(activity)) {
            LARK -> R.style.Theme_ExpressAssistant_Lark
            CUSTOM -> {
                val warm = Store.colorScheme(activity) == "warm"
                val serif = Store.themeFont(activity) == FONT_SERIF
                when {
                    warm && serif -> R.style.Theme_ExpressAssistant_Lark
                    warm && !serif -> R.style.Theme_ExpressAssistant_LarkSans
                    !warm && serif -> R.style.Theme_ExpressAssistant_Serif
                    else -> R.style.Theme_ExpressAssistant
                }
            }
            else -> R.style.Theme_ExpressAssistant
        }
        activity.setTheme(style)
    }

    fun current(activity: Activity): String = Store.theme(activity)
}
