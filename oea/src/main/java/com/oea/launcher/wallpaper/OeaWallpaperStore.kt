package com.oea.launcher.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.util.DisplayMetrics
import android.util.Log
import com.oea.engine.core.OeaGuard
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The user's wallpaper choices and the one place OEA talks to Android's
 * wallpaper service.
 *
 * OEA applies wallpapers through Android (`SET_WALLPAPER` is granted at install
 * time, so no prompt and no special access is involved), which means the bundled
 * scenes work exactly like a photo or the stock picker: they cover Home, and the
 * lock screen when asked for. Rendering happens on a worker thread and the
 * result is verified, so a device that refuses the write says so instead of
 * silently doing nothing.
 */
object OeaWallpaperStore {
    private const val TAG = "OeaWallpaper"
    private const val PREFS = "oea_wallpaper"

    private const val KEY_SCENE = "scene_id"
    private const val KEY_TINT = "theme_follows_scene"
    private const val KEY_DIM = "dim_percent"
    private const val KEY_LAST_APPLIED = "last_applied"
    private const val KEY_LAST_TARGET = "last_target"
    private const val KEY_SYSTEM_WALLPAPER_ID = "system_wallpaper_id"

    /** What a scene should be applied to. */
    enum class Target(val label: String) {
        HOME("Home screen"),
        LOCK("Lock screen"),
        BOTH("Home and lock screen"),
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------- state

    /** The bundled scene in use, or null when the user keeps Android's own wallpaper. */
    fun scene(context: Context): OeaWallpaper? = OeaWallpaper.byId(prefs(context).getString(KEY_SCENE, null))

    fun setScene(context: Context, scene: OeaWallpaper?) {
        prefs(context).edit().apply {
            if (scene == null) remove(KEY_SCENE) else putString(KEY_SCENE, scene.id)
        }.apply()
    }

    /** True when the launcher theme should take its accent from the bundled scene. */
    fun themeFollowsScene(context: Context): Boolean = prefs(context).getBoolean(KEY_TINT, true)

    fun setThemeFollowsScene(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TINT, enabled).apply()
    }

    /** 0 = as drawn, 60 = noticeably darker (helps icons on very busy scenes). */
    fun dimPercent(context: Context): Int = prefs(context).getInt(KEY_DIM, 0).coerceIn(0, 60)

    fun setDimPercent(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_DIM, value.coerceIn(0, 60)).apply()
    }

    fun lastApplied(context: Context): String? = prefs(context).getString(KEY_LAST_APPLIED, null)

    fun lastTarget(context: Context): Target? =
        prefs(context).getString(KEY_LAST_TARGET, null)?.let { name -> Target.entries.firstOrNull { it.name == name } }

    /** True when the chosen scene is (still) what Android shows on Home. */
    fun coversHome(context: Context): Boolean {
        if (scene(context) == null) return false
        return lastTarget(context) != Target.LOCK
    }

    /**
     * Drops OEA's scene when Android's Home wallpaper is no longer the one OEA
     * applied — the user changed it in the stock picker or another app. Without
     * this, the theme would keep following a wallpaper that is not on screen.
     */
    fun syncWithSystem(context: Context) {
        val app = context.applicationContext
        val recorded = prefs(app).getInt(KEY_SYSTEM_WALLPAPER_ID, -1)
        if (recorded < 0 || !coversHome(app)) return
        val current = runCatching { WallpaperManager.getInstance(app).getWallpaperId(WallpaperManager.FLAG_SYSTEM) }.getOrDefault(-1)
        if (current > 0 && current != recorded) {
            // Somebody else owns Home's wallpaper now; OEA stops tinting from it.
            prefs(app).edit().remove(KEY_SYSTEM_WALLPAPER_ID).apply()
            setScene(app, null)
        }
    }

    // ------------------------------------------------------------------ apply

    /** Result of an apply request, so the UI can be precise instead of optimistic. */
    sealed class Result(val message: String) {
        /** A bundled scene is now the wallpaper. */
        class Applied(val scene: OeaWallpaper, val target: Target) : Result("${scene.label} is now the ${target.label.lowercase()} wallpaper")

        /** The user's own photo is now the wallpaper; OEA's scene is no longer in use. */
        class Photo(val target: Target) : Result("Your photo is now the ${target.label.lowercase()} wallpaper")

        class Failed(val why: String) : Result(why)
    }

    private val applying = AtomicBoolean(false)

    /** True while a wallpaper is being rendered and written. */
    val isApplying: Boolean get() = applying.get()

    /**
     * Renders and applies [scene] on a worker thread, then reports what happened
     * on the main thread. Safe to call with a bitmap-heavy scene: the render is
     * bounded to the screen's own resolution and the bitmap is recycled.
     */
    fun apply(context: Context, scene: OeaWallpaper, target: Target, onDone: (Result) -> Unit) {
        val app = context.applicationContext
        if (!applying.compareAndSet(false, true)) {
            onDone(Result.Failed("A wallpaper is already being applied."))
            return
        }
        OeaGuard.start("oea-wallpaper-apply", "wallpaper") {
            val result = OeaGuard.call("wallpaper:apply", Result.Failed("Android could not apply that wallpaper.") as Result) {
                val size = wallpaperSize(app)
                val bitmap = OeaWallpaperRenderer.render(scene, size.first, size.second)
                val dimmed = applyDim(app, bitmap, scene)
                val ok = write(app, dimmed, target)
                if (dimmed !== bitmap) runCatching { dimmed.recycle() }
                runCatching { bitmap.recycle() }
                if (ok) {
                    setScene(app, scene)
                    prefs(app).edit()
                        .putString(KEY_LAST_APPLIED, scene.id)
                        .putString(KEY_LAST_TARGET, target.name)
                        .apply()
                    revealOnHome(app, target)
                    Result.Applied(scene, target)
                } else {
                    Result.Failed("Android refused the wallpaper. It may be restricted by the device's wallpaper settings.")
                }
            }
            applying.set(false)
            OeaGuard.post("wallpaper:result", android.os.Handler(android.os.Looper.getMainLooper())) { onDone(result) }
        }
    }

    /** Re-applies the scene in use (used after a dim change or a device rotation). */
    fun reapply(context: Context, target: Target? = null, onDone: (Result) -> Unit) {
        val scene = scene(context) ?: return onDone(Result.Failed("No OEA wallpaper is selected."))
        apply(context, scene, target ?: lastTarget(context) ?: Target.HOME, onDone)
    }

    /**
     * A wallpaper was just written to Android through [target]. When it covers
     * Home, Home must stop drawing its own backdrop (a live Tide/Depth/Ink
     * scene or an OEA still scene), or the freshly applied wallpaper would sit
     * invisibly behind it — the exact "I applied a theme but Home did not
     * change" report. The user can always turn a live scene back on; the last
     * explicit choice wins.
     */
    fun revealOnHome(context: Context, target: Target) {
        if (target == Target.LOCK) return
        val app = context.applicationContext
        val store = com.oea.launcher.data.OeaDataStore.get(app)
        if (store.wallpaperSource() == com.oea.launcher.data.OeaDataStore.WALLPAPER_OEA) {
            store.setHomeScene(null)
            store.setWallpaperSource(com.oea.launcher.data.OeaDataStore.WALLPAPER_SYSTEM)
        }
        com.oea.launcher.ui.OeaTheme.invalidate()
    }

    /** Applies a user-picked photo. Fails cleanly when the image cannot be read. */
    fun applyBitmap(context: Context, bitmap: Bitmap, target: Target, onDone: (Result) -> Unit) {
        val app = context.applicationContext
        if (!applying.compareAndSet(false, true)) {
            onDone(Result.Failed("A wallpaper is already being applied."))
            return
        }
        OeaGuard.start("oea-wallpaper-photo", "wallpaper") {
            val ok = OeaGuard.call("wallpaper:photo", false) { write(app, bitmap, target) }
            applying.set(false)
            // A photo is not a bundled scene, so the theme stops following one.
            if (ok) {
                setScene(app, null)
                revealOnHome(app, target)
            }
            OeaGuard.post("wallpaper:result", android.os.Handler(android.os.Looper.getMainLooper())) {
                onDone(if (ok) Result.Photo(target) else Result.Failed("Android could not apply that image."))
            }
        }
    }

    /** The bitmap Android should receive: the rendered scene plus the dim choice. */
    private fun applyDim(context: Context, bitmap: Bitmap, scene: OeaWallpaper): Bitmap {
        val percent = dimPercent(context)
        if (percent <= 0) return bitmap
        return runCatching {
            val dimmed = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(dimmed)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            val paint = android.graphics.Paint()
            paint.color = android.graphics.Color.argb((percent * 2.55f).toInt().coerceIn(0, 255), 0, 0, 0)
            canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), paint)
            dimmed
        }.getOrDefault(bitmap)
    }

    private fun write(context: Context, bitmap: Bitmap, target: Target): Boolean {
        val manager = WallpaperManager.getInstance(context)
        val flags = when (target) {
            Target.HOME -> WallpaperManager.FLAG_SYSTEM
            Target.LOCK -> WallpaperManager.FLAG_LOCK
            Target.BOTH -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
        }
        val written = runCatching {
            manager.setBitmap(bitmap, null, true, flags)
            true
        }.getOrElse { error ->
            Log.w(TAG, "Wallpaper write failed", error)
            false
        }
        if (!written) return false
        // Remember which system wallpaper OEA produced, so [syncWithSystem] can
        // tell the difference between OEA's own write and a later change by the user.
        if (target != Target.LOCK) {
            runCatching { manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM) }
                .getOrNull()
                ?.let { id -> if (id > 0) prefs(context).edit().putInt(KEY_SYSTEM_WALLPAPER_ID, id).apply() }
        }
        return true
    }

    /** Render size: the screen's own pixels, capped so a huge phone cannot exhaust memory. */
    private fun wallpaperSize(context: Context): Pair<Int, Int> {
        val metrics: DisplayMetrics = context.resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(720)
        val height = metrics.heightPixels.coerceAtLeast(1280)
        val longEdge = maxOf(width, height)
        if (longEdge <= OeaWallpaperRenderer.MAX_EDGE) return width to height
        val scale = OeaWallpaperRenderer.MAX_EDGE.toFloat() / longEdge
        return (width * scale).toInt().coerceAtLeast(720) to (height * scale).toInt().coerceAtLeast(1280)
    }
}
