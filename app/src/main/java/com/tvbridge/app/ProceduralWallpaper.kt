package com.tvbridge.app

import android.graphics.*

object ProceduralWallpaper {
    fun bitmap(width: Int, height: Int, dark: Boolean) =
        Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            .also { draw(Canvas(it), width, height, dark) }

    fun draw(canvas: Canvas, width: Int, height: Int, dark: Boolean) {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        if (dark) {
            // Apple-inspired Deep Carbon / Obsidian atmospheric gradient
            paint.shader = LinearGradient(
                0f, 0f, w, h,
                intArrayOf(0xff090c12.toInt(), 0xff0f141f.toInt(), 0xff131926.toInt(), 0xff0b0e14.toInt()),
                floatArrayOf(0f, 0.35f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)

            // Subtle top-right atmospheric bloom (Deep Navy / Midnight Teal)
            paint.shader = RadialGradient(
                w * 0.82f, h * 0.18f, w * 0.58f,
                intArrayOf(0x281a3858, 0x1213273e, 0x00000000),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)

            // Subtle bottom-left ambient glow (Graphite Indigo)
            paint.shader = RadialGradient(
                w * 0.14f, h * 0.84f, w * 0.62f,
                intArrayOf(0x221f283d, 0x0e172030, 0x00000000),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)

            // Soft center ambient depth
            paint.shader = RadialGradient(
                w * 0.50f, h * 0.48f, w * 0.42f,
                intArrayOf(0x16182234, 0x00000000),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
        } else {
            // Refined Ceramic / Platinum Light Theme
            paint.shader = LinearGradient(
                0f, 0f, w, h,
                intArrayOf(0xfff0f3f8.toInt(), 0xffe2e8f0.toInt(), 0xffdbe3ec.toInt()),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)

            paint.shader = RadialGradient(
                w * 0.80f, h * 0.20f, w * 0.50f,
                0x30c8d8ea, 0x00000000,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
        }
    }
}
