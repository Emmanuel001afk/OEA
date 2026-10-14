package com.oea.launcher.privilege

import android.content.Context
import android.util.Log

/**
 * OEA's own access doctor: what OEA is allowed to do, *verified*, and what it can
 * do about it without a PC or another app.
 *
 * A settings screen that only reads `Settings.canDrawOverlays` is how a user ends
 * up saying "it is switched on but nothing happens": Android can report a
 * permission as granted while the capability is still not usable (AppOps-backed
 * firmware, a service enabled in Settings but never bound, an OEM that keeps a
 * permission in its own list). Every row here therefore carries the state OEA
 * *observed*, not the state the switch claims, and every row knows
 *  - how to fix it (OEA's own root channel, the device-owner API, or the exact
 *    Android screen),
 *  - what to do on a phone where nothing OEA has can fix it, and
 *  - the exact shell command OEA would run, so the same fix can be repeated from a
 *    PC by anyone who prefers that route — OEA never *requires* the PC.
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
        /** OEA can grant it itself (root or device owner). */
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
        val command: String? = null,
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
                        "Nothing is broken: the feature falls back to its own Home surface.",
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
        val owner = OeaPrivileges.isDeviceOwner(app)
        return when (privilege) {
            Privilege.OVERLAY -> {
                val reported = OeaPrivileges.canOverlay(app)
                val draws = reported && com.oea.launcher.workspace.OeaDynamicIslandOverlayService.verifyOverlay(app)
                when {
                    draws -> Finding(privilege, State.WORKING, "OEA drew a test window over the screen and it stayed.", "Nothing to do.")
                    reported -> Finding(
                        privilege, State.STALE,
                        "Android says the permission is granted, but drawing over apps was refused.",
                        "Switch Display over other apps off and on again for OEA, then press Verify. On some firmware the AppOps entry is the one that matters.",
                        command = command(root, "appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow"),
                    )
                    root -> Finding(privilege, State.OEA_CAN_FIX, "Not granted; OEA has root and can grant it now.", "Tap Fix.", command = command(root, "appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow"))
                    owner -> Finding(privilege, State.OEA_CAN_FIX, "Not granted; OEA is the device owner and can grant it now.", "Tap Fix.")
                    else -> Finding(privilege, State.NEEDS_TAP, "Not granted.", "Android Settings › Apps › OEA › Display over other apps.", command = command(root, "appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow"))
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
                        command = command(root, "settings put secure enabled_accessibility_services ${assistantList(app)}"),
                    )
                    root -> Finding(privilege, State.OEA_CAN_FIX, "Not enabled; OEA has root and can enable its own service.", "Tap Fix.", command = command(root, "settings put secure enabled_accessibility_services ${assistantList(app)}"))
                    owner -> Finding(privilege, State.OEA_CAN_FIX, "Not enabled; OEA is the device owner and can enable it.", "Tap Fix.")
                    else -> Finding(privilege, State.NEEDS_TAP, "Not enabled.", "Turn on OEA Assistant in Android Settings › Accessibility › Installed apps.", command = "settings put secure enabled_accessibility_services ${assistantList(app)}")
                }
            }

            Privilege.NOTIFICATION_ACCESS -> {
                val can = OeaPrivileges.canReadNotifications(app)
                when {
                    can -> Finding(privilege, State.WORKING, "OEA can read notifications: the island, dots and Now Playing work.", "Nothing to do.")
                    root -> Finding(privilege, State.OEA_CAN_FIX, "Not granted; OEA has root and can grant it.", "Tap Fix.", command = command(root, "cmd notification allow_listener ${assistantComponent(app)}"))
                    else -> Finding(privilege, State.NEEDS_TAP, "Not granted.", "Android Settings › Notifications › Device & app notifications › OEA.", command = command(root, "cmd notification allow_listener ${assistantComponent(app)}"))
                }
            }

            Privilege.USAGE_ACCESS -> {
                val can = OeaPrivileges.canReadUsage(app)
                when {
                    can -> Finding(privilege, State.WORKING, "OEA can see which app is in front and your most-used apps.", "Nothing to do.")
                    root -> Finding(privilege, State.OEA_CAN_FIX, "Not granted; OEA has root and can grant it.", "Tap Fix.", command = command(root, "appops set ${app.packageName} GET_USAGE_STATS allow"))
                    else -> Finding(privilege, State.NEEDS_TAP, "Not granted.", "Android Settings › Special app access › Usage access › OEA.", command = command(root, "appops set ${app.packageName} GET_USAGE_STATS allow"))
                }
            }

            Privilege.DND_ACCESS -> {
                val can = OeaPrivileges.canUseDnd(app)
                when {
                    can -> Finding(privilege, State.WORKING, "OEA can switch sound mode and Do Not Disturb.", "Nothing to do.")
                    root -> Finding(privilege, State.OEA_CAN_FIX, "Not granted; OEA has root and can grant it.", "Tap Fix.", command = command(root, "appops set ${app.packageName} ACCESS_NOTIFICATION_POLICY allow"))
                    else -> Finding(privilege, State.NEEDS_TAP, "Not granted.", "Android Settings › Sound & vibration › Do Not Disturb access.", command = command(root, "appops set ${app.packageName} ACCESS_NOTIFICATION_POLICY allow"))
                }
            }

            Privilege.BATTERY_UNRESTRICTED -> {
                val ok = OeaPrivileges.ignoresBatteryOptimizations(app)
                when {
                    ok -> Finding(privilege, State.WORKING, "Android will not freeze OEA in the background.", "Nothing to do.")
                    root -> Finding(privilege, State.OEA_CAN_FIX, "Still battery-optimised; OEA has root and can exempt itself.", "Tap Fix.", command = command(root, "dumpsys deviceidle whitelist +${app.packageName}"))
                    else -> Finding(privilege, State.NEEDS_TAP, "Battery optimisation is on.", "Android Settings › Apps › OEA › Battery › Unrestricted.", command = command(root, "dumpsys deviceidle whitelist +${app.packageName}"))
                }
            }

            Privilege.DEVICE_ADMIN -> if (OeaPrivileges.isDeviceAdmin(app)) {
                Finding(privilege, State.WORKING, "OEA's Device admin is active: the Freezer and the lock fallback work.", "Nothing to do.")
            } else {
                Finding(privilege, State.NEEDS_TAP, "Not active.", "Activate OEA's Device admin in Settings › Lock & power — no root and no PC needed.")
            }

            Privilege.HOME_ROLE -> if (OeaPrivileges.holdsHomeRole(app)) {
                Finding(privilege, State.WORKING, "OEA is the default Home app.", "Nothing to do.")
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
                    "Not needed: every feature works through its owner-granted route or its Home fallback. The command exists for anyone who wants the strongest setup: adb shell dpm set-device-owner ${app.packageName}/${'$'}DEVICE_OWNER_RECEIVER",
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
                    root && android.os.Build.VERSION.SDK_INT < 33 -> Finding(privilege, State.OEA_CAN_FIX, "Not allowed; OEA has root and can grant it.", "Tap Fix.", command = command(root, "pm grant ${app.packageName} ${runtimePermission(privilege)}"))
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
    private val DEVICE_OWNER_RECEIVER = "com.oea.launcher.power.OeaDeviceAdminReceiver"

    /**
     * The command as it should be shown: with OEA's own `su -c` prefix when the
     * phone has root (so the row can be copied and run exactly as OEA runs it),
     * and bare otherwise (so it can be pasted into a PC's adb shell).
     */
    private fun command(root: Boolean, cmd: String): String = if (root) "su -c \"$cmd\"" else cmd

    /**
     * The accessibility service list OEA writes when it grants itself the
     * assistant: the user's existing services are kept, OEA's is appended
     * (or removed) — never replaced, which would silently switch other apps off.
     */
    private fun assistantList(app: Context): String {
        val existing = runCatching {
            android.provider.Settings.Secure.getString(app.contentResolver, "enabled_accessibility_services").orEmpty()
        }.getOrDefault("")
        val ours = assistantComponent(app)
        val keep = existing.split(':').map { it.trim() }.filter { it.isNotEmpty() && it != ours }
        return (keep + ours).joinToString(":")
    }

    /** What the doctor fixes by itself, in one pass. */
    fun fixAll(context: Context, onDone: (OeaPrivileges.Summary) -> Unit) {
        OeaPrivileges.repairAll(context, onDone)
    }
}
