package com.oea.launcher.themes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.View
import com.oea.launcher.icons.OeaIconController
import com.oea.launcher.ui.OeaTheme
import java.util.concurrent.Executors

/**
 * A live miniature of how a theme looks on Home or on the lock screen: the
 * real wallpaper (centre-cropped like Android does), with Home's icons, dock
 * and search bar, or the lock screen's clock and a notification, drawn in
 * the theme's colours, icon shape and glass style.
 */
class OeaThemePreviewView(context: Context, private val lockScreen: Boolean) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frame = RectF()
    private val tmp = RectF()
    private val clip = Path()
    private var bitmap: Bitmap? = null
    private var requested: String? = null

    var theme: OeaThemeSpec? = null
        set(value) { field = value; invalidate() }
    /** Rounded "phone" corners; 0 draws a plain rectangle (full-screen preview). */
    var cornerDp = 18f
        set(value) { field = value; invalidate() }

    fun setImage(ref: OeaImageRef?) {
        if (ref == null) {
            requested = null
            bitmap = null
            invalidate()
            return
        }
        if (requested == ref.key && bitmap != null) return
        requested = ref.key
        val cached = OeaThemeImages.cached(ref)
        if (cached != null) {
            bitmap = cached
            invalidate()
            return
        }
        bitmap = null
        invalidate()
        val key = ref.key
        val app = context.applicationContext
        loader.execute {
            val loaded = OeaThemeImages.thumbnail(app, ref)
            main.post {
                if (requested == key) {
                    bitmap = loaded
                    invalidate()
                }
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val wanted = (w * 2.05f).toInt()
        val h = when (mode) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(wanted, MeasureSpec.getSize(heightMeasureSpec))
            else -> wanted
        }
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = cornerDp * density
        frame.set(0f, 0f, w, h)
        clip.reset()
        clip.addRoundRect(frame, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        drawWallpaper(canvas, w, h)
        if (lockScreen) drawLock(canvas, w, h) else drawHome(canvas, w, h)
        canvas.restore()
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1f, density)
        paint.color = Color.argb(0x40, 255, 255, 255)
        canvas.drawRoundRect(frame, r, r, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawWallpaper(canvas: Canvas, w: Float, h: Float) {
        val image = bitmap
        val t = theme
        if (image == null || image.isRecycled) {
            paint.shader = LinearGradient(0f, 0f, 0f, h, t?.surfaceTint ?: Color.rgb(20, 24, 32), Color.rgb(8, 10, 14), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
            return
        }
        val scale = maxOf(w / image.width, h / image.height)
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate((w - image.width * scale) / 2f, (h - image.height * scale) / 2f)
        }
        paint.shader = BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    private fun accent(): Int = theme?.accentDark ?: Color.rgb(138, 180, 248)

    private fun drawStatus(canvas: Canvas, w: Float) {
        text.color = Color.WHITE
        text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        text.textSize = w * 0.045f
        text.textAlign = Paint.Align.LEFT
        canvas.drawText("12:30", w * 0.07f, w * 0.085f, text)
        paint.color = Color.argb(0xE0, 255, 255, 255)
        for (i in 0 until 3) canvas.drawCircle(w * (0.80f + i * 0.055f), w * 0.07f, w * 0.014f, paint)
    }

    private fun drawHome(canvas: Canvas, w: Float, h: Float) {
        val t = theme
        // Soft top and bottom scrims, like OEA Home, keep labels legible.
        paint.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(Color.argb(0x55, 0, 0, 0), Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(0x66, 0, 0, 0)),
            floatArrayOf(0f, 0.22f, 0.65f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        drawStatus(canvas, w)
        // At a Glance
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        text.textSize = w * 0.15f
        text.color = Color.WHITE
        canvas.drawText("12:30", w * 0.09f, h * 0.17f, text)
        text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        text.textSize = w * 0.045f
        canvas.drawText("Tuesday, Oct 14", w * 0.10f, h * 0.17f + w * 0.075f, text)
        // App grid
        val cols = 4
        val icon = w * 0.13f
        val gapX = (w - cols * icon) / (cols + 1)
        val shape = t?.iconShape ?: "system"
        val top = h * 0.46f
        val hues = floatArrayOf(0f, 35f, 120f, 200f, 260f, 320f, 160f, 50f)
        val base = OeaTheme.hsl(accent())
        for (row in 0 until 2) for (col in 0 until cols) {
            val x = gapX + col * (icon + gapX)
            val y = top + row * (icon * 1.75f)
            val hue = (base[0] + hues[(row * cols + col) % hues.size]) % 360f
            drawIcon(canvas, x, y, icon, shape, OeaTheme.hslToColor(hue, 0.55f, 0.55f))
            paint.color = Color.argb(0xCC, 255, 255, 255)
            tmp.set(x + icon * 0.15f, y + icon * 1.12f, x + icon * 0.85f, y + icon * 1.22f)
            canvas.drawRoundRect(tmp, icon * 0.05f, icon * 0.05f, paint)
        }
        // Dock
        val dockTop = h * 0.80f
        val dockH = icon * 1.45f
        tmp.set(w * 0.05f, dockTop, w * 0.95f, dockTop + dockH)
        drawSurface(canvas, tmp, dockH * 0.42f, t?.dockStyle ?: "glass", t?.glassStyle ?: "classic")
        for (i in 0 until 4) {
            val x = gapX + i * (icon + gapX)
            drawIcon(canvas, x, dockTop + (dockH - icon) / 2f, icon, shape, OeaTheme.hslToColor((base[0] + i * 70f) % 360f, 0.5f, 0.5f))
        }
        // Search pill
        tmp.set(w * 0.08f, dockTop + dockH + h * 0.022f, w * 0.92f, dockTop + dockH + h * 0.022f + w * 0.11f)
        drawSurface(canvas, tmp, tmp.height() / 2f, "glass", t?.glassStyle ?: "classic")
        paint.color = accent()
        canvas.drawCircle(tmp.left + tmp.height() * 0.6f, tmp.centerY(), tmp.height() * 0.16f, paint)
    }

    private fun drawLock(canvas: Canvas, w: Float, h: Float) {
        paint.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(Color.argb(0x50, 0, 0, 0), Color.TRANSPARENT, Color.argb(0x60, 0, 0, 0)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        drawStatus(canvas, w)
        // Padlock
        paint.color = Color.WHITE
        val cx = w / 2f
        tmp.set(cx - w * 0.03f, h * 0.085f, cx + w * 0.03f, h * 0.085f + w * 0.05f)
        canvas.drawRoundRect(tmp, w * 0.01f, w * 0.01f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w * 0.012f
        tmp.set(cx - w * 0.02f, h * 0.085f - w * 0.03f, cx + w * 0.02f, h * 0.085f + w * 0.01f)
        canvas.drawArc(tmp, 180f, 180f, false, paint)
        paint.style = Paint.Style.FILL
        // Clock and date
        text.textAlign = Paint.Align.CENTER
        text.color = Color.WHITE
        text.typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        text.textSize = w * 0.25f
        canvas.drawText("12:30", cx, h * 0.27f, text)
        text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        text.textSize = w * 0.05f
        canvas.drawText("Tuesday, October 14", cx, h * 0.27f + w * 0.09f, text)
        // A notification
        tmp.set(w * 0.07f, h * 0.55f, w * 0.93f, h * 0.55f + w * 0.24f)
        drawSurface(canvas, tmp, w * 0.06f, "glass", theme?.glassStyle ?: "classic")
        paint.color = accent()
        canvas.drawCircle(tmp.left + w * 0.09f, tmp.centerY(), w * 0.045f, paint)
        paint.color = Color.argb(0xEE, 255, 255, 255)
        canvas.drawRoundRect(RectF(tmp.left + w * 0.17f, tmp.top + w * 0.07f, tmp.left + w * 0.55f, tmp.top + w * 0.10f), w * 0.015f, w * 0.015f, paint)
        paint.color = Color.argb(0xAA, 255, 255, 255)
        canvas.drawRoundRect(RectF(tmp.left + w * 0.17f, tmp.top + w * 0.135f, tmp.right - w * 0.08f, tmp.top + w * 0.16f), w * 0.012f, w * 0.012f, paint)
        // Shortcuts and the unlock hint
        paint.color = Color.argb(0x55, 0, 0, 0)
        canvas.drawCircle(w * 0.16f, h * 0.92f, w * 0.075f, paint)
        canvas.drawCircle(w * 0.84f, h * 0.92f, w * 0.075f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(w * 0.16f, h * 0.92f, w * 0.02f, paint)
        canvas.drawCircle(w * 0.84f, h * 0.92f, w * 0.02f, paint)
        paint.color = Color.argb(0xCC, 255, 255, 255)
        tmp.set(cx - w * 0.12f, h * 0.965f, cx + w * 0.12f, h * 0.965f + w * 0.012f)
        canvas.drawRoundRect(tmp, w * 0.006f, w * 0.006f, paint)
    }

    private fun drawIcon(canvas: Canvas, x: Float, y: Float, size: Float, shape: String, color: Int) {
        val path = OeaIconController.shapePath(shape, size.toInt()) ?: Path().apply {
            addRoundRect(RectF(0f, 0f, size, size), size * 0.3f, size * 0.3f, Path.Direction.CW)
        }
        canvas.save()
        canvas.translate(x, y)
        paint.shader = LinearGradient(0f, 0f, size, size, OeaTheme.blend(color, Color.WHITE, 0.25f), OeaTheme.blend(color, Color.BLACK, 0.15f), Shader.TileMode.CLAMP)
        canvas.drawPath(path, paint)
        paint.shader = null
        paint.color = Color.argb(0xE6, 255, 255, 255)
        canvas.drawCircle(size / 2f, size / 2f, size * 0.17f, paint)
        canvas.restore()
    }

    /** Dock, search and notification surfaces in the theme's glass style. */
    private fun drawSurface(canvas: Canvas, rect: RectF, radius: Float, style: String, glass: String) {
        when (style) {
            "none" -> return
            "solid" -> {
                paint.color = Color.argb(0xD8, 18, 20, 26)
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
            else -> if (glass == "liquid") {
                paint.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, Color.argb(0x66, 255, 255, 255), Color.argb(0x26, 255, 255, 255), Shader.TileMode.CLAMP)
                canvas.drawRoundRect(rect, radius, radius, paint)
                paint.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, Color.argb(0xCC, 255, 255, 255), Color.argb(0x14, 255, 255, 255), Shader.TileMode.CLAMP)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = maxOf(1f, density * 0.9f)
                canvas.drawRoundRect(rect, radius, radius, paint)
                paint.style = Paint.Style.FILL
                paint.shader = null
            } else {
                paint.color = Color.argb(0x40, 255, 255, 255)
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
        }
    }

    companion object {
        private val main = Handler(Looper.getMainLooper())
        private val loader = Executors.newFixedThreadPool(2) { task -> Thread(task, "OeaThemePreview").apply { isDaemon = true } }
    }
}
