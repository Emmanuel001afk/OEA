package com.oea.launcher.privilege

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OEA's own access doctor: what OEA is allowed to do, *verified*, and which
 * permissions still need Android's consent or an available privileged channel.
 *
 * A settings screen that only reads `Settings.canDrawOverlays` is how a user ends
 * up saying "it is switched on but nothing happens": Android can report a
 * permission as granted while the capability is still not usable (AppOps-backed
 * firmware, a service enabled in Settings but never bound, an OEM that keeps a
 * permission in its own list). Every row here therefore carries the state OEA
 * *observed*, not the state the switch claims, and every row knows
 *  - how to fix it (root, a paired OEA Authority session, a supported
 *    device-owner grant, or the exact Android screen),
 *  - what to do when no available channel can fix it, and
 *  - the exact shell command for diagnostics or a manually approved repair.
 * Ordinary launcher use does not require root or a paired Authority session.
 */
object OeaAccessDoctor {

    private const val TAG = "OeaPrivileges"

    /** What the doctor found, in the order a user cares about it. */
    enum class State {
        /** Working, verified live. */
        WORKING,
        /** Switched on, but not in effect. Fixable, usually by switching it off and on. */
        STALE,
        /** Not granted yet; the user can grant it in one tap. */
        NEEDS_TAP,
        /** OEA can request it through root, paired OEA Authority, or a supported owner grant. */
        OEA_CAN_FIX,
        /** This device cannot provide it at all. */
        UNAVAILABLE,
    }

    /** One verified capability. */
    data class Finding(
        val privilege: Privilege,
        val state: State,
        /** What OEA observed, in plain words — the evidence, not the switch. */
        val evidence: String,
        /** What to do next. */
        val advice: String,
        /** The shell command that grants this, when there is one. */
        val command: String? = null
) {
        val isReady: Boolean get() = state == State.WORKING
    }

    /**
     * Checks every privilege OEA can use and reports what each one actually does.
     *
     * Runs on a worker thread: two of the checks open a window or query Android's
     * own services, which must never happen on the main thread.
     */
    fun examine(context: Context): List<Finding> {
        val app = context.applicationContext
        val grants = OeaPrivileges.snapshot(app, force = true)
        val findings = ArrayList<Finding>(Privilege.entries.size)
        Privilege.entries.forEach { privilege ->
            val finding = runCatching { inspect(app, privilege, grants[privilege] ?: Grant.DENIED) }
                .onFailure { Log.w(TAG, "Access check failed for $privilege", it) }
                .getOrElse {
                    Finding(
                        privilege, State.UNAVAILABLE,
                        "OEA could not check this on this device (${it.javaClass.simpleName}).",
                        "Nothing is broken: the feature falls back to its own Home surface."
)
                }
            findings += finding
        }
        return findings.sortedBy { order(it.state) }
    }

    private fun order(state: State): Int = when (state) {
        State.STALE -> 0
        State.NEEDS_TAP -> 1
        State.OEA_CAN_FIX -> 2
        State.UNAVAILABLE -> 3
        State.WORKING -> 4
    }

    private fun inspect(app: Context, privilege: Privilege, grant: Grant): Finding {
        val root = OeaShell.available()
        val authority = OeaAdbAuthority.isSessionReady()
        val privilegedShell = root || authority
        val shellLabel = if (root) "root" else "OEA Authority"
        val owner = OeaPrivileges.isDeviceOwner(app)
        return when (privilege) {
            Privilege.OVERLAY -> {
                val reported = OeaPrivileges.canOverlay(app)
                // Adding a real window needs a thread with a Looper, so this one
                // check is posted to the main thread and waited for.
                val draws = reported && onMain { com.oea.launcher.workspace.OeaDynamicIslandOverlayService.verifyOverlay(app) }
                when {
                    draws -> Finding(privilege, State.WORKING, "OEA drew a test window over the screen and it stayed.", "Nothing to do.")
                    reported -> Finding(
                        privilege, State.STALE,
                        "Android says the permission is granted, but drawing over apps was refused.",
                        "Switch Display over other apps off and on again for OEA, then press Verify. On some firmware the AppOps entry is the one that matters.",
                        command = repairCommand(app, root, privilege)
)
                    privilegedShell -> Finding(privilege, State.OEA_CAN_FIX, "Not granted; OEA has $shellLabel access and can grant it now.", "Tap Fix.", command = repairCommand(app, root, privilege))
                    else -> Finding(privilege, State.NEEDS_TAP, "Not granted.", "Android Settings › Apps › OEA › Display over other apps.", command = repairCommand(app, root, privilege))
                }
            }

            Privilege.ACCESSIBILITY -> {
                val bound = OeaPrivileges.accessibilityServiceBound(app)
                val pending = OeaPrivileges.assistantPending(app)
                when {
                    bound -> Finding(privilege, State.WORKING, "OEA Assistant is connected and answering.", "Nothing to do.")
                    // Enabled in Settings but not bound: the classic "switch is on,
                    // nothing happens" case, and switching it off and on is the fix.
                    pending -> Finding(
                        privilege, State.STALE,
                        "OEA Assistant is switched on in Android's list but Android never bound it.",
                        "Turn OEA Assistant off and on again in Android Settings › Accessibility. If it stays greyed out, restart the phone once — Android binds accessibility services at boot.",
                        command = repairCommand(app, root, privilege)
)
                    privilegedShell -> Finding(privilege, State.OEA_CAN_FIX, "Not enabled; OEA has $shellLabel access and can enable its service.", "Tap Fix.", command = repairCommand(app, root, privilege))
                    else -> Finding(privilege, State.NEEDS_TAP, "Not enabled.", "Turn on OEA Assistant in Android Settings › Accessibility › Installed apps.", command = repairCommand(app, root, privilege))
                }
            }

            Privilege.NOTIFICATION_ACCESS -> {
                val can = OeaPrivileges.canReadNotifications(app)
                when {
                    can -> Finding(privilege, State.WORKING, "OEA can read notifications: the island, dots and Now Playing work.", "Nothing to do.")
                    privilegedShell -> Finding(privilege, State.OEA_CAN_FIX, "Not granted; OEA has $shellLabel access and can grant it.", "Tap Fix.", command = repairCommand(app, root, privilege))
                    else -> Finding(privilege, State.NEEDS_TAP, "Not granted.", "Android Settings › Notifications › Device & app notifications › OEA.", command = repairCommand(app, root, privilege))
                }
            }

            Privilege.USAGE_ACCESS -> {
                val can = OeaPrivileges.canReadUsage(app)
                when {
                    can -> Finding(privilege, State.WORKING, "OEA can see which app is in front and your most-used apps.", "Nothing to do.")
                    privilegedShell -> Finding(privilege, State.OEA_CAN_FIX, "Not granted; OEA has $shellLabel access and can grant it.", "Tap Fix.", command = repairCommand(app, root, privilege))
                    else -> Finding(privilege, State.NEEDS_TAP, "Not granted.", "Android Settings › Special app access › Usage access › OEA.", command = repairCommand(app, root, privilege))
                }
            }

            Privilege.DND_ACCESS -> {
                val can = OeaPrivileges.canUseDnd(app)
                when {
                    can -> Finding(privilege, State.WORKING, "OEA can switch sound mode and Do Not Disturb.", "Nothing to do.")
                    privilegedShell -> Finding(privilege, State.OEA_CAN_FIX, "Not granted; OEA has $shellLabel access and can ask Android to grant DND access.", "Tap Fix.", command = repairCommand(app, root, privilege))
                    else -> Finding(privilege, State.NEEDS_TAP, "Not granted.", "Android Settings › Sound & vibration › Do Not Disturb access.")
                }
            }

            Privilege.BATTERY_UNRESTRICTED -> {
                val ok = OeaPrivileges.ignoresBatteryOptimizations(app)
                when {
                    ok -> Finding(privilege, State.WORKING, "Android will not freeze OEA in the background.", "Nothing to do.")
                    privilegedShell -> Finding(privilege, State.OEA_CAN_FIX, "Still battery-optimised; OEA has $shellLabel access and can exempt itself.", "Tap Fix.", command = repairCommand(app, root, privilege))
                    else -> Finding(privilege, State.NEEDS_TAP, "Battery optimisation is on.", "Android Settings › Apps › OEA › Battery › Unrestricted.", command = repairCommand(app, root, privilege))
                }
            }

            Privilege.DEVICE_ADMIN -> when {
                com.oea.launcher.power.OeaPower.deviceAdminCanLock(app) ->
                    Finding(privilege, State.WORKING, "OEA Device Admin is active and Android confirms the screen-lock policy.", "Nothing to do.")
                OeaPrivileges.isDeviceAdmin(app) ->
                    Finding(privilege, State.STALE, "OEA Device Admin is active, but Android has not granted the force-lock policy.", "Open Android Security settings, deactivate OEA's administrator, then return to Lock & power and activate Device Admin again. OEA will not remove the administrator automatically.")
                else ->
                    Finding(privilege, State.NEEDS_TAP, "OEA Device Admin is not active.", "Activate OEA Device Admin in Settings › Lock & power — no root and no PC needed.")
            }

            Privilege.HOME_ROLE -> if (OeaPrivileges.holdsHomeRole(app)) {
                Finding(privilege, State.WORKING, "OEA is the default Home app.", "Nothing to do.")
            } else if (privilegedShell) {
                Finding(privilege, State.OEA_CAN_FIX, "OEA has $shellLabel access and can request itself as Home.", "Tap Fix.", command = repairCommand(app, root, privilege))
            } else {
                Finding(privilege, State.NEEDS_TAP, "OEA is not the default Home app yet.", "Android Settings › Apps › Default apps › Home app.")
            }

            Privilege.DIALER_ROLE -> if (OeaPrivileges.holdsRole(app, android.app.role.RoleManager.ROLE_DIALER)) {
                Finding(privilege, State.WORKING, "OEA Phone is the default dialer.", "Nothing to do.")
            } else {
                Finding(privilege, State.NEEDS_TAP, "OEA Phone is not the default dialer.", "Android Settings › Apps › Default apps › Phone app.")
            }

            Privilege.CALL_SCREENING_ROLE -> if (OeaPrivileges.holdsRole(app, android.app.role.RoleManager.ROLE_CALL_SCREENING)) {
                Finding(privilege, State.WORKING, "OEA screens calls: the Call Blocker's rules run.", "Nothing to do.")
            } else {
                Finding(privilege, State.NEEDS_TAP, "Call screening is not granted.", "Android Settings › Apps › Default apps › Caller ID & spam app.")
            }

            Privilege.DEVICE_OWNER -> if (owner) {
                Finding(privilege, State.WORKING, "OEA is the device owner: it can grant its own permissions.", "Nothing to do.")
            } else {
                Finding(
                    privilege, State.UNAVAILABLE,
                    "OEA is not the device owner. Android only allows this on a phone that has been reset, or with one command from a computer.",
                    "Not needed: every feature works through its owner-granted route or its Home fallback. The command exists for anyone who wants the strongest setup: adb shell dpm set-device-owner ${app.packageName}/${'$'}DEVICE_OWNER_RECEIVER"
)
            }

            Privilege.ROOT -> if (root) {
                Finding(privilege, State.WORKING, "su answered: OEA's own privileged channel is live.", "Nothing to do.")
            } else {
                Finding(privilege, State.UNAVAILABLE, "No root on this phone.", "Nothing needed: OEA never requires root. Everything above has a route that does not use it.")
            }

            Privilege.NOTIFICATIONS, Privilege.CONTACTS, Privilege.CAMERA -> {
                val granted = OeaPrivileges.hasRuntimePermission(app, runtimePermission(privilege))
                when {
                    granted -> Finding(privilege, State.WORKING, "${privilege.title} is allowed.", "Nothing to do.")
                    privilegedShell && android.os.Build.VERSION.SDK_INT < 33 -> Finding(privilege, State.OEA_CAN_FIX, "Not allowed; OEA has $shellLabel access and can grant it.", "Tap Fix.", command = repairCommand(app, root, privilege))
                    else -> Finding(privilege, State.NEEDS_TAP, "Not allowed.", "Android will ask the first time OEA needs it; you can also grant it in Settings › Apps › OEA › Permissions.")
                }
            }
        }
    }

    private fun runtimePermission(privilege: Privilege): String = when (privilege) {
        Privilege.NOTIFICATIONS -> if (android.os.Build.VERSION.SDK_INT >= 33) "android.permission.POST_NOTIFICATIONS" else "android.permission.ACCESS_NOTIFICATION_POLICY"
        Privilege.CONTACTS -> android.Manifest.permission.READ_CONTACTS
        else -> android.Manifest.permission.CAMERA
    }

    /**
     * The component Android needs for the device-owner command. It is the same
     * DeviceAdminReceiver OEA already declares, so the command works without
     * anything extra installed.
     */
    private val DEVICE_OWNER_RECEIVER = "com.oea.launcher.applock.OeaDeviceAdminReceiver"

    /**
     * Runs a short check on the main thread and waits for it. Only used for the one
     * capability that has to touch the window manager; everything else is answered
     * from Android's own services on the calling thread.
     */
    private fun onMain(block: () -> Boolean): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return runCatching(block).getOrDefault(false)
        val latch = CountDownLatch(1)
        val answer = AtomicBoolean(false)
        val posted = runCatching {
            Handler(Looper.getMainLooper()).post {
                answer.set(runCatching(block).getOrDefault(false))
                latch.countDown()
            }
        }.isSuccess
        if (!posted) return false
        // A blocked main thread must not hang the doctor: answer "not verified"
        // and let the row say so rather than freezing the screen.
        runCatching { latch.await(900L, TimeUnit.MILLISECONDS) }
        return answer.get()
    }

    /**
     * The command as it should be shown: with OEA's own `su -c` prefix when the
     * phone has root (so the row can be copied and run exactly as OEA runs it),
     * and bare otherwise (so it can be pasted into a PC's adb shell).
     */
    private fun command(root: Boolean, cmd: String): String = if (root) "su -c \"$cmd\"" else cmd

    /** Use the same package-scoped command that the grant path actually executes. */
    private fun repairCommand(app: Context, root: Boolean, privilege: Privilege): String? =
        OeaPrivileges.rootCommand(app, privilege)?.let { command(root, it) }

    /** What the doctor fixes by itself, in one pass. */
    fun fixAll(context: Context, onDone: (OeaPrivileges.Summary) -> Unit) {
        OeaPrivileges.repairAll(context, onDone)
    }
}
