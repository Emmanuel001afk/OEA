package com.oea.launcher.multitask

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import com.oea.launcher.data.OeaDataStore
import com.oea.launcher.model.OeaAppInfo

/**
 * One place that turns "this app" into an Intent Android will actually open.
 *
 * Every multitasking surface — floating windows, split screen, live projection
 * windows — used to ask for `packageManager.getLaunchIntentForPackage(...)`.
 * That call answers a different question than the one being asked: it returns
 * the *package's* default entry point, and says nothing about the specific
 * activity OEA is holding. Three things went wrong because of it:
 *
 *  * **OEA's own apps opened Home instead of the app.** Every OEA tool — Notes,
 *    Calculator, Messages, Phone, Game Boost — lives in OEA's package, so
 *    `getLaunchIntentForPackage("com.oea.launcher")` answers with the launcher
 *    itself. Asking to float Notes opened OEA Home.
 *  * **The wrong activity opened** whenever an entry pointed at a specific
 *    component (an app's secondary activity, an alias, a pinned shortcut's
 *    target): the main activity came up instead.
 *  * **A stale component failed outright** after an app update renamed or
 *    disabled its activity, with no attempt to resolve what replaced it.
 *
 * This resolves the component OEA actually means, verifies the phone will
 * really let OEA start it, and only then falls back — in the same order — to
 * the package's remaining launcher activities and finally to its default.
 */
object OeaMultitaskIntents {

    /** The standard launcher shape every OEA launch uses, so apps resume their own task. */
    private fun base(): Intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    private fun componentIntent(packageName: String, className: String): Intent =
        base().setClassName(packageName, className)

    /**
     * True when OEA can really start [intent].
     *
     * A component that exists but is disabled, or is another app's activity
     * that is not exported, throws when started — which is how a "Float" tap
     * used to do nothing at all rather than fall back to something that works.
     */
    private fun startable(context: Context, intent: Intent): Boolean = runCatching {
        val pm = context.packageManager
        val component = intent.component ?: return intent.resolveActivity(pm) != null
        val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
            pm.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getActivityInfo(component, 0)
        }
        if (!info.enabled) return false
        // Same package can always start its own activities; another app's must
        // be exported or Android refuses the start with a SecurityException.
        if (!info.exported && info.packageName != context.packageName) return false
        true
    }.getOrDefault(false)

    private fun query(context: Context, intent: Intent): List<ResolveInfo> = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.packageManager.queryIntentActivities(
                intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
    }.getOrDefault(emptyList())

    /** The package's declared launcher activities, in Android's own order. */
    private fun launcherActivities(context: Context, packageName: String): List<ResolveInfo> =
        query(context, base().setPackage(packageName))

    /**
     * Components that are home screens.
     *
     * OEA Home is itself a launcher activity, so it sits in the same list as
     * every OEA tool. Choosing it as the fallback for "open this OEA app" is
     * how the old code dropped the user back on the home screen.
     */
    private fun homeComponents(context: Context): Set<String> =
        query(context, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            .mapNotNull { it.activityInfo?.let { info -> "${info.packageName}/${info.name}" } }
            .toSet()

    /** Launcher activities that are not home screens, then the home screens. */
    private fun ranked(context: Context, packageName: String): List<ResolveInfo> {
        val activities = launcherActivities(context, packageName)
        val homes = homeComponents(context)
        val (isHome, isApp) = activities.partition { "${it.activityInfo.packageName}/${it.activityInfo.name}" in homes }
        return isApp + isHome
    }

    /**
     * The launch intent for [packageName]/[className], or null when the phone
     * offers nothing OEA is allowed to start.
     */
    fun resolve(context: Context, packageName: String, className: String?): Intent? {
        if (packageName.isBlank()) return null

        // 1. The component OEA is actually holding.
        if (!className.isNullOrBlank()) {
            val direct = componentIntent(packageName, className)
            if (startable(context, direct)) return direct
        }

        // 2. The same class under the name it has now, then any other launcher
        //    activity of the package. An update can rename, move or disable the
        //    exact component while leaving the app perfectly openable.
        val activities = ranked(context, packageName)
        val chosen = activities.firstOrNull { it.activityInfo.name == className }
            ?: activities.firstOrNull { !className.isNullOrBlank() && it.activityInfo.name.endsWith(".$className") }
            ?: activities.firstOrNull { !className.isNullOrBlank() && className.endsWith(it.activityInfo.name) }
            ?: activities.firstOrNull()
        if (chosen != null) {
            val intent = componentIntent(chosen.activityInfo.packageName, chosen.activityInfo.name)
            if (startable(context, intent)) return intent
        }

        // 3. Android's own answer for the package, provided it really resolves.
        return runCatching { context.packageManager.getLaunchIntentForPackage(packageName) }
            .getOrNull()
            ?.let { if (startable(context, it)) it else null }
    }

    /** Convenience for the app entries OEA's lists and tiles carry. */
    fun resolve(context: Context, app: OeaAppInfo): Intent? =
        resolve(context, app.packageName, app.className)

    /**
     * Notes the launch in OEA's recents, so Overview and the drawer's "Recent
     * use" agree with what multitasking actually opened. Without it, an app
     * used all day in a floating window never appeared in the places that
     * answer "what did I just use?".
     */
    fun record(context: Context, packageName: String, className: String?) {
        if (packageName.isBlank()) return
        runCatching { OeaDataStore.get(context).recordLaunch(packageName, className.orEmpty()) }
    }

    fun record(context: Context, app: OeaAppInfo) = record(context, app.packageName, app.className)
}
