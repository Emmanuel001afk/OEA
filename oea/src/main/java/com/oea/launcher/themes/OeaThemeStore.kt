package com.oea.launcher.themes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.util.LruCache
import com.oea.launcher.ui.OeaTheme
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Persistence for the Themes app: the user's own themes and the wallpaper
 * library. Imported pictures are copied (downscaled to a phone-sized image)
 * into OEA's private storage, so a theme keeps working after the original
 * photo is moved or deleted and no storage permission is needed.
 */
class OeaThemeStore private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val themesDir = File(app.filesDir, "oea_themes")
    private val wallpapersDir = File(app.filesDir, "oea_wallpapers")

    // ------------------------------------------------------------ themes

    fun customThemes(): List<OeaThemeSpec> = runCatching {
        val array = JSONArray(prefs.getString(KEY_CUSTOM, "[]") ?: "[]")
        (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::themeFrom) }
    }.getOrDefault(emptyList())

    fun allThemes(): List<OeaThemeSpec> = OeaThemeCatalog.builtIns + customThemes()

    fun find(id: String?): OeaThemeSpec? = if (id.isNullOrBlank()) null else allThemes().firstOrNull { it.id == id }

    fun saveCustom(theme: OeaThemeSpec) {
        val list = customThemes().filterNot { it.id == theme.id } + theme.copy(builtIn = false)
        writeCustom(list)
    }

    /** Deletes a custom theme and the private copies of its pictures (never shared files). */
    fun deleteCustom(id: String) {
        val theme = customThemes().firstOrNull { it.id == id } ?: return
        writeCustom(customThemes().filterNot { it.id == id })
        listOf(theme.home, theme.lock).filterIsInstance<OeaImageRef.Imported>()
            .filter { it.path.startsWith(themesDir.path) }
            .forEach { ref -> runCatching { File(ref.path).delete() } }
    }

    private fun writeCustom(list: List<OeaThemeSpec>) {
        val array = JSONArray()
        list.forEach { array.put(themeJson(it)) }
        prefs.edit().putString(KEY_CUSTOM, array.toString()).apply()
    }

    fun lastAppliedId(): String = prefs.getString(KEY_APPLIED, "") ?: ""
    fun setLastApplied(id: String) { prefs.edit().putString(KEY_APPLIED, id).apply() }

    // ------------------------------------------------------- export / import

    /**
     * Packs a theme and its pictures into one portable `.oea-theme.json`
     * string, so it can be uploaded (SAF targets Drive and friends), copied to
     * another phone, or kept as a backup. Built-in themes export too.
     */
    fun exportTheme(id: String): String? {
        val theme = find(id) ?: return null
        return runCatching {
            val out = JSONObject()
            out.put("oeaTheme", 1)
            out.put("name", theme.name); out.put("tagline", theme.tagline)
            out.put("accentDark", theme.accentDark); out.put("accentLight", theme.accentLight)
            out.put("tint", theme.surfaceTint); out.put("mode", theme.mode)
            out.put("glass", theme.glassStyle); out.put("shape", theme.iconShape)
            out.put("dock", theme.dockStyle); out.put("effect", theme.pageEffect)
            imageB64(theme.home)?.let { out.put("homeImage", it) }
            if (!theme.sharedImagery) imageB64(theme.lock)?.let { out.put("lockImage", it) }
            out.toString()
        }.onFailure { Log.w(TAG, "Could not export theme", it) }.getOrNull()
    }

    /** Base64 JPEG/PNG bytes of a picture (private copies and bundled art). */
    private fun imageB64(ref: OeaImageRef): String? = runCatching {
        val bytes: ByteArray = when (ref) {
            is OeaImageRef.Imported -> File(ref.path).takeIf { it.isFile }?.readBytes()
            is OeaImageRef.Bundled -> if (ref.resId == 0) {
                val bmp = com.oea.launcher.wallpaper.OeaArtwork.render(ref.name, 720, 1280) ?: return null
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
                bmp.recycle()
                out.toByteArray()
            } else app.resources.openRawResource(ref.resId)?.use { it.readBytes() }
            is OeaImageRef.Generated -> {
                val bmp = com.oea.launcher.wallpaper.OeaArtwork.render(ref.name, 720, 1280) ?: return null
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
                bmp.recycle()
                out.toByteArray()
            }
        } ?: return null
        // Refuse a pathological file before it becomes a giant share/upload.
        if (bytes.size > 14 * 1024 * 1024) return null
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }.getOrNull()

    /**
     * Imports a theme file made by [exportTheme]; its pictures are copied into
     * private storage so the theme survives anything happening to the file.
     * Call off the main thread.
     */
    fun importTheme(json: String): OeaThemeSpec? = runCatching {
        val o = JSONObject(json)
        if (o.optInt("oeaTheme", 0) != 1) return null
        val themeId = "t_${System.currentTimeMillis()}"
        themesDir.mkdirs()
        fun saveImage(b64Key: String, slot: String): OeaImageRef? {
            val b64 = o.optString(b64Key, "")
            if (b64.isBlank()) return null
            val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP) ?: return null
            if (bytes.isEmpty() || bytes.size > 14 * 1024 * 1024) return null
            val target = File(themesDir, "${themeId}_${slot}.jpg")
            target.writeBytes(bytes)
            return OeaImageRef.Imported(target.path)
        }
        val home = saveImage("homeImage", "home") ?: return null
        val lock = saveImage("lockImage", "lock") ?: home
        val spec = OeaThemeSpec(
            id = themeId,
            name = o.optString("name", "Imported theme"),
            tagline = o.optString("tagline", ""),
            home = home,
            lock = lock,
            accentDark = o.optInt("accentDark", Color.rgb(138, 180, 248)),
            accentLight = o.optInt("accentLight", Color.rgb(26, 102, 220)),
            surfaceTint = o.optInt("tint", 0),
            mode = o.optString("mode", "system"),
            glassStyle = o.optString("glass", "classic"),
            iconShape = o.optString("shape", "system"),
            dockStyle = o.optString("dock", "glass"),
            pageEffect = o.optString("effect", "classic"),
            builtIn = false,
        )
        saveCustom(spec)
        spec
    }.onFailure { Log.w(TAG, "Could not import theme", it) }.getOrNull()

    private fun themeJson(t: OeaThemeSpec) = JSONObject().apply {
        put("id", t.id); put("name", t.name); put("tagline", t.tagline)
        put("home", t.home.key); put("lock", t.lock.key)
        put("accentDark", t.accentDark); put("accentLight", t.accentLight); put("tint", t.surfaceTint)
        put("mode", t.mode); put("glass", t.glassStyle); put("shape", t.iconShape)
        put("dock", t.dockStyle); put("effect", t.pageEffect)
    }

    private fun themeFrom(o: JSONObject): OeaThemeSpec? {
        val home = OeaImageRef.parse(o.optString("home")) ?: return null
        val lock = OeaImageRef.parse(o.optString("lock")) ?: home
        return OeaThemeSpec(
            id = o.optString("id").ifBlank { return null },
            name = o.optString("name").ifBlank { "My theme" },
            tagline = o.optString("tagline"),
            home = home,
            lock = lock,
            accentDark = o.optInt("accentDark", Color.rgb(138, 180, 248)),
            accentLight = o.optInt("accentLight", Color.rgb(26, 102, 220)),
            surfaceTint = o.optInt("tint", 0),
            mode = o.optString("mode", "system"),
            glassStyle = o.optString("glass", "classic"),
            iconShape = o.optString("shape", "system"),
            dockStyle = o.optString("dock", "glass"),
            pageEffect = o.optString("effect", "classic"),
            builtIn = false
)
    }

    // ------------------------------------------------------- wallpapers

    /** Pictures the user added, newest first. */
    fun importedWallpapers(): List<OeaImageRef.Imported> =
        (wallpapersDir.listFiles { file -> file.isFile && file.name.endsWith(".jpg") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .map { OeaImageRef.Imported(it.path) }

    fun libraryImages(): List<OeaImageRef> = importedWallpapers() + OeaThemeCatalog.bundledImages

    fun deleteImported(ref: OeaImageRef.Imported) {
        if (!ref.path.startsWith(wallpapersDir.path)) return
        runCatching { File(ref.path).delete() }
        OeaThemeImages.evict(ref.key)
    }

    /** Copies a picked picture into the wallpaper library; call off the main thread. */
    fun importToLibrary(uri: Uri): OeaImageRef.Imported? = importInto(uri, wallpapersDir, "wp_${System.currentTimeMillis()}")

    /** Copies a picture into a custom theme's private storage; call off the main thread. */
    fun importForTheme(uri: Uri, themeId: String, slot: String): OeaImageRef.Imported? =
        importInto(uri, themesDir, "${themeId}_${slot}_${System.currentTimeMillis()}")

    /** Makes a theme-private copy of a library picture so deleting it from the library never breaks the theme. */
    fun copyForTheme(ref: OeaImageRef, themeId: String, slot: String): OeaImageRef {
        if (ref !is OeaImageRef.Imported || ref.path.startsWith(themesDir.path)) return ref
        return runCatching {
            themesDir.mkdirs()
            val target = File(themesDir, "${themeId}_${slot}_${System.currentTimeMillis()}.jpg")
            File(ref.path).copyTo(target, overwrite = true)
            OeaImageRef.Imported(target.path) as OeaImageRef
        }.getOrDefault(ref)
    }

    private fun importInto(uri: Uri, dir: File, name: String): OeaImageRef.Imported? = runCatching {
        dir.mkdirs()
        val bitmap = decodeScaled(MAX_W, MAX_H) { app.contentResolver.openInputStream(uri) } ?: return null
        val target = File(dir, "$name.jpg")
        FileOutputStream(target).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out) }
        bitmap.recycle()
        OeaImageRef.Imported(target.path)
    }.onFailure { Log.w(TAG, "Could not import picture", it) }.getOrNull()

    companion object {
        private const val TAG = "OeaThemeStore"
        private const val PREFS = "oea_themes"
        private const val KEY_CUSTOM = "custom_themes"
        private const val KEY_APPLIED = "last_applied_theme"
        /** Phone-sized copies: enough for QHD screens, far smaller than camera photos. */
        const val MAX_W = 1440
        const val MAX_H = 3200

        @Volatile private var instance: OeaThemeStore? = null
        fun get(context: Context): OeaThemeStore = instance ?: synchronized(this) {
            instance ?: OeaThemeStore(context).also { instance = it }
        }

        /** Decodes at most [maxW]×[maxH] (keeping aspect) without loading the full picture. */
        fun decodeScaled(maxW: Int, maxH: Int, source: () -> InputStream?): Bitmap? {
            // A bounds-only decode always returns null (it reads the size and
            // stops), so the result must not be used as "failed". The size is
            // read from the options; a stream that cannot be opened is the only
            // real failure here.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val opened = source() ?: return null
            opened.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxW || bounds.outHeight / (sample * 2) >= maxH) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = source()?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
            val scale = minOf(maxW / decoded.width.toFloat(), maxH / decoded.height.toFloat(), 1f)
            if (scale >= 0.999f) return decoded
            val scaled = Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
            if (scaled !== decoded) decoded.recycle()
            return scaled
        }
    }
}

/** Picture loading for previews and applying, with a small thumbnail cache. */
object OeaThemeImages {
    private val cache = object : LruCache<String, Bitmap>(((Runtime.getRuntime().maxMemory() / 16).coerceAtMost(24L * 1024 * 1024)).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * A stream of the picture's bytes. Generated artwork has no bytes — it is
     * drawn — so callers that need pixels use [thumbnail] or [renderForWallpaper].
     */
    fun stream(context: Context, ref: OeaImageRef): InputStream? = when (ref) {
        // A drawn picture has no bytes; callers ask thumbnail()/renderForWallpaper().
        is OeaImageRef.Bundled -> if (ref.resId == 0) null else context.resources.openRawResource(ref.resId)
        is OeaImageRef.Imported -> File(ref.path).takeIf { it.isFile }?.inputStream()
        // Generated pictures are drawn, not decoded; callers that need pixels use
        // thumbnail(), and the wallpaper applier draws them itself.
        is OeaImageRef.Generated -> null
    }

    /** A preview-sized bitmap (call off the main thread). */
    fun thumbnail(context: Context, ref: OeaImageRef, maxW: Int = 360, maxH: Int = 780): Bitmap? {
        val key = "${ref.key}@$maxW"
        cache.get(key)?.let { return it }
        val bitmap = when {
            ref is OeaImageRef.Generated -> com.oea.launcher.wallpaper.OeaArtwork.render(ref.name, maxW, maxH)
            ref is OeaImageRef.Bundled && ref.resId == 0 -> com.oea.launcher.wallpaper.OeaArtwork.render(ref.name, maxW, maxH)
            else -> runCatching { OeaThemeStore.decodeScaled(maxW, maxH) { stream(context, ref) } }.getOrNull()
        } ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    /**
     * Renders an image at wallpaper size. Bundled and imported pictures go through
     * Android's decoder; generated artwork is drawn at exactly this size, which is
     * why the wallpaper applier asks here instead of opening a stream.
     */
    fun renderForWallpaper(context: Context, ref: OeaImageRef, width: Int, height: Int): Bitmap? = when {
        ref is OeaImageRef.Generated -> com.oea.launcher.wallpaper.OeaArtwork.render(ref.name, width, height)
        ref is OeaImageRef.Bundled && ref.resId == 0 -> com.oea.launcher.wallpaper.OeaArtwork.render(ref.name, width, height)
        else -> runCatching { OeaThemeStore.decodeScaled(width, height) { stream(context, ref) } }.getOrNull()
    }

    fun cached(ref: OeaImageRef, maxW: Int = 360): Bitmap? = cache.get("${ref.key}@$maxW")

    fun evict(key: String) {
        cache.snapshot().keys.filter { it.startsWith("$key@") }.forEach { cache.remove(it) }
    }

    /**
     * Theme colours from a picture: the most colourful frequent hue becomes
     * the accent (a light tone for dark panels and a deep tone for light
     * panels) and a dark version of it tints the panels.
     */
    fun colorsFrom(bitmap: Bitmap): Triple<Int, Int, Int> {
        val small = Bitmap.createScaledBitmap(bitmap, 48, 96, true)
        val buckets = FloatArray(24)
        val sat = FloatArray(24)
        var avgR = 0L; var avgG = 0L; var avgB = 0L
        val count = small.width * small.height
        for (y in 0 until small.height) for (x in 0 until small.width) {
            val c = small.getPixel(x, y)
            avgR += Color.red(c); avgG += Color.green(c); avgB += Color.blue(c)
            val hsl = OeaTheme.hsl(c)
            if (hsl[1] < 0.18f || hsl[2] < 0.12f || hsl[2] > 0.92f) continue
            val bucket = ((hsl[0] / 15f).toInt()).coerceIn(0, 23)
            val weight = hsl[1] * (1f - kotlin.math.abs(hsl[2] - 0.5f))
            buckets[bucket] += weight
            sat[bucket] = maxOf(sat[bucket], hsl[1])
        }
        if (small !== bitmap) small.recycle()
        val best = buckets.indices.maxByOrNull { buckets[it] } ?: 0
        val average = Color.rgb((avgR / count).toInt(), (avgG / count).toInt(), (avgB / count).toInt())
        if (buckets[best] <= 0.5f) {
            // A greyscale picture: keep a neutral, readable accent.
            return Triple(Color.rgb(215, 220, 228), Color.rgb(60, 67, 80), OeaTheme.blend(average, Color.BLACK, 0.55f))
        }
        val hue = best * 15f + 7.5f
        val s = sat[best].coerceIn(0.45f, 0.85f)
        val accentDark = OeaTheme.hslToColor(hue, s, 0.74f)
        val accentLight = OeaTheme.hslToColor(hue, s, 0.38f)
        val tint = OeaTheme.hslToColor(hue, (s * 0.6f).coerceAtMost(0.55f), 0.22f)
        return Triple(accentDark, accentLight, tint)
    }
}
