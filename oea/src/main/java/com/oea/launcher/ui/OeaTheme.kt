package com.oea.launcher.ui

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import com.oea.launcher.data.OeaDataStore
import com.oea.launcher.wallpaper.OeaWallpaper
import com.oea.launcher.wallpaper.OeaWallpaperStore

/**
 * One OEA colour system for Home, the app drawer, OEA Settings and Game Boost.
 *
 * Panels (drawer, sheets, settings) follow the chosen theme mode. Text that is
 * drawn directly over the wallpaper always follows the wallpaper brightness, so
 * Home stays legible on any wallpaper regardless of the panel theme.
 */
data class OeaPalette(
    val dark: Boolean,
    val accent: Int,
    val onAccent: Int,
    val accentSoft: Int,
    val onAccentSoft: Int,
    val background: Int,
    val drawer: Int,
    val card: Int,
    val cardHigh: Int,
    val stroke: Int,
    val text: Int,
    val textSecondary: Int,
    val textTertiary: Int,
    val homeText: Int,
    val homeTextSecondary: Int,
    val homeShadow: Int,
    val wallpaperLight: Boolean,
    val glass: Int,
    val glassStrong: Int,
    val glassStroke: Int,
    val scrimTop: Int,
    val scrimBottom: Int,
    val danger: Int,
    val success: Int,
    val warning: Int,
) {
    fun withAlpha(color: Int, alpha: Int): Int = OeaTheme.alpha(color, alpha)
}

object OeaTheme {
    private val FALLBACK_ACCENT_DARK = Color.rgb(138, 180, 248)
    private val FALLBACK_ACCENT_LIGHT = Color.rgb(26, 102, 220)

    fun resolve(context: Context, wallpaperLightOverride: Boolean? = null): OeaPalette {
        val store = OeaDataStore.get(context)
        val dark = when (store.themeMode()) {
            "dark" -> true
            "light" -> false
            else -> isSystemNight(context)
        }
        // When one of OEA's own wallpapers is in use, it is the wallpaper: its own
        // brightness decides Home's text colour and, if the user allows it, the
        // accent. Android's wallpaper colours would describe a different image.
        val scene = if (OeaWallpaperStore.themeFollowsScene(context) && OeaWallpaperStore.coversHome(context)) {
            OeaWallpaperStore.scene(context)
        } else {
            null
        }
        val wallpaperLight = wallpaperLightOverride ?: scene?.light ?: wallpaperIsLight(context) ?: false
        val accent = when (store.accentMode()) {
            "blue" -> if (dark) FALLBACK_ACCENT_DARK else FALLBACK_ACCENT_LIGHT
            "teal" -> if (dark) Color.rgb(102, 217, 204) else Color.rgb(0, 121, 107)
            "violet" -> if (dark) Color.rgb(196, 168, 255) else Color.rgb(103, 80, 164)
            "rose" -> if (dark) Color.rgb(255, 178, 190) else Color.rgb(176, 46, 84)
            "amber" -> if (dark) Color.rgb(255, 198, 108) else Color.rgb(140, 90, 0)
            "green" -> if (dark) Color.rgb(140, 214, 150) else Color.rgb(35, 120, 55)
            "indigo" -> if (dark) Color.rgb(159, 168, 255) else Color.rgb(57, 73, 171)
            "coral" -> if (dark) Color.rgb(255, 171, 145) else Color.rgb(191, 54, 12)
            "lime" -> if (dark) Color.rgb(197, 225, 165) else Color.rgb(51, 105, 30)
            "slate" -> if (dark) Color.rgb(176, 190, 197) else Color.rgb(55, 71, 79)
            else -> scene?.let { sceneAccent(it, dark) } ?: dynamicAccent(context, dark) ?: wallpaperAccent(context, dark)
                ?: if (dark) FALLBACK_ACCENT_DARK else FALLBACK_ACCENT_LIGHT
        }
        val dyn = Build.VERSION.SDK_INT >= 31 && store.accentMode() == "wallpaper"
        val background: Int
        val card: Int
        val cardHigh: Int
        val text: Int
        val textSecondary: Int
        val accentSoft: Int
        val onAccentSoft: Int
        if (dark) {
            background = sys(context, android.R.color.system_neutral1_900, dyn) ?: Color.rgb(14, 16, 20)
            val n800 = sys(context, android.R.color.system_neutral1_800, dyn) ?: Color.rgb(34, 38, 46)
            card = blend(background, n800, 0.42f)
            cardHigh = blend(background, n800, 0.78f)
            text = sys(context, android.R.color.system_neutral1_50, dyn) ?: Color.rgb(242, 244, 247)
            textSecondary = sys(context, android.R.color.system_neutral2_200, dyn) ?: Color.rgb(170, 178, 190)
            accentSoft = sys(context, android.R.color.system_accent1_700, dyn) ?: blend(background, accent, 0.28f)
            onAccentSoft = sys(context, android.R.color.system_accent1_100, dyn) ?: blend(Color.WHITE, accent, 0.25f)
        } else {
            background = sys(context, android.R.color.system_neutral1_10, dyn) ?: Color.rgb(246, 247, 250)
            card = Color.WHITE
            cardHigh = sys(context, android.R.color.system_neutral1_100, dyn) ?: Color.rgb(232, 235, 241)
            text = sys(context, android.R.color.system_neutral1_900, dyn) ?: Color.rgb(24, 27, 33)
            textSecondary = sys(context, android.R.color.system_neutral2_700, dyn) ?: Color.rgb(84, 92, 106)
            accentSoft = sys(context, android.R.color.system_accent1_100, dyn) ?: blend(Color.WHITE, accent, 0.18f)
            onAccentSoft = sys(context, android.R.color.system_accent1_800, dyn) ?: blend(Color.BLACK, accent, 0.55f)
        }
        val onAccent = if (luminance(accent) > 0.45f) Color.rgb(16, 20, 28) else Color.WHITE
        return OeaPalette(
            dark = dark,
            accent = accent,
            onAccent = onAccent,
            accentSoft = accentSoft,
            onAccentSoft = onAccentSoft,
            background = background,
            drawer = alpha(background, if (dark) 0xF4 else 0xF7),
            card = card,
            cardHigh = cardHigh,
            stroke = if (dark) alpha(Color.WHITE, 0x1C) else alpha(Color.BLACK, 0x14),
            text = text,
            textSecondary = textSecondary,
            textTertiary = blend(textSecondary, background, 0.35f),
            homeText = if (wallpaperLight) Color.rgb(18, 20, 25) else Color.WHITE,
            homeTextSecondary = if (wallpaperLight) alpha(Color.rgb(18, 20, 25), 0xC8) else alpha(Color.WHITE, 0xDC),
            homeShadow = if (wallpaperLight) alpha(Color.WHITE, 0x66) else alpha(Color.BLACK, 0xA6),
            wallpaperLight = wallpaperLight,
            glass = if (wallpaperLight) alpha(Color.WHITE, 0x8C) else alpha(Color.WHITE, 0x2B),
            glassStrong = if (wallpaperLight) alpha(Color.WHITE, 0xC8) else alpha(Color.rgb(20, 22, 28), 0x9E),
            glassStroke = if (wallpaperLight) alpha(Color.BLACK, 0x14) else alpha(Color.WHITE, 0x26),
            scrimTop = if (wallpaperLight) alpha(Color.WHITE, 0x26) else alpha(Color.BLACK, 0x4A),
            scrimBottom = if (wallpaperLight) alpha(Color.WHITE, 0x30) else alpha(Color.BLACK, 0x5C),
            danger = if (dark) Color.rgb(255, 128, 128) else Color.rgb(196, 40, 52),
            success = if (dark) Color.rgb(110, 220, 165) else Color.rgb(18, 128, 82),
            warning = if (dark) Color.rgb(255, 196, 110) else Color.rgb(170, 96, 0),
        )
    }

    fun isSystemNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** True when dark text reads better over the current system wallpaper; null when unknown. */
    fun wallpaperIsLight(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < 27) return null
        val colors = wallpaperColors(context) ?: return null
        if (Build.VERSION.SDK_INT >= 31) {
            return (colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
        }
        return luminance(colors.primaryColor.toArgb()) > 0.6f
    }

    private fun wallpaperColors(context: Context): WallpaperColors? {
        if (Build.VERSION.SDK_INT < 27) return null
        return runCatching {
            WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        }.getOrNull()
    }

    /**
     * A bundled scene's own colour, tuned to read as an accent on the current
     * theme: lighter on dark panels, deeper on light ones, and never washed out.
     */
    private fun sceneAccent(scene: OeaWallpaper, dark: Boolean): Int {
        val hsl = hsl(scene.accent)
        val saturation = hsl[1].coerceIn(0.42f, 0.9f)
        val lightness = if (dark) 0.74f else 0.42f
        return hslToColor(hsl[0], saturation, lightness)
    }

    private fun dynamicAccent(context: Context, dark: Boolean): Int? {
        if (Build.VERSION.SDK_INT < 31) return null
        return sys(context, if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600, true)
    }

    private fun wallpaperAccent(context: Context, dark: Boolean): Int? {
        if (Build.VERSION.SDK_INT < 27) return null
        val colors = wallpaperColors(context) ?: return null
        val candidates = listOfNotNull(colors.primaryColor, colors.secondaryColor, colors.tertiaryColor)
            .map { it.toArgb() }
        // Prefer the most colourful wallpaper colour; grey wallpapers keep OEA blue.
        val best = candidates.maxByOrNull { hsl(it)[1] } ?: return null
        val h = hsl(best)
        if (h[1] < 0.12f) return null
        return hslToColor(h[0], h[1].coerceIn(0.45f, 0.85f), if (dark) 0.74f else 0.40f)
    }

    private fun sys(context: Context, id: Int, enabled: Boolean): Int? {
        if (!enabled || Build.VERSION.SDK_INT < 31) return null
        return runCatching { context.getColor(id) }.getOrNull()
    }

    fun alpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    fun blend(from: Int, to: Int, ratio: Float): Int {
        val r = ratio.coerceIn(0f, 1f)
        val inv = 1f - r
        return Color.argb(
            (Color.alpha(from) * inv + Color.alpha(to) * r).toInt(),
            (Color.red(from) * inv + Color.red(to) * r).toInt(),
            (Color.green(from) * inv + Color.green(to) * r).toInt(),
            (Color.blue(from) * inv + Color.blue(to) * r).toInt(),
        )
    }

    fun luminance(color: Int): Float {
        fun channel(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return (0.2126 * channel(Color.red(color)) + 0.7152 * channel(Color.green(color)) +
            0.0722 * channel(Color.blue(color))).toFloat()
    }

    fun hsl(color: Int): FloatArray {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2f
        if (max == min) return floatArrayOf(0f, 0f, l)
        val d = max - min
        val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        val h = when (max) {
            r -> ((g - b) / d + (if (g < b) 6f else 0f))
            g -> ((b - r) / d + 2f)
            else -> ((r - g) / d + 4f)
        } * 60f
        return floatArrayOf(h, s, l)
    }

    fun hslToColor(h: Float, s: Float, l: Float): Int {
        val c = (1f - Math.abs(2f * l - 1f)) * s
        val x = c * (1f - Math.abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color.rgb(
            ((r + m) * 255f).toInt().coerceIn(0, 255),
            ((g + m) * 255f).toInt().coerceIn(0, 255),
            ((b + m) * 255f).toInt().coerceIn(0, 255),
        )
    }
}
