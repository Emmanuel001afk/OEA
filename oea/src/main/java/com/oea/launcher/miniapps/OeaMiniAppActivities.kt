package com.oea.launcher.miniapps

/**
 * The concrete, independently launchable activity of every OEA mini-app.
 *
 * One shared window with a launch extra could not give a mini-app what a real
 * application needs: its own entry in the app list and on Home, its own task in
 * Recents with its own name and icon, and a screen that cannot be confused by
 * intent extras or activity-alias metadata being misread by a given Android
 * build. Each class below is that real application entry; all behaviour still
 * lives in the builders, so these stay one-liners.
 */

/** Clock — alarms, timers, stopwatch, world clock. */
class OeaClockActivity : OeaMiniAppActivity() { override val appId get() = "clock" }

/** Calendar — month/week/day views, events and reminders. */
class OeaCalendarActivity : OeaMiniAppActivity() { override val appId get() = "calendar" }

/** EMAGE — photos, albums, downloads, and the OEA pictures (wallpapers, lock screens, live scenes). */
class OeaImageActivity : OeaMiniAppActivity() { override val appId get() = "emage" }

/** VIP (Video Instant Player) — full-screen video and audio playback with PIP. */
class OeaVipActivity : OeaMiniAppActivity() { override val appId get() = "vip" }

/** Weather — forecast with a real data source. */
class OeaWeatherActivity : OeaMiniAppActivity() { override val appId get() = "weather" }

/** Calculator — arithmetic, scientific, memory and tape. */
class OeaCalculatorActivity : OeaMiniAppActivity() { override val appId get() = "calculator" }

/** Messaging — SMS with the correct Android role handling. */
class OeaMessagingActivity : OeaMiniAppActivity() { override val appId get() = "messaging" }

/** Notes — notes, private notes and voice notes. */
class OeaNotesActivity : OeaMiniAppActivity() { override val appId get() = "notes" }

/** Camera — viewfinder, flash, timer and capture. */
class OeaCameraActivity : OeaMiniAppActivity() { override val appId get() = "camera" }

/** Console — the central utility: diagnostics, device, security, command system. */
class OeaConsoleActivity : OeaMiniAppActivity() { override val appId get() = "console" }

/** Voice — the launcher's own speech recognizer. */
class OeaVoiceActivity : OeaMiniAppActivity() { override val appId get() = "voice" }
