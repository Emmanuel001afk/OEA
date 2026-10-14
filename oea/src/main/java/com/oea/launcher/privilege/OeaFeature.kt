package com.oea.launcher.privilege

/**
 * Every OEA feature whose reach depends on a privilege, with the surface it
 * prefers and the two it falls back to. Keeping the table in one place is what
 * lets OEA degrade gracefully everywhere instead of a feature being quietly
 * unavailable on devices that lack one permission.
 *
 * [preferred] is used when [needs] is granted, [pending] when Android has been
 * told yes but has not bound the service yet, and [fallback] when it is denied.
 */
enum class OeaFeature(
    val title: String,
    val needs: Privilege,
    val preferred: Surface,
    val pending: Surface,
    val fallback: Surface,
    val effect: String,
) {
    /** Live activity (Dynamic Island) above other apps; inside Home it needs nothing. */
    ISLAND_OVER_APPS(
        "Live activity over other apps",
        Privilege.OVERLAY,
        Surface.OVERLAY,
        Surface.HOME,
        Surface.HOME,
        "the island stays inside Home and inside games instead of floating over every app",
    ),

    /** Notification content: dots on icons, Now Playing, actionable cards. */
    NOTIFICATION_CONTENT(
        "Notification dots and Now Playing",
        Privilege.NOTIFICATION_ACCESS,
        Surface.HOME,
        Surface.HOME,
        Surface.NONE,
        "charging and OEA's own calls still show; other apps' notification content stays hidden",
    ),

    /** Game Boost's draggable RAM/network panel above the game. */
    GAME_BOOST_PANEL(
        "Game Boost panel over games",
        Privilege.OVERLAY,
        Surface.OVERLAY,
        Surface.OVERLAY,
        Surface.NOTIFICATION,
        "the panel moves into Game Boost and a notification with the same actions",
    ),

    /** Knowing which game is in front, for boost, islands and usage ranking. */
    FOREGROUND_GAME(
        "Foreground game detection",
        Privilege.USAGE_ACCESS,
        Surface.OVERLAY,
        Surface.OVERLAY,
        Surface.HOME,
        "Game Boost follows apps you launch from OEA Home instead of every game",
    ),

    /** Lock screen from a Home gesture. */
    SCREEN_LOCK(
        "Lock screen gesture",
        Privilege.ACCESSIBILITY,
        Surface.HOME,
        Surface.HOME,
        Surface.NONE,
        "double-tap to lock is unavailable; the gesture can be set to something else",
    ),

    /** System Recents and split-screen toggles. */
    SYSTEM_SHELF(
        "Recents, notifications and quick settings gestures",
        Privilege.ACCESSIBILITY,
        Surface.HOME,
        Surface.HOME,
        Surface.HOME,
        "notifications and quick settings still open through Android's own status bar",
    ),

    /** Force-stopping an app for the no-root App Freezer. */
    FORCE_STOP(
        "Force-stop (App Freezer)",
        Privilege.ACCESSIBILITY,
        Surface.HOME,
        Surface.HOME,
        Surface.HOME,
        "apps stay hibernated inside OEA, which already blocks launching them",
    ),

    /** Real Android-level freezing of another app. */
    APP_FREEZE(
        "Freeze another app's process",
        Privilege.DEVICE_OWNER,
        Surface.HOME,
        Surface.HOME,
        Surface.HOME,
        "OEA Freeze (hibernate inside OEA plus force-stop) is used instead of Android suspension",
    ),

    /** Silent, self-directed permission grants without touching Settings. */
    SELF_GRANT(
        "Grant OEA permissions itself",
        Privilege.ROOT,
        Surface.HOME,
        Surface.HOME,
        Surface.HOME,
        "each permission is switched on from Android's own settings page with one tap",
    ),

    /** Keeping OEA's services alive on aggressive OEM battery managers. */
    BACKGROUND_LIFE(
        "Stay alive in the background",
        Privilege.BATTERY_UNRESTRICTED,
        Surface.HOME,
        Surface.HOME,
        Surface.HOME,
        "OEA restarts its services when Home resumes, which covers most OEM kills",
    ),

    /** Answering and ending calls from the island or a Home card. */
    CALL_CONTROL(
        "Call controls",
        Privilege.DIALER_ROLE,
        Surface.CALL,
        Surface.CALL,
        Surface.NOTIFICATION,
        "incoming calls use the system dialer's notification; OEA's own call screens still open",
    ),

    /** Screening/blocking calls by rule. */
    CALL_BLOCKING(
        "Call blocking rules",
        Privilege.CALL_SCREENING_ROLE,
        Surface.CALL,
        Surface.CALL,
        Surface.NONE,
        "saved rules stay in OEA but Android will not hand over the call before it rings",
    ),

    /** Ranking apps by how often they are used. */
    USAGE_RANKING(
        "Most-used order",
        Privilege.USAGE_ACCESS,
        Surface.HOME,
        Surface.HOME,
        Surface.HOME,
        "OEA ranks by its own launch history instead of Android's full usage stats",
    ),

    /** Silencing notifications while a game runs. */
    SILENCE_GAMES(
        "Silence during games",
        Privilege.DND_ACCESS,
        Surface.NOTIFICATION,
        Surface.NOTIFICATION,
        Surface.NONE,
        "Game Boost keeps its other controls; Do Not Disturb stays a manual switch",
    ),
}
