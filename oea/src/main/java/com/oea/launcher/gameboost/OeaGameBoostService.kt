package com.oea.launcher.gameboost

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.util.Locale

class OeaGameBoostService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var activeGame: String? = null
    private var previousInterruptionFilter: Int? = null
    private val tick = object : Runnable {
        override fun run() {
            if (!OeaGameBoostStore.enabled(this@OeaGameBoostService) || !isUsageAccessGranted()) { stopSelf(); return }
            val game = foregroundPackage()
            if (game != null && OeaGameBoostStore.isGame(this@OeaGameBoostService, game)) {
                if (activeGame != game) { activeGame?.let(::deactivate); activeGame = game; activate(game) } else updateOverlay()
            } else if (activeGame != null) { deactivate(activeGame!!); activeGame = null }
            handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle("OEA Game Boost").setContentText("Monitoring selected games").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        else startForeground(NOTIFICATION_ID, notification)
        handler.post(tick)
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); activeGame?.let(::deactivate); removeOverlay(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun activate(packageName: String) {
        if (OeaGameBoostStore.prefs(this).getBoolean("dnd", true)) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.isNotificationPolicyAccessGranted) { previousInterruptionFilter = nm.currentInterruptionFilter; nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY) }
        }
        if (Settings.canDrawOverlays(this)) showOverlay(packageName)
    }
    private fun deactivate(@Suppress("UNUSED_PARAMETER") packageName: String) {
        val nm = getSystemService(NotificationManager::class.java)
        restoreDnd()
        removeOverlay()
    }
    private fun showOverlay(packageName: String) {
        if (overlay != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = android.widget.FrameLayout(this).apply {
            setPadding(0, 0, 0, 0)
        }
        val pill = TextView(this).apply {
            text = "OEA"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 11f
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xEE202124.toInt())
                cornerRadius = 999f
            }
        }
        val panel = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(18, 12, 18, 12)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xEE202124.toInt())
                cornerRadius = 28f
            }
            visibility = View.GONE
        }
        val title = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            text = "OEA Game Boost"
        }
        val status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            text = packageName.substringAfterLast('.') + " • RAM"
            setPadding(0, 6, 0, 8)
        }
        val focus = TextView(this).apply {
            setTextColor(0xFFB9C7FF.toInt())
            textSize = 10f
            text = "Game focus active"
            setPadding(0, 0, 0, 8)
        }
        val controls = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        val dnd = TextView(this).apply {
            text = if (OeaGameBoostStore.prefs(this@OeaGameBoostService).getBoolean("dnd", true)) "DND ON" else "DND OFF"
            setTextColor(Color.WHITE)
            textSize = 10f
            setPadding(12, 8, 12, 8)
            setOnClickListener {
                val next = !OeaGameBoostStore.prefs(this@OeaGameBoostService).getBoolean("dnd", true)
                OeaGameBoostStore.prefs(this@OeaGameBoostService).edit().putBoolean("dnd", next).apply()
                if (!next) restoreDnd()
                text = if (next) "DND ON" else "DND OFF"
            }
        }
        val close = TextView(this).apply {
            text = "CLOSE"
            setTextColor(Color.WHITE)
            textSize = 10f
            setPadding(12, 8, 12, 8)
            setOnClickListener { removeOverlay() }
        }
        controls.addView(dnd)
        controls.addView(close)
        panel.addView(title)
        panel.addView(status)
        panel.addView(focus)
        panel.addView(controls)
        root.addView(panel, android.widget.FrameLayout.LayoutParams(dp(230), -2))
        root.addView(pill, android.widget.FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END))
        pill.setOnClickListener { panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        root.setOnClickListener { }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 12; y = 96 }
        runCatching { wm.addView(root, params); overlay = root }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun restoreDnd() {
        val nm = getSystemService(NotificationManager::class.java)
        previousInterruptionFilter?.let { if (nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(it) }
        previousInterruptionFilter = null
    }

    private fun updateOverlay() {
        val root = overlay as? android.widget.FrameLayout ?: return
        val panel = root.getChildAt(0) as? android.widget.LinearLayout ?: return
        val status = panel.getChildAt(1) as? TextView ?: return
        val info = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        val used = (info.totalMem - info.availMem) / (1024.0 * 1024.0)
        val total = info.totalMem / (1024.0 * 1024.0)
        val state = if (info.lowMemory) " • LOW" else ""
        status.text = String.format(Locale.US, "%s • RAM %.0f / %.0f MB%s",
            activeGame?.substringAfterLast('.') ?: "game", used, total, state)
    }

    private fun removeOverlay() { overlay?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }; overlay = null }
    private fun foregroundPackage(): String? {
        val usm = getSystemService(UsageStatsManager::class.java) ?: return null
        val end = System.currentTimeMillis()
        val stats = runCatching { usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, end - 10_000, end) }.getOrNull() ?: return null
        return stats.maxByOrNull { it.lastTimeUsed }?.packageName
    }
    private fun isUsageAccessGranted() = runCatching {
        val appOps = getSystemService(AppOpsManager::class.java)
        appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "OEA Game Boost", NotificationManager.IMPORTANCE_LOW))
    }
    companion object { private const val CHANNEL = "oea_game_boost"; private const val NOTIFICATION_ID = 4107 }
}
object OeaGameBoostStore {
    private const val PREFS = "oea_game_boost"
    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
    fun setEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("enabled", enabled).apply()
    fun games(context: Context) = prefs(context).getStringSet("games", emptySet()).orEmpty()
    fun setGames(context: Context, games: Set<String>) = prefs(context).edit().putStringSet("games", games).apply()
    fun isGame(context: Context, packageName: String) = games(context).contains(packageName)
}