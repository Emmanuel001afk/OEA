package com.oea.engine.perf

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import java.io.File
import kotlin.math.sqrt

/** Rendering quality tiers. [AUTO] resolves to the device's recommended tier. */
enum class QualityTier(val label: String) {
    LOW("Low"), MEDIUM("Medium"), HIGH("High"), ULTRA("Ultra");

    companion object {
        fun parse(value: String): QualityTier? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/**
 * Concrete rendering settings for a tier. Resolution is dynamic between
 * [minScale] and [maxScale] (×native); above 1.0 the scene is supersampled.
 */
data class QualitySettings(
    val tier: QualityTier,
    val minScale: Float,
    val maxScale: Float,
    /** Upper bound on rendered pixels, protecting memory and bandwidth on very high-res screens. */
    val maxRenderPixels: Int,
    val fxaa: Boolean,
    val bloom: Boolean,
    val shadows: Boolean,
    val shadowMapSize: Int,
    /** Multiplier for particle counts (0..1). */
    val particleDensity: Float,
    /** Multiplier for view and detail distances. */
    val detailDistance: Float,
    val anisotropy: Float,
    val vignette: Boolean,
) {
    companion object {
        fun forTier(tier: QualityTier): QualitySettings = when (tier) {
            QualityTier.LOW -> QualitySettings(tier, 0.55f, 0.8f, 1_600_000, fxaa = true, bloom = false, shadows = false, shadowMapSize = 0,
                particleDensity = 0.45f, detailDistance = 0.7f, anisotropy = 1f, vignette = false)
            QualityTier.MEDIUM -> QualitySettings(tier, 0.65f, 1.0f, 3_000_000, fxaa = true, bloom = false, shadows = false, shadowMapSize = 0,
                particleDensity = 0.7f, detailDistance = 0.85f, anisotropy = 2f, vignette = true)
            QualityTier.HIGH -> QualitySettings(tier, 0.75f, 1.0f, 4_300_000, fxaa = true, bloom = true, shadows = true, shadowMapSize = 1024,
                particleDensity = 1f, detailDistance = 1f, anisotropy = 4f, vignette = true)
            QualityTier.ULTRA -> QualitySettings(tier, 0.85f, 1.35f, 6_200_000, fxaa = true, bloom = true, shadows = true, shadowMapSize = 2048,
                particleDensity = 1f, detailDistance = 1.25f, anisotropy = 8f, vignette = true)
        }
    }
}

/**
 * What this phone can do: CPU, memory, display modes, Android performance
 * class and (after the first frame) the GPU. Produces the recommended tier.
 */
class DeviceProfile private constructor(
    val cores: Int,
    val maxCpuMhz: Int,
    val totalRamMb: Long,
    val lowRamDevice: Boolean,
    /** Android media performance class (e.g. 33, 34), 0 if not declared. */
    val performanceClass: Int,
    val maxRefreshRate: Float,
    val refreshRates: List<Float>,
    val displayWidth: Int,
    val displayHeight: Int,
    val glEsVersion: Int,
    val socModel: String,
) {
    @Volatile var gpuRenderer: String = ""
        private set
    @Volatile var gpuVendor: String = ""
        private set
    @Volatile var glVersion: String = ""
        private set
    @Volatile var maxTextureSize: Int = 4096
        private set
    @Volatile var maxSamples: Int = 4
        private set
    @Volatile var anisotropicFiltering: Boolean = false
        private set
    @Volatile var timerQueries: Boolean = false
        private set

    /** Recommended tier from all known signals; refined once the GPU is known. */
    @Volatile var recommendedTier: QualityTier = computeTier()
        private set

    /**
     * Whether this phone can run an OpenGL ES 3.0 context.
     *
     * Android's own `reqGlEsVersion` is the first answer, but it is a *declared*
     * minimum and plenty of phones report 2.0 while running ES 3.x perfectly well.
     * The other answer is the driver's own version string, which is only known once
     * a context exists. Either one is enough: a game is never blocked by a field
     * Android merely declares, only by a real context that fails to arrive.
     */
    val supportsEs3 get() = glEsVersion >= 0x30000 || glVersion.startsWith("OpenGL ES 3")

    internal fun updateGpu(renderer: String, vendor: String, version: String, maxTexture: Int, samples: Int, extensions: String) {
        gpuRenderer = renderer
        gpuVendor = vendor
        glVersion = version
        maxTextureSize = maxTexture
        maxSamples = samples
        anisotropicFiltering = extensions.contains("GL_EXT_texture_filter_anisotropic")
        timerQueries = extensions.contains("GL_EXT_disjoint_timer_query")
        recommendedTier = computeTier()
    }

    /** Tier score: higher is faster. Each signal is optional; unknowns score neutral. */
    private fun computeTier(): QualityTier {
        var score = 0
        val ramGb = totalRamMb / 1024f
        score += when {
            ramGb >= 11f -> 3
            ramGb >= 7.5f -> 2
            ramGb >= 5.5f -> 1
            ramGb < 3.5f -> -2
            else -> 0
        }
        if (lowRamDevice) score -= 2
        if (cores >= 8) score += 1
        if (maxCpuMhz > 0) score += when {
            maxCpuMhz >= 3000 -> 2
            maxCpuMhz >= 2600 -> 1
            maxCpuMhz < 2000 -> -1
            else -> 0
        }
        score += when {
            performanceClass >= 34 -> 3
            performanceClass >= 33 -> 2
            performanceClass >= 31 -> 1
            else -> 0
        }
        if (maxRefreshRate >= 119f) score += 1
        score += gpuScore(gpuRenderer)
        return when {
            score >= 8 -> QualityTier.ULTRA
            score >= 4 -> QualityTier.HIGH
            score >= 1 -> QualityTier.MEDIUM
            else -> QualityTier.LOW
        }
    }

    private fun gpuScore(renderer: String): Int {
        val r = renderer.lowercase()
        if (r.isBlank()) return 0
        Regex("adreno.*?(\\d{3})").find(r)?.groupValues?.get(1)?.toIntOrNull()?.let { n ->
            return when {
                n >= 730 -> 3
                n >= 650 -> 2
                n >= 610 -> 1
                n >= 500 -> -1
                else -> -2
            }
        }
        if (r.contains("immortalis")) return 3
        Regex("mali-g(\\d+)").find(r)?.groupValues?.get(1)?.toIntOrNull()?.let { n ->
            return when {
                n >= 710 -> 3
                n >= 610 -> 2
                n >= 76 -> 1
                n >= 52 -> 0
                else -> -1
            }
        }
        if (r.contains("xclipse")) return 2
        if (r.contains("powervr")) return -1
        if (r.contains("mali-t") || r.contains("mali-4")) return -2
        return 0
    }

    fun describe(): String = buildString {
        append("${cores} cores")
        if (maxCpuMhz > 0) append(" up to ${"%.1f".format(maxCpuMhz / 1000f)} GHz")
        append(" • ${"%.1f".format(totalRamMb / 1024f)} GB RAM")
        append(" • ${maxRefreshRate.toInt()} Hz")
        if (gpuRenderer.isNotBlank()) append(" • $gpuRenderer")
        if (performanceClass > 0) append(" • performance class $performanceClass")
    }

    companion object {
        fun measure(context: Context): DeviceProfile {
            val am = context.getSystemService(ActivityManager::class.java)
            val mem = ActivityManager.MemoryInfo()
            runCatching { am?.getMemoryInfo(mem) }
            val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            val modes = runCatching { display?.supportedModes?.toList() }.getOrNull().orEmpty()
            val rates = modes.map { it.refreshRate }.distinct().sorted()
            val current = runCatching { display?.mode }.getOrNull()
            return DeviceProfile(
                cores = Runtime.getRuntime().availableProcessors(),
                maxCpuMhz = readMaxCpuMhz(),
                totalRamMb = mem.totalMem / (1024L * 1024L),
                lowRamDevice = am?.isLowRamDevice == true,
                performanceClass = if (Build.VERSION.SDK_INT >= 31) Build.VERSION.MEDIA_PERFORMANCE_CLASS else 0,
                maxRefreshRate = rates.maxOrNull() ?: (display?.refreshRate ?: 60f),
                refreshRates = rates,
                displayWidth = current?.physicalWidth ?: context.resources.displayMetrics.widthPixels,
                displayHeight = current?.physicalHeight ?: context.resources.displayMetrics.heightPixels,
                glEsVersion = am?.deviceConfigurationInfo?.reqGlEsVersion ?: 0x30000,
                socModel = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim() else Build.HARDWARE.orEmpty(),
            )
        }

        private fun readMaxCpuMhz(): Int {
            var best = 0
            for (i in 0 until Runtime.getRuntime().availableProcessors()) {
                val khz = runCatching {
                    File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toInt()
                }.getOrDefault(0)
                if (khz / 1000 > best) best = khz / 1000
            }
            return best
        }

        /** Render scale ceiling so the scene never exceeds [maxPixels]. */
        fun pixelCapScale(width: Int, height: Int, maxPixels: Int): Float {
            val px = width.toLong() * height.toLong()
            if (px <= 0L) return 1f
            return sqrt(maxPixels.toDouble() / px.toDouble()).toFloat()
        }
    }
}
