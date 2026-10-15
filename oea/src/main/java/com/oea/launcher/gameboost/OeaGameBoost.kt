package com.oea.launcher.gameboost

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import com.oea.launcher.OeaLauncherActivity
import com.oea.launcher.assistant.OeaAssistant
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * When Game Boost runs, and nothing more.
 *
 * Game Boost used to keep a foreground service alive whenever its switch was
 * on, polling Usage access every second (and restarting itself each time OEA
 * Home resumed) even when no game was open. That was a steady battery drain
 * with a misleading "Game session active" notification.
 *
 * A session now starts only when a selected game actually comes to the front:
 *  - it was opened from OEA (Home, drawer, folders, search, Game Boost) — free;
 *  - OEA Assistant reports a game window (event driven, no polling);
 *  - optionally, one Usage access check right after the user leaves OEA Home
 *    (catches games opened from Recents or a notification).
 *
 * The session service watches for the user leaving the game only while the
 * game session lasts, pauses while the screen is off, and stops itself (with
 * its notification) when the session ends.
 */
object OeaGameBoost {
    const val ACTION_START_SESSION = "com.oea.launcher.gameboost.START_SESSION"
    const val ACTION_END_SESSION = "com.oea.launcher.gameboost.END_SESSION"
    const val EXTRA_PACKAGE = "oea_game_package"

    private const val TAG = "OeaGameBoost"
    private const val PREF_LEAVE_HOME_CHECK = "detect_on_leaving_home"

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "OeaGameBoostProbe").apply { isDaemon = true }
    }

    @Volatile private var serviceRef: WeakReference<OeaGameBoostService>? = null

    internal fun attach(service: OeaGameBoostService) { serviceRef = WeakReference(service) }
    internal fun detach(service: OeaGameBoostService) { if (serviceRef?.get() === service) serviceRef = null }

    private val service: OeaGameBoostService? get() = serviceRef?.get()

    /** The game whose session is running, or null when Game Boost is idle. */
    val activeGame: String? get() = service?.currentGame()

    /** One Usage access check when leaving Home (default on); never a background poll. */
    fun leaveHomeCheck(context: Context) = OeaGameBoostStore.prefs(context).getBoolean(PREF_LEAVE_HOME_CHECK, true)
    fun setLeaveHomeCheck(context: Context, enabled: Boolean) {
        OeaGameBoostStore.prefs(context).edit().putBoolean(PREF_LEAVE_HOME_CHECK, enabled).apply()
    }

    fun usageAccess(context: Context): Boolean = runCatching {
        context.getSystemService(AppOpsManager::class.java).checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /**
     * A session must be able to notice that the user left the game, or its
     * overlay and Do Not Disturb would linger over other apps. That needs OEA
     * Assistant (preferred, event driven) or Usage access.
     */
    fun canFollowSessions(context: Context): Boolean = OeaAssistant.isEnabled(context) || usageAccess(context)

    /** OEA launched [packageName] from one of its own surfaces. */
    fun onAppLaunched(context: Context, packageName: String) {
        val app = context.applicationContext
        if (com.oea.launcher.perf.OeaLowMode.active(app)) return
        if (!OeaGameBoostStore.enabled(app)) return
        if (packageName == app.packageName) return
        if (!OeaGameBoostStore.isGame(app, packageName)) return
        if (!canFollowSessions(app)) {
            Log.i(TAG, "Not starting a session for $packageName: neither OEA Assistant nor Usage access is available")
            return
        }
        startSession(app, packageName)
    }

    /** OEA Assistant saw a new window in front (event driven; no polling). */
    fun onWindowChanged(context: Context, packageName: String, className: String?) {
        val app = context.applicationContext
        if (com.oea.launcher.perf.OeaLowMode.active(app)) return
        if (!OeaGameBoostStore.enabled(app)) return
        val running = service
        if (running != null && running.currentGame() != null) {
            running.onForegroundReported(packageName, className, fresh = true)
            return
        }
        if (packageName == app.packageName) return
        if (OeaGameBoostStore.isGame(app, packageName)) startSession(app, packageName)
    }

    /**
     * Low mode is on: end any running session and stop the overlay service.
     * Sessions also refuse to start while Low mode is active (see [onAppLaunched]
     * and [onWindowChanged]), so this cannot simply be undone by opening a game.
     */
    fun onLowMode(context: Context) {
        val app = context.applicationContext
        runCatching { app.stopService(Intent(app, OeaGameBoostService::class.java)) }
            .onFailure { Log.w(TAG, "Game Boost service did not stop for Low mode", it) }
    }

    /** OEA Home came to the front: any running game session has ended. */
    fun onHomeResumed() {
        main.removeCallbacks(probeRunnable)
        probeContext = null
        service?.onHomeShown()
    }

    /**
     * OEA Home stopped. If the user opened a game from Recents or a
     * notification instead of from OEA, one short Usage access query notices
     * it. It runs within Android's grace window for starting a foreground
     * service after leaving the foreground, and never repeats.
     */
    fun onHomeStopped(context: Context) {
        val app = context.applicationContext
        if (!OeaGameBoostStore.enabled(app) || service?.currentGame() != null) return
        if (OeaAssistant.isConnected()) return // window events already cover this, with no query at all
        if (!leaveHomeCheck(app) || !usageAccess(app)) return
        probeContext = app
        probeAttempts = 0
        main.removeCallbacks(probeRunnable)
        main.postDelayed(probeRunnable, 1_200L)
    }

    @Volatile private var probeContext: Context? = null
    private var probeAttempts = 0
    private val probeRunnable = object : Runnable {
        override fun run() {
            val app = probeContext ?: return
            probeAttempts++
            val attempt = probeAttempts
            worker.execute {
                val front = runCatching { lastResumedPackage(app, 15_000L) }.getOrNull()
                main.post {
                    if (probeContext !== app) return@post
                    val pkg = front?.first
                    val cls = front?.second
                    when {
                        pkg != null && pkg != app.packageName && OeaGameBoostStore.isGame(app, pkg) -> {
                            probeContext = null
                            startSession(app, pkg)
                        }
                        // OEA Home is back, or the user simply went to another app.
                        pkg == app.packageName && cls == OeaLauncherActivity::class.java.name -> probeContext = null
                        attempt < 2 -> main.postDelayed(this, 2_000L) // the game may still be starting
                        else -> probeContext = null
                    }
                }
            }
        }
    }

    /** Applies changed Game Boost settings to a running session (never starts one). */
    fun refresh() {
        service?.refreshSession()
    }

    /** Ends the running session, e.g. when Game Boost is switched off. */
    fun endSession(context: Context) {
        val running = service
        if (running != null) running.endSession("ended by the user") else {
            context.applicationContext.stopService(Intent(context.applicationContext, OeaGameBoostService::class.java))
        }
    }

    private fun startSession(context: Context, packageName: String) {
        val running = service
        if (running != null) {
            running.beginSession(packageName)
            return
        }
        val intent = Intent(context, OeaGameBoostService::class.java)
            .setAction(ACTION_START_SESSION)
            .putExtra(EXTRA_PACKAGE, packageName)
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }.onFailure { error ->
            // Android may refuse a background start (for example after the
            // grace window). Nothing lingers: the next launch from OEA works.
            Log.w(TAG, "Android did not allow starting the Game Boost session for $packageName", error)
        }
    }

    /** Latest ACTIVITY_RESUMED package and class within [windowMs], via Usage access. */
    internal fun lastResumedPackage(context: Context, windowMs: Long): Pair<String, String?>? {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val end = System.currentTimeMillis()
        val events = usm.queryEvents(end - windowMs, end) ?: return null
        val event = UsageEvents.Event()
        var latest: Pair<String, String?>? = null
        var latestAt = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val resumed = if (Build.VERSION.SDK_INT >= 29) {
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            } else {
                @Suppress("DEPRECATION")
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            }
            if (resumed && event.timeStamp >= latestAt) {
                latestAt = event.timeStamp
                latest = event.packageName to event.className
            }
        }
        return latest
    }
}
