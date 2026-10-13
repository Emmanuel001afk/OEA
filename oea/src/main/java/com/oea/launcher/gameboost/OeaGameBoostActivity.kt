package com.oea.launcher.gameboost

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.BatteryManager
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
import com.oea.launcher.R
import com.oea.launcher.icons.OeaIconController
import com.oea.launcher.model.OeaAppInfo
import com.oea.launcher.model.OeaAppModel
import com.oea.launcher.ui.OeaInsets
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaScreenFailure
import com.oea.launcher.ui.OeaTheme
import com.oea.launcher.ui.OeaType
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
    private lateinit var kit: OeaKit
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
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
            OeaInsets.edgeToEdge(window, lightBars = false)
            val root = FrameLayout(this)
            scroll = ScrollView(this).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                clipToPadding = false
            }
            content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            scroll.addView(content, FrameLayout.LayoutParams(-1, -2))
            root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
            root.setOnApplyWindowInsetsListener { _, insets ->
                val bars = OeaInsets.bars(insets)
                scroll.setPadding(bars[0], bars[1], bars[2], bars[3])
                insets
            }
            setContentView(root)
            render()
            loadApps()
        } catch (error: Exception) {
            OeaScreenFailure.show(this, "Game Boost", error)
        }
    }

    override fun onResume() {
        super.onResume()
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
            if (OeaGameBoostStore.enabled(this)) runCatching { OeaGameBoostStore.syncDetectedGames(this) }
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
        return OeaPalette(
            dark = true,
            accent = accent,
            onAccent = Color.rgb(6, 10, 18),
            accentSoft = OeaTheme.blend(card, accent, 0.22f),
            onAccentSoft = OeaTheme.blend(Color.WHITE, accent, 0.25f),
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
            warning = Color.rgb(255, 196, 100),
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
        val y = scroll.scrollY
        palette = gamePalette()
        kit = OeaKit(this, palette)
        window.decorView.setBackgroundColor(palette.background)
        content.removeAllViews()
        content.setPadding(dp(16), dp(10), dp(16), dp(28))
        hero()
        statsRow()
        gamesSection()
        overlaySection()
        sessionSection()
        permissionsSection()
        kit.note(content, "Game Boost runs a lightweight game-session layer: it detects your games, can silence notifications, shows live RAM and an in-game control. Android does not let any app raise another app's CPU or GPU priority, so OEA never claims to.")
        scroll.post { scroll.scrollTo(0, y) }
        updateStats()
    }

    private fun usageGranted(): Boolean = runCatching {
        getSystemService(AppOpsManager::class.java).checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    private fun selectedGames(): List<OeaAppInfo> {
        val games = OeaGameBoostStore.games(this)
        val manual = OeaGameBoostStore.mode(this) == "manual_automatic"
        return installedApps.filter { it.packageName in games && (manual || !loaded || it.packageName in detected) }
            .sortedBy { it.label.lowercase() }
    }

    private fun hero() {
        val enabled = OeaGameBoostStore.enabled(this)
        val usage = usageGranted()
        val count = selectedGames().size
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(20), dp(20), dp(22))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(OeaTheme.blend(palette.card, palette.accent, if (enabled) 0.34f else 0.12f), palette.card, Color.rgb(10, 13, 22)),
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
        titles.addView(kit.text("Game Boost", 26f, palette.text, OeaType.medium))
        val status = when {
            !enabled -> "Off • tap the power button to start"
            !usage -> "Needs Usage access to detect games"
            count == 0 -> "On • add games to boost"
            else -> "Active • watching $count game" + if (count == 1) "" else "s"
        }
        titles.addView(kit.text(status, 13.5f, if (enabled && usage) palette.accent else palette.textSecondary, OeaType.medium))
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
        if (enabled && !usage) {
            card.addView(kit.button("Grant Usage access", palette.accent, palette.onAccent, R.drawable.oic_insights) { openUsageAccess() },
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
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (battery != null) {
            val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val temp = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            if (level >= 0) batteryValue?.text = "${level * 100 / scale}%"
            batteryCaption?.text = buildString {
                if (temp != Int.MIN_VALUE) append(String.format("%.1f °C", temp / 10f))
                if (charging) append(if (isEmpty()) "Charging" else " • charging")
            }
        }
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
        addView(ImageView(this@OeaGameBoostActivity).apply { setImageDrawable(kit.appIcon(app)) }, LinearLayout.LayoutParams(dp(52), dp(52)))
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
        val choices = installedApps.filter { manual || it.packageName in detected }.sortedBy { it.label.lowercase() }
        if (choices.isEmpty()) {
            toast("Android doesn't list any installed games. Switch to Auto + manual to pick apps yourself.")
            return
        }
        val before = OeaGameBoostStore.games(this)
        kit.pickApps(
            if (manual) "Add games & apps" else "Select games",
            if (manual) "Automatic detection stays on." else "Only Android-recognized games are listed.",
            choices, before, { it.packageName }, confirmLabel = "Save",
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
        val launch = packageManager.getLaunchIntentForPackage(app.packageName)
        if (launch == null) {
            toast("${app.label} can't be opened")
            return
        }
        if (!OeaGameBoostStore.enabled(this)) toast("Game Boost is off. Turn it on to boost this session.")
        runCatching { startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { toast("Android could not open ${app.label}") }
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
        kit.switchRow(group, R.drawable.oic_wifi, "Network speed on the button", "Download and upload speed in a small readout under the floating button. The panel always shows it.",
            p.getBoolean(OeaGameBoostService.PREF_NETWORK_BADGE, true)) { on ->
            p.edit().putBoolean(OeaGameBoostService.PREF_NETWORK_BADGE, on).apply()
            refreshService()
        }
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
    }

    private fun permissionsSection() {
        kit.sectionTitle(content, "Permissions")
        val group = kit.group(content)
        fun status(ok: Boolean, required: Boolean) = if (ok) "Allowed" else if (required) "Required" else "Off"
        val usage = usageGranted()
        val overlay = Settings.canDrawOverlays(this)
        val dnd = getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true
        kit.row(group, R.drawable.oic_insights, "Usage access", "Detects when one of your games is in front", value = status(usage, true),
            iconBg = statusBg(usage), iconTint = statusTint(usage)) { openUsageAccess() }
        kit.row(group, R.drawable.oic_layers, "Display over other apps", "Shows the OEA RAM control above games", value = status(overlay, true),
            iconBg = statusBg(overlay), iconTint = statusTint(overlay)) {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        }
        kit.row(group, R.drawable.oic_do_not_disturb_on, "Do Not Disturb access", "Lets Game Boost silence notifications", value = status(dnd, false),
            iconBg = statusBg(dnd), iconTint = statusTint(dnd)) {
            runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
                .onFailure { toast("Android did not expose the DND access page.") }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val notify = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            kit.row(group, R.drawable.oic_notifications, "Notifications", "Shows the Game Boost status notification", value = status(notify, false),
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

    private fun toggleEnabled() {
        if (OeaGameBoostStore.enabled(this)) {
            OeaGameBoostStore.setEnabled(this, false)
            stopService(Intent(this, OeaGameBoostService::class.java))
            render()
            return
        }
        if (!usageGranted()) {
            toast("Grant Usage access first so OEA can detect the active game.")
            openUsageAccess()
            return
        }
        OeaGameBoostStore.setEnabled(this, true)
        startBoostService()
        render()
    }

    private fun startBoostService() {
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(Intent(this, OeaGameBoostService::class.java))
            else startService(Intent(this, OeaGameBoostService::class.java))
        }.onFailure {
            OeaGameBoostStore.setEnabled(this, false)
            toast("Game Boost service could not start.")
        }
    }

    /** Applies setting changes to a running session without restarting detection. */
    private fun refreshService() {
        if (!OeaGameBoostStore.enabled(this)) return
        runCatching {
            val intent = Intent(this, OeaGameBoostService::class.java).setAction(OeaGameBoostService.ACTION_REFRESH)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        }
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
