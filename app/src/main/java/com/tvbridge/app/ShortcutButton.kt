package com.tvbridge.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.widget.Button

class ShortcutButton(context: Context) : Button(context) {
    var icon: Drawable? = null
    var iconPixels = 92f

    init {
        paint.isAntiAlias = true
        paint.isSubpixelText = true
    }

    override fun onDraw(canvas: Canvas) {
        val scale = NativeUi.scale(context)
        val iconSize = iconPixels * scale
        val gap = 14f * scale

        paint.textSize = 28f * scale
        paint.color = currentTextColor

        val fm = paint.fontMetrics
        val textHeight = fm.descent - fm.ascent
        val totalContentHeight = iconSize + gap + textHeight
        val top = ((height - totalContentHeight) / 2f).coerceAtLeast(8f * scale)

        // Draw App Icon
        icon?.let {
            val left = (width - iconSize) / 2f
            it.setBounds(left.toInt(), top.toInt(), (left + iconSize).toInt(), (top + iconSize).toInt())
            it.draw(canvas)
        }

        // Draw App Label with high-readability subtle shadow
        val label = TextUtils.ellipsize(text, paint, (width - 24f * scale).coerceAtLeast(1f), TextUtils.TruncateAt.END).toString()
        val textX = (width - paint.measureText(label)) / 2f
        val textY = top + iconSize + gap - fm.ascent

        if (isFocused || isSelected) {
            paint.setShadowLayer(6f * scale, 0f, 2f * scale, 0x88000000.toInt())
        } else {
            paint.setShadowLayer(3f * scale, 0f, 1f * scale, 0x55000000)
        }

        canvas.drawText(label, textX, textY, paint)
        paint.clearShadowLayer()
    }
}
