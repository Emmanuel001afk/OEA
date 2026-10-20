package com.oea.launcher.gameboost

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.View
import java.util.Locale

/**
 * A lightweight, approximate network-health category for the in-game panel.
 * It uses Android's active-network estimate and signal level, never traffic
 * counters or a periodic speed test. The floating button stays unobstructed.
 */
internal data class OeaNetworkQuality(
    val level: String,
    val link: String,
    val color: Int
)

internal class OeaNetworkQualityReader(context: Context) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val lock = Any()
    private val probing = java.util.concurrent.atomic.AtomicBoolean(false)
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "oea-network-check").apply { isDaemon = true }
    }
    @Volatile private var checkedNetwork: android.net.Network? = null
    @Volatile private var lastProbeAt = 0L
    @Volatile private var probeSucceeded: Boolean? = null

    fun read(): OeaNetworkQuality = runCatching {
        val manager = connectivity
            ?: return OeaNetworkQuality("UNKNOWN", "Unavailable", COLOR_OFFLINE)
        val network = manager.activeNetwork
            ?: return OeaNetworkQuality("OFFLINE", "Disconnected", COLOR_OFFLINE)
        val caps = manager.getNetworkCapabilities(network)
            ?: return OeaNetworkQuality("OFFLINE", "Disconnected", COLOR_OFFLINE)
        val link = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Online"
        }
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return OeaNetworkQuality("OFFLINE", link, COLOR_OFFLINE)
        }
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            scheduleProbe(network)
            return OeaNetworkQuality("NO INTERNET", link, COLOR_OFFLINE)
        }

        val now = android.os.SystemClock.elapsedRealtime()
        val changed = checkedNetwork != network
        if (changed) synchronized(lock) {
            if (checkedNetwork != network) {
                checkedNetwork = network
                lastProbeAt = 0L
                probeSucceeded = null
            }
        }
        if (changed || now - lastProbeAt >= PROBE_INTERVAL_MS) scheduleProbe(network)

        when (probeSucceeded) {
            false -> return OeaNetworkQuality("NO INTERNET", link, COLOR_OFFLINE)
            null -> return OeaNetworkQuality("CHECKING", link, COLOR_OFFLINE)
            true -> Unit
        }

        // Android's bandwidth is an estimate, not measured throughput. Only
        // show a quality grade after a recent request actually reached the internet.
        val bandwidthKbps = caps.linkDownstreamBandwidthKbps.coerceAtLeast(0)
        val score = when {
            bandwidthKbps >= FAST_BANDWIDTH_KBPS -> 2
            bandwidthKbps >= MEDIUM_BANDWIDTH_KBPS -> 1
            else -> 0
        }
        when (score) {
            2 -> OeaNetworkQuality("FAST", link, COLOR_FAST)
            1 -> OeaNetworkQuality("MEDIUM", link, COLOR_MEDIUM)
            else -> OeaNetworkQuality("SLOW", link, COLOR_SLOW)
        }
    }.getOrElse {
        OeaNetworkQuality("UNKNOWN", "Unavailable", COLOR_OFFLINE)
    }

    private fun scheduleProbe(network: android.net.Network) {
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (checkedNetwork != network) {
                checkedNetwork = network
                lastProbeAt = 0L
                probeSucceeded = null
            }
            if (probing.get() || now - lastProbeAt < PROBE_INTERVAL_MS) return
            lastProbeAt = now
            if (!probing.compareAndSet(false, true)) return
        }
        executor.execute {
            var success = false
            try {
                val connection = network.openConnection(java.net.URL(PROBE_URL)) as java.net.HttpURLConnection
                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = PROBE_TIMEOUT_MS
                    connection.readTimeout = PROBE_TIMEOUT_MS
                    connection.instanceFollowRedirects = false
                    connection.useCaches = false
                    connection.setRequestProperty("Cache-Control", "no-cache")
                    success = connection.responseCode == 204
                } finally {
                    connection.disconnect()
                }
            } catch (_: Throwable) {
                success = false
            } finally {
                synchronized(lock) {
                    if (connectivity?.activeNetwork == network && checkedNetwork == network) {
                        probeSucceeded = success
                    }
                    probing.set(false)
                }
            }
        }
    }

    private companion object {
        const val FAST_BANDWIDTH_KBPS = 12_000
        const val MEDIUM_BANDWIDTH_KBPS = 2_500
        const val PROBE_INTERVAL_MS = 15_000L
        const val PROBE_TIMEOUT_MS = 2_500
        const val PROBE_URL = "https://connectivitycheck.gstatic.com/generate_204"
        const val COLOR_FAST = 0xFF6BE6A5.toInt()
        const val COLOR_MEDIUM = 0xFFFFC96B.toInt()
        const val COLOR_SLOW = 0xFFFF7B7B.toInt()
        const val COLOR_OFFLINE = 0xFF9BAABD.toInt()
    }
}

private fun View.sp(value: Float): Float =
    value * resources.displayMetrics.scaledDensity

private fun View.dpf(value: Float): Float = value * resources.displayMetrics.density

private fun Int.withAlphaByte(alpha: Int): Int =
    (this and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

/**
 * Compact status strip inside the expanded Game Boost panel. It deliberately
 * reports a simple Slow / Medium / Fast network category instead of raw
 * download/upload counters, plus link type and battery state.
 */
internal class OeaGameStatusStripView(context: Context) : View(context) {
    private var quality = OeaNetworkQuality("UNKNOWN", "Unavailable", 0xFF9BAABD.toInt())
    private var battery = "—"
    private var batteryCaption = "BATTERY"
    private var showNetwork = true
    private var base = 0xFF0A1020.toInt()
    private var accent = 0xFF2357FF.toInt()
    private val rect = RectF()
    private val background = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val divider = Paint(Paint.ANTI_ALIAS_FLAG)
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9FB2C8.toInt()
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.08f
    }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        fontFeatureSettings = "tnum"
    }

    init {
        contentDescription = "Network quality and battery"
    }

    fun update(network: OeaNetworkQuality, batteryText: String, charging: Boolean, showNetworkQuality: Boolean) {
        val nextCaption = if (charging) "CHARGING" else "BATTERY"
        if (network == quality && batteryText == battery && nextCaption == batteryCaption && showNetworkQuality == showNetwork) return
        quality = network
        battery = batteryText
        batteryCaption = nextCaption
        showNetwork = showNetworkQuality
        contentDescription = if (showNetwork) {
            "Network quality ${network.level.lowercase(Locale.US)}, ${network.link}, $nextCaption $batteryText"
        } else {
            "$nextCaption $batteryText"
        }
        invalidate()
    }

    fun setColors(baseColor: Int, accentColor: Int) {
        if (baseColor == base && accentColor == accent) return
        base = baseColor
        accent = accentColor
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, resolveSize(dpf(46f).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val strokeWidth = dpf(1f)
        rect.set(strokeWidth / 2f, strokeWidth / 2f, w - strokeWidth / 2f, h - strokeWidth / 2f)
        val radius = dpf(12f)
        background.color = base.withAlphaByte(0xB8)
        stroke.color = accent.withAlphaByte(0x55)
        stroke.strokeWidth = strokeWidth
        canvas.drawRoundRect(rect, radius, radius, background)
        canvas.drawRoundRect(rect, radius, radius, stroke)

        caption.textSize = sp(7.5f)
        value.textSize = sp(11.5f)
        if (showNetwork) {
            val column = w / 3f
            divider.color = Color.WHITE.withAlphaByte(0x1E)
            divider.strokeWidth = maxOf(1f, dpf(0.6f))
            canvas.drawLine(column, h * 0.22f, column, h * 0.78f, divider)
            canvas.drawLine(column * 2f, h * 0.22f, column * 2f, h * 0.78f, divider)
            drawColumn(canvas, 0f, column, h, "NETWORK", quality.level, quality.color)
            drawColumn(canvas, column, column, h, "LINK", quality.link, Color.WHITE)
            drawColumn(canvas, column * 2f, column, h, batteryCaption, battery, Color.WHITE)
        } else {
            drawColumn(canvas, 0f, w, h, batteryCaption, battery, Color.WHITE)
        }
    }

    private fun drawColumn(
        canvas: Canvas,
        left: Float,
        width: Float,
        height: Float,
        label: String,
        text: String,
        valueColor: Int
) {
        val pad = dpf(8f)
        val captionBaseline = height * 0.40f
        val valueBaseline = height * 0.78f
        caption.color = 0xFF9FB2C8.toInt()
        canvas.drawText(label, left + pad, captionBaseline, caption)
        value.color = valueColor
        val available = width - pad * 2f
        val shown = if (value.measureText(text) > available) text.take(5) else text
        canvas.drawText(shown, left + pad, valueBaseline, value)
    }
}
