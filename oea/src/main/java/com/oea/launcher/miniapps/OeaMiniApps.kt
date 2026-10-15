package com.oea.launcher.miniapps

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import com.oea.launcher.R
import com.oea.launcher.fault.OeaFaultShield
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaType

/**
 * OEA's launcher mini-apps: the small tools that should not need a separate app
 * installed, or a trip through the store.
 *
 * Every mini-app is a plain View builder. That keeps them cheap to open and,
 * more importantly, **contained**: a mini-app builds inside a `runCatching`, so a fault
 * in one shows a card in its own page and leaves Home, the drawer and every other
 * mini-app untouched. Nothing here can take the launcher down with it.
 *
 * Expanded ecosystem: Clock, Calendar, EMAGE (OEA Image), VIP (Video Instant Player),
 * Weather, Calculator (full), Messaging, Notes (full), Camera and Console (central utility).
 */
object OeaMiniApps {

    /** One mini-app: how it is listed, the activity that owns it, and how its view is built. */
    class App(
        val id: String,
        val label: String,
        val blurb: String,
        val icon: Int,
        /** The mini-app's own application entry (own icon, label and task in the manifest). */
        val activity: Class<out OeaMiniAppActivity>,
        /** Builds the mini-app's view. [host] is the activity showing it. */
        val build: (host: Activity, palette: OeaPalette) -> View
)

    val all: List<App> = listOf(
        App(
            "clock", "Clock", "Alarms, timers, stopwatch, world clock — separate from Calendar", R.drawable.ic_oea_clock, OeaClockActivity::class.java
) { host, palette -> OeaClockApp.build(host, palette) },
        App(
            "calendar", "Calendar", "Month, week and day views with events and reminders", R.drawable.ic_oea_calendar, OeaCalendarActivity::class.java
) { host, palette -> OeaCalendarApp.build(host, palette) },
        App(
            "emage", "OEA Image", "EMAGE • Browse, albums, downloads, previews and organization", R.drawable.ic_oea_image, OeaImageActivity::class.java
) { host, palette -> OeaImageApp.build(host, palette) },
        App(
            "vip", "OEA VIP", "Video Instant Player • Smooth playback, rotation, seeking", R.drawable.ic_oea_video, OeaVipActivity::class.java
) { host, palette -> OeaVideoApp.build(host, palette) },
        App(
            "weather", "OEA Weather", "Forecasts, real data from Open-Meteo, widgets", R.drawable.ic_oea_weather, OeaWeatherActivity::class.java
) { host, palette -> OeaWeatherApp.build(host, palette) },
        App(
            "calculator", "OEA Calculator", "Full calculator with scientific, history and tape", R.drawable.oic_calculate, OeaCalculatorActivity::class.java
) { host, palette -> OeaCalculatorApp.build(host, palette) },
        App(
            "messaging", "OEA Messaging", "SMS messaging with proper permissions and roles", R.drawable.ic_oea_messaging, OeaMessagingActivity::class.java
) { host, palette -> OeaMessagingApp.build(host, palette) },
        App(
            "notes", "OEA Notes", "Notes, private notes, voice notes, search and organization", R.drawable.ic_oea_notes, OeaNotesActivity::class.java
) { host, palette -> OeaNotesApp.build(host, palette) },
        App(
            "camera", "OEA Camera", "Camera with preview, flash, timer and capture controls", R.drawable.ic_oea_camera, OeaCameraActivity::class.java
) { host, palette -> OeaCameraApp.build(host, palette) },
        App(
            "console", "OEA Console", "Central utility: diagnostics, device info, security, command system", R.drawable.ic_oea_console, OeaConsoleActivity::class.java
) { host, palette -> OeaConsoleApp.build(host, palette) },
        App(
            "gallery", "Gallery", "Every OEA picture — wallpapers, lock screens, your photos and live scenes", R.drawable.oic_photo_library, OeaGalleryActivity::class.java
) { host, palette -> OeaGalleryApp.build(host, palette) },
        App(
            "voice", "OEA Voice", "Launcher's own speech recognizer — offline + launcher commands", R.drawable.oic_mic, OeaVoiceActivity::class.java
) { host, palette -> OeaVoiceApp.build(host, palette) }
)

    fun byId(id: String?): App? = all.firstOrNull { it.id == id }

    fun build(host: Activity, id: String?, kit: OeaKit): View {
        val app = byId(id)
        if (app == null) {
            return kit.text("This mini-app is no longer available.", 15f, kit.palette.textSecondary).apply {
                setPadding(kit.dp(6), kit.dp(10), kit.dp(6), kit.dp(10))
            }
        }
        val started = System.currentTimeMillis()
        return runCatching { app.build(host, kit.palette) }
            .onFailure { error ->
                Log.w(TAG, "Mini-app ${app.id} could not build", error)
                OeaFaultShield.note("miniapp:${app.id}", error)
            }
            .getOrElse {
                val card = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
                card.addView(kit.banner(card, R.drawable.oic_warning_fill, "${app.label} could not start", "A problem in this mini-app was contained — the launcher is unaffected."))
                card.addView(
                    kit.row(card, R.drawable.oic_shield, "See what happened", "Settings › Stability keeps the details") {
                        runCatching {
                            host.startActivity(
                                Intent(host, com.oea.launcher.OeaSystemToolsActivity::class.java)
                                    .putExtra(com.oea.launcher.OeaSystemToolsActivity.EXTRA_SCREEN, "stability")
)
                        }
                    }
)
                card
            }
            .also { built ->
                built.alpha = 0f
                built.animate().alpha(1f).setDuration(160L).start()
                if (System.currentTimeMillis() - started > 250L) Log.i(TAG, "Mini-app ${app.id} took ${System.currentTimeMillis() - started} ms to build")
            }
    }

    /**
     * Opens a mini-app through its own activity and task. With no [id] it opens
     * the index (kept for Settings and the Home menu; the index is not a
     * launcher entry any more — the apps now stand on their own).
     */
    /**
     * The explicit launch intent for a mini-app's own activity (or the index
     * when [id] is null/unknown). Used by the launcher, widgets, gestures and
     * voice commands, so every one of them lands on the same real application.
     */
    fun launchIntent(context: Context, id: String?): Intent {
        val app = byId(id)
        return Intent(context, app?.activity ?: OeaMiniAppActivity::class.java)
    }

    fun open(context: Context, id: String? = null): Boolean = runCatching {
        val intent = launchIntent(context, id)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrElse {
        Log.w(TAG, "Mini-app host could not start", it)
        false
    }

    fun tile(kit: OeaKit, app: App, onClick: () -> Unit): View {
        val column = LinearLayout(kit.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            isClickable = true
            background = kit.ripple(kit.rounded(kit.palette.card, 20), 20)
            setPadding(kit.dp(12), kit.dp(12), kit.dp(12), kit.dp(12))
            setOnClickListener { onClick() }
        }
        column.addView(kit.iconBadge(app.icon, kit.palette.accentSoft, kit.palette.onAccentSoft, 44))
        column.addView(
            kit.text(app.label, 14f, kit.palette.text, OeaType.medium).apply {
                setPadding(0, kit.dp(8), 0, 0)
                gravity = android.view.Gravity.CENTER
            }
)
        column.addView(
            kit.text(app.blurb, 11.5f, kit.palette.textTertiary).apply {
                setPadding(0, kit.dp(2), 0, 0)
                gravity = android.view.Gravity.CENTER
                maxLines = 3
                setLineSpacing(kit.dpf(2f), 1f)
            }
)
        return column
    }

    fun grid(kit: OeaKit, parent: LinearLayout, onOpen: (App) -> Unit) {
        all.chunked(3).forEach { chunk ->
            val row = LinearLayout(kit.context).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEachIndexed { index, app ->
                row.addView(
                    tile(kit, app) { onOpen(app) },
                    LinearLayout.LayoutParams(0, -2, 1f).apply {
                        leftMargin = if (index == 0) 0 else kit.dp(5)
                        rightMargin = if (index == chunk.lastIndex) 0 else kit.dp(5)
                    }
)
            }
            repeat(3 - chunk.size) {
                row.addView(View(kit.context), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = kit.dp(5) })
            }
            parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(10) })
        }
    }

    private const val TAG = "OeaMiniApps"
}
