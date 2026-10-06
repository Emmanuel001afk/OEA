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
        previousInterruptionFilter?.let { if (nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(it) }
        previousInterruptionFilter = null
        removeOverlay()
    }
    private fun showOverlay(packageName: String) {
        if (overlay != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val text = TextView(this).apply {
            setTextColor(Color.WHITE); setBackgroundColor(0xCC202124.toInt()); setPadding(24, 12, 24, 12); textSize = 12f
            text = "OEA BOOST • " + packageName.substringAfterLast('.') + "\nRAM focus: monitoring • apps remain open"
        }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.TOP or Gravity.END; x = 16; y = 96 }
        runCatching { wm.addView(text, params); overlay = text }
    }
    private fun updateOverlay() {
        val view = overlay as? TextView ?: return
        val info = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        val used = (info.totalMem - info.availMem) / (1024.0 * 1024.0)
        val total = info.totalMem / (1024.0 * 1024.0)
        view.text = String.format(Locale.US, "OEA BOOST • %s\nRAM: %.0f / %.0f MB • other apps stay open", activeGame?.substringAfterLast('.') ?: "game", used, total)
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