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
import android.os.BatteryManager
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
            OeaGameBoostStore.syncDetectedGames(this@OeaGameBoostService)
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
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val panel = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xEE202124.toInt())
                cornerRadius = dp(22).toFloat()
            }
            visibility = View.GONE
        }
        val title = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            text = "OEA Game Boost"
        }
        val gameName = TextView(this).apply {
            setTextColor(0xFFB9C7FF.toInt()); textSize = 11f
            text = packageName.substringAfterLast('.')
            setPadding(0, dp(3), 0, dp(9))
        }
        val metrics = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 12f
            text = "RAM • reading…"; setPadding(0, 0, 0, dp(5))
        }
        val device = TextView(this).apply {
            setTextColor(0xFFD0D0D0.toInt()); textSize = 11f
            text = "Battery • reading…"; setPadding(0, 0, 0, dp(10))
        }
        val controls = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        val dnd = chip("DND")
        val hide = chip("HIDE")
        controls.addView(dnd, android.widget.LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(6) })
        controls.addView(hide, android.widget.LinearLayout.LayoutParams(0, dp(40), 1f))
        dnd.setOnClickListener { toggleDnd(dnd) }
        hide.setOnClickListener { panel.visibility = View.GONE }
        panel.addView(title); panel.addView(gameName); panel.addView(metrics); panel.addView(device); panel.addView(controls)

        val pill = TextView(this).apply {
            gravity = Gravity.CENTER; setTextColor(0xFF202124.toInt()); textSize = 9f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFF2F3F5.toInt()); cornerRadius = dp(999).toFloat()
            }
            elevation = dp(4).toFloat()
            contentDescription = "Open OEA Game Boost controls"
        }
        pill.setOnClickListener { panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        root.addView(panel, android.widget.LinearLayout.LayoutParams(dp(250), -2))
        root.addView(pill, android.widget.LinearLayout.LayoutParams(dp(76), dp(30)).apply { gravity = Gravity.END; topMargin = dp(2) })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = dp(8); y = dp(88) }
        runCatching { wm.addView(root, params); overlay = root; updateOverlay() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun chip(label: String): TextView = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = 10f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF34363A.toInt()); cornerRadius = dp(12).toFloat()
        }
        isClickable = true; isFocusable = true
    }

    private fun toggleDnd(button: TextView) {
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            button.text = "DND ACCESS"
            runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        val next = !OeaGameBoostStore.prefs(this).getBoolean("dnd", true)
        OeaGameBoostStore.prefs(this).edit().putBoolean("dnd", next).apply()
        if (next) {
            if (previousInterruptionFilter == null) previousInterruptionFilter = nm.currentInterruptionFilter
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        } else restoreDnd()
        updateDndButton(button)
    }

    private fun updateDndButton(button: TextView) {
        val nm = getSystemService(NotificationManager::class.java)
        button.text = when {
            !nm.isNotificationPolicyAccessGranted -> "DND ACCESS"
            OeaGameBoostStore.prefs(this).getBoolean("dnd", true) -> "DND ON"
            else -> "DND OFF"
        }
    }

    private fun updateOverlay() {
        val root = overlay as? android.widget.LinearLayout ?: run {
            activeGame?.let { if (Settings.canDrawOverlays(this)) showOverlay(it) }
            return
        }
        val panel = root.getChildAt(0) as? android.widget.LinearLayout ?: return
        val gameName = panel.getChildAt(1) as? TextView ?: return
        val metrics = panel.getChildAt(2) as? TextView ?: return
        val device = panel.getChildAt(3) as? TextView ?: return
        val controls = panel.getChildAt(4) as? android.widget.LinearLayout ?: return
        val dnd = controls.getChildAt(0) as? TextView ?: return

        val info = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        val totalMb = info.totalMem / (1024.0 * 1024.0)
        val availableMb = info.availMem / (1024.0 * 1024.0)
        val usedMb = (totalMb - availableMb).coerceAtLeast(0.0)
        val usedPct = if (totalMb > 0) usedMb / totalMb * 100.0 else 0.0
        val state = if (info.lowMemory) " • LOW MEMORY" else ""
        metrics.text = String.format(Locale.US, "RAM %.0f / %.0f MB • %.0f%%%s", usedMb, totalMb, usedPct, state)

        val battery = getSystemService(BATTERY_SERVICE) as BatteryManager
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        device.text = if (level in 0..100) "Battery $level% • Game focus active" else "Game focus active"
        gameName.text = activeGame?.substringAfterLast('.') ?: "Game"
        updateDndButton(dnd)
        (root.getChildAt(1) as? TextView)?.text = String.format(Locale.US, "RAM %.0f%%", usedPct)
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
    fun dismissedGames(context: Context) = prefs(context).getStringSet("dismissed_games", emptySet()).orEmpty()
    fun isGame(context: Context, packageName: String) = games(context).contains(packageName)

    /** Uses Android's declared application category where available, with a conservative legacy fallback. */
    fun detectedGames(context: Context): Set<String> {
        val pm = context.packageManager
        return pm.getInstalledApplications(0).asSequence()
            .filter { it.packageName != context.packageName }
            .filter {
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    it.category == android.content.pm.ApplicationInfo.CATEGORY_GAME
                } else {
                    pm.getApplicationLabel(it).toString().lowercase().let { label ->
                        label.contains("game") || label.contains("arcade")
                    }
                }
            }
            .map { it.packageName }
            .toSet()
    }

    fun syncDetectedGames(context: Context): Set<String> {
        val detected = detectedGames(context)
        val dismissed = dismissedGames(context)
        val merged = games(context).toMutableSet().apply { addAll(detected.filterNot(dismissed::contains)) }
        setGames(context, merged)
        return merged
    }

    fun removeGame(context: Context, packageName: String) {
        setGames(context, games(context).toMutableSet().apply { remove(packageName) })
        prefs(context).edit().putStringSet("dismissed_games", dismissedGames(context).toMutableSet().apply { add(packageName) }).apply()
    }

    fun restoreGame(context: Context, packageName: String) {
        prefs(context).edit().putStringSet("dismissed_games", dismissedGames(context).toMutableSet().apply { remove(packageName) }).apply()
    }
}