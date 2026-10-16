package com.oea.launcher.privilege

import android.app.Activity
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import com.oea.engine.core.OeaGuard
import com.oea.launcher.assistant.OeaAssistant
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Reads what this device currently lets OEA do, opens the right Android page for
 * anything the user must switch on, and grants by itself whatever OEA is allowed
 * to grant (device owner or root).
 *
 * Design rules, which every OEA feature follows:
 *
 * 1. **Detect, don't assume.** A privilege's state is read from Android whenever
 *    it is asked for, with a short cache so a screen can render in one pass. A
 *    [ContentObserver] and an appops listener keep Home and the Settings pages
 *    honest when the user changes something in Android's own UI.
 * 2. **Degradation, not denial.** [surfaceFor] answers "what is the best way to
 *    show this on this device right now", so a feature falls back to a Home-side
 *    or notification-side presentation instead of doing nothing.
 * 3. **No helper app, no PC.** [grant] first tries [OeaShell] (root) and
 *    [DevicePolicyManager] (device owner); only then does it hand the user to
 *    Android's own settings page — and it verifies the result afterwards instead
 *    of trusting that visiting a page changed anything.
 */
object OeaPrivileges {
    private const val TAG = "OeaPrivileges"
    private const val HOME_ACTIVITY = "com.oea.launcher.OeaLauncherActivity"

    /**
     * What asking for a privilege produced. [granted] is only ever true when the
     * new state was read back from Android, never when a command merely returned 0.
     */
    data class Outcome(val kind: Kind, val message: String, val granted: Boolean = kind == Kind.GRANTED) {
        enum class Kind {
            /** OEA changed Android's state itself and confirmed it. */
            GRANTED,

            /** Android's settings page is open; OEA verifies when the user returns. */
            OPENED,

            /** Nothing on this device can grant it (no root, not device owner). */
            BLOCKED,

            /** The change was accepted but Android did not apply it. */
            UNVERIFIED,
        }

        companion object {
            fun granted(how: String) = Outcome(Kind.GRANTED, "Granted by OEA ($how)")
            fun opened(page: String) = Outcome(Kind.OPENED, "Open $page and switch OEA on")
            fun blocked(why: String) = Outcome(Kind.BLOCKED, why)
            fun unverified(why: String) = Outcome(Kind.UNVERIFIED, why)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = OeaGuard.pool("privileges", 1, "oea-privileges")
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val lock = Any()

    @Volatile private var cached: Map<Privilege, Grant> = emptyMap()
    @Volatile private var cachedAt = 0L
    @Volatile private var observer: ContentObserver? = null
    @Volatile private var appOpsWatcher: AppOpsManager.OnOpChangedListener? = null

    /** Notified when a privilege changed, on the main thread. */
    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    private fun publish() = main.post { listeners.toList().forEach { OeaGuard.run("privileges:listener", it) } }

    // ------------------------------------------------------------------- read

    /** State of every privilege; recomputed at most every 500 ms unless [force]. */
    fun snapshot(context: Context, force: Boolean = false): Map<Privilege, Grant> {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (!force && cached.isNotEmpty() && now - cachedAt < 500L) return cached
        }
        val value = readAll(app)
        val changed = synchronized(lock) {
            val different = cached != value
            cached = value
            cachedAt = System.currentTimeMillis()
            different
        }
        if (changed) publish()
        return value
    }

    fun granted(context: Context, privilege: Privilege): Grant = snapshot(context)[privilege] ?: read(context, privilege)

    fun isReady(context: Context, privilege: Privilege): Boolean = granted(context, privilege) == Grant.GRANTED

    /** True when OEA can switch this on without a tap from the user. */
    fun selfGrantable(context: Context, privilege: Privilege): Boolean = when (privilege) {
        Privilege.DEVICE_OWNER, Privilege.DEVICE_ADMIN, Privilege.DIALER_ROLE,
        Privilege.CALL_SCREENING_ROLE, Privilege.DND_ACCESS, Privilege.ROOT,
        -> false

        else -> isDeviceOwner(context) && privilege.runtimePermission != null ||
            (OeaShell.available() || OeaAdbAuthority.isSessionReady()) && rootCommand(context, privilege) != null
    }

    private fun read(context: Context, privilege: Privilege): Grant = when (privilege) {
        Privilege.HOME_ROLE -> if (holdsHomeRole(context)) Grant.GRANTED else Grant.DENIED
        Privilege.ACCESSIBILITY -> when {
            OeaAssistant.isConnected() -> Grant.GRANTED
            OeaAssistant.isEnabled(context) -> Grant.PENDING
            else -> Grant.DENIED
        }

        Privilege.OVERLAY -> if (canOverlay(context)) Grant.GRANTED else Grant.DENIED
        Privilege.NOTIFICATION_ACCESS -> if (canReadNotifications(context)) Grant.GRANTED else Grant.DENIED
        Privilege.USAGE_ACCESS -> if (canReadUsage(context)) Grant.GRANTED else Grant.DENIED
        Privilege.DEVICE_ADMIN -> if (isDeviceAdmin(context)) Grant.GRANTED else Grant.DENIED
        Privilege.DEVICE_OWNER -> if (isDeviceOwner(context)) Grant.GRANTED else Grant.UNSUPPORTED
        Privilege.ROOT -> if (OeaShell.available()) Grant.GRANTED else Grant.UNSUPPORTED
        Privilege.DND_ACCESS -> if (canUseDnd(context)) Grant.GRANTED else Grant.DENIED
        Privilege.BATTERY_UNRESTRICTED -> if (ignoresBatteryOptimizations(context)) Grant.GRANTED else Grant.DENIED
        Privilege.DIALER_ROLE -> if (holdsRole(context, RoleManager.ROLE_DIALER)) Grant.GRANTED else Grant.DENIED
        Privilege.CALL_SCREENING_ROLE -> if (holdsRole(context, RoleManager.ROLE_CALL_SCREENING)) Grant.GRANTED else Grant.DENIED
        Privilege.NOTIFICATIONS -> if (hasRuntimePermission(context, "android.permission.POST_NOTIFICATIONS")) Grant.GRANTED else Grant.DENIED
        Privilege.CONTACTS -> if (hasRuntimePermission(context, "android.permission.READ_CONTACTS")) Grant.GRANTED else Grant.DENIED
        Privilege.CAMERA -> if (hasRuntimePermission(context, "android.permission.CAMERA")) Grant.GRANTED else Grant.DENIED
    }

    private fun readAll(context: Context): Map<Privilege, Grant> {
        val out = LinkedHashMap<Privilege, Grant>(Privilege.entries.size)
        Privilege.entries.forEach { privilege ->
            out[privilege] = OeaGuard.call("privileges:read", Grant.DENIED) { read(context, privilege) }
        }
        return out
    }

    // ------------------------------------------------------- capability probes

    fun canOverlay(context: Context): Boolean = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    /**
     * Opens Android's "Display over other apps" screen for OEA, trying the three
     * shapes that exist in the wild: the per-app toggle, the global list, and the
     * AppOps-backed activity some firmware uses instead. Returns false when none of
     * them can be opened, so the caller can explain the manual route rather than
     * leaving the user on a screen that never appeared.
     */
    fun requestOverlay(context: Context, requestCode: Int): Boolean {
        val perApp = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        val list = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        val appOps = Intent("com.android.settings.action.APP_OPS_DRAW_OVER_OTHER_APPS")
        return listOf(perApp, list, appOps).any { intent ->
            runCatching {
                if (context is android.app.Activity) {
                    context.startActivityForResult(intent, requestCode)
                } else {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                true
            }.getOrDefault(false)
        }
    }

    fun canReadNotifications(context: Context): Boolean = runCatching {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        enabled.split(':').any { it.isNotBlank() && it.contains(context.packageName) }
    }.getOrDefault(false)

    fun canReadUsage(context: Context): Boolean = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    fun deviceAdminComponent(context: Context): ComponentName =
        ComponentName(context.packageName, "com.oea.launcher.applock.OeaDeviceAdminReceiver")

    fun isDeviceAdmin(context: Context): Boolean = runCatching {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        dpm.isAdminActive(deviceAdminComponent(context))
    }.getOrDefault(false)

    fun isDeviceOwner(context: Context): Boolean = runCatching {
        context.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(context.packageName) == true
    }.getOrDefault(false)

    fun canUseDnd(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT >= 24) manager.isNotificationPolicyAccessGranted else false
    }.getOrDefault(false)

    fun ignoresBatteryOptimizations(context: Context): Boolean = runCatching {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

    fun holdsHomeRole(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val roles = context.getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)) return@runCatching roles.isRoleHeld(RoleManager.ROLE_HOME)
        }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val info = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        info?.activityInfo?.packageName == context.packageName
    }.getOrDefault(false)

    fun holdsRole(context: Context, role: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < 29) return false
        val roles = context.getSystemService(RoleManager::class.java) ?: return false
        roles.isRoleAvailable(role) && roles.isRoleHeld(role)
    }.getOrDefault(false)

    fun hasRuntimePermission(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** True when the user switched OEA Assistant on but Android has not bound it yet. */
    fun assistantPending(context: Context): Boolean = granted(context, Privilege.ACCESSIBILITY) == Grant.PENDING

    /** Whether Android itself reports the service as enabled for this user. */
    fun accessibilityServiceBound(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        // getEnabledAccessibilityServiceList(-1) asks for every feedback type;
        // each service reports the package that hosts it.
        manager.getEnabledAccessibilityServiceList(-1)
            .any { it.resolveInfo?.serviceInfo?.packageName == context.packageName }
    }.getOrDefault(false)

    /**
     * A granted-but-stopped service is a different problem from a denied one:
     * asking the user to switch it on again would be wrong, so OEA nudges
     * Android to rebind it (root only) and otherwise leaves it alone.
     */
    fun reconnectAssistant(context: Context): Boolean {
        if (!OeaAssistant.isEnabled(context) || OeaAssistant.isConnected()) return false
        if (OeaShell.available()) {
            return OeaGuard.call("privileges:rebind", false) {
                OeaShell.applyAndVerify("settings put secure accessibility_enabled 1") { OeaAssistant.isConnected() }
            }
        }
        if (OeaAdbAuthority.isSessionReady()) {
            val (ok, _) = OeaAdbAuthority.runShell("settings put secure accessibility_enabled 1")
            return ok
        }
        return false
    }

    // ------------------------------------------------------------- capabilities

    /**
     * The best surface for [feature] on this device, right now. Every feature in
     * OEA asks this instead of testing a permission itself, so a denied
     * privilege changes presentation, never availability.
     */
    fun surfaceFor(context: Context, feature: OeaFeature): Surface {
        val state = granted(context, feature.needs)
        return when {
            state == Grant.GRANTED -> feature.preferred
            state == Grant.PENDING -> feature.pending
            else -> feature.fallback
        }
    }

    /** Rows for the Settings screen, in the order the user should read them. */
    fun capabilities(context: Context): List<Capability> {
        val state = snapshot(context, force = true)
        val rows = ArrayList<Capability>(OeaFeature.entries.size)
        OeaFeature.entries.forEach { feature ->
            val grant = state[feature.needs] ?: Grant.DENIED
            rows += Capability(
                feature = feature.title,
                requires = feature.needs,
                granted = grant,
                surface = when (grant) {
                    Grant.GRANTED -> feature.preferred
                    Grant.PENDING -> feature.pending
                    else -> feature.fallback
                },
                effect = if (grant == Grant.GRANTED) "Full" else feature.effect,
                selfGrantable = selfGrantable(context, feature.needs)
)
        }
        return rows
    }

    /** Every privilege with its current state, for the access screen. */
    fun privilegeRows(context: Context): List<Pair<Privilege, Grant>> {
        val state = snapshot(context, force = true)
        return Privilege.entries.map { it to (state[it] ?: Grant.DENIED) }
    }

    // ------------------------------------------------------------------- grant

    /**
     * Grants [privilege] when OEA has the authority for it, otherwise opens
     * Android's page for it. The result is verified by reading the state back.
     */
    fun grant(activity: Activity, privilege: Privilege, onDone: (Outcome) -> Unit) {
        val context = activity.applicationContext
        if (granted(context, privilege) == Grant.GRANTED) {
            onDone(Outcome.granted("already on"))
            return
        }
        val queued = runCatching {
            io.execute {
                val outcome = OeaGuard.call("privileges:grant", Outcome.blocked("OEA could not check this permission.")) {
                    apply(context, privilege)
                }
                main.post {
                    if (outcome.granted) publish()
                    onDone(outcome)
                }
            }
        }.isSuccess
        if (!queued) onDone(Outcome.blocked("OEA could not start the permission check. Try again."))
    }

    private fun apply(context: Context, privilege: Privilege): Outcome {
        val pkg = context.packageName
        // 1. Device owner: Android lets OEA grant runtime permissions itself.
        val runtime = privilege.runtimePermission
        if (runtime != null && isDeviceOwner(context)) {
            val confirmed = OeaGuard.call("privileges:dpm", false) {
                context.getSystemService(DevicePolicyManager::class.java)
                    ?.setPermissionGrantState(deviceAdminComponent(context), pkg, runtime, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
                hasRuntimePermission(context, runtime)
            }
            return if (confirmed) Outcome.granted("device owner") else Outcome.unverified("Android did not confirm the permission.")
        }
        // 2. Root: the commands a helper app would run, run by OEA itself.
        if (OeaShell.available()) {
            val command = rootCommand(context, privilege)
                ?: return Outcome.blocked("Android requires the user to switch this on; no command can do it.")
            val verified = OeaShell.applyAndVerify(command) { read(context, privilege) == Grant.GRANTED }
            return if (verified) Outcome.granted("root")
            else Outcome.unverified("Root accepted the change but Android did not apply it.")
        }
        // 3. OEA's embedded Wireless Debugging bridge. Pairing is explicit and
        // user-authorized; once connected, use only OEA's scoped command allowlist.
        if (OeaAdbAuthority.isSessionReady()) {
            val command = rootCommand(context, privilege)
                ?: return Outcome.blocked("Android requires the user to switch this on; OEA has no supported command for it.")
            val (accepted, output) = OeaAdbAuthority.runShell(command)
            if (!accepted) return Outcome.unverified(output.ifBlank { "OEA Authority could not apply the change." })
            repeat(6) {
                if (runCatching { read(context, privilege) == Grant.GRANTED }.getOrDefault(false)) {
                    return Outcome.granted("OEA Authority (Wireless Debugging)")
                }
                Thread.sleep(200)
            }
            return Outcome.unverified("The bridge accepted the command, but Android did not confirm the permission became active.")
        }
        // 4. No privileged channel: the user's own tap, verified when they return.
        return openAndroidPage(context, privilege)
    }

    /** The exact shell command behind a privilege, or null when no command exists. */
    private fun rootCommand(context: Context, privilege: Privilege): String? {
        val pkg = OeaShell.sanitizeTarget(context.packageName, context.packageName) ?: return null
        return when (privilege) {
            Privilege.OVERLAY -> "appops set $pkg SYSTEM_ALERT_WINDOW allow"
            Privilege.USAGE_ACCESS -> "appops set $pkg GET_USAGE_STATS allow"
            Privilege.ACCESSIBILITY -> {
                val ours = "$pkg/${OeaAssistant.component(context).className}"
                val others = Settings.Secure.getString(context.contentResolver, "enabled_accessibility_services")
                    .orEmpty().split(':').filter { it.isNotBlank() && !it.contains(pkg) }
                "settings put secure enabled_accessibility_services ${(others + ours).joinToString(":")} && settings put secure accessibility_enabled 1"
            }

            Privilege.NOTIFICATION_ACCESS -> {
                val ours = "$pkg/com.oea.launcher.notifications.OeaNotificationListener"
                val others = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                    .orEmpty().split(':').filter { it.isNotBlank() && !it.contains(pkg) }
                "settings put secure enabled_notification_listeners ${(others + ours).joinToString(":")}"
            }

            Privilege.BATTERY_UNRESTRICTED -> "dumpsys deviceidle whitelist +$pkg"
            Privilege.DND_ACCESS -> "cmd notification allow_dnd $pkg"
            Privilege.HOME_ROLE -> "cmd package set-home-activity $pkg/$HOME_ACTIVITY"
            else -> privilege.runtimePermission?.let { "pm grant $pkg $it" }
        }
    }

    /** Android's own page for a privilege. */
    fun openAndroidPage(context: Context, privilege: Privilege): Outcome {
        val target = pageFor(context, privilege) ?: return Outcome.blocked("${privilege.title} is not offered by this Android version.")
        val intent = target.first
        val flags = if (context is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK
        intent.addFlags(flags)
        val opened = OeaGuard.run("privileges:open-page") { context.startActivity(intent) }
        return if (opened) Outcome.opened(target.second) else Outcome.blocked("Android's ${target.second} page is not available on this device.")
    }

    private fun pageFor(context: Context, privilege: Privilege): Pair<Intent, String>? {
        val packageUri = Uri.parse("package:${context.packageName}")
        var intent: Intent? = null
        var label = privilege.title
        when (privilege) {
            Privilege.OVERLAY -> {
                intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri); label = "Display over other apps"
            }

            Privilege.ACCESSIBILITY -> {
                intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS); label = "Accessibility settings"
            }

            Privilege.NOTIFICATION_ACCESS -> {
                intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS); label = "Notification access"
            }

            Privilege.USAGE_ACCESS -> {
                intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS); label = "Usage access"
            }

            Privilege.DEVICE_ADMIN -> {
                intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, deviceAdminComponent(context))
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "OEA uses device admin for the App Freezer and to protect Home settings.")
                label = "Device admin"
            }

            Privilege.DEVICE_OWNER -> {
                // Android only honours this while the device has no accounts, which is the
                // one moment a launcher may become device owner without a PC.
                intent = if (Build.VERSION.SDK_INT >= 21) Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE) else null
                label = "Device owner setup"
            }

            Privilege.DND_ACCESS -> {
                intent = if (Build.VERSION.SDK_INT >= 24) Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) else null
                label = "Do Not Disturb access"
            }

            Privilege.BATTERY_UNRESTRICTED -> {
                intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS); label = "Battery optimisation"
            }

            Privilege.HOME_ROLE -> {
                intent = Intent(Settings.ACTION_HOME_SETTINGS); label = "Default Home app"
            }

            Privilege.NOTIFICATIONS -> {
                intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                label = "Notifications"
            }

            Privilege.CONTACTS, Privilege.CAMERA, Privilege.ROOT -> {
                intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri); label = "App permissions"
            }

            Privilege.DIALER_ROLE, Privilege.CALL_SCREENING_ROLE -> {
                intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS); label = "Default apps"
            }
        }
        val target = intent ?: return null
        return Pair(target, label)
    }

    /** The shortest route to OEA Assistant's own switch (Android 12+ has a details page). */
    fun openAssistantPage(context: Context): Outcome {
        if (Build.VERSION.SDK_INT >= 31) {
            val intent = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, OeaAssistant.component(context).flattenToString())
                .addFlags(if (context is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK)
            if (OeaGuard.run("privileges:assistant-page") { context.startActivity(intent) }) {
                return Outcome.opened("OEA Assistant's accessibility page")
            }
        }
        return openAndroidPage(context, Privilege.ACCESSIBILITY)
    }

    /**
     * Switches on everything OEA can switch on by itself and reports what still
     * needs a tap. On a rooted device, or where OEA is the device owner, OEA
     * needs no helper app at all.
     */
    fun repairAll(context: Context, onDone: (Summary) -> Unit) {
        val app = context.applicationContext
        val queued = runCatching {
            io.execute {
                val summary = OeaGuard.call("privileges:repair", Summary(0, 0, emptyList())) {
                    var fixed = 0
                    val remaining = ArrayList<String>()
                    Privilege.entries.forEach { privilege ->
                        if (read(app, privilege) == Grant.GRANTED) return@forEach
                        if (!selfGrantable(app, privilege)) {
                            if (privilege != Privilege.ROOT && privilege != Privilege.DEVICE_OWNER) remaining += privilege.title
                            return@forEach
                        }
                        if (apply(app, privilege).granted) fixed++ else remaining += privilege.title
                    }
                    Summary(fixed, remaining.size, remaining)
                }
                main.post {
                    publish()
                    onDone(summary)
                }
            }
        }.isSuccess
        // A stopped worker must still answer the caller, so the UI never waits forever.
        if (!queued) onDone(Summary(0, 0, emptyList()))
    }

    data class Summary(val granted: Int, val needsUser: Int, val names: List<String>) {
        fun describe(): String = when {
            granted == 0 && needsUser == 0 -> "Everything OEA can use is already on."
            needsUser == 0 -> "OEA turned on $granted permission${if (granted == 1) "" else "s"}."
            else -> "OEA turned on $granted; $needsUser still need your tap (${names.joinToString(", ")})."
        }
    }

    // ------------------------------------------------------------------ listen

    /**
     * Watches Android so OEA notices a grant or revocation the moment the user
     * changes it in Settings, instead of waiting for a screen to be reopened.
     */
    fun startObserving(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            if (observer != null) return
            val change = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = changedExternally(app)
            }
            OeaGuard.run("privileges:observe") {
                app.contentResolver.registerContentObserver(Settings.Secure.getUriFor("enabled_accessibility_services"), false, change)
                app.contentResolver.registerContentObserver(Settings.Secure.getUriFor("accessibility_enabled"), false, change)
                app.contentResolver.registerContentObserver(Settings.Secure.getUriFor("enabled_notification_listeners"), false, change)
            }
            observer = change
            watchOverlayOps(app)
        }
    }

    fun stopObserving(context: Context) {
        synchronized(lock) {
            observer?.let { runCatching { context.contentResolver.unregisterContentObserver(it) } }
            observer = null
            val watcher = appOpsWatcher
            if (watcher != null) {
                runCatching { context.getSystemService(AppOpsManager::class.java)?.stopWatchingMode(watcher) }
            }
            appOpsWatcher = null
        }
    }

    private fun changedExternally(app: Context) {
        // snapshot()'s cache makes this cheap even when Android reports several
        // related changes at once; the log line keeps it diagnosable.
        cachedAt = 0L
        Log.i(TAG, "Android's permission state changed; refreshing capabilities")
        snapshot(app, force = true)
    }

    /** Android 10+ notifies appops changes, which covers overlay and usage access. */
    private fun watchOverlayOps(context: Context) {
        if (Build.VERSION.SDK_INT < 29) return
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return
        val listener = AppOpsManager.OnOpChangedListener { op, _ ->
            if (op == AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW || op == AppOpsManager.OPSTR_GET_USAGE_STATS) changedExternally(context)
        }
        OeaGuard.run("privileges:watch-appops") {
            // startWatchingMode covers every supported Android version; the
            // newer addOnOpChangedListener only exists from Android 11.
            @Suppress("DEPRECATION")
            ops.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, context.packageName, listener)
            appOpsWatcher = listener
        }
    }
}

/** The runtime permission behind a privilege, when the privilege is a runtime one. */
internal val Privilege.runtimePermission: String?
    get() = when (this) {
        Privilege.NOTIFICATIONS -> "android.permission.POST_NOTIFICATIONS"
        Privilege.CONTACTS -> "android.permission.READ_CONTACTS"
        Privilege.CAMERA -> "android.permission.CAMERA"
        else -> null
    }
