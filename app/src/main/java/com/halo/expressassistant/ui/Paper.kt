package com.halo.expressassistant.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.halo.expressassistant.R
import com.halo.expressassistant.data.Store

object Paper {
    private var textureBitmap: Bitmap? = null

    private fun texture(context: Context): Bitmap =
        textureBitmap ?: BitmapFactory.decodeResource(context.resources, R.drawable.rice_paper)
            .also { textureBitmap = it }

    private fun intensity(context: Context): Int = Store.paperIntensity(context)

    private fun isNight(context: Context): Boolean {
        val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    /** 页面背景：米纸纹理 + 提亮补偿，强度可调。 */
    fun surface(context: Context): Drawable {
        val f = intensity(context) / 100f
        val base = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorSurface,
            Color.WHITE
        )
        val night = isNight(context)
        val layers = ArrayList<Drawable>()
        layers.add(ColorDrawable(base))
        if (f > 0f) {
            layers.add(textureLayer(context, if (night) 0.30f else 0.85f, f))
            if (!night) {
                layers.add(
                    ColorDrawable(
                        Color.argb(
                            (0.35f * 255 * minOf(f, 1f)).toInt(),
                            0xFF, 0xFD, 0xF7
                        )
                    )
                )
            }
        }
        return LayerDrawable(layers.toTypedArray())
    }

    /** 卡片：保留圆角与按压水波，叠一层更克制的米纸。 */
    fun styleCard(context: Context, card: MaterialCardView) {
        if (Store.paperIntensity(context) <= 0) return
        val f = intensity(context) / 100f
        val night = isNight(context)
        val radius = card.radius
        val color = card.cardBackgroundColor?.defaultColor
            ?: MaterialColors.getColor(
                context,
                com.google.android.material.R.attr.colorSurfaceContainerLow,
                0
            )
        val shape = GradientDrawable().apply {
            cornerRadius = radius
            setColor(color)
        }
        val layers = ArrayList<Drawable>()
        layers.add(shape)
        if (f > 0f) {
            layers.add(textureLayer(context, if (night) 0.25f else 0.55f, f))
            if (!night) {
                layers.add(
                    ColorDrawable(
                        Color.argb(
                            (0.20f * 255 * minOf(f, 1f)).toInt(),
                            0xFF, 0xFD, 0xF7
                        )
                    )
                )
            }
        }
        val content = LayerDrawable(layers.toTypedArray())
        val mask = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.BLACK)
        }
        val highlight = MaterialColors.getColor(
            context,
            android.R.attr.colorControlHighlight,
            0x1A000000
        )
        card.background = RippleDrawable(ColorStateList.valueOf(highlight), content, mask)
    }

    /** 整页应用：页面背景 + 工具栏 + 当前层级里的所有卡片。 */
    fun apply(context: Context, root: ViewGroup, toolbar: View? = null) {
        if (Store.paperIntensity(context) <= 0) return
        root.background = surface(context)
        if (toolbar != null) toolbar.background = surface(context)
        walkCards(root)
    }

    /** 给动态创建的视图树（弹窗、后加的卡片）补纸纹。 */
    fun styleTree(context: Context, view: View) {
        if (Store.paperIntensity(context) <= 0) return
        if (view is MaterialCardView) styleCard(context, view)
        if (view is ViewGroup) walkCards(view)
    }

    private fun textureLayer(context: Context, baseAlpha: Float, f: Float): Drawable =
        TiledTextureDrawable(texture(context), (baseAlpha * 255 * f).toInt().coerceIn(0, 255))

    private fun walkCards(group: ViewGroup) {
        for (i in 0 until group.childCount) {
            val v = group.getChildAt(i)
            if (v is MaterialCardView) styleCard(v.context, v)
            if (v is ViewGroup) walkCards(v)
        }
    }

    /** 平铺纸纹，但不贡献固有尺寸，避免把 wrap_content 的卡片撑高。 */
    private class TiledTextureDrawable(
        bitmap: Bitmap,
        alpha: Int
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            this.alpha = alpha
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            canvas.drawRect(
                b.left.toFloat(),
                b.top.toFloat(),
                b.right.toFloat(),
                b.bottom.toFloat(),
                paint
            )
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = -1

        override fun getIntrinsicHeight(): Int = -1

        override fun getMinimumWidth(): Int = 0

        override fun getMinimumHeight(): Int = 0
    }
}
