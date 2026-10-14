package com.oea.launcher.icons

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import com.oea.launcher.data.OeaDataStore
import com.oea.launcher.model.OeaAppInfo
import com.oea.launcher.ui.OeaTheme
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * OEA icon engine.
 *
 * - Resolves icons from installed ADW/Nova-style icon packs, falling back to the
 *   app's real launcher icon.
 * - Reshapes adaptive icons with the chosen OEA icon shape (circle, squircle,
 *   rounded square, square, teardrop) by masking their real layers, the same way
 *   native launchers do, with anti-aliased edges.
 * - Android 13+ themed icons: tints the app's monochrome layer with OEA colours.
 * - Caches rendered bitmaps process-wide so Home, the drawer and OEA Settings
 *   share one warm cache, and can preload icons off the main thread.
 */
class OeaIconController(private val context: Context) {
    data class IconPack(val packageName: String, val label: String)

    private val packageManager = context.packageManager
    private val store = OeaDataStore.get(context)

    fun installedPacks(): List<IconPack> {
        val candidates = linkedMapOf<String, String>()
        val actions = listOf(
            "org.adw.launcher.THEMES",
            "com.novalauncher.THEME",
            "com.anddoes.launcher.THEME",
            "com.teslacoilsw.launcher.THEME",
            "com.dlto.atom.launcher.THEME",
        )
        actions.forEach { action ->
            runCatching {
                packageManager.queryIntentActivities(Intent(action), PackageManager.MATCH_ALL)
                    .forEach { info ->
                        val app = info.activityInfo.applicationInfo
                        if (app.packageName != context.packageName) {
                            candidates[app.packageName] = app.loadLabel(packageManager).toString()
                        }
                    }
            }
        }
        // Some packs expose appfilter.xml without advertising a theme-picker activity.
        runCatching {
            packageManager.getInstalledApplications(PackageManager.GET_META_DATA).forEach { app ->
                if (app.packageName == context.packageName || candidates.containsKey(app.packageName)) return@forEach
                val hasFilter = runCatching {
                    val res = packageManager.getResourcesForApplication(app.packageName)
                    res.getIdentifier("appfilter", "xml", app.packageName) != 0 ||
                        res.getIdentifier("app_filter", "xml", app.packageName) != 0
                }.getOrDefault(false)
                if (hasFilter) candidates[app.packageName] = app.loadLabel(packageManager).toString()
            }
        }
        return candidates.map { IconPack(it.key, it.value) }.sortedBy { it.label.lowercase() }
    }

    /** Styled icon for a launcher activity (shape, pack and themed treatment applied). */
    fun icon(packageName: String, className: String? = null): Drawable? {
        val bitmap = iconBitmap(packageName, className) ?: return null
        return BitmapDrawable(context.resources, bitmap)
    }

    fun iconBitmap(packageName: String, className: String? = null): Bitmap? {
        val style = currentStyle()
        val key = style.key + "|" + packageName + "/" + className.orEmpty()
        rendered.get(key)?.let { return it }
        // Rendered icons persist across process restarts: after Android kills
        // OEA in the background, Home comes back with its icons immediately
        // instead of re-rendering every icon on the main thread.
        val disk = diskName(key, packageName, style.pack)
        if (disk != null) readDisk(disk)?.let { cached ->
            rendered.put(key, cached)
            return cached
        }
        val base = rawIcon(packageName, className, style.pack) ?: return null
        val bitmap = runCatching { render(base.drawable, base.fromPack, style) }.getOrNull() ?: return null
        rendered.put(key, bitmap)
        if (disk != null) writeDisk(disk, bitmap)
        return bitmap
    }

    /** Warms the cache for [apps]; call from a background thread. */
    fun preload(apps: List<Pair<String, String>>) {
        apps.forEach { (pkg, cls) -> runCatching { iconBitmap(pkg, cls) } }
    }

    /**
     * Records each app's install stamp. Only stamped apps use the persistent
     * icon cache, so an icon is never reused after its app was updated.
     */
    fun updateStamps(apps: List<OeaAppInfo>) {
        apps.forEach { if (it.stamp != 0L) stamps[it.packageName] = it.stamp }
    }

    /** Drops rendered icons (e.g. after a shape, size, pack or theme change). */
    /**
     * Under memory pressure Home trades a little icon warmth for headroom: half
     * the bitmap cache and the packed raw states go, and the LruCache limit is
     * lowered until the next full invalidate.
     */
    fun trimMemory(aggressive: Boolean) {
        runCatching {
            if (aggressive) {
                rendered.evictAll()
                rawStates.clear()
                packMappings.clear()
            } else {
                rendered.trimToSize(rendered.maxSize() / 2)
            }
        }
    }

    fun invalidate() {
        rendered.evictAll()
    }

    fun invalidatePackage(packageName: String) {
        rawStates.keys.filter { it.contains("|$packageName/") }.forEach { rawStates.remove(it) }
        rendered.snapshot().keys.filter { it.contains("|$packageName/") }.forEach { rendered.remove(it) }
        stamps.remove(packageName)
        // An updated icon pack changes every icon it themes.
        if (packageName == store.iconPackPackage()) {
            packMappings.remove(packageName)
            rawStates.clear()
            rendered.evictAll()
        }
        val dir = diskDir()
        diskExecutor.execute {
            runCatching { dir.listFiles { file -> file.name.startsWith(packageName + "_") }?.forEach { it.delete() } }
        }
    }

    // ------------------------------------------------------------ disk cache

    private fun diskDir(): File = File(context.cacheDir, DISK_DIR)

    private fun diskName(key: String, packageName: String, pack: String?): String? {
        val stamp = stamps[packageName] ?: return null
        val packStamp = pack?.let { stamps[it] } ?: 0L
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-1").digest("$key|$stamp|$packStamp".toByteArray())
            packageName + "_" + digest.joinToString("") { "%02x".format(it) }.take(24) + ".png"
        }.getOrNull()
    }

    private fun readDisk(name: String): Bitmap? = runCatching {
        val file = File(diskDir(), name)
        if (!file.isFile) null else BitmapFactory.decodeFile(file.path)
    }.getOrNull()

    private fun writeDisk(name: String, bitmap: Bitmap) {
        val dir = diskDir()
        diskExecutor.execute {
            runCatching {
                if (!dir.isDirectory) dir.mkdirs()
                val target = File(dir, name)
                if (target.isFile) return@runCatching
                val temp = File(dir, "$name.tmp")
                FileOutputStream(temp).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
                if (!temp.renameTo(target)) temp.delete()
                if (++diskWrites % 64 == 0) pruneDisk(dir)
            }
        }
    }

    /** Keeps the persistent cache bounded (old styles and removed apps age out). */
    private fun pruneDisk(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        if (files.size <= DISK_MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - DISK_KEEP_FILES).forEach { it.delete() }
    }

    // ------------------------------------------------------------------ style

    private data class Style(val key: String, val shape: String, val themed: Boolean, val pack: String?, val sizePx: Int, val themedBg: Int, val themedFg: Int)

    private fun currentStyle(): Style {
        val shape = store.iconShape()
        val themed = store.themedIcons() && Build.VERSION.SDK_INT >= 33
        val pack = store.iconPackPackage()
        val sizePx = (maxOf(store.iconSizeDp(), 48) * context.resources.displayMetrics.density).toInt().coerceAtLeast(48)
        var bg = 0
        var fg = 0
        if (themed) {
            val palette = OeaTheme.resolve(context)
            bg = if (palette.dark) OeaTheme.blend(palette.background, palette.accent, 0.20f) else palette.accentSoft
            fg = if (palette.dark) OeaTheme.blend(Color.WHITE, palette.accent, 0.35f) else palette.onAccentSoft
        }
        return Style("$shape|$themed|$pack|$sizePx|$bg|$fg", shape, themed, pack, sizePx, bg, fg)
    }

    private class Raw(val drawable: Drawable, val fromPack: Boolean)

    private fun rawIcon(packageName: String, className: String?, pack: String?): Raw? {
        val key = "${pack.orEmpty()}|$packageName/${className.orEmpty()}"
        rawStates[key]?.let { state ->
            val d = state.newDrawable()?.mutate() ?: return@let
            return Raw(d, rawFromPack[key] == true)
        }
        val packDrawable = loadPackIcon(pack, packageName, className)
        val drawable = packDrawable
            ?: runCatching {
                if (!className.isNullOrBlank()) packageManager.getActivityIcon(ComponentName(packageName, className))
                else packageManager.getApplicationIcon(packageName)
            }.getOrNull()
            ?: runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull()
            ?: return null
        drawable.constantState?.let {
            rawStates[key] = it
            rawFromPack[key] = packDrawable != null
        }
        return Raw(drawable.mutate(), packDrawable != null)
    }

    private fun render(drawable: Drawable, fromPack: Boolean, style: Style): Bitmap {
        val s = style.sizePx
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val adaptive = drawable as? AdaptiveIconDrawable
        if (fromPack) {
            drawFitted(canvas, drawable, s)
            return out
        }
        if (adaptive != null) {
            adaptive.setBounds(0, 0, s, s)
            val mask = shapePath(style.shape, s) ?: adaptive.iconMask
            val mono = if (style.themed && Build.VERSION.SDK_INT >= 33) adaptive.monochrome else null
            val layers = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val layerCanvas = Canvas(layers)
            val extra = s / 4
            if (mono != null) {
                layerCanvas.drawColor(style.themedBg)
                mono.mutate()
                mono.setBounds(-extra, -extra, s + extra, s + extra)
                mono.setTint(style.themedFg)
                mono.draw(layerCanvas)
            } else {
                adaptive.background?.let { bg ->
                    bg.setBounds(-extra, -extra, s + extra, s + extra)
                    bg.draw(layerCanvas)
                }
                adaptive.foreground?.let { fg ->
                    fg.setBounds(-extra, -extra, s + extra, s + extra)
                    fg.draw(layerCanvas)
                }
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                shader = BitmapShader(layers, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
            canvas.drawPath(mask, paint)
            layers.recycle()
            return out
        }
        // Legacy (non-adaptive) icon.
        val shape = shapePath(style.shape, s)
        if (shape == null) {
            drawFitted(canvas, drawable, s)
            return out
        }
        val probe = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        drawFitted(Canvas(probe), drawable, s)
        val inset = (s * 0.06f).toInt().coerceAtLeast(2)
        val fullBleed = listOf(
            probe.getPixel(inset, inset), probe.getPixel(s - 1 - inset, inset),
            probe.getPixel(inset, s - 1 - inset), probe.getPixel(s - 1 - inset, s - 1 - inset),
        ).all { Color.alpha(it) > 230 }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (fullBleed) {
            paint.shader = BitmapShader(probe, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            canvas.drawPath(shape, paint)
        } else {
            // Wrap the legacy icon in the shape so every icon on Home matches.
            paint.color = if (style.themed) style.themedBg else Color.WHITE
            canvas.drawPath(shape, paint)
            val scaled = (s * 0.72f).toInt()
            val offset = (s - scaled) / 2
            canvas.save()
            canvas.translate(offset.toFloat(), offset.toFloat())
            canvas.scale(scaled / s.toFloat(), scaled / s.toFloat())
            canvas.drawBitmap(probe, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
        }
        probe.recycle()
        return out
    }

    private fun drawFitted(canvas: Canvas, drawable: Drawable, s: Int) {
        val w = drawable.intrinsicWidth
        val h = drawable.intrinsicHeight
        if (w <= 0 || h <= 0 || w == h) {
            drawable.setBounds(0, 0, s, s)
        } else {
            val scale = s / maxOf(w, h).toFloat()
            val dw = (w * scale).toInt()
            val dh = (h * scale).toInt()
            drawable.setBounds((s - dw) / 2, (s - dh) / 2, (s - dw) / 2 + dw, (s - dh) / 2 + dh)
        }
        drawable.draw(canvas)
    }

    private fun loadPackIcon(packPackage: String?, packageName: String, className: String?): Drawable? {
        if (packPackage.isNullOrBlank()) return null
        val resources = runCatching { packageManager.getResourcesForApplication(packPackage) }.getOrNull() ?: return null
        val mapping = mappings(packPackage, resources)
        val component = className?.let { ComponentName(packageName, it).flattenToString() }
        val drawableName = (component?.let(mapping::get))
            ?: mapping.entries.firstOrNull { it.key.startsWith("$packageName/") }?.value
            ?: return null
        val id = resources.getIdentifier(drawableName, "drawable", packPackage)
            .takeIf { it != 0 }
            ?: resources.getIdentifier(drawableName, "mipmap", packPackage)
        if (id == 0) return null
        return runCatching { resources.getDrawable(id, null) }.getOrNull()
    }

    private fun mappings(packPackage: String, resources: Resources): Map<String, String> {
        packMappings[packPackage]?.let { return it }
        val xmlId = resources.getIdentifier("appfilter", "xml", packPackage)
            .takeIf { it != 0 }
            ?: resources.getIdentifier("app_filter", "xml", packPackage)
        if (xmlId == 0) {
            packMappings[packPackage] = emptyMap()
            return emptyMap()
        }
        val result = linkedMapOf<String, String>()
        runCatching {
            resources.getXml(xmlId).use { parser ->
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && parser.name == "item") {
                        val rawComponent = parser.getAttributeValue(null, "component")
                        val drawable = parser.getAttributeValue(null, "drawable")
                        if (!rawComponent.isNullOrBlank() && !drawable.isNullOrBlank()) {
                            val flattened = rawComponent.removePrefix("ComponentInfo{").removeSuffix("}")
                            val canonical = ComponentName.unflattenFromString(flattened)?.flattenToString() ?: flattened
                            result[canonical] = drawable
                        }
                    }
                    event = parser.next()
                }
            }
        }
        packMappings[packPackage] = result
        return result
    }

    companion object {
        @Volatile private var sharedInstance: OeaIconController? = null

        fun shared(context: Context): OeaIconController = sharedInstance ?: synchronized(this) {
            sharedInstance ?: OeaIconController(context.applicationContext).also { sharedInstance = it }
        }

        private const val DISK_DIR = "oea_icons"
        private const val DISK_MAX_FILES = 2400
        private const val DISK_KEEP_FILES = 1600
        private val stamps = ConcurrentHashMap<String, Long>()
        @Volatile private var diskWrites = 0
        private val diskExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "OeaIconDisk").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }
        private val rawStates = ConcurrentHashMap<String, Drawable.ConstantState>()
        private val rawFromPack = ConcurrentHashMap<String, Boolean>()
        private val packMappings = ConcurrentHashMap<String, Map<String, String>>()
        private val rendered = object : LruCache<String, Bitmap>(
            (Runtime.getRuntime().maxMemory() / 7).coerceAtMost(56L * 1024 * 1024).toInt(),
        ) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }

        /** Shape outline used by icons and folder tiles; null means the device's own mask. */
        fun shapePath(shape: String, size: Int): Path? {
            val s = size.toFloat()
            return when (shape) {
                "circle" -> Path().apply { addCircle(s / 2f, s / 2f, s / 2f, Path.Direction.CW) }
                "rounded" -> Path().apply { addRoundRect(RectF(0f, 0f, s, s), s * 0.24f, s * 0.24f, Path.Direction.CW) }
                "square" -> Path().apply { addRoundRect(RectF(0f, 0f, s, s), s * 0.08f, s * 0.08f, Path.Direction.CW) }
                "teardrop" -> Path().apply {
                    val r = s / 2f
                    val small = s * 0.12f
                    addRoundRect(RectF(0f, 0f, s, s), floatArrayOf(r, r, r, r, small, small, r, r), Path.Direction.CW)
                }
                "squircle" -> Path().apply {
                    val half = s / 2f
                    val steps = 96
                    for (i in 0..steps) {
                        val t = 2.0 * Math.PI * i / steps
                        val c = cos(t)
                        val n = sin(t)
                        val x = half + half * sign(c) * abs(c).pow(0.5)
                        val y = half + half * sign(n) * abs(n).pow(0.5)
                        if (i == 0) moveTo(x.toFloat(), y.toFloat()) else lineTo(x.toFloat(), y.toFloat())
                    }
                    close()
                }
                else -> null
            }
        }

        /** The device's own adaptive-icon mask, used when OEA's shape is "system". */
        fun systemMask(size: Int): Path = AdaptiveIconDrawable(ColorDrawable(Color.WHITE), ColorDrawable(Color.TRANSPARENT)).run {
            setBounds(0, 0, size, size)
            Path(iconMask)
        }
    }
}
