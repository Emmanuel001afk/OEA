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
 * Expanded ecosystem: Clock, Calendar, EMAGE (photos, albums and OEA pictures), VIP
 * (Video Instant Player), Weather, Calculator, Messaging, Notes, Camera, Console and Voice.
 */
object OeaMiniApps {

    /** How much chrome the host puts around a mini-app. */
    enum class Style {
        /** The title page: a large title, a subtitle, and a scrollable column. */
        Page,

        /**
         * No page at all: the mini-app owns the whole window, its own app bar and
         * its own scrolling. Media apps need this — a virtualised grid inside a
         * settings ScrollView is the layout that made EMAGE and VIP feel like
         * previews of an app rather than one, and it is also what made them stop
         * at a few hundred items.
         */
        FullBleed
    }

    /** One mini-app: how it is listed, the activity that owns it, and how its view is built. */
    class App(
        val id: String,
        val label: String,
        val blurb: String,
        val icon: Int,
        /** The mini-app's own application entry (own icon, label and task in the manifest). */
        val activity: Class<out OeaMiniAppActivity>,
        /** The chrome the host wraps it in. */
        val style: Style = Style.Page,
        /** Builds the mini-app's view. [host] is the activity showing it. */
        val build: (host: Activity, palette: OeaPalette) -> View
)

    /**
     * Every mini-app, in drawer and settings order. Names carry no "OEA"
     * prefix (only OEA Settings keeps it). EMAGE is the one picture app: it
     * replaces the old Image and Gallery apps and contains the OEA pictures.
     */
    val all: List<App> = listOf(
        App(
            "clock", "Clock", "Alarms, timers, stopwatch, world clock — separate from Calendar", R.drawable.ic_oea_clock, OeaClockActivity::class.java
        ) { host, palette -> OeaClockApp.build(host, palette) },
        App(
            "calendar", "Calendar", "Month, week and day views with events and reminders", R.drawable.ic_oea_calendar, OeaCalendarActivity::class.java
        ) { host, palette -> OeaCalendarApp.build(host, palette) },
        App(
            "emage", "EMAGE", "Every picture on this phone: albums, days, favourites and the OEA roll", R.drawable.ic_oea_image,
            OeaImageActivity::class.java, Style.FullBleed
        ) { host, palette -> OeaImageApp.build(host, palette) },
        App(
            "vip", "VIP", "Video Instant Player • albums, playlists, subtitles, speed, PIP", R.drawable.ic_oea_video,
            OeaVipActivity::class.java, Style.FullBleed
        ) { host, palette -> OeaVideoApp.build(host, palette) },
        App(
            "weather", "Weather", "Forecasts, real data from Open-Meteo, widgets", R.drawable.ic_oea_weather, OeaWeatherActivity::class.java
        ) { host, palette -> OeaWeatherApp.build(host, palette) },
        App(
            "calculator", "Calculator", "Full calculator with scientific, history and tape", R.drawable.ic_oea_calculator, OeaCalculatorActivity::class.java
        ) { host, palette -> OeaCalculatorApp.build(host, palette) },
        App(
            "messaging", "Messaging", "SMS history, search, conversation review and replies", R.drawable.ic_oea_messaging, OeaMessagingActivity::class.java
        ) { host, palette -> OeaMessagingApp.build(host, palette) },
        App(
            "notes", "Notes", "Notes, private notes, voice notes, search and organization", R.drawable.ic_oea_notes, OeaNotesActivity::class.java
        ) { host, palette -> OeaNotesApp.build(host, palette) },
        App(
            "camera", "Camera", "Full-screen camera: photo, video, zoom and the modes you need", R.drawable.ic_oea_camera,
            OeaCameraActivity::class.java, Style.FullBleed
        ) { host, palette -> OeaCameraApp.build(host, palette) },
        App(
            "console", "Console", "Central utility: diagnostics, device info, security, command system", R.drawable.ic_oea_console, OeaConsoleActivity::class.java
        ) { host, palette -> OeaConsoleApp.build(host, palette) },
        App(
            "voice", "Voice", "Launcher's own speech recognizer — offline + launcher commands", R.drawable.ic_oea_voice, OeaVoiceActivity::class.java
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
        // Null is the intentional Settings/Home index entry. A misspelled or
        // stale non-null ID must never silently open that index instead of the
        // app the user selected.
        if (id == null) return Intent(context, OeaMiniAppActivity::class.java)
        val app = byId(id)
            ?: throw IllegalArgumentException("Unknown OEA mini-app id: $id")
        return Intent(context, app.activity)
    }

    fun open(context: Context, id: String? = null): Boolean = runCatching {
        val app = if (id == null) null else byId(id)
            ?: throw IllegalArgumentException("Unknown OEA mini-app id: $id")
        if (app != null && !OeaMiniAppSwitches.isOn(context, app.id)) {
            android.widget.Toast.makeText(context, "${app.label} is turned off in OEA Settings › Mini-apps", android.widget.Toast.LENGTH_LONG).show()
            return@runCatching false
        }
        val intent = launchIntent(context, id)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrElse { error ->
        val subsystem = if (id == null) "miniapp:index:launch" else "miniapp:$id:launch"
        Log.w(TAG, "OEA ${id ?: "mini-app index"} could not start", error)
        OeaFaultShield.note(subsystem, error)
        runCatching {
            android.widget.Toast.makeText(
                context,
                if (id == null) "OEA mini-app index could not open." else "${byId(id)?.label ?: id} could not open. OEA recorded the error.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
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
        // Mini-apps that are switched off in OEA Settings are not offered here.
        all.filter { OeaMiniAppSwitches.isOn(kit.context, it.id) }.chunked(3).forEach { chunk ->
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
