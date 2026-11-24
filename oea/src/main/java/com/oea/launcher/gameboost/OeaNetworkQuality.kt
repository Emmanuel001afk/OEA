package com.oea.launcher.gameboost

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import android.os.SystemClock
import android.view.View
import java.util.Locale

/** How far back per-app traffic is read. Short enough to read as "now". */
private const val WINDOW_MS = 6_000L
private const val SCOPE_NONE = 0
private const val SCOPE_GAME = 1
private const val SCOPE_DEVICE = 2

/** Link quality is an Android link estimate, not a measurement of a game's server. */
internal data class OeaNetworkQuality(
    val level: String,
    val link: String,
    val color: Int,
    val down: String = "—",
    val up: String = "—",
    val trafficScope: String = "Game traffic unavailable"
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
    private var trafficGame: String? = null
    private var trafficUid = -1
    private val rates = OeaTrafficRates()
    private var trafficScopeKey = SCOPE_NONE

    /**
     * Fills in the download and upload numbers, by the most honest source this
     * phone actually allows.
     *
     * Android has refused other apps' UID counters since Android 10 —
     * [TrafficStats.getUidRxBytes] simply answers UNSUPPORTED (-1) for
     * anything but the caller, which is why the panel's up and down used to
     * sit at "—" for every game that is not OEA's own. So:
     *
     * 1. per-app, through [NetworkStatsManager.queryDetailsForUid], which is
     *    allowed when OEA holds Usage Access (the same permission the rest of
     *    Game Boost already asks for);
     * 2. otherwise the phone's whole traffic, from the counters that are always
     *    readable — shown as what it is, never dressed up as the game's own.
     */
    private fun sampleTraffic(game: String?) {
        if (game != trafficGame) {
            trafficGame = game
            val pkg = if (game != null && OeaGameBoostStore.isOeaGameKey(game)) appContext.packageName else game
            trafficUid = if (pkg == null) -1 else runCatching { appContext.packageManager.getApplicationInfo(pkg, 0).uid }.getOrDefault(-1)
            rates.reset()
        }
        val now = SystemClock.elapsedRealtime()
        if (trafficUid >= 0) {
            val perApp = uidRates(trafficUid)
            if (perApp != null) {
                rates.report(perApp.first, perApp.second)
                trafficScopeKey = SCOPE_GAME
                return
            }
        }
        val rx = runCatching { TrafficStats.getTotalRxBytes() }.getOrDefault(-1L)
        val tx = runCatching { TrafficStats.getTotalTxBytes() }.getOrDefault(-1L)
        if (rx >= 0L && tx >= 0L) {
            rates.sample(now, rx, tx)
            trafficScopeKey = SCOPE_DEVICE
        } else {
            rates.reset()
            trafficScopeKey = SCOPE_NONE
        }
    }

    /**
     * Bytes per second for one app over the last few seconds, or null when this
     * phone will not say. [NetworkStatsManager] is only allowed to read another
     * app's usage when OEA holds Usage Access.
     */
    private fun uidRates(uid: Int): Pair<Double, Double>? {
        if (Build.VERSION.SDK_INT < 24) return null
        if (uid != android.os.Process.myUid() && !com.oea.launcher.privilege.OeaPrivileges.canReadUsage(appContext)) return null
        val manager = appContext.getSystemService(android.app.usage.NetworkStatsManager::class.java) ?: return null
        val end = System.currentTimeMillis()
        val start = end - WINDOW_MS
        var rx = 0L
        var tx = 0L
        var sawBucket = false
        for (type in intArrayOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) {
            val stats = runCatching { manager.queryDetailsForUid(type, null, start, end, uid) }.getOrNull() ?: continue
            runCatching {
                val bucket = android.app.usage.NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    rx += bucket.rxBytes
                    tx += bucket.txBytes
                    sawBucket = true
                }
            }.onFailure { stats.close() }
            runCatching { stats.close() }
        }
        if (!sawBucket) return null
        val seconds = WINDOW_MS / 1000.0
        return (rx / seconds) to (tx / seconds)
    }

    fun close() { executor.shutdownNow() }


    fun read(game: String?): OeaNetworkQuality {
        sampleTraffic(game)
        val quality = readLink()
        val unavailable = rates.down == "—" || rates.up == "—"
        val scope = when {
            unavailable -> "Traffic unavailable"
            trafficScopeKey == SCOPE_GAME -> if (game != null && OeaGameBoostStore.isOeaGameKey(game)) "OEA app traffic" else "Game traffic"
            trafficScopeKey == SCOPE_DEVICE -> "All traffic on the phone"
            else -> "Traffic unavailable"
        }
        return quality.copy(down = rates.down, up = rates.up, trafficScope = scope)
    }

    private fun readLink(): OeaNetworkQuality = runCatching {
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

        // Retain the last answer while the refresh runs in the background.
        when (probeSucceeded) {
            false -> return OeaNetworkQuality("PROBE FAILED", link, COLOR_OFFLINE)
            null -> Unit // Android already validated this network; show its link estimate.
            true -> Unit
        }

        // Android's bandwidth is an estimate, not measured throughput.
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
        const val PROBE_INTERVAL_MS = 5_000L
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
 * Compact status strip: estimated link category and/or the selected game's
 * actual traffic rate when Android exposes its UID counters.
 */
internal class OeaGameStatusStripView(context: Context) : View(context) {
    private var quality = OeaNetworkQuality("UNKNOWN", "Unavailable", 0xFF9BAABD.toInt())
    private var battery = "—"
    private var batteryCaption = "BATTERY"
    private var showNetwork = true
    private var networkMode = "status"
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

    fun update(network: OeaNetworkQuality, batteryText: String, caption: String, showNetworkQuality: Boolean, mode: String) {
        // The caption is decided by the battery reading itself — CHARGING only while
        // the phone is taking charge, PLUGGED while it is on a cable and holding.
        val nextCaption = caption.uppercase(Locale.US)
        if (network == quality && batteryText == battery && nextCaption == batteryCaption && showNetworkQuality == showNetwork && mode == networkMode) return
        quality = network
        battery = batteryText
        batteryCaption = nextCaption
        showNetwork = showNetworkQuality
        networkMode = mode
        contentDescription = if (showNetwork) {
            "Link estimate ${network.level.lowercase(Locale.US)}, ${network.link}; ${network.trafficScope}, download ${network.down}, upload ${network.up}; $nextCaption $batteryText"
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
        if (!showNetwork) {
            drawColumn(canvas, 0f, w, h, batteryCaption, battery, Color.WHITE)
            return
        }
        val columns = when (networkMode) {
            "rates" -> listOf(Triple("GAME DOWN", quality.down, Color.WHITE), Triple("GAME UP", quality.up, Color.WHITE))
            "both" -> listOf(Triple("LINK", quality.level, quality.color), Triple("GAME DOWN", quality.down, Color.WHITE), Triple("GAME UP", quality.up, Color.WHITE))
            else -> listOf(Triple("LINK EST.", quality.level, quality.color), Triple("TYPE", quality.link, Color.WHITE))
        } + Triple(batteryCaption, battery, Color.WHITE)
        val column = w / columns.size
        divider.color = Color.WHITE.withAlphaByte(0x1E)
        divider.strokeWidth = maxOf(1f, dpf(0.6f))
        columns.forEachIndexed { i, item ->
            if (i > 0) canvas.drawLine(column * i, h * 0.22f, column * i, h * 0.78f, divider)
            drawColumn(canvas, column * i, column, h, item.first, item.second, item.third)
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
        val captionSize = caption.textSize
        val available = width - pad * 2f
        if (caption.measureText(label) > available && available > 0f) caption.textSize = captionSize * (available / caption.measureText(label)).coerceAtLeast(0.65f)
        canvas.drawText(label, left + pad, captionBaseline, caption)
        caption.textSize = captionSize
        value.color = valueColor
        val oldSize = value.textSize
        if (value.measureText(text) > available && available > 0f) value.textSize = oldSize * (available / value.measureText(text)).coerceAtLeast(0.65f)
        canvas.drawText(text, left + pad, valueBaseline, value)
        value.textSize = oldSize
    }
}
