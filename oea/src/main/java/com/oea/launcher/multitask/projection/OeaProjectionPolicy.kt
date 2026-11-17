package com.oea.launcher.multitask.projection

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * The one question the live-window feature must answer before it promises
 * anything: can *this* activity ever be placed on OEA's private display?
 *
 * The answer is not OEA's to give — it is Android's, and it does not vary by
 * device, manufacturer or launch route. Verified in the Android 13, 14 and 15
 * sources (identical in all three),
 * `ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay`:
 *
 * ```
 * if (!display.isTrusted()) {                        // app-created: never trusted
 *     if ((aInfo.flags & ActivityInfo.FLAG_ALLOW_EMBEDDED) == 0) return false;
 *     if (checkPermission(ACTIVITY_EMBEDDING) == DENIED && !uidPresentOnDisplay)
 *         return false;
 * }
 * ```
 *
 * Read plainly, that is two conditions, and OEA can only ever satisfy the
 * second one:
 *
 *  1. **The activity being launched must itself declare
 *     `android:allowEmbedded="true"`.** OEA cannot change this for somebody
 *     else's app, and no third-party app declares it. This is why every
 *     "floating window" or "parallel apps" feature on stock Android is a
 *     system or manufacturer feature — it is not a missing OEA trick.
 *  2. **The caller needs the `ACTIVITY_EMBEDDING` signature permission, or
 *     already to have an activity record of its own on the display.** That
 *     second half is exactly what the trampoline exists for: once one OEA
 *     activity sits on the display, `DisplayContent.isUidPresent(uid)` is
 *     true for OEA — and `isUidPresent` counts `ActivityRecord`s only, which
 *     is why a Presentation window alone never unlocked it.
 *
 * So the honest matrix is:
 *
 *  * OEA's own apps that declare `allowEmbedded` → projectable, for real;
 *  * any other app → blocked by the platform, on every stock Android 13–15
 *    device, by every launch route OEA is allowed to try.
 *
 * Checking this up front is what turns a 40-second wait that ends in an empty
 * window into a single sentence the user can act on.
 */
object OeaProjectionPolicy {

    /**
     * `ActivityInfo.FLAG_ALLOW_EMBEDDED`. Hidden from the SDK (and marked
     * deprecated in AOSP with a "no longer used" TODO), but still the exact
     * bit `isCallerAllowedToLaunchOnDisplay` tests on Android 13, 14 and 15 —
     * so OEA has to read it by value.
     */
    private const val FLAG_ALLOW_EMBEDDED = 0x80000000.toInt()

    enum class Verdict {
        /** Declares allowEmbedded: Android's own gate can let it through. */
        PROJECTABLE,

        /** Does not declare allowEmbedded: no route exists on this platform. */
        BLOCKED,

        /** OEA could not read the activity (uninstalled, disabled, renamed). */
        UNKNOWN
    }

    data class Answer(
        val verdict: Verdict,
        /** The activity the verdict was reached for, when OEA could find one. */
        val className: String?,
        val detail: String
    ) {
        val projectable: Boolean get() = verdict != Verdict.BLOCKED
    }

    private fun activityInfo(context: Context, packageName: String, className: String) =
        runCatching {
            val cn = ComponentName(packageName, className)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getActivityInfo(cn, PackageManager.ComponentInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getActivityInfo(cn, 0)
            }
        }.getOrNull()

    /** True / false / null for "OEA could not read this activity". */
    fun allowsEmbedded(context: Context, packageName: String, className: String): Boolean? =
        if (packageName.isBlank() || className.isBlank()) null
        else activityInfo(context, packageName, className)?.let { (it.flags and FLAG_ALLOW_EMBEDDED) != 0 }

    /**
     * The verdict for one component. [fallbackClassName] lets a caller that
     * only holds a package name still get an answer: the resolver in
     * [com.oea.launcher.multitask.OeaMultitaskIntents] knows which activity
     * OEA would actually open.
     */
    fun check(context: Context, packageName: String, className: String?): Answer {
        if (packageName.isBlank()) {
            return Answer(Verdict.UNKNOWN, null, "no package name was available")
        }
        val resolved = className?.takeIf { it.isNotBlank() }
            ?: com.oea.launcher.multitask.OeaMultitaskIntents
                .resolve(context, packageName, null)?.component?.className
        if (resolved.isNullOrBlank()) {
            return Answer(
                Verdict.UNKNOWN, null,
                "$packageName has no activity OEA is allowed to open"
            )
        }
        val info = activityInfo(context, packageName, resolved)
            ?: return Answer(
                Verdict.UNKNOWN, resolved,
                "OEA could not read $packageName/$resolved (uninstalled, disabled or renamed?)"
            )
        val allows = (info.flags and FLAG_ALLOW_EMBEDDED) != 0
        return if (allows) {
            Answer(
                Verdict.PROJECTABLE, resolved,
                "$resolved declares android:allowEmbedded=\"true\", the one condition " +
                    "Android requires of an activity that runs on an app-created display"
            )
        } else {
            Answer(
                Verdict.BLOCKED, resolved,
                "$resolved does not declare android:allowEmbedded=\"true\""
            )
        }
    }

    /**
     * The user-facing sentence for a blocked app. Deliberately names the real
     * constraint rather than blaming the phone: the user can act on "Android
     * does not allow this", and cannot act on "something went wrong".
     */
    fun blockedMessage(appLabel: String, answer: Answer): String =
        "$appLabel cannot be shown in a live window. Android only lets an activity that " +
            "declares android:allowEmbedded=\"true\" run on a display an app created — that is a " +
            "platform rule on Android 13–15, not a setting OEA can change, and third-party apps " +
            "never declare it. OEA's own apps (Notes, Calculator, Messages, Phone, Clock, " +
            "Weather and the rest) do, and open here for real."

    /** Short marker for list rows in the floating workspace. */
    fun shortNote(): String = "Android blocks this app on a private display"
}
