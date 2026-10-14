package com.oea.launcher.privilege

import android.content.ComponentName
import android.content.Context
import android.os.Build

/**
 * The privileges Android asks an app to be granted before certain features work,
 * and the fallback OEA uses when the user has not granted them.
 *
 * OEA never depends on a separate privileged helper app. Everything here is
 * either (a) a permission the user grants inside Android's own settings, (b) a
 * capability Android gives to a home app, dialer or device owner, or (c) root
 * when the device already has it. A denied privilege is therefore always a
 * reduced feature, never a broken launcher.
 */
enum class Privilege(val title: String, val summary: String) {
    /** Being the default Home app: Home button, widget pinning, app shortcuts. */
    HOME_ROLE("Default Home app", "Android grants this from its own Home or Roles dialog, no external tool."),

    /** Accessibility service: lock screen, Recents, split screen, foreground awareness, force-stop. */
    ACCESSIBILITY("OEA Assistant (accessibility)", "Android's Accessibility settings page; grantable by the user alone."),

    /** Drawing over other apps: system-wide island, Game Boost panel, floating controls. */
    OVERLAY("Display over other apps", "Android's per-app toggle; grantable by the user alone."),

    /** Notification access: dots, Now Playing, notification actions in the island. */
    NOTIFICATION_ACCESS("Notification access", "Android's Notification-access page; grantable by the user alone."),

    /** Usage access: most used order, screen time, Game Boost's foreground game. */
    USAGE_ACCESS("Usage access", "Android's Special app access page; grantable by the user alone."),

    /** Device admin: the no-root App Freezer's pause and the lock action. */
    DEVICE_ADMIN("Device admin (App Freezer)", "Android's Security page; grantable by the user alone."),

    /** Device owner: the strongest role Android gives a launcher without a PC. */
    DEVICE_OWNER("Device owner", "Set up once during a factory reset (or with one command on a PC); then OEA can grant permissions itself."),

    /** Root (su): OEA's own privileged channel, stronger than any helper app. */
    ROOT("Root (su)", "Only used when the device already has a working su binary; never required."),

    /** Do-Not-Disturb access: silence game sessions and calls by policy. */
    DND_ACCESS("Do Not Disturb access", "Android's Sound page; grantable by the user alone."),

    /** Battery optimization exemption: keeps services alive on aggressive OEMs. */
    BATTERY_UNRESTRICTED("Unrestricted battery", "Android's Battery page; grantable by the user alone."),

    /** Dialer role: OEA Phone answers, holds and routes calls through Telecom. */
    DIALER_ROLE("Default dialer", "Android's default-dialer dialog; grantable by the user alone."),

    /** Call screening role: Call Blocker's rules. */
    CALL_SCREENING_ROLE("Call screening", "Android's default-call-screening dialog; grantable by the user alone."),

    /** Notifications themselves (Android 13+ runtime permission). */
    NOTIFICATIONS("Notifications", "Android's runtime permission dialog."),

    CONTACTS("Contacts", "Android's runtime permission dialog."),

    /** Camera: the island's camera view and any capture OEA itself offers. */
    CAMERA("Camera", "Android's runtime permission dialog."),
}

/** Whether a privilege is in effect. [PENDING] means granted but not yet live. */
enum class Grant {
    GRANTED,
    /** Switched on by the user, but Android has not bound the service yet. */
    PENDING,
    DENIED,
    /** This Android version or device cannot provide it at all. */
    UNSUPPORTED,
}

/** How OEA delivers a feature, from best to most limited. */
enum class Surface(val label: String, val detail: String) {
    /** Inside OEA Home's own window: no extra permission, full integration. */
    HOME("Inside Home", "part of the launcher's own screen"),

    /** A real system-wide window; needs the overlay privilege (or root/device owner). */
    OVERLAY("Over other apps", "a window above every app"),

    /** An ordinary notification; always available, no special access. */
    NOTIFICATION("Notification", "a heads-up card with buttons"),

    /** Telecom / in-call surface, for call features. */
    CALL("Call screen", "Android's call window"),

    /** Not available on this device. */
    NONE("Unavailable", "this device or permission set cannot show it"),
}

/**
 * One feature's capability: what it needs, what it has now, and what it falls
 * back to. The Settings screen and every feature read this instead of poking
 * Android's permission APIs directly, so behaviour stays consistent.
 */
data class Capability(
    val feature: String,
    val requires: Privilege,
    val granted: Grant,
    /** The best surface this device can offer right now. */
    val surface: Surface,
    /** What the user actually loses when [surface] is not [Surface.OVERLAY]/[Surface.HOME]. */
    val effect: String,
    /** True when OEA can fix this itself (device owner or root), without a settings page. */
    val selfGrantable: Boolean,
) {
    val isReady get() = granted == Grant.GRANTED
    val isPending get() = granted == Grant.PENDING
}

/** Component of OEA Assistant, used by every accessibility check. */
internal fun assistantComponent(context: Context) =
    ComponentName(context.packageName, "com.oea.launcher.assistant.OeaAssistantService")

internal fun notificationListenerComponent(context: Context) =
    ComponentName(context.packageName, "com.oea.launcher.notifications.OeaNotificationListener")

/** True when the device is old enough that a check makes no sense. */
internal fun atLeast(api: Int): Boolean = Build.VERSION.SDK_INT >= api
