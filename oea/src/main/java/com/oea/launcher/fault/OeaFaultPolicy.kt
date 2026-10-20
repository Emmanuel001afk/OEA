package com.oea.launcher.fault

/**
 * Pure fault model. No Android types, so the classification rules can be
 * unit tested on the JVM (see `oea/src/test`).
 */

/** Where a fault came from, decided from the thread that threw. */
enum class ThreadKind {
    /** OEA Home's own UI thread. */
    MAIN_HOME,

    /** UI thread while a game, hub, boost or other OEA screen is in front. */
    MAIN_SCREEN,

    /** A worker thread OEA or the engine started (sockets, audio, decode, jobs). */
    WORKER,

    /** A thread owned by a framework view (OpenGL rendering for games). */
    RENDER,

    /** A Binder call into one of OEA's services (accessibility, notification listener). */
    BINDER,

    /** Anything else, including threads owned by a third-party widget provider. */
    UNKNOWN,
}

/** What OEA does about a fault. Every value except [ESCALATE] keeps Home alive. */
enum class FaultAction {
    /** Log it and carry on; the subsystem that failed already stopped itself. */
    CONTAIN,

    /** Log it and close only the OEA screen in front (game, hub, boost, phone). */
    CLOSE_SCREEN,

    /** Log it, keep the saved layout and rebuild the Home surface in place. */
    REBUILD_HOME,

    /** Rebuild Home with optional subsystems (widgets, glance, island) switched off. */
    SAFE_MODE,

    /** The process cannot be trusted any more; let Android do its normal restart. */
    ESCALATE,
}

/** Everything the policy needs to know about one fault. */
data class FaultInput(
    val kind: ThreadKind,
    /** Subsystem tag, e.g. `home`, `game:runner`, `island-host`, `widget-host`.
     * The runner id is intentionally stable even though its public name is Endless Days. */
    val subsystem: String,
    /** Faults of the same subsystem seen in [windowMs] so far, including this one. */
    val repeats: Int,
    val windowMs: Long,
    /** True for VirtualMachineError (out of memory, internal error), which cannot be survived. */
    val fatalError: Boolean,
    /** True for StackOverflowError, which is recoverable once the stack unwinds. */
    val stackOverflow: Boolean,
    /** Home is already running with optional subsystems disabled. */
    val alreadyInSafeMode: Boolean,
    /** Home rendered successfully at least once this session. */
    val homeRendered: Boolean,
    /** The screen in front belongs to OEA (a game, the hub, Game Boost, …). */
    val onOeaScreen: Boolean
) {
    val isGame get() = subsystem.startsWith("game:") || subsystem.startsWith("oea-games") || subsystem.startsWith("engine")
    val isHome get() = subsystem == "home" || subsystem.startsWith("home:") || subsystem.startsWith("workspace")
}

/**
 * OEA's stability rules. The intent, in order of priority:
 *
 * 1. Never take the launcher down because of something outside Home: a game,
 *    a widget provider, a socket thread, an accessibility callback.
 * 2. Prefer the least destructive action that keeps the user's screen usable.
 * 3. Stop looping: repeated faults at one site escalate (rebuild → safe mode)
 *    until the cause is disabled rather than retried forever.
 * 4. Only give up when the process genuinely cannot continue.
 */
object OeaFaultPolicy {
    /** Home faults tolerated inside the window before the surface is rebuilt. */
    const val HOME_REBUILD_AFTER = 1

    /** Home faults inside the window that make OEA drop optional subsystems. */
    const val HOME_SAFE_MODE_AFTER = 3

    /** Window used for both counters above. */
    const val WINDOW_MS = 20_000L

    fun decide(input: FaultInput): FaultDecision = when {
        input.fatalError && input.kind == ThreadKind.WORKER ->
            FaultDecision(FaultAction.CONTAIN, "worker thread ran out of memory; its subsystem stopped")

        input.fatalError ->
            FaultDecision(FaultAction.ESCALATE, "the virtual machine cannot continue (${input.subsystem})")

        input.kind == ThreadKind.MAIN_HOME -> when {
            input.repeats >= HOME_SAFE_MODE_AFTER && !input.alreadyInSafeMode ->
                FaultDecision(FaultAction.SAFE_MODE, "${input.repeats} Home faults in ${input.windowMs / 1000}s; starting Home without optional subsystems")

            input.repeats >= HOME_SAFE_MODE_AFTER ->
                FaultDecision(FaultAction.ESCALATE, "Home keeps failing even in safe mode; letting Android restart it")

            input.homeRendered ->
                FaultDecision(FaultAction.REBUILD_HOME, "recovering the running Home surface")

            else ->
                FaultDecision(FaultAction.REBUILD_HOME, "Home has not rendered yet; rebuilding the surface")
        }

        input.kind == ThreadKind.MAIN_SCREEN ->
            if (input.onOeaScreen) FaultDecision(FaultAction.CLOSE_SCREEN, "closing the OEA screen in front; Home is untouched")
            else FaultDecision(FaultAction.CONTAIN, "the fault belonged to another app's window")

        input.kind == ThreadKind.RENDER || input.isGame ->
            if (input.repeats > CLOSE_AFTER_REPEATS)
                FaultDecision(FaultAction.CLOSE_SCREEN, "the game keeps faulting; closing it")
            else
                FaultDecision(FaultAction.CLOSE_SCREEN, "the game stopped; OEA Home keeps running")

        input.repeats >= LOOP_LIMIT ->
            FaultDecision(FaultAction.CONTAIN, "${input.subsystem} faulted ${input.repeats} times; it is disabled for this session")

        else -> FaultDecision(FaultAction.CONTAIN, "${input.subsystem} was contained")
    }

    /** A subsystem that faults this often in the window is switched off for the session. */
    const val LOOP_LIMIT = 12

    /** Game faults before the game screen is force-closed. */
    const val CLOSE_AFTER_REPEATS = 4

    /**
     * Classifies a throwable. [VirtualMachineError] means the process is no
     * longer trustworthy, except on a worker thread where the memory was that
     * thread's own and the heap recovers after it unwinds.
     */
    fun isFatalError(error: Throwable): Boolean =
        error is VirtualMachineError && error !is StackOverflowError

    fun isStackOverflow(error: Throwable): Boolean = error is StackOverflowError

    /**
     * True when the visible activity is an independently recoverable OEA screen,
     * not the launcher workspace itself. Used by the crash boundary and stall
     * watchdog so a mini-app or utility screen cannot be mistaken for Home.
     */
    fun isRecoverableOeaScreenName(className: String): Boolean {
        val name = className.lowercase()
        return name.contains(".games.") ||
            name.contains("gameboost") ||
            name.contains(".phone.") ||
            name.contains(".callblocker.") ||
            name.contains(".freezer.") ||
            name.contains(".applock.") ||
            name.contains(".assistant.") ||
            name.startsWith("com.oea.launcher.miniapps.")
    }

    /** Stable diagnostic identity for each concrete mini-app activity. */
    fun subsystemForScreenClassName(className: String): String? {
        val prefix = "com.oea.launcher.miniapps."
        if (!className.startsWith(prefix)) return null
        val simpleName = className.substringAfterLast('.')
        if (simpleName == "OeaMiniAppActivity") return "miniapp:host"
        val id = simpleName.removePrefix("Oea").removeSuffix("Activity")
            .replace(Regex("([a-z])([A-Z])"), "$1-$2")
            .lowercase()
        return "miniapp:" + id.ifBlank { "unknown" }
    }

    /**
     * Maps a thread to its role. [screenIsGame] is supplied by the caller,
     * which knows what OEA screen (if any) is in front.
     */
    fun classify(thread: Thread, mainThread: Thread, screenIsGame: Boolean): ThreadKind = when {
        thread === mainThread -> if (screenIsGame) ThreadKind.MAIN_SCREEN else ThreadKind.MAIN_HOME
        thread.name.startsWith("GLThread") -> ThreadKind.RENDER
        thread.name.startsWith("oea-") || thread.name.startsWith("Oea") || thread.name.startsWith("DefaultDispatcher") ||
            thread.name.startsWith("kotlinx") || thread.name.contains("AsyncTask") || thread.name.startsWith("pool-") -> ThreadKind.WORKER

        thread.name.startsWith("Binder:") || thread.name.startsWith("binder:") -> ThreadKind.BINDER
        else -> ThreadKind.UNKNOWN
    }
}

/** A decision plus the reason shown in the diagnostics screen. */
data class FaultDecision(val action: FaultAction, val reason: String) {
    /** True when OEA handles the fault itself and the process must keep running. */
    val keepsLauncherAlive: Boolean get() = action != FaultAction.ESCALATE
}
