package com.halo.expressassistant.ai

import android.content.Context
import android.text.Spanned
import io.noties.markwon.Markwon

object Markdown {
    @Volatile
    private var markwon: Markwon? = null

    fun render(context: Context, text: String): Spanned {
        val m = markwon ?: Markwon.create(context).also { markwon = it }
        return m.toMarkdown(text)
    }
}
