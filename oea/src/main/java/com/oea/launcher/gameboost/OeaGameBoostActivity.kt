package com.oea.launcher.gameboost

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.oea.launcher.privilege.OeaPrivileges
import com.oea.launcher.privilege.Privilege
import com.oea.launcher.R
import com.oea.launcher.power.OeaBatteryState
import com.oea.launcher.assistant.OeaAssistant
import com.oea.launcher.icons.OeaIconController
import com.oea.launcher.model.OeaAppInfo
import com.oea.launcher.model.OeaAppModel
import com.oea.launcher.ui.OeaAppKit
import com.oea.launcher.ui.OeaInsets
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaScreenFailure
import com.oea.launcher.ui.OeaTheme
import com.oea.launcher.ui.OeaType
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Game Boost as its own app: a game-console style hub for the existing Game
 * Boost engine (OeaGameBoostService). It only reads and writes the engine's
 * settings and starts/stops/refreshes the service; game detection, the
 * in-game overlay and DND sessions are unchanged.
 */
class OeaGameBoostActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { task ->
        Thread(task, "OeaGameBoostApps").apply { isDaemon = true }
    }
    private lateinit var palette: OeaPalette
    private lateinit var kit: OeaAppKit
    private lateinit var rootHost: FrameLayout
    private var scroll: ScrollView? = null
    private lateinit var content: LinearLayout
    private var activeTab = "boost"
    private val tabScrollY = HashMap<String, Int>()
    private var installedApps: List<OeaAppInfo> = emptyList()
    private var detected: Set<String> = emptySet()
    private var loaded = false
    private var ramValue: TextView? = null
    private var ramCaption: TextView? = null
    private var ramBar: MeterBar? = null
    private var batteryValue: TextView? = null
    private var batteryCaption: TextView? = null
    private val statsTick = object : Runnable {
        override fun run() {
            runCatching { updateStats() }
                .onFailure { android.util.Log.w("OeaGameBoostActivity", "Could not refresh system stats", it) }
            handler.postDelayed(this, 2000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            activeTab = savedInstanceState?.getString("boost_tab") ?: "boost"
            OeaInsets.edgeToEdge(window, lightBars = false)
            rootHost = FrameLayout(this)
            setContentView(rootHost)
            render()
            loadApps()
            // A plug or unplug must show immediately, not at the next two-second tick.
            OeaBatteryState.observe(rootHost) { runCatching { updateStats() } }
        } catch (error: Exception) {
            OeaScreenFailure.show(this, "Game Boost", error)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("boost_tab", activeTab)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        // This screen is in front, so no game can be. If a session left the
        // phone holding a game's Do Not Disturb — its process was killed before
        // it could put things back — this settles it instead of waiting for the
        // next game to notice.
        if (OeaGameBoostSessionState.restoreIfOrphaned(this, OeaGameBoost.activeGame != null)) {
            toast("Do Not Disturb was still on from your last game. OEA has put it back.")
        }
        if (loaded) render()
        handler.post(statsTick)
    }

    override fun onPause() {
        handler.removeCallbacks(statsTick)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        io.shutdownNow()
        super.onDestroy()
    }

    private fun loadApps() {
        io.execute {
            val apps = runCatching { OeaAppModel(this).query() }.getOrDefault(emptyList())
                .filter { it.packageName != packageName }
                .distinctBy { it.packageName }
            OeaGameBoostStore.invalidateDetectedGames()
            val found = runCatching { OeaGameBoostStore.detectedGames(this) }.getOrDefault(emptySet())
            // Persist Android-recognized games during catalogue refresh even
            // while Game Boost is off. Enabling the service never requires the
            // user to add or save each detected game manually.
            runCatching { OeaGameBoostStore.syncDetectedGames(this) }
                .onFailure { android.util.Log.w("OeaGameBoostActivity", "Could not save detected games", it) }
            runCatching {
                OeaIconController.shared(this).preload(apps.filter {
                    it.packageName in found || it.packageName in OeaGameBoostStore.games(this)
                }.map { it.packageName to it.className })
            }.onFailure { android.util.Log.w("OeaGameBoostActivity", "Could not preload game icons", it) }
            handler.post {
                if (isDestroyed) return@post
                installedApps = apps
                detected = found
                loaded = true
                if (!isFinishing) render()
            }
        }
    }

    // ================================================================= theme

    private fun accentFor(mode: String): Int = when (mode) {
        "green" -> Color.rgb(61, 220, 132)
        "purple" -> Color.rgb(169, 116, 255)
        "cyan" -> Color.rgb(34, 211, 238)
        "red" -> Color.rgb(255, 83, 112)
        "amber" -> Color.rgb(255, 181, 71)
        "rgb" -> Color.rgb(87, 200, 255)
        else -> Color.rgb(76, 141, 255)
    }

    private fun gamePalette(): OeaPalette {
        val accent = accentFor(prefs().getString("ram_color_mode", "blue") ?: "blue")
        val background = Color.rgb(7, 9, 15)
        val card = Color.rgb(17, 22, 34)
        val accentSoft = OeaTheme.blend(card, accent, 0.24f)
        return OeaPalette(
            dark = true,
            accent = accent,
            onAccent = OeaTheme.onColor(accent),
            accentSoft = accentSoft,
            onAccentSoft = OeaTheme.onColor(accentSoft),
            background = background,
            drawer = background,
            card = card,
            cardHigh = Color.rgb(27, 34, 50),
            stroke = OeaTheme.alpha(Color.WHITE, 0x16),
            text = Color.rgb(240, 244, 250),
            textSecondary = Color.rgb(150, 162, 182),
            textTertiary = Color.rgb(98, 110, 130),
            homeText = Color.WHITE,
            homeTextSecondary = Color.WHITE,
            homeShadow = Color.BLACK,
            wallpaperLight = false,
            glass = OeaTheme.alpha(Color.WHITE, 0x1A),
            glassStrong = OeaTheme.alpha(Color.BLACK, 0x99),
            glassStroke = OeaTheme.alpha(Color.WHITE, 0x22),
            scrimTop = Color.TRANSPARENT,
            scrimBottom = Color.TRANSPARENT,
            danger = Color.rgb(255, 110, 120),
            success = Color.rgb(80, 225, 160),
            warning = Color.rgb(255, 196, 100)
)
    }

    private fun prefs() = OeaGameBoostStore.prefs(this)

    // ================================================================ render

    private fun render() {
        try {
            renderContent()
        } catch (error: Exception) {
            OeaScreenFailure.show(this, "Game Boost", error)
        }
    }

    private fun renderContent() {
        scroll?.let {
            tabScrollY[activeTab] = if (::kit.isInitialized) kit.currentScrollY(it) else it.scrollY
        }
        if (activeTab !in setOf("boost", "games", "controls")) activeTab = "boost"
        palette = gamePalette()
        kit = OeaAppKit(this, palette)
        OeaInsets.edgeToEdge(window, lightBars = false)
        runCatching { window.decorView.setBackgroundColor(palette.background) }
        ramValue = null
        ramCaption = null
        ramBar = null
        batteryValue = null
        batteryCaption = null

        val games = selectedGames()
        val (title, subtitle) = when (activeTab) {
            "games" -> "Games" to if (!loaded) "Scanning installed games…" else "${games.size} ready to boost"
            "controls" -> "HUD & Controls" to "Floating overlay, colour and permissions"
            else -> "Game Boost" to if (OeaGameBoostStore.enabled(this)) "Console engine active" else "Tap the power ring to arm"
        }
        val shell = kit.appShell(
            title = title,
            subtitle = subtitle,
            navItems = listOf(
                OeaAppKit.NavItem("boost", "Boost", R.drawable.oic_stadia_controller_fill),
                OeaAppKit.NavItem("games", "Games", R.drawable.oic_grid_view),
                OeaAppKit.NavItem("controls", "Controls", R.drawable.oic_tune)
            ),
            initialNav = activeTab
        ) { next ->
            scroll?.let { tabScrollY[activeTab] = kit.currentScrollY(it) }
            activeTab = next
            scroll = null
            render()
        }
        if (activeTab == "games") {
            shell.bar.addAction(R.drawable.oic_add, "Add games") { chooseGames() }
        } else if (activeTab == "boost") {
            shell.bar.addAction(R.drawable.oic_add, "Add games") { chooseGames() }
        }

        content = shell.body
        when (activeTab) {
            "games" -> {
                gamesSection()
                detectionSection()
            }
            "controls" -> {
                overlaySection()
                sessionSection()
                permissionsSection()
                kit.note(content, "Game Boost shows its control only over games and restores Do Not Disturb when you leave. Android does not allow raising another app's CPU or GPU priority.")
            }
            else -> {
                hero()
                statsRow()
                quickLaunchSection(games)
                sessionSection()
                val overlayOk = OeaPrivileges.canOverlay(this)
                if (!overlayOk) {
                    kit.banner(
                        content,
                        R.drawable.oic_layers,
                        "Enable floating HUD",
                        "Allow Display over other apps so the in-game RAM & network control appears over your games.",
                        OeaKit.Tone.WARNING,
                        "Allow"
                    ) {
                        OeaPrivileges.grant(this, Privilege.OVERLAY) { outcome ->
                            toast(outcome.message)
                            handler.postDelayed({ if (loaded) render() }, 700L)
                        }
                    }
                }
            }
        }

        val oldCount = rootHost.childCount
        rootHost.addView(shell.root, FrameLayout.LayoutParams(-1, -1))
        if (oldCount > 0) rootHost.removeViews(0, oldCount)
        scroll = shell.scroll
        val y = tabScrollY[activeTab] ?: 0
        if (y > 0) kit.restoreScroll(shell.scroll, y)
        if (activeTab == "boost") updateStats()
    }

    private fun quickLaunchSection(games: List<OeaAppInfo>) {
        kit.sectionTitle(content, "Quick launch")
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(12), dp(8), dp(12))
            background = kit.rounded(palette.card, 26, palette.stroke, 1)
        }
        val cells = games.take(7).map { gameCell(it) } + addCell()
        cells.chunked(4).forEach { chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { row.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
            repeat(4 - chunk.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            grid.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        if (!loaded) {
            grid.addView(kit.text("Finding your games…", 13f, palette.textSecondary).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(4))
            }, LinearLayout.LayoutParams(-1, -2))
        }
        content.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
    }

    private fun usageGranted(): Boolean = runCatching {
        getSystemService(AppOpsManager::class.java).checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName
) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** Display name of the game whose session is running, OEA-hosted games included. */
    private fun runningGameLabel(key: String): String =
        if (OeaGameBoostStore.isOeaGameKey(key)) {
            OeaGameBoostStore.oeaGameTitles()[OeaGameBoostStore.oeaGameId(key)] ?: "OEA game"
        } else {
            installedApps.firstOrNull { it.packageName == key }?.label ?: "your game"
        }

    private fun selectedGames(): List<OeaAppInfo> {
        // "Your games" now shows every game recognised by identity: Android's
        // declared game category plus OEA's own engine games (unless dismissed).
        // Manual picks appear only in Auto+manual.  This keeps automatic mode truly
        // automatic while preserving the ability to boost a non-game app when the
        // user explicitly asked for it.
        val dismissed = OeaGameBoostStore.dismissedGames(this)
        val modeManual = OeaGameBoostStore.mode(this) == "manual_automatic"
        val installed = installedApps.filter { app ->
            app.packageName !in dismissed && (OeaGameBoostStore.isCategoryGame(this, app.packageName) ||
                (modeManual && app.packageName in OeaGameBoostStore.games(this)))
        }
        val oea = OeaGameBoostStore.oeaGameEntries().filter { it.packageName !in dismissed }
        return (installed + oea).sortedBy { it.label.lowercase() }
    }

    private fun hero() {
        val enabled = OeaGameBoostStore.enabled(this)
        val count = selectedGames().size
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(20), dp(20), dp(22))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(OeaTheme.blend(palette.card, palette.accent, if (enabled) 0.34f else 0.12f), palette.card, Color.rgb(10, 13, 22))
).apply {
                cornerRadius = dp(30).toFloat()
                setStroke(dp(1), OeaTheme.alpha(palette.accent, if (enabled) 0x66 else 0x22))
            }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(kit.iconBadge(R.drawable.oic_stadia_controller_fill, OeaTheme.alpha(palette.accent, 0x33), palette.accent, 44),
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val canFollow = OeaGameBoost.canFollowSessions(this)
        val running = OeaGameBoost.activeGame
        titles.addView(kit.text(
            when {
                running != null -> runningGameLabel(running)
                enabled && canFollow -> "Engine armed"
                enabled -> "Setup needed"
                else -> "Standby"
            },
            21f, palette.text, OeaType.medium
        ))
        val status = when {
            !enabled -> "Tap the power ring to enable Game Boost"
            !canFollow -> "Enable OEA Assistant or Usage access"
            running != null -> "Session boosting now"
            count == 0 -> "Ready · add games to boost"
            else -> "Starts automatically for $count game" + (if (count == 1) "" else "s")
        }
        titles.addView(kit.text(status, 13.5f, if (enabled && canFollow) palette.accent else palette.textSecondary, OeaType.medium))
        top.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(top, LinearLayout.LayoutParams(-1, -2))
        val ring = PowerRing(this, enabled, palette.accent, palette.textTertiary, palette.card).apply {
            contentDescription = if (enabled) "Turn Game Boost off" else "Turn Game Boost on"
            setOnClickListener { toggleEnabled() }
        }
        card.addView(ring, LinearLayout.LayoutParams(dp(150), dp(150)).apply { topMargin = dp(18); bottomMargin = dp(6) })
        card.addView(kit.text(if (enabled) "BOOST ON" else "BOOST OFF", 13f, if (enabled) palette.accent else palette.textSecondary, OeaType.condensed).apply {
            letterSpacing = 0.18f
        })
        if (enabled && !canFollow) {
            card.addView(kit.button("Set up game detection", palette.accent, palette.onAccent, R.drawable.oic_insights) { chooseDetection() },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
        }
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }

    private fun statsRow() {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun tile(icon: Int, label: String): Pair<LinearLayout, Pair<TextView, TextView>> {
            val t = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = kit.rounded(palette.card, 22, palette.stroke, 1)
            }
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(kit.iconView(icon, palette.accent, 18), LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(6) })
            head.addView(kit.text(label, 12f, palette.textSecondary, OeaType.medium))
            t.addView(head)
            val value = kit.text("—", 22f, palette.text, OeaType.medium).apply { setPadding(0, dp(8), 0, 0) }
            val caption = kit.text("", 11.5f, palette.textTertiary).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            t.addView(value)
            t.addView(caption)
            return t to (value to caption)
        }
        val (ram, ramTexts) = tile(R.drawable.oic_memory, "RAM")
        ramValue = ramTexts.first
        ramCaption = ramTexts.second
        ramBar = MeterBar(this, palette.accent, palette.cardHigh)
        ram.addView(ramBar, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(8) })
        val (battery, batteryTexts) = tile(R.drawable.oic_thermostat, "Battery")
        batteryValue = batteryTexts.first
        batteryCaption = batteryTexts.second
        row.addView(ram, LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = dp(6) })
        row.addView(battery, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(6) })
        content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
    }

    private fun updateStats() {
        val am = getSystemService(ActivityManager::class.java) ?: return
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        if (info.totalMem > 0) {
            val used = info.totalMem - info.availMem
            val percent = (used * 100 / info.totalMem).toInt()
            ramValue?.text = "$percent%"
            ramCaption?.text = String.format("%.1f of %.1f GB used", used / 1e9, info.totalMem / 1e9)
            ramBar?.progress = percent / 100f
        }
        // The same reading the in-game strip uses: rounded and clamped rather than
        // `level * 100 / scale`, "—" when the phone cannot say (never 0%), and a
        // caption that knows the difference between charging, full and plugged-in.
        val reading = OeaBatteryState.read(this)
        batteryValue?.text = reading.label
        batteryCaption?.text = listOfNotNull(
            reading.temperatureCelsius?.let { String.format(Locale.US, "%.1f °C", it) },
            reading.chargingNote
        ).joinToString(" • ")
    }

    private fun gamesSection() {
        val manual = OeaGameBoostStore.mode(this) == "manual_automatic"
        kit.sectionTitle(content, "Your games")
        content.addView(kit.segmented(listOf("Auto-detect", "Auto + manual"), if (manual) 1 else 0) { index ->
            OeaGameBoostStore.setMode(this, if (index == 1) "manual_automatic" else "automatic")
            refreshService()
            render()
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        kit.note(content, if (manual) "Games Android recognizes are added automatically, and you can add any other app."
            else "Only apps Android recognizes as games are boosted. Switch to Auto + manual to add other apps.")
        val games = selectedGames()
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(12), dp(8), dp(12))
            background = kit.rounded(palette.card, 26, palette.stroke, 1)
        }
        val cells = games.map { app -> gameCell(app) } + addCell()
        cells.chunked(4).forEach { chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { row.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
            repeat(4 - chunk.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            grid.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        if (!loaded) grid.addView(kit.text("Finding your games…", 13f, palette.textSecondary).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
        }, LinearLayout.LayoutParams(-1, -2))
        content.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        if (games.isNotEmpty()) kit.note(content, "Tap a game to play with Game Boost. Long-press to remove it.")
    }

    private fun gameCell(app: OeaAppInfo): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(4), dp(8), dp(4), dp(8))
        background = kit.ripple(null, 18)
        isClickable = true
        contentDescription = "Play ${app.label}"
        addView(ImageView(this@OeaGameBoostActivity).apply {
            // Async: a synchronous resolve per cell froze the grid on a cold
            // cache. The fallback shows until the real icon arrives.
            val cellKey = app.packageName + "/" + app.className
            tag = cellKey
            val fallback = if (OeaGameBoostStore.isOeaGameKey(app.packageName)) {
                // OEA-hosted games carry the OEA Games badge.
                runCatching { getDrawable(R.drawable.ic_oea_games) }.getOrNull()
            } else {
                // Android declares some packages as games without a
                // launcher icon (instant apps, stubs); keep the cell
                // readable with the game controller mark instead of
                // leaving a blank tile.
                kit.icon(R.drawable.oic_stadia_controller_fill, palette.textSecondary)
            }
            setImageDrawable(kit.appIconAsync(app) { drawable ->
                if (tag == cellKey) setImageDrawable(drawable)
            } ?: fallback)
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        addView(kit.text(app.label, 12f, palette.text).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        setOnClickListener { play(app) }
        setOnLongClickListener {
            kit.confirm("Remove ${app.label}?", "Game Boost will stop activating for this app. You can add it back any time.", "Remove", destructive = true) {
                OeaGameBoostStore.removeGame(this@OeaGameBoostActivity, app.packageName)
                refreshService()
                render()
            }
            true
        }
    }

    private fun addCell(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(4), dp(8), dp(4), dp(8))
        background = kit.ripple(null, 18)
        isClickable = true
        contentDescription = "Add games"
        addView(FrameLayout(this@OeaGameBoostActivity).apply {
            background = kit.oval(Color.TRANSPARENT, OeaTheme.alpha(palette.accent, 0x99), 2)
            addView(ImageView(this@OeaGameBoostActivity).apply { setImageDrawable(kit.icon(R.drawable.oic_add, palette.accent)) },
                FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        addView(kit.text("Add", 12f, palette.accent, OeaType.medium).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        setOnClickListener { chooseGames() }
    }

    private fun chooseGames() {
        if (!loaded) {
            toast("Still loading your apps…")
            return
        }
        val manual = OeaGameBoostStore.mode(this) == "manual_automatic"
        // OEA's hub games are always selectable, whatever the detection mode says.
        val choices = (OeaGameBoostStore.oeaGameEntries() +
            installedApps.filter { manual || it.packageName in detected }).sortedBy { it.label.lowercase() }
        if (choices.isEmpty()) {
            toast("Android doesn't list any installed games. Switch to Auto + manual to pick apps yourself.")
            return
        }
        val before = OeaGameBoostStore.games(this)
        kit.pickApps(
            if (manual) "Add games & apps" else "Select games",
            if (manual) "Automatic detection stays on." else "Only Android-recognized games are listed.",
            choices, before, { it.packageName }, confirmLabel = "Save"
) { chosen ->
            choices.forEach { app ->
                if (app.packageName in chosen) OeaGameBoostStore.restoreGame(this, app.packageName)
                else if (app.packageName in before) OeaGameBoostStore.removeGame(this, app.packageName)
            }
            val preserved = before.filter { old -> choices.none { it.packageName == old } }.toSet()
            OeaGameBoostStore.setGames(this, chosen + preserved)
            refreshService()
            render()
        }
    }

    private fun play(app: OeaAppInfo) {
        if (OeaGameBoostStore.isOeaGameKey(app.packageName)) {
            // OEA's own games open through the hub, which starts the session itself.
            if (!OeaGameBoostStore.enabled(this)) toast("Game Boost is off. Turn it on to boost this session.")
            runCatching {
                startActivity(Intent(this, com.oea.launcher.games.OeaGamesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { toast("The game hub could not open") }
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(app.packageName)
        if (launch == null) {
            toast("${app.label} can't be opened")
            return
        }
        if (!OeaGameBoostStore.enabled(this)) toast("Game Boost is off. Turn it on to boost this session.")
        runCatching {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            OeaGameBoost.onAppLaunched(this, app.packageName)
        }.onFailure { toast("Android could not open ${app.label}") }
    }

    private fun overlaySection() {
        val p = prefs()
        kit.sectionTitle(content, "In-game overlay")
        val group = kit.group(content)
        kit.switchRow(group, R.drawable.oic_memory, "Floating OEA RAM control", "Stays above your game until you leave it. When hidden, double-tap the screen to bring it back.",
            p.getBoolean("ram_handle_visible", true)) { on ->
            p.edit().putBoolean("ram_handle_visible", on).apply()
            refreshService()
        }
        kit.switchRow(group, R.drawable.oic_wifi, "Network in the panel", "Show link status, game traffic, or both in the expanded panel.",
            p.getBoolean(OeaGameBoostService.PREF_NETWORK_QUALITY, true)) { on ->
            p.edit().putBoolean(OeaGameBoostService.PREF_NETWORK_QUALITY, on).apply()
            refreshService()
        }
        val networkModes = listOf("status", "rates", "both")
        val networkBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(12)) }
        networkBox.addView(kit.text("Network display", 13f, palette.textSecondary, OeaType.medium))
        networkBox.addView(kit.segmented(listOf("Status", "Down / up", "Both"), networkModes.indexOf(p.getString("network_display_mode", "status")).coerceAtLeast(0)) { i ->
            p.edit().putString("network_display_mode", networkModes[i]).apply()
            refreshService()
            render()
        })
        networkBox.addView(kit.text("Status is Android's link estimate, not game-server latency. Down / up is the game app's current traffic (KB/s or MB/s), not a speed test; Android may block other apps' counters, shown as —. OEA games share the OEA app's traffic.", 11.5f, palette.textSecondary).apply { setPadding(0, dp(8), 0, 0) })
        group.addView(networkBox)
        val sizes = listOf("small", "medium", "large")
        val sizeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(14)) }
        sizeBox.addView(kit.text("Size", 13f, palette.textSecondary, OeaType.medium).apply { setPadding(dp(2), 0, 0, dp(8)) })
        sizeBox.addView(kit.segmented(listOf("Small", "Medium", "Large"), sizes.indexOf(p.getString("ram_handle_size", "medium")).coerceAtLeast(0)) { i ->
            p.edit().putString("ram_handle_size", sizes[i]).putBoolean("ram_handle_dragged", false).apply()
            refreshService()
            render()
        })
        sizeBox.addView(kit.text("Corner", 13f, palette.textSecondary, OeaType.medium).apply { setPadding(dp(2), dp(14), 0, dp(8)) })
        val corners = listOf("top_left" to "Top left", "top_right" to "Top right", "bottom_left" to "Bottom left", "bottom_right" to "Bottom right")
        val current = p.getString("ram_handle_corner", "bottom_right")
        val cornerRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        corners.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { (value, label) ->
                row.addView(kit.chip(label, value == current) {
                    p.edit().putString("ram_handle_corner", value).putBoolean("ram_handle_dragged", false).apply()
                    refreshService()
                    render()
                }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            cornerRows.addView(row)
        }
        sizeBox.addView(cornerRows)
        sizeBox.addView(kit.text("Colour", 13f, palette.textSecondary, OeaType.medium).apply { setPadding(dp(2), dp(14), 0, dp(10)) })
        val modes = listOf("blue", "green", "purple", "cyan", "red", "amber", "rgb")
        val selected = p.getString("ram_color_mode", "blue") ?: "blue"
        val swatches = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        modes.forEach { mode ->
            swatches.addView(Swatch(this, if (mode == "rgb") null else accentFor(mode), mode == selected, palette.text).apply {
                contentDescription = if (mode == "rgb") "Rainbow, cycles through every colour" else "$mode pulse"
                setOnClickListener {
                    p.edit().putString("ram_color_mode", mode).apply()
                    refreshService()
                    render()
                }
            }, LinearLayout.LayoutParams(0, dp(40), 1f))
        }
        sizeBox.addView(swatches)
        if (selected == "rgb") {
            sizeBox.addView(kit.text("Rainbow: the button and panel smoothly cycle through every colour while you play.", 12f, palette.textTertiary).apply {
                setPadding(dp(2), dp(8), 0, 0)
            })
        }
        group.addView(View(this).apply { setBackgroundColor(palette.stroke) }, LinearLayout.LayoutParams(-1, maxOf(1, dp(1) / 2)).apply { leftMargin = dp(16); rightMargin = dp(16) })
        group.addView(sizeBox)
    }

    private fun sessionSection() {
        val p = prefs()
        kit.sectionTitle(content, "Game session")
        val group = kit.group(content)
        kit.switchRow(group, R.drawable.oic_rocket_launch, "Performance boost", "Keeps the screen awake and the session focused while you play", p.getBoolean("boost", true)) { on ->
            p.edit().putBoolean("boost", on).apply()
            refreshService()
        }
        kit.switchRow(group, R.drawable.oic_do_not_disturb_on, "Do Not Disturb while gaming", "Silences notifications during a game and restores your previous mode afterwards", p.getBoolean("dnd", true)) { on ->
            p.edit().putBoolean("dnd", on).apply()
            refreshService()
        }
        kit.note(
            content,
            "Both switches are applied every time a game opens — the same game again or a different one — and put back " +
                "exactly as they were when the game closes. A screen recording still running is stopped and saved. " +
                "If a session is interrupted before it can restore, the next time you open Game Boost or OEA Home puts it right."
        )
    }

    private fun permissionsSection() {
        kit.sectionTitle(content, "Permissions")
        val group = kit.group(content)
        fun status(ok: Boolean, required: Boolean) = if (ok) "Allowed" else if (required) "Required" else "Off"
        val overlay = OeaPrivileges.canOverlay(this)
        val dnd = getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true
        kit.row(group, R.drawable.oic_layers, "Display over other apps", "Shows the OEA RAM control above games", value = status(overlay, true),
            iconBg = statusBg(overlay), iconTint = statusTint(overlay)) {
            // One tap: OEA grants it itself when the phone allows that (root or
            // device owner) and otherwise opens exactly the right Android page,
            // then verifies the result.
            OeaPrivileges.grant(this, Privilege.OVERLAY) { outcome ->
                toast(outcome.message)
                handler.postDelayed({ if (loaded) render() }, 700L)
            }
        }
        kit.row(group, R.drawable.oic_do_not_disturb_on, "Do Not Disturb access", "Lets Game Boost silence notifications", value = status(dnd, false),
            iconBg = statusBg(dnd), iconTint = statusTint(dnd)) {
            runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
                .onFailure { toast("Android did not expose the DND access page.") }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val notify = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            kit.row(group, R.drawable.oic_notifications, "Notifications", "Shows the session or Usage-access return watcher", value = status(notify, false),
                iconBg = statusBg(notify), iconTint = statusTint(notify)) {
                if (!notify) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 41)
            }
        }
    }

    private fun statusBg(ok: Boolean) = OeaTheme.alpha(if (ok) palette.success else palette.warning, 0x2E)
    private fun statusTint(ok: Boolean) = if (ok) palette.success else palette.warning

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    // ============================================================== engine

    /**
     * Turning Game Boost on starts nothing by itself: a session begins when
     * one of the selected games opens and ends when the user leaves it.
     */
    private fun toggleEnabled() {
        if (OeaGameBoostStore.enabled(this)) {
            OeaGameBoostStore.setEnabled(this, false)
            OeaGameBoost.endSession(this)
            OeaAssistant.refreshEventMask()
            render()
            return
        }
        if (!OeaGameBoost.canFollowSessions(this)) {
            chooseDetection()
            return
        }
        OeaGameBoostStore.setEnabled(this, true)
        OeaAssistant.refreshEventMask()
        toast("Game Boost is on. It starts by itself when you open one of your games.")
        render()
    }

    /** OEA Assistant (event driven, preferred) or Usage access lets a session notice when you leave the game. */
    private fun chooseDetection() {
        kit.choose(
            "How should Game Boost notice your games?",
            "A session needs to know when you leave the game, so its overlay and Do Not Disturb never linger over other apps.",
            listOf("OEA Assistant (recommended)", "Usage access"),
            0,
            listOf(
                "Follows game windows as they open and close. No polling and no Usage access.",
                "Checks the foreground every two seconds during a session and while waiting for a return, with a visible notification. Pauses while the screen is off."
)
) { pick ->
            if (pick == 0) OeaAssistant.openSetup(this) else openUsageAccess()
        }
    }

    private fun detectionSection() {
        kit.sectionTitle(content, "Game detection")
        val group = kit.group(content)
        val assistantOn = OeaAssistant.isEnabled(this)
        val usage = usageGranted()
        kit.row(group, R.drawable.oic_sports_esports, "Games opened from OEA", "Home, the drawer, folders, search and this app start a session directly",
            value = "Always", chevron = false)
        kit.row(group, R.drawable.oic_accessibility_new, "OEA Assistant", "Recommended. Follows which app's window is in front (package names only), with no polling",
            value = if (assistantOn) "On" else "Off", iconBg = statusBg(assistantOn), iconTint = statusTint(assistantOn)) { OeaAssistant.openSetup(this) }
        kit.row(group, R.drawable.oic_insights, "Usage access", "Checks the foreground during a game and while waiting for a return when Assistant is not connected",
            value = if (usage) "Allowed" else "Off", iconBg = statusBg(usage || assistantOn), iconTint = statusTint(usage || assistantOn)) { openUsageAccess() }
        kit.switchRow(group, R.drawable.oic_home, "Check once when leaving Home", "Catches games opened from Recents or a notification with a single Usage access check. Not needed with OEA Assistant.",
            OeaGameBoost.leaveHomeCheck(this)) { on -> OeaGameBoost.setLeaveHomeCheck(this, on) }
    }

    /** Applies setting changes to a running session; never starts one. */
    private fun refreshService() {
        if (!OeaGameBoostStore.enabled(this) || OeaGameBoost.activeGame == null) return
        runCatching {
            startService(Intent(this, OeaGameBoostService::class.java).setAction(OeaGameBoostService.ACTION_REFRESH))
        }.onFailure { OeaGameBoost.refresh() }
    }

    private fun openUsageAccess() {
        runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    // ================================================================ views

    /** The big power button: a glowing ring that breathes while Game Boost is on. */
    private class PowerRing(context: Context, private val on: Boolean, private val accent: Int, private val off: Int, private val fill: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var glow = 0.5f
        private val animator: ValueAnimator? = if (on) ValueAnimator.ofFloat(0.35f, 1f).apply {
            duration = 1600L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { glow = it.animatedValue as Float; invalidate() }
        } else null
        private val icon = context.getDrawable(R.drawable.oic_bolt_fill)?.mutate()

        init { isClickable = true }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); animator?.start() }
        override fun onDetachedFromWindow() { animator?.cancel(); super.onDetachedFromWindow() }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) / 2f
            val d = resources.displayMetrics.density
            paint.style = Paint.Style.FILL
            paint.color = OeaTheme.alpha(if (on) accent else off, if (on) (40 * glow).toInt() + 14 else 18)
            canvas.drawCircle(cx, cy, r, paint)
            paint.color = fill
            canvas.drawCircle(cx, cy, r * 0.74f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 6f * d
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = if (on) accent else OeaTheme.alpha(off, 0xAA)
            rect.set(cx - r * 0.74f, cy - r * 0.74f, cx + r * 0.74f, cy + r * 0.74f)
            canvas.drawArc(rect, -90f, 360f, false, paint)
            icon?.let {
                it.setTint(if (on) accent else off)
                val s = (r * 0.62f).toInt()
                it.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
                it.draw(canvas)
            }
        }
    }

    /** Thin rounded meter (RAM usage). */
    private class MeterBar(context: Context, private val accent: Int, private val track: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        var progress = 0f
            set(value) { field = value.coerceIn(0f, 1f); invalidate() }

        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            paint.color = track
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, r, r, paint)
            paint.color = if (progress > 0.85f) Color.rgb(255, 110, 120) else accent
            rect.set(0f, 0f, width * progress, height.toFloat())
            canvas.drawRoundRect(rect, r, r, paint)
        }
    }

    /** Colour swatch; null colour draws the RGB spectrum. */
    /**
     * Colour choice. A null [color] is the rainbow option: its spectrum slowly
     * spins so it reads as a live, cycling colour rather than a static picture.
     */
    private class Swatch(context: Context, private val color: Int?, private val selected: Boolean, private val ring: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var spin = 0f
        private var spinner: android.animation.ValueAnimator? = null

        init { isClickable = true }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (color != null || spinner != null) return
            spinner = android.animation.ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 6_000L
                repeatCount = android.animation.ValueAnimator.INFINITE
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener {
                    spin = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDetachedFromWindow() {
            spinner?.cancel()
            spinner = null
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) / 2f - 4f * d
            paint.style = Paint.Style.FILL
            if (color == null) {
                paint.shader = SweepGradient(cx, cy, intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED), null)
                canvas.save()
                canvas.rotate(spin, cx, cy)
            } else {
                paint.shader = null
                paint.color = color
            }
            canvas.drawCircle(cx, cy, if (selected) r * 0.78f else r * 0.86f, paint)
            if (color == null) canvas.restore()
            paint.shader = null
            if (selected) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.5f * d
                paint.color = ring
                canvas.drawCircle(cx, cy, r, paint)
            }
        }
    }
}
