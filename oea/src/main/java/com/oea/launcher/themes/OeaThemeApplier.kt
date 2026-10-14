package com.oea.launcher.themes

import android.app.WallpaperManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.oea.launcher.data.OeaDataStore
import com.oea.launcher.icons.OeaIconController
import com.oea.launcher.ui.OeaTheme
import java.util.concurrent.Executors

/**
 * Applies themes and wallpapers.
 *
 * Only appearance settings change: wallpapers, OEA's colours, icon shape,
 * dock and glass style and page motion. The Home layout (pages, icons,
 * folders, app pairs, widgets, dock contents), the grid and every other
 * personal setting live in separate stores that this class never writes.
 */
object OeaThemeApplier {
    enum class Target(val label: String) { BOTH("Home & lock screen"), HOME("Home screen"), LOCK("Lock screen") }

    data class Options(
        val wallpapers: Boolean = true,
        val colors: Boolean = true,
        val style: Boolean = true,
        val target: Target = Target.BOTH,
    )

    data class Result(val homeOk: Boolean?, val lockOk: Boolean?, val message: String)

    private const val TAG = "OeaThemeApplier"
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "OeaThemeApply").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    fun applyTheme(context: Context, theme: OeaThemeSpec, options: Options, done: (Result) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            var homeOk: Boolean? = null
            var lockOk: Boolean? = null
            if (options.wallpapers) {
                val pair = setWallpapers(app, theme.home, theme.lock, options.target)
                homeOk = pair.first
                lockOk = pair.second
            }
            main.post {
                val store = OeaDataStore.get(app)
                if (options.colors) {
                    store.applyThemeColors(theme.id, theme.accentDark, theme.accentLight, theme.surfaceTint)
                    if (theme.mode == "dark" || theme.mode == "light") store.setThemeMode(theme.mode)
                }
                if (options.style) {
                    store.setGlassStyle(theme.glassStyle)
                    store.setDockStyle(theme.dockStyle)
                    store.setPageEffect(theme.pageEffect)
                    if (theme.iconShape != store.iconShape()) {
                        store.setIconShape(theme.iconShape)
                        OeaIconController.shared(app).invalidate()
                    }
                }
                OeaThemeStore.get(app).setLastApplied(theme.id)
                OeaTheme.invalidate()
                done(Result(homeOk, lockOk, summary(theme.name, homeOk, lockOk, options)))
            }
        }
    }

    /** Sets one picture as wallpaper for [target]. */
    fun applyWallpaper(context: Context, image: OeaImageRef, target: Target, done: (Result) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val (home, lock) = setWallpapers(app, image, image, target)
            main.post {
                OeaTheme.invalidate()
                done(Result(home, lock, summary(null, home, lock, Options(colors = false, style = false, target = target))))
            }
        }
    }

    /** Returns OEA to the wallpaper's own colours without touching icons or layout. */
    fun useDeviceColors(context: Context) {
        val store = OeaDataStore.get(context)
        store.setAccentMode("wallpaper")
        store.setGlassStyle(OeaDataStore.GLASS_CLASSIC)
        OeaThemeStore.get(context).setLastApplied("")
        OeaTheme.invalidate()
    }

    private fun setWallpapers(context: Context, home: OeaImageRef, lock: OeaImageRef, target: Target): Pair<Boolean?, Boolean?> {
        val wm = runCatching { WallpaperManager.getInstance(context) }.getOrNull() ?: return false to false
        if (!wm.isWallpaperSupported || !wm.isSetWallpaperAllowed) return false to false
        val size = runCatching {
            val metrics = context.resources.displayMetrics
            metrics.widthPixels to metrics.heightPixels
        }.getOrDefault(1080 to 2340)
        fun set(ref: OeaImageRef, which: Int): Boolean = runCatching {
            when (ref) {
                // Artwork OEA draws itself: render at screen size and hand Android
                // the bitmap. There is no stream to decode, so the old path would
                // simply have failed and left the lock screen untouched.
                is OeaImageRef.Generated -> {
                    val bitmap = OeaThemeImages.renderForWallpaper(context, ref, size.first, size.second) ?: return@runCatching false
                    val ok = wm.setBitmap(bitmap, null, true, which)
                    runCatching { bitmap.recycle() }
                    ok
                }
                else -> OeaThemeImages.stream(context, ref)?.use { stream -> wm.setStream(stream, null, true, which) != 0 } ?: false
            }
        }.onFailure { Log.w(TAG, "Android refused the wallpaper (which=$which)", it) }.getOrDefault(false)
        return when (target) {
            Target.HOME -> set(home, WallpaperManager.FLAG_SYSTEM) to null
            Target.LOCK -> null to set(lock, WallpaperManager.FLAG_LOCK)
            Target.BOTH -> if (home.key == lock.key) {
                val ok = set(home, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
                ok to ok
            } else {
                set(home, WallpaperManager.FLAG_SYSTEM) to set(lock, WallpaperManager.FLAG_LOCK)
            }
        }
    }

    private fun summary(name: String?, home: Boolean?, lock: Boolean?, options: Options): String {
        val failed = listOfNotNull(if (home == false) "Home" else null, if (lock == false) "lock screen" else null)
        val prefix = if (name != null) "$name applied" else "Wallpaper applied"
        return when {
            failed.isEmpty() && name != null && !options.wallpapers -> "$name colours and style applied."
            failed.isEmpty() -> "$prefix. Your apps, folders and widgets are unchanged."
            failed.size == 2 || (home == null && lock == false) || (lock == null && home == false) ->
                "Android didn't accept the wallpaper on this device." + if (name != null && (options.colors || options.style)) " Colours and style were applied." else ""
            else -> "$prefix, but Android kept its own ${failed.joinToString()} wallpaper (some phones manage the lock screen themselves)."
        }
    }
}
