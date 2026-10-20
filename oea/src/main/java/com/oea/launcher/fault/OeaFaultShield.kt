package com.oea.launcher.fault

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.oea.engine.core.OeaGuard
import com.oea.launcher.data.OeaDataStore
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * OEA's stability boundary.
 *
 * OEA is a home screen: if the process dies, the user loses the launcher, not
 * just a feature. So every fault inside the app is routed here — from a game,
 * a widget provider, a socket thread, an accessibility callback or Home itself —
 * and [OeaFaultPolicy] decides the least destructive way to keep Home usable.
 *
 * Three layers work together:
 *
 * 1. [install] replaces the process default uncaught-exception handler, so a
 *    fault on *any* thread (including framework threads such as a game's
 *    GLThread) is classified before Android kills the process.
 * 2. [guard] / [value] wrap the entry points of each OEA screen and service, so
 *    a fault is handled where it is understood instead of escaping.
 * 3. A stall watchdog samples Home's UI thread, so a hang (a slow widget, a
 *    blocked content provider) is recorded with its stack instead of showing
 *    Android's "OEA Launcher isn't responding" dialog.
 *
 * A fault that reaches the main thread's own uncaught-exception handler has
 * already unwound `Looper.loop()`, so merely posting a recovery Runnable would
 * never run — nothing pumps that queue once the loop has returned. When the
 * fault belongs to a game (or any other OEA screen) rather than Home itself,
 * [onUncaught] handles it and then resumes the very same Looper in place
 * (its `MessageQueue` was never destroyed, only the pump loop returned). That
 * is what actually keeps OEA alive and the default launcher unchanged instead
 * of the whole process dying — which is what used to carry the user out of
 * OEA and back to Android's "set default launcher" prompt. Repeated crashes in
 * a short window still fall through to a real process restart rather than
 * looping forever.
 *
 * Nothing here is a substitute for fixing the fault; the point is that a fault
 * in one part of OEA must never be visible as a launcher crash.
 */
object OeaFaultShield {
    private const val TAG = "OeaFault"

    /** What the front screen must provide for the shield to be able to help it. */
    interface Recovery {
        /** Rebuilds Home's surface in place, keeping the saved layout. True when done. */
        fun rebuildHome(): Boolean = false

        /** Closes only the OEA screen in front (game, hub, boost, phone). True when closed. */
        fun closeFrontScreen(): Boolean = false

        /** Restarts Home with optional subsystems disabled. True when handled. */
        fun enterSafeMode(): Boolean = false
    }

    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val mainThread = Looper.getMainLooper().thread
    private val repeats = HashMap<String, MutableList<Long>>()
    private val listeners = CopyOnWriteArraySet<(OeaFaultRecord) -> Unit>()
    private val stallRecords = AtomicInteger()
    private val pendingToken = AtomicLong()
    @Volatile private var servedToken = 0L
    @Volatile private var lastStallAt = 0L
    @Volatile private var installed = false
    @Volatile private var watchdogRunning = false
    @Volatile private var previous: Thread.UncaughtExceptionHandler? = null
    @Volatile
    var recovery: Recovery? = null
        private set
    @Volatile private var frontScreen: Activity? = null
    @Volatile private var frontIsGame = false
    @Volatile private var frontIsRecoverableScreen = false
    @Volatile private var homeRendered = false

    /** Home is on screen and usable; faults are then recovered in place instead of shown. */
    @Volatile var homeHealthy = false
        private set

    /** Optional subsystems (widgets, At a Glance, island, game boost overlays) are skipped. */
    @Volatile var safeMode = false
        private set

    /** True while the front screen is an OEA game or the games hub. */
    val isGameScreenInFront get() = frontIsGame

    fun addListener(listener: (OeaFaultRecord) -> Unit) { listeners += listener }
    fun removeListener(listener: (OeaFaultRecord) -> Unit) { listeners -= listener }

    /**
     * A screen that wants to present faults itself while it is in front (Home does,
     * because it can offer a "Details" button straight into the fault log).
     *
     * Returning true means "I showed it"; the shield then stays quiet. This exists
     * because containing a fault *silently* was the worst of both worlds: the user
     * saw something misbehave and had no way to find out what. Every contained fault
     * is now either shown by a screen or announced, never only written to a file.
     */
    @Volatile private var alertPresenter: ((OeaFaultRecord) -> Boolean)? = null

    fun setAlertPresenter(presenter: ((OeaFaultRecord) -> Boolean)?) { alertPresenter = presenter }

    /** Announces a contained fault that no screen took responsibility for. */
    private fun announce(record: OeaFaultRecord) {
        val app = appContext ?: return
        val now = System.currentTimeMillis()
        val last = lastAlert
        // At most one announcement every few seconds: a fault loop must not turn
        // into a toast loop, which is its own kind of unusable launcher.
        if (now - last.at < 12_000L && last.subsystem == record.subsystem) return
        lastAlert = last.copy(at = now, subsystem = record.subsystem)
        runCatching {
            android.widget.Toast.makeText(
                app,
                "OEA contained a problem in ${record.subsystemLabel()}. Settings › Stability has the details.",
                android.widget.Toast.LENGTH_LONG
).show()
        }
    }

    private data class AlertMark(val at: Long = 0L, val subsystem: String = "")

    @Volatile private var lastAlert = AlertMark()

    // ------------------------------------------------------------------ install

    fun install(app: Application) {
        if (installed) return
        installed = true
        appContext = app
        OeaFaultLog.init(app)
        safeMode = OeaDataStore.get(app).homeSafeMode()
        // A fatal fault shortly before this start means the last session ended badly;
        // give Home a clean, minimal start instead of replaying the same failure.
        val lastFatal = OeaFaultLog.latest(FaultAction.ESCALATE)
        if (lastFatal > 0L && System.currentTimeMillis() - lastFatal < 120_000L && !safeMode) {
            safeMode = true
            OeaDataStore.get(app).setHomeSafeMode(true)
            Log.w(TAG, "Previous OEA session ended in an unrecoverable fault; Home starts in safe mode")
        }
        trackFrontScreen(app)
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error -> onUncaught(thread, error) }
        // The engine is a library inside this process; let it report faults here too,
        // so a contained engine fault still shows up in OEA's health log.
        OeaGuard.sink = OeaGuard.Sink { source, error, fatal ->
            val kind = OeaFaultPolicy.classify(Thread.currentThread(), mainThread, frontIsRecoverableScreen)
            handle(if (fatal && kind == ThreadKind.UNKNOWN) ThreadKind.WORKER else kind, source, error, fatal, contained = true)
        }
        startWatchdog()
    }

    /** Home calls this after its first successful attach, once per session. */
    fun noteHomeRendered() {
        homeRendered = true
        homeHealthy = true
    }

    fun noteHomeUnhealthy() { homeHealthy = false }

    fun setRecovery(value: Recovery?) { recovery = value }

    fun setSafeMode(enabled: Boolean, persist: Context? = null) {
        safeMode = enabled
        persist?.let { OeaDataStore.get(it).setHomeSafeMode(enabled) }
    }

    // ---------------------------------------------------------------- guarding

    /**
     * Runs [block] with the fault policy behind it. Returns true when the block
     * completed. Used at every screen and service entry point so a fault is
     * handled where it can be explained and recovered.
     */
    inline fun guard(owner: String, block: () -> Unit): Boolean {
        try {
            block()
            return true
        } catch (t: Throwable) {
            OeaFaultShield.onFaulted(owner, t, contained = true)
            return false
        }
    }

    /** [guard] for code that produces a value. */
    inline fun <T> value(owner: String, fallback: T, block: () -> T): T =
        try {
            block()
        } catch (t: Throwable) {
            OeaFaultShield.onFaulted(owner, t, contained = true)
            fallback
        }

    /** Records an already-handled problem (a fallback was taken) without triggering recovery. */
    fun note(owner: String, error: Throwable) {
        val record = OeaFaultRecord.of(
            kind = OeaFaultPolicy.classify(Thread.currentThread(), mainThread, frontIsRecoverableScreen),
            thread = Thread.currentThread(),
            subsystem = owner,
            error = error,
            action = FaultAction.CONTAIN,
            reason = "handled with a fallback",
            repeats = 1,
            process = processName()
)
        OeaFaultLog.record(record)
        publish(record)
    }

    /** Records an already-handled problem described by text only (no exception object). */
    fun note(owner: String, message: String) = note(owner, IllegalStateException(message))

    /**
     * Called by [guard] / [value] when a screen or service entry point throws.
     *
     * The fault is recorded, and Home-scoped faults additionally run the policy's
     * action: a Home that failed to resume or redraw is not a Home the user can
     * use, so the surface is rebuilt (or safe mode is entered) right away. A
     * game-scoped fault is left alone here — the game screen owns its own
     * recovery, and Home must not react to it at all.
     */
    fun onFaulted(owner: String, error: Throwable, contained: Boolean) {
        val kind = OeaFaultPolicy.classify(Thread.currentThread(), mainThread, frontIsRecoverableScreen)
        val action = handle(kind, owner, error, fatal = false, contained = contained, thread = Thread.currentThread())
        val homeScoped = owner == "home" || owner.startsWith("home:") || owner.startsWith("workspace")
        if (homeScoped && (action == FaultAction.REBUILD_HOME || action == FaultAction.SAFE_MODE)) act(action)
    }

    // ------------------------------------------------------------- fault intake

    /** How many times in a row we have resumed the main Looper after a game-screen fault. */
    private val consecutiveMainResumes = AtomicInteger()

    private fun onUncaught(thread: Thread, error: Throwable) {
        val kind = OeaFaultPolicy.classify(thread, mainThread, frontIsRecoverableScreen)
        val subsystem = subsystemOf(thread, error)
        // An uncaught fault on the main thread has already unwound Looper.loop(),
        // which stops the message queue from being pumped — merely posting a
        // "close the screen" Runnable at this point would never run, and the
        // process is a zombie until Android kills it. The only way to actually
        // keep Home alive is to resume pumping that same Looper ourselves right
        // here, after handling the fault, instead of letting the thread finish
        // and the process die. This only applies while a game or other OEA
        // screen (not Home itself) was in front: Home's own main-thread faults
        // keep going through [escalate], exactly as before.
        if (thread === mainThread) {
            if (kind == ThreadKind.MAIN_SCREEN && frontScreen != null && homeRendered &&
                consecutiveMainResumes.get() < MAX_CONSECUTIVE_RESUMES
            ) {
                consecutiveMainResumes.incrementAndGet()
                val seen = runCatching { countRecent(subsystem) }.getOrDefault(1)
                val input = FaultInput(
                    kind = kind, subsystem = subsystem, repeats = seen, windowMs = OeaFaultPolicy.WINDOW_MS,
                    fatalError = OeaFaultPolicy.isFatalError(error), stackOverflow = OeaFaultPolicy.isStackOverflow(error),
                    alreadyInSafeMode = safeMode, homeRendered = homeRendered, onOeaScreen = true
                )
                val decision = OeaFaultPolicy.decide(input)
                val record = OeaFaultRecord.of(kind, thread, subsystem, error, decision.action, decision.reason, seen, processName())
                runCatching { OeaFaultLog.record(record) }
                publish(record)
                // The crashed call stack has already unwound; nothing it held is
                // still running, so acting now (closing the game's activity) is safe.
                runCatching { act(decision.action) }
                // Repeated crashes in a short window fall through to a real
                // process restart instead of resuming forever; once this session
                // has gone 15s without another main-thread game fault, forgive it.
                main.postDelayed({ consecutiveMainResumes.set(0) }, 15_000L)
                // Resume the SAME Looper (its MessageQueue was never destroyed,
                // only the pump loop returned): this is what actually prevents
                // the process from dying and Android from forgetting OEA is the
                // default launcher. If resuming itself faults again, handle that
                // fault the same way (it still counts against the limit above).
                try {
                    Looper.loop()
                } catch (inner: Throwable) {
                    onUncaught(thread, inner)
                }
                return
            }
            val seen = runCatching { countRecent(subsystem) }.getOrDefault(1)
            runCatching {
                OeaFaultLog.record(
                    OeaFaultRecord.of(
                        kind = kind,
                        thread = thread,
                        subsystem = subsystem,
                        error = error,
                        action = FaultAction.ESCALATE,
                        reason = "uncaught fault on the UI thread; the process restarts instead of leaving a frozen Home",
                        repeats = seen,
                        process = processName()
                    )
                )
            }
            escalate(thread, error)
            return
        }
        // A worker, GL or binder thread can die without taking the UI loop with
        // it, so the policy's containment actions genuinely survive there.
        val action = handle(kind, subsystem, error, fatal = true, contained = false, thread = thread)
        if (action == FaultAction.ESCALATE) escalate(thread, error)
    }

    /** Hands the dying thread back to Android's own handler (records the crash and kills the process). */
    private fun escalate(thread: Thread, error: Throwable) {
        val handler = previous
        if (handler != null) {
            runCatching { handler.uncaughtException(thread, error) }
        } else {
            Log.e(TAG, "Unrecoverable fault with no previous handler; killing the process so Home can restart", error)
            runCatching { android.os.Process.killProcess(android.os.Process.myPid()) }
            runCatching { kotlin.system.exitProcess(10) }
        }
    }

    /** @return the action taken, so [onUncaught] can decide whether to escalate. */
    private fun handle(
        kind: ThreadKind,
        subsystem: String,
        error: Throwable,
        fatal: Boolean,
        contained: Boolean,
        thread: Thread = Thread.currentThread()
): FaultAction {
        val seen = countRecent(subsystem)
        val input = FaultInput(
            kind = kind,
            subsystem = subsystem,
            repeats = seen,
            windowMs = OeaFaultPolicy.WINDOW_MS,
            fatalError = fatal && OeaFaultPolicy.isFatalError(error),
            stackOverflow = OeaFaultPolicy.isStackOverflow(error),
            alreadyInSafeMode = safeMode,
            homeRendered = homeRendered,
            onOeaScreen = frontScreen != null
)
        var decision = OeaFaultPolicy.decide(input)
        // A fault the caller already handled never escalates; recovery is optional.
        if (contained && decision.action == FaultAction.ESCALATE) {
            decision = FaultDecision(FaultAction.SAFE_MODE, "repeated faults at $subsystem; disabling optional subsystems")
        }
        val record = OeaFaultRecord.of(kind, thread, subsystem, error, decision.action, decision.reason, seen, processName())
        OeaFaultLog.record(record)
        publish(record)
        if (!contained) act(decision.action)
        return decision.action
    }

    private fun act(action: FaultAction) {
        when (action) {
            FaultAction.CONTAIN -> Unit
            FaultAction.CLOSE_SCREEN -> main.post {
                // Every action is itself contained: recovery must not be able to
                // produce a second fault (which would loop through the handler).
                val r = recovery
                if (r == null || !OeaFaultShield.value("fault:close", false) { r.closeFrontScreen() }) closeFrontActivity()
            }

            FaultAction.REBUILD_HOME -> main.post {
                val r = recovery
                if (r == null || !OeaFaultShield.value("fault:rebuild", false) { r.rebuildHome() }) {
                    // A failed Home rebuild must never finish the launcher Activity.
                    // Closing it can make Android forget the active Home surface and
                    // leave the user at a blank screen or the default-launcher chooser.
                    homeHealthy = false
                    safeMode = true
                    context()?.let { app -> OeaDataStore.get(app).setHomeSafeMode(true) }
                    val recoveredInSafeMode = r != null &&
                        OeaFaultShield.value("fault:safe-mode-after-rebuild", false) { r.enterSafeMode() }
                    if (!recoveredInSafeMode) restartHome()
                }
            }

            FaultAction.SAFE_MODE -> main.post {
                safeMode = true
                context()?.let { app -> OeaDataStore.get(app).setHomeSafeMode(true) }
                val r = recovery
                if (r == null || !OeaFaultShield.value("fault:safe-mode", false) { r.enterSafeMode() }) restartHome()
            }

            FaultAction.ESCALATE -> Unit
        }
    }

    private fun publish(record: OeaFaultRecord) {
        main.post {
            listeners.toList().forEach { runCatching { it(record) } }
            val shown = runCatching { alertPresenter?.invoke(record) == true }.getOrDefault(false)
            if (!shown) runCatching { announce(record) }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun closeFrontActivity() {
        val activity = frontScreen ?: return
        main.post { runCatching { if (!activity.isFinishing) activity.finish() } }
    }

    /** Last resort: ask Android to bring Home up again (the activity recreates itself). */
    private fun restartHome() {
        val app = context() ?: return
        main.post {
            runCatching {
                val intent = app.packageManager.getLaunchIntentForPackage(app.packageName)
                    ?: return@runCatching
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                app.startActivity(intent)
            }.onFailure { Log.w(TAG, "Home could not be restarted automatically", it) }
        }
    }

    private fun subsystemOf(thread: Thread, error: Throwable): String {
        val name = thread.name
        val fromThread = when {
            name.startsWith("GLThread") -> "game:renderer"
            name.startsWith("oea-") -> "engine:" + name.removePrefix("oea-").takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
            else -> null
        }
        if (fromThread != null) return fromThread
        val top = error.stackTrace.firstOrNull()?.className.orEmpty()
        val screenSubsystem = if (thread === mainThread) {
            frontScreen?.javaClass?.name?.let { OeaFaultPolicy.subsystemForScreenClassName(it) }
        } else null
        if (screenSubsystem != null) return screenSubsystem
        return when {
            top.startsWith("com.oea.launcher.miniapps.") ->
                OeaFaultPolicy.subsystemForScreenClassName(top) ?: "miniapp:unknown"
            top.startsWith("com.oea.games.") -> "game:" + top.substringAfter("com.oea.games.").substringBefore('.')
            top.startsWith("com.oea.engine.") -> "engine:" + top.substringAfter("com.oea.engine.").substringBefore('.')
            top.startsWith("com.oea.launcher.games.") -> "oea-games"
            top.startsWith("com.oea.launcher.gameboost.") -> "game-boost"
            top.startsWith("com.oea.launcher.phone.") -> "phone"
            top.startsWith("com.oea.launcher.callblocker.") -> "call-blocker"
            top.startsWith("com.oea.launcher.freezer.") || top.startsWith("com.oea.launcher.applock.") -> "freezer"
            top.startsWith("com.oea.launcher.assistant.") -> "assistant"
            top.startsWith("com.oea.launcher.widgets") -> "widget-host"
            top.startsWith("com.oea.launcher.workspace.") || top.startsWith("com.oea.launcher.engine.") ||
                top.startsWith("com.oea.launcher.ui.") || top.startsWith("com.oea.launcher.model.") -> "home"
            else -> if (thread === mainThread) "home" else "system"
        }
    }

    private fun countRecent(subsystem: String): Int = synchronized(lock) {
        val now = System.currentTimeMillis()
        val list = repeats.getOrPut(subsystem) { ArrayList() }
        list.add(now)
        while (list.isNotEmpty() && now - list.first() > OeaFaultPolicy.WINDOW_MS) list.removeAt(0)
        if (list.size > 64) list.subList(0, list.size - 64).clear()
        list.size
    }

    /** Fault counts per subsystem for the current session, used by the health list. */
    fun sessionCounts(): Map<String, Int> = synchronized(lock) { repeats.mapValues { it.value.size } }

    // ---------------------------------------------------------------- stall net

    /**
     * Samples Home's UI thread. A token posted to the main Looper that is not
     * served within [STALL_MS] means something blocked it; the stack says what.
     */
    private fun startWatchdog() {
        if (watchdogRunning) return
        watchdogRunning = true
        Thread({
            while (true) {
                val token = pendingToken.incrementAndGet()
                runCatching { main.post { servedToken = token } }
                Thread.sleep(TICK_MS)
                val unserved = pendingToken.get() - servedToken
                if (unserved >= STALL_TICKS) {
                    servedToken = pendingToken.get()
                    recordStall(unserved)
                }
            }
        }, "oea-watchdog").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun recordStall(unserved: Long) {
        // A game in front legitimately keeps the shared main thread busy with
        // nothing to do about it, and a paused Home can be idle by design.
        if (frontIsRecoverableScreen) return
        // Use monotonic elapsed time for the cooldown; wall-clock changes must
        // not disable or flood the Home stall watchdog.
        val elapsedNow = android.os.SystemClock.elapsedRealtime()
        if (elapsedNow - lastStallAt < 30_000L) return
        lastStallAt = elapsedNow
        val now = System.currentTimeMillis()
        val frames = runCatching {
            mainThread.stackTrace.take(5).map { "${it.methodName}(${it.lineNumber})" }
        }.getOrDefault(emptyList())
        stallRecords.incrementAndGet()
        val blockedMs = unserved * TICK_MS
        val record = OeaFaultRecord(
            at = now,
            process = processName(),
            thread = "main",
            kind = ThreadKind.MAIN_HOME,
            subsystem = "home:stall",
            errorClass = "UiThreadBlocked",
            message = "Home's UI thread did not respond for about ${blockedMs}ms",
            frames = frames,
            action = if (stallRecords.get() >= 3) FaultAction.SAFE_MODE else FaultAction.CONTAIN,
            reason = if (stallRecords.get() >= 3) "repeated stalls; optional subsystems will be skipped" else "recorded for diagnosis",
            repeats = stallRecords.get()
)
        OeaFaultLog.record(record)
        publish(record)
        if (stallRecords.get() >= 3) act(FaultAction.SAFE_MODE)
    }

    /** Application context, set by [install]; used for preference writes and restarts. */
    private var appContext: Context? = null

    /** Null only before [install] ran, in which case there is nothing to recover. */
    private fun context(): Context? = appContext

    private fun processName(): String = runCatching {
        if (Build.VERSION.SDK_INT >= 28) Application.getProcessName()
        else String(readBytes(File("/proc/self/cmdline"))).trim('\u0000', ' ')
    }.getOrDefault("com.oea.launcher")

    private fun readBytes(file: File): ByteArray = file.readBytes()

    // ------------------------------------------------------------- front screen

    private fun trackFrontScreen(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                frontScreen = activity
                frontIsGame = isGameActivity(activity)
                frontIsRecoverableScreen = isRecoverableOeaActivity(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                if (frontScreen === activity) {
                    frontScreen = null
                    frontIsGame = false
                    frontIsRecoverableScreen = false
                }
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                if (frontScreen === activity) {
                    frontScreen = null
                    frontIsGame = false
                    frontIsRecoverableScreen = false
                }
            }
        })
    }

    private fun isGameActivity(activity: Activity): Boolean {
        val name = activity.javaClass.name
        return name.contains(".games.") || name.contains("GameBoost") || name.contains(".gameboost.")
    }

    private fun isRecoverableOeaActivity(activity: Activity): Boolean =
        isGameActivity(activity) ||
            OeaFaultPolicy.isRecoverableOeaScreenName(activity.javaClass.name)

    private const val TICK_MS = 1_000L
    private const val STALL_TICKS = 6L
    /** Consecutive main-thread game faults resumed in place before a real process restart is used instead. */
    private const val MAX_CONSECUTIVE_RESUMES = 5
}
