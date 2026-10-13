package com.oea.engine.perf

import android.app.Activity
import android.app.GameManager
import android.app.GameState
import android.content.Context
import android.opengl.EGL14
import android.os.Build
import android.os.PerformanceHintManager
import android.os.PowerManager
import android.os.Process
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import com.oea.engine.EngineSettings
import com.oea.engine.OeaEngine
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Rolling frame statistics. All values are in milliseconds unless noted. */
class FrameStats internal constructor() {
    private val intervals = FloatArray(SIZE)
    private val work = FloatArray(SIZE)
    private var index = 0
    private var count = 0
    @Volatile var fps = 0f
        private set
    @Volatile var avgFrameMs = 0f
        private set
    @Volatile var avgWorkMs = 0f
        private set
    /** Average of the slowest 1% of recent frames, as FPS. */
    @Volatile var onePercentLowFps = 0f
        private set
    @Volatile var jankFrames = 0
        private set

    internal fun add(intervalMs: Float, workMs: Float, targetMs: Float) {
        intervals[index] = intervalMs
        work[index] = workMs
        index = (index + 1) % SIZE
        if (count < SIZE) count++
        if (intervalMs > targetMs * 1.5f) jankFrames++
        if (index % 15 == 0) recompute()
    }

    private fun recompute() {
        if (count == 0) return
        var sum = 0f; var wsum = 0f
        for (i in 0 until count) { sum += intervals[i]; wsum += work[i] }
        avgFrameMs = sum / count
        avgWorkMs = wsum / count
        fps = if (avgFrameMs > 0f) 1000f / avgFrameMs else 0f
        val sorted = intervals.copyOf(count).also { it.sort() }
        val n = max(1, count / 100)
        var worst = 0f
        for (i in count - n until count) worst += sorted[i]
        worst /= n
        onePercentLowFps = if (worst > 0f) 1000f / worst else 0f
    }

    private companion object { const val SIZE = 240 }
}

/**
 * Pushes the device as far as it can go and keeps it there:
 * - picks the display's highest refresh rate (or the user's cap) and paces frames to it;
 * - Android Dynamic Performance Framework (ADPF) hint sessions so the CPU
 *   governor ramps for the game thread's real workload;
 * - dynamic resolution between the tier's limits (supersampling when there is headroom);
 * - thermal awareness: steps quality down before the device throttles hard;
 * - Android Game Mode (battery / performance) and game-state hints.
 */
class PerformanceManager internal constructor(private val context: Context, private val settings: EngineSettings) {
    val stats = FrameStats()

    /** Resolved quality for this session (setting or device recommendation). */
    @Volatile var quality: QualitySettings = resolveQuality()
        private set
    @Volatile var displayRefreshRate: Float = 60f
        private set
    /** Effective frame-rate target. */
    @Volatile var targetFps: Int = 60
        private set
    /** Current dynamic render scale (×native resolution). */
    @Volatile var renderScale: Float = 1f
        private set
    @Volatile var thermalStatus: Int = 0
        private set
    @Volatile var thermalHeadroom: Float = Float.NaN
        private set
    @Volatile var adpfActive = false
        private set
    /** Android Game Mode: 1 standard, 2 performance, 3 battery (0 unsupported). */
    @Volatile var gameMode: Int = 0
        private set

    private var hintSession: PerformanceHintManager.Session? = null
    private var swapInterval = 1
    private var pixelCapScale = 1f
    private var lastFrameNs = 0L
    private var windowFrames = 0
    private var windowMisses = 0
    private var windowWorkNs = 0L
    private var framesSinceChange = 0
    private var lastHeadroomPoll = 0L
    private var thermalListener: Any? = null

    /** Applies display mode, sustained mode and game-mode hints. Call on the UI thread. */
    fun attach(activity: Activity) {
        quality = resolveQuality()
        gameMode = if (Build.VERSION.SDK_INT >= 31) runCatching {
            context.getSystemService(GameManager::class.java)?.gameMode ?: 0
        }.getOrDefault(0) else 0
        val display = if (Build.VERSION.SDK_INT >= 30) activity.display else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay
        }
        val window = activity.window
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (display != null) {
            val current = display.mode
            val sameSize = display.supportedModes.filter {
                it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
            }.ifEmpty { listOf(current) }
            val wanted = desiredFps()
            val chosen = if (wanted <= 0) sameSize.maxByOrNull { it.refreshRate } else {
                sameSize.filter { it.refreshRate + 0.5f >= wanted }.minByOrNull { it.refreshRate } ?: sameSize.maxByOrNull { it.refreshRate }
            } ?: current
            displayRefreshRate = chosen.refreshRate
            val attrs = window.attributes
            attrs.preferredDisplayModeId = chosen.modeId
            attrs.preferredRefreshRate = chosen.refreshRate
            window.attributes = attrs
        }
        val wanted = desiredFps()
        targetFps = if (wanted <= 0) displayRefreshRate.roundToInt() else min(wanted, displayRefreshRate.roundToInt())
        if (Build.VERSION.SDK_INT >= 24) {
            val pm = context.getSystemService(PowerManager::class.java)
            val sustained = settings.sustainedPerformance && pm?.isSustainedPerformanceModeSupported == true
            runCatching { window.setSustainedPerformanceMode(sustained) }
        }
        registerThermal()
        setGameState(loading = false, playing = true)
        Log.i(TAG, "Display ${displayRefreshRate}Hz, target ${targetFps}fps, tier ${quality.tier}, ${OeaEngine.device.describe()}")
    }

    fun detach(activity: Activity) {
        setGameState(loading = false, playing = false)
        unregisterThermal()
        if (Build.VERSION.SDK_INT >= 24) runCatching { activity.window.setSustainedPerformanceMode(false) }
    }

    /** Hints the compositor that this surface renders at the target rate. */
    fun applySurfaceFrameRate(surface: Surface) {
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching { surface.setFrameRate(targetFps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ALWAYS) }
        } else if (Build.VERSION.SDK_INT >= 30) {
            runCatching { surface.setFrameRate(targetFps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE) }
        }
    }

    // ------------------------------------------------------------------ GL thread

    internal fun onGlThreadStarted() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
            .onFailure { runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) } }
        if (Build.VERSION.SDK_INT >= 31 && hintSession == null) {
            runCatching {
                val phm = context.getSystemService(PerformanceHintManager::class.java)
                hintSession = phm?.createHintSession(intArrayOf(Process.myTid()), targetNanos())
                adpfActive = hintSession != null
            }.onFailure { Log.w(TAG, "ADPF unavailable", it) }
        }
    }

    internal fun onSurfaceSize(width: Int, height: Int) {
        pixelCapScale = DeviceProfile.pixelCapScale(width, height, quality.maxRenderPixels)
        // Pace below the display rate with the swap interval (exact, jitter-free).
        swapInterval = max(1, (displayRefreshRate / max(1, targetFps)).roundToInt())
        runCatching { EGL14.eglSwapInterval(EGL14.eglGetCurrentDisplay(), swapInterval) }
        renderScale = min(1f, maxScale())
        framesSinceChange = 0
    }

    internal fun onGlThreadStopped() {
        runCatching { hintSession?.close() }
        hintSession = null
        adpfActive = false
    }

    /** Records a frame: [workNs] is time spent on the game thread before buffer swap. */
    internal fun endFrame(nowNs: Long, workNs: Long) {
        val interval = if (lastFrameNs == 0L) targetNanos() else nowNs - lastFrameNs
        lastFrameNs = nowNs
        val targetNs = targetNanos()
        stats.add(interval / 1e6f, workNs / 1e6f, targetNs / 1e6f)
        if (Build.VERSION.SDK_INT >= 31) {
            hintSession?.let { s -> runCatching { s.reportActualWorkDuration(max(1L, workNs)) } }
        }
        pollHeadroom(nowNs)
        adapt(interval, workNs, targetNs)
    }

    /** Resets frame pacing after a pause, so the first frame is not counted as a huge stall. */
    internal fun resetTiming() { lastFrameNs = 0L; windowFrames = 0; windowMisses = 0; windowWorkNs = 0L }

    private fun adapt(intervalNs: Long, workNs: Long, targetNs: Long) {
        framesSinceChange++
        val ceiling = maxScale()
        if (!settings.adaptiveResolution) {
            renderScale = min(quality.maxScale, ceiling).coerceAtLeast(quality.minScale)
            return
        }
        if (renderScale > ceiling) renderScale = ceiling
        windowFrames++
        windowWorkNs += workNs
        if (intervalNs > targetNs * 13 / 10 * swapInterval) windowMisses++
        if (windowFrames < 60) return
        val avgWork = windowWorkNs / windowFrames
        when {
            windowMisses >= 8 && renderScale > quality.minScale -> {
                renderScale = max(quality.minScale, renderScale - 0.07f)
                framesSinceChange = 0
            }
            windowMisses <= 1 && avgWork < targetNs * 0.6f && framesSinceChange > 180 && renderScale < ceiling -> {
                renderScale = min(ceiling, renderScale + 0.04f)
                framesSinceChange = 0
            }
        }
        windowFrames = 0; windowMisses = 0; windowWorkNs = 0L
    }

    private fun maxScale(): Float {
        var cap = min(quality.maxScale, pixelCapScale)
        val status = thermalStatus
        if (status >= PowerManager.THERMAL_STATUS_SEVERE) cap = min(cap, quality.minScale + 0.1f)
        else if (status >= PowerManager.THERMAL_STATUS_MODERATE) cap = min(cap, 1f)
        val headroom = thermalHeadroom
        if (!headroom.isNaN() && headroom > 0.9f) cap = min(cap, max(quality.minScale, 0.9f))
        if (gameMode == GAME_MODE_BATTERY) cap = min(cap, 0.85f)
        return max(quality.minScale, cap)
    }

    /** Size of the scene render target relative to the screen (never below native, never above the pixel cap). */
    internal val allocationScale: Float get() = max(1f, min(quality.maxScale, pixelCapScale))

    /** Bloom and shadows are dropped while the device is hot. */
    val thermalLimited: Boolean get() = thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE || gameMode == GAME_MODE_BATTERY

    private fun pollHeadroom(nowNs: Long) {
        if (Build.VERSION.SDK_INT < 30 || nowNs - lastHeadroomPoll < 3_000_000_000L) return
        lastHeadroomPoll = nowNs
        val pm = context.getSystemService(PowerManager::class.java) ?: return
        runCatching { thermalHeadroom = pm.getThermalHeadroom(10) }
    }

    private fun registerThermal() {
        if (Build.VERSION.SDK_INT < 29 || thermalListener != null) return
        val pm = context.getSystemService(PowerManager::class.java) ?: return
        val listener = PowerManager.OnThermalStatusChangedListener { status -> thermalStatus = status }
        runCatching {
            pm.addThermalStatusListener(listener)
            thermalStatus = pm.currentThermalStatus
            thermalListener = listener
        }
    }

    private fun unregisterThermal() {
        if (Build.VERSION.SDK_INT < 29) return
        val listener = thermalListener as? PowerManager.OnThermalStatusChangedListener ?: return
        runCatching { context.getSystemService(PowerManager::class.java)?.removeThermalStatusListener(listener) }
        thermalListener = null
    }

    private fun setGameState(loading: Boolean, playing: Boolean) {
        if (Build.VERSION.SDK_INT < 33) return
        runCatching {
            context.getSystemService(GameManager::class.java)?.setGameState(
                GameState(loading, if (playing) GameState.MODE_GAMEPLAY_INTERRUPTIBLE else GameState.MODE_NONE),
            )
        }
    }

    private fun desiredFps(): Int {
        val wanted = settings.fpsTarget
        return if (gameMode == GAME_MODE_BATTERY && (wanted == 0 || wanted > 60)) 60 else wanted
    }

    private fun resolveQuality(): QualitySettings {
        val tier = QualityTier.parse(settings.quality) ?: OeaEngine.device.recommendedTier
        return QualitySettings.forTier(tier)
    }

    /** Re-reads the quality setting (e.g. after the GPU becomes known or the user changed it). */
    internal fun refreshQuality(width: Int, height: Int) {
        quality = resolveQuality()
        pixelCapScale = DeviceProfile.pixelCapScale(width, height, quality.maxRenderPixels)
        renderScale = renderScale.coerceIn(quality.minScale, maxScale())
    }

    private fun targetNanos(): Long = 1_000_000_000L / max(1, targetFps)

    private companion object {
        const val TAG = "OeaPerf"
        const val GAME_MODE_BATTERY = 3
        @Suppress("unused") val DISPLAY_CLASS = Display::class.java
    }
}
