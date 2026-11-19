package com.tvbridge.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import java.lang.ref.WeakReference

object GlassUi {
    const val PANEL_RADIUS = 28f
    const val BUTTON_RADIUS = 22f
    const val TILE_RADIUS = 22f
    const val TAB_RADIUS = 24f
    const val CHIP_RADIUS = 18f
    const val CIRCLE_RADIUS = 100f

    data class Frost(val bitmap: Bitmap, val width: Int, val height: Int, val left: Int = 0, val top: Int = 0)

    fun ink(context: Context, dark: Boolean = HomeAppearance.dark(context)) =
        if (dark) 0xfff6f7fb.toInt() else 0xff1c222b.toInt()

    fun muted(context: Context, dark: Boolean = HomeAppearance.dark(context)) =
        if (dark) 0xff949ba8.toInt() else 0xff6b7280.toInt()

    fun colors(context: Context, dark: Boolean = HomeAppearance.dark(context)) =
        ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_enabled),
                intArrayOf(android.R.attr.state_focused),
                intArrayOf(android.R.attr.state_pressed),
                intArrayOf(android.R.attr.state_selected),
                intArrayOf()
            ),
            intArrayOf(
                if (dark) 0x66949ba8.toInt() else 0x666b7280.toInt(),
                0xffffffff.toInt(),
                0xffffffff.toInt(),
                0xffffffff.toInt(),
                if (dark) 0xffd1d5e2.toInt() else 0xff323a48.toInt()
            )
        )

    fun activity(context: Context): Activity? {
        var c = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return c as? Activity
    }

    private var staticFrostBitmap: Bitmap? = null

    @Synchronized
    fun staticFrost(context: Context): Frost {
        if (staticFrostBitmap == null) {
            staticFrostBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff121722.toInt()) }
        }
        val size = context.resources.displayMetrics
        val root = activity(context)?.window?.decorView
        val w = root?.width?.takeIf { it > 0 } ?: size.widthPixels
        val h = root?.height?.takeIf { it > 0 } ?: size.heightPixels
        return Frost(staticFrostBitmap!!, w, h, 0, 0)
    }

    fun snapshot(context: Context): Frost? = staticFrost(context)

    fun video(context: Context): Boolean {
        if (context.packageName == "com.tvbridge.engine") return true
        fun contains(view: View): Boolean {
            if (!view.isShown) return false
            if (view is android.view.SurfaceView) return true
            if (view is android.view.ViewGroup) {
                for (i in 0 until view.childCount) {
                    if (contains(view.getChildAt(i))) return true
                }
            }
            return false
        }
        return activity(context)?.window?.decorView?.let(::contains) ?: false
    }

    fun backdrop(view: View): Drawable = object : Drawable() {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        override fun draw(canvas: Canvas) {
            val saved = canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            HomeAppearance.drawBackdrop(view.context, canvas, bounds.width(), bounds.height(), HomeAppearance.dark(view.context), paint)
            canvas.restoreToCount(saved)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        @Deprecated("Legacy Drawable") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    fun surface(
        view: View,
        radius: Float = 22f,
        frost: Frost? = null,
        video: Boolean = video(view.context),
        dark: Boolean? = null,
        dense: Boolean = false,
        border: Boolean = true,
        faint: Boolean = false,
        alphaBoost: Int = 0,
        bottomCorners: Boolean = true
    ): Drawable = Material(view, radius, dark, dense, border, faint, alphaBoost, bottomCorners)

    fun selectedBorder(view: View, radius: Float = 18f): Drawable {
        val scale = NativeUi.scale(view.context)
        fun borderDrawable(stroke: Float, strokeColor: Int, fillColor: Int) = GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = radius * scale
            if (stroke > 0f) setStroke(stroke.toInt(), strokeColor)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), borderDrawable(1.8f * scale, 0xf0ffffff.toInt(), 0x33ffffff))
            addState(intArrayOf(android.R.attr.state_focused), borderDrawable(1.8f * scale, 0xf0ffffff.toInt(), 0x33ffffff))
            addState(intArrayOf(android.R.attr.state_selected), borderDrawable(1.8f * scale, 0xf0ffffff.toInt(), 0x33ffffff))
            addState(intArrayOf(), borderDrawable(0f, 0x00000000, 0x00000000))
        }
    }

    object SmoothCorners {
        private const val SMOOTHING = 0.6f

        fun smoothRect(rect: RectF, radius: Float, bottomRadius: Float = radius): Path = Path().apply {
            if (radius <= 0f && bottomRadius <= 0f) {
                addRect(rect, Path.Direction.CW)
                return@apply
            }
            val budget = Math.min(rect.width(), rect.height()) / 2f
            if (budget <= 0f) {
                addRect(rect, Path.Direction.CW)
                return@apply
            }
            val top = Corner(radius, SMOOTHING, budget)
            val bot = Corner(bottomRadius, SMOOTHING, budget)
            moveTo(rect.left + top.p, rect.top)
            top.addTo(this, rect.right, rect.top, 1f, 0f, 0f, 1f)
            bot.addTo(this, rect.right, rect.bottom, 0f, 1f, -1f, 0f)
            bot.addTo(this, rect.left, rect.bottom, -1f, 0f, 0f, -1f)
            top.addTo(this, rect.left, rect.top, 0f, -1f, 1f, 0f)
            close()
        }

        private class Corner(radius: Float, smoothing: Float, budget: Float) {
            val p: Float
            private val r: Float
            private val a: Float
            private val b: Float
            private val c: Float
            private val d: Float
            private val arc: Float

            init {
                var s = smoothing
                if (radius > 0f && (1f + s) * radius > budget) s = (budget / radius - 1f).coerceIn(0f, s)
                r = Math.min(radius, budget).coerceAtLeast(0f)
                p = Math.min((1f + s) * r, budget)
                arc = (Math.PI / 2.0 * (1.0 - s)).toFloat()
                val arcLen = Math.sin(arc / 2.0).toFloat() * r * Math.sqrt(2.0).toFloat()
                val alpha = (Math.PI.toFloat() / 2f - arc) / 2f
                val p3p4 = r * Math.tan(alpha / 2.0).toFloat()
                val beta = Math.PI.toFloat() / 4f * s
                c = p3p4 * Math.cos(beta.toDouble()).toFloat()
                d = c * Math.tan(beta.toDouble()).toFloat()
                b = (p - arcLen - c - d) / 3f
                a = 2f * b
            }

            fun addTo(path: Path, vx: Float, vy: Float, ux: Float, uy: Float, wx: Float, wy: Float) {
                fun x(i: Float, j: Float) = vx + ux * i + wx * j
                fun y(i: Float, j: Float) = vy + uy * i + wy * j
                if (r <= 0f) { path.lineTo(vx, vy); return }
                path.lineTo(x(-p, 0f), y(-p, 0f))
                path.cubicTo(x(-p + a, 0f), y(-p + a, 0f), x(-p + a + b, 0f), y(-p + a + b, 0f), x(-p + a + b + c, d), y(-p + a + b + c, d))
                val f0 = -Math.PI.toFloat() / 4f - arc / 2f
                val f1 = -Math.PI.toFloat() / 4f + arc / 2f
                val k = 4f / 3f * Math.tan(arc / 4.0).toFloat() * r
                val e0x = -r + r * Math.cos(f0.toDouble()).toFloat(); val e0y = r + r * Math.sin(f0.toDouble()).toFloat()
                val e1x = -r + r * Math.cos(f1.toDouble()).toFloat(); val e1y = r + r * Math.sin(f1.toDouble()).toFloat()
                path.cubicTo(
                    x(e0x - k * Math.sin(f0.toDouble()).toFloat(), e0y + k * Math.cos(f0.toDouble()).toFloat()), y(e0x - k * Math.sin(f0.toDouble()).toFloat(), e0y + k * Math.cos(f0.toDouble()).toFloat()),
                    x(e1x + k * Math.sin(f1.toDouble()).toFloat(), e1y - k * Math.cos(f1.toDouble()).toFloat()), y(e1x + k * Math.sin(f1.toDouble()).toFloat(), e1y - k * Math.cos(f1.toDouble()).toFloat()),
                    x(e1x, e1y), y(e1x, e1y),
                )
                path.cubicTo(x(0f, p - a - b), y(0f, p - a - b), x(0f, p - a), y(0f, p - a), x(0f, p), y(0f, p))
            }
        }
    }

    private class Material(
        view: View,
        private val radius: Float,
        private val forceDark: Boolean?,
        private val dense: Boolean,
        private val border: Boolean = true,
        private val faint: Boolean = false,
        private val alphaBoost: Int = 0,
        private val bottomCorners: Boolean = true
    ) : Drawable() {
        private val target = WeakReference(view)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var isSelected = false
        private val path = Path()
        private val edgePath = Path()
        private val edgeRect = RectF()
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private var geometryDirty = true
        private var geometryScale = -1f

        override fun onBoundsChange(bounds: Rect) { geometryDirty = true }

        private fun geometry(scale: Float) {
            if (!geometryDirty && geometryScale == scale) return
            geometryDirty = false
            geometryScale = scale
            rect.set(bounds)
            val corner = radius * scale
            path.set(SmoothCorners.smoothRect(rect, corner, if (bottomCorners) corner else 0f))
            val rimWidth = (if (isSelected) 1.8f else 1.0f) * scale
            rimPaint.strokeWidth = rimWidth
            edgeRect.set(rect)
            edgeRect.inset(rimWidth / 2f, rimWidth / 2f)
            val edgeCorner = (corner - rimWidth / 2f).coerceAtLeast(0f)
            edgePath.set(SmoothCorners.smoothRect(edgeRect, edgeCorner, if (bottomCorners) edgeCorner else 0f))
        }

        override fun isStateful() = true

        override fun onStateChange(state: IntArray): Boolean {
            val sel = state.any {
                it == android.R.attr.state_focused ||
                it == android.R.attr.state_pressed ||
                it == android.R.attr.state_selected
            }
            if (sel != isSelected) {
                isSelected = sel
                geometryDirty = true
                invalidateSelf()
                return true
            }
            return false
        }

        override fun draw(canvas: Canvas) {
            val view = target.get() ?: return
            val dark = forceDark ?: HomeAppearance.dark(view.context)
            val scale = NativeUi.scale(view.context)
            geometry(scale)
            if (rect.width() <= 0 || rect.height() <= 0) return

            val saved = canvas.save()
            canvas.clipPath(path)

            if (dark) {
                // Apple-inspired Deep Charcoal Layered Glass Fill
                if (isSelected) {
                    // Radiant focused card state
                    paint.shader = LinearGradient(
                        rect.left, rect.top, rect.left, rect.bottom,
                        intArrayOf(0xee2c384d.toInt(), 0xf01a2332.toInt()),
                        floatArrayOf(0f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    paint.style = Paint.Style.FILL
                    canvas.drawRect(rect, paint)

                    // Subtle specular illumination wash
                    paint.shader = null
                    paint.color = 0x22ffffff
                    canvas.drawRect(rect, paint)
                } else {
                    // Elegant resting glass surface
                    val topAlpha = (if (dense) 0xee else if (faint) 0xa8 else 0xc8) + alphaBoost
                    val botAlpha = (if (dense) 0xf4 else if (faint) 0xb8 else 0xd8) + alphaBoost
                    val clampedTop = topAlpha.coerceIn(0, 255)
                    val clampedBot = botAlpha.coerceIn(0, 255)

                    paint.shader = LinearGradient(
                        rect.left, rect.top, rect.left, rect.bottom,
                        intArrayOf(
                            Color.argb(clampedTop, 22, 29, 41),
                            Color.argb(clampedBot, 13, 17, 25)
                        ),
                        floatArrayOf(0f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    paint.style = Paint.Style.FILL
                    canvas.drawRect(rect, paint)

                    // Top 1px Specular Edge Reflection (acrylic glass sheen)
                    paint.shader = LinearGradient(
                        rect.left, rect.top, rect.left, rect.top + 28f * scale,
                        intArrayOf(0x24ffffff, 0x00000000),
                        floatArrayOf(0f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawRect(rect.left, rect.top, rect.right, rect.top + 28f * scale, paint)
                }
            } else {
                // Ceramic Light Theme Material
                val alpha = (if (isSelected) 245 else if (dense) 230 else if (faint) 180 else 200) + alphaBoost
                paint.shader = null
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(alpha.coerceIn(0, 255), 242, 246, 250)
                canvas.drawRect(rect, paint)

                if (isSelected) {
                    paint.color = 0x30ffffff
                    canvas.drawRect(rect, paint)
                }
            }

            canvas.restoreToCount(saved)

            if (radius <= 0 || !border) return

            // High-precision 1px highlight border / rim
            if (isSelected) {
                // High-contrast luminous border on focus
                rimPaint.color = Color.argb(235, 255, 255, 255)
            } else {
                // Subtle 1px translucent glass border
                rimPaint.color = if (dark) Color.argb(32, 255, 255, 255) else Color.argb(45, 0, 0, 0)
            }
            canvas.drawPath(edgePath, rimPaint)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(filter: ColorFilter?) {}
        @Deprecated("Legacy Drawable") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
