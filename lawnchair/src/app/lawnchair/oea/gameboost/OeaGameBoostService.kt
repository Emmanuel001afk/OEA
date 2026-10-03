package app.lawnchair.oea.gameboost

import android.app.*
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.android.launcher3.R
import java.util.Locale

class OeaGameBoostService : android.app.Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val engine by lazy { OeaGameBoostEngine(this) }
    private var overlay: View? = null
    private var activeGame: String? = null
    private var previousZen: Int? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!OeaGameBoostStore.enabled(this@OeaGameBoostService) || !isUsageAccessGranted()) {
                stopSelf()
                return
            }
            val game = foregroundPackage()
            if (game != null && OeaGameBoostStore.isGame(this@OeaGameBoostService, game)) {
                if (activeGame != game) {
                    activeGame?.let(::deactivate)
                    activeGame = game
                    activate(game)
                } else {
                    updateOverlay()
                }
            } else if (activeGame != null) {
                deactivate(activeGame!!)
                activeGame = null
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = NotificationCompat.Builder(this, "oea_game_boost")
            .setSmallIcon(R.drawable.ic_launcher_home)
            .setContentTitle("OEA Game Boost")
            .setContentText("Monitoring selected games")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                4107,
                notification,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            )
        } else {
            startForeground(4107, notification)
        }
        handler.post(tick)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        activeGame?.let(::deactivate)
        removeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun activate(packageName: String) {
        engine.enter(OeaGameProfile(packageName = packageName))
        val prefs = OeaGameBoostStore.prefs(this)
        if (prefs.getBoolean("dnd", true)) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.isNotificationPolicyAccessGranted) {
                previousZen = nm.currentInterruptionFilter
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            }
        }
        if (Settings.canDrawOverlays(this)) showOverlay(packageName)
    }

    private fun deactivate(packageName: String) {
        val nm = getSystemService(NotificationManager::class.java)
        previousZen?.let {
            if (nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(it)
        }
        previousZen = null
        engine.exit()
        removeOverlay()
    }

    private fun showOverlay(packageName: String) {
        if (overlay != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val text = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC202124.toInt())
            setPadding(24, 12, 24, 12)
            textSize = 12f
            text = "OEA BOOST • " + packageName.substringAfterLast('.') + "\nMonitoring…"
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 96
        }
        runCatching {
            wm.addView(text, params)
            overlay = text
        }
    }

    private fun updateOverlay() {
        val view = overlay as? TextView ?: return
        val telemetry = engine.telemetry()
        val memory = (telemetry.memory as? OeaObserved.Value)?.value
        val temp = (telemetry.batteryTemperatureC as? OeaObserved.Value)?.value
        val powerSave = (telemetry.powerSave as? OeaObserved.Value)?.value
        if (memory == null) {
            view.text = "OEA BOOST • " + (activeGame?.substringAfterLast('.') ?: "game") +
                "\nMemory telemetry restricted"
            return
        }
        val used = (memory.totalMem - memory.availMem) / (1024.0 * 1024.0)
        val total = memory.totalMem / (1024.0 * 1024.0)
        val tempText = temp?.let { String.format(Locale.US, " • %.1f°C", it) } ?: ""
        val saveText = if (powerSave == true) " • Power save ON" else ""
        view.text = String.format(
            Locale.US,
            "OEA BOOST • %s\nRAM: %.0f / %.0f MB%s%s\nOther apps stay open",
            activeGame?.substringAfterLast('.') ?: "game",
            used,
            total,
            tempText,
            saveText
        )
    }

    private fun removeOverlay() {
        overlay?.let {
            runCatching {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it)
            }
        }
        overlay = null
    }

    private fun foregroundPackage(): String? {
        val usm = getSystemService(UsageStatsManager::class.java) ?: return null
        val end = System.currentTimeMillis()
        val stats = runCatching {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, end - 10_000, end)
        }.getOrNull() ?: return null
        return stats.maxByOrNull { it.lastTimeUsed }?.packageName
    }

    private fun isUsageAccessGranted(): Boolean = runCatching {
        val appOps = getSystemService(AppOpsManager::class.java)
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        ) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    "oea_game_boost",
                    "OEA Game Boost",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }
}

object OeaGameBoostStore {
    private const val PREFS = "oea_game_boost"
    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
    fun setEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean("enabled", enabled).apply()
    fun games(context: Context) = prefs(context).getStringSet("games", emptySet()).orEmpty()
    fun setGames(context: Context, games: Set<String>) =
        prefs(context).edit().putStringSet("games", games).apply()
    fun isGame(context: Context, packageName: String) = games(context).contains(packageName)
}
