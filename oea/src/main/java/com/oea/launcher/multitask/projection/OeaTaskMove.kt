package com.oea.launcher.multitask.projection

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.os.IBinder
import java.lang.reflect.InvocationTargetException

/**
 * Attempts to move a task OEA already owns onto OEA's own virtual display.
 *
 * Why this exists (AOSP sources, Android 13 r84): an activity may only be
 * PLACED on an app-created display via `launchDisplayId` while the caller's
 * uid already has an ACTIVITY RECORD on that display
 * (`ActivityTaskSupervisorService.isCallerAllowedToLaunchOnDisplay` —
 * windows such as Presentations do not count). That is a chicken-and-egg
 * problem for the very first activity, which stock Android therefore never
 * lets an ordinary app solve by launching; the remaining legal mechanism is
 * to start that activity normally (it lands on the phone) and then ASK the
 * system to re-parent its task to the display:
 *
 *  1. `ActivityManager.moveTaskToDisplay(taskId, displayId)` — an OEM-public
 *     API on several Android 13 forks (it is hidden in stock AOSP);
 *  2. the same `moveRootTaskToDisplay`/`moveTaskToDisplay` calls through
 *     the raw `activity_task` binder, for builds where the framework
 *     wrapper above is unavailable — the system server still enforces its
 *     own permissions on every call (stock AOSP requires the signature
 *     permission INTERNAL_SYSTEM_WINDOW here, so this only succeeds on
 *     builds whose manufacturer relaxed it);
 *  3. `ActivityManager.startActivityFromRecents(taskId, options)` with
 *     `launchDisplayId` in the options, which re-launches a task that is
 *     already in Recents onto a display (subject to the same display rule
 *     as any launch, so it only helps once the rule can pass).
 *
 * (The `ActivityTaskManager.getService()` wrapper of route 2 is deliberately
 * NOT used: the platform blocklist forbids reflecting into it for apps
 * targeting API 36 and above, so it is dead by construction on modern
 * targets. The raw binder reaches the same system service through a handle
 * the platform itself hands out, and every outcome — accepted, refused, or
 * permission-demanded — is recorded as evidence.)
 *
 * Every attempt is returned as evidence with its exact outcome — including
 * the permissions the platform demanded — because this ladder is exactly the
 * place where one manufacturer differs from the next. Nothing here bypasses
 * a platform check: each call still goes to the system server, which enforces
 * its own permissions, and every refusal is recorded, not retried blindly.
 *
 * The target application is never re-parented by this object — only OEA's
 * own starter task. The target inherits the display by being started from
 * the starter once the starter sits there (see
 * [OeaProjectionTrampolineActivity]).
 */
object OeaTaskMove {

    /** One reparent attempt, recorded for the Stage-B report. */
    data class Attempt(
        val api: String,
        val ok: Boolean,
        val detail: String
    ) {
        fun toEvidenceLine(): OeaEvidenceLine = OeaEvidenceLine(
            "Reparent API: $api",
            if (ok) "call accepted" else "refused — $detail"
        )
    }

    /**
     * Tries every task-move API this device exposes, in order. Returns one
     * [Attempt] per API (never throws; a missing API is itself recorded).
     */
    fun moveTaskToDisplay(context: Context, taskId: Int, displayId: Int): List<Attempt> {
        val attempts = mutableListOf<Attempt>()
        attempts += tryAmMove(context, taskId, displayId)
        attempts += tryRawBinder(taskId, displayId)
        return attempts
    }

    /**
     * Re-launches a task that is already in Recents onto a display, using
     * the (hidden but reflection-reachable) `startActivityFromRecents`.
     */
    fun relaunchOnDisplay(context: Context, taskId: Int, options: Bundle): Attempt {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return Attempt("ActivityManager.startActivityFromRecents", false, "no ActivityManager")
        return try {
            val method = am.javaClass.methods.firstOrNull {
                it.name == "startActivityFromRecents" && it.parameterTypes.size == 2
            } ?: return Attempt(
                "ActivityManager.startActivityFromRecents", false,
                "not present on this device's ActivityManager"
            )
            method.isAccessible = true
            method.invoke(am, taskId, options)
            Attempt("ActivityManager.startActivityFromRecents", true, "call accepted")
        } catch (t: Throwable) {
            Attempt(
                "ActivityManager.startActivityFromRecents", false,
                unwrap("startActivityFromRecents", t)
            )
        }
    }

    // ------------------------------------------------------------- private

    private fun tryAmMove(context: Context, taskId: Int, displayId: Int): Attempt {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return Attempt("ActivityManager.moveTaskToDisplay", false, "no ActivityManager")
        return try {
            val method = am.javaClass.methods.firstOrNull { it.name == "moveTaskToDisplay" }
                ?: return Attempt(
                    "ActivityManager.moveTaskToDisplay", false,
                    "not present on this device's ActivityManager"
                )
            method.isAccessible = true
            method.invoke(am, taskId, displayId)
            Attempt("ActivityManager.moveTaskToDisplay", true, "call accepted")
        } catch (t: Throwable) {
            Attempt("ActivityManager.moveTaskToDisplay", false, unwrap("moveTaskToDisplay", t))
        }
    }

    private fun tryRawBinder(taskId: Int, displayId: Int): Attempt {
        val label = "binder activity_task"
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getDeclaredMethod("getService", String::class.java)
            getService.isAccessible = true
            val binder = getService.invoke(null, "activity_task") as? IBinder
                ?: return Attempt(label, false, "activity_task service missing")
            val stub = Class.forName("android.app.IActivityTaskManager\$Stub")
            val asInterface = stub.getMethod("asInterface", IBinder::class.java)
            val service = asInterface.invoke(null, binder)
                ?: return Attempt(label, false, "asInterface returned null")
            val moved = invokeMove(service, taskId, displayId)
                ?: return Attempt(
                    label, false,
                    "no moveRootTaskToDisplay/moveTaskToDisplay method"
                )
            Attempt("$label.$moved", true, "call accepted")
        } catch (t: Throwable) {
            Attempt(label, false, unwrap("activity_task binder", t))
        }
    }

    private fun invokeMove(service: Any, taskId: Int, displayId: Int): String? {
        for (name in listOf("moveRootTaskToDisplay", "moveTaskToDisplay")) {
            val method = service.javaClass.methods.firstOrNull {
                it.name == name && it.parameterTypes.size == 2
            } ?: continue
            method.isAccessible = true
            method.invoke(service, taskId, displayId)
            return name
        }
        return null
    }

    private fun unwrap(prefix: String, t: Throwable): String {
        val cause = if (t is InvocationTargetException) t.targetException ?: t.cause ?: t else t
        return "$prefix: ${cause.javaClass.simpleName}: ${cause.message}"
    }
}
