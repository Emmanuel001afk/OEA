package com.oea.launcher.themes

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.oea.launcher.R
import com.oea.launcher.data.OeaDataStore
import com.oea.launcher.ui.OeaInsets
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaScreenFailure
import com.oea.launcher.ui.OeaTheme
import com.oea.launcher.ui.OeaType
import java.util.concurrent.Executors

/**
 * OEA Themes: its own app (own icon, label and task) for browsing,
 * previewing and applying themes, creating personal themes from your own
 * pictures and managing a wallpaper library. Every change is an appearance
 * change only; the Home layout and personal settings are never modified.
 */
class OeaThemesActivity : Activity() {
    private val store by lazy { OeaThemeStore.get(this) }
    private val data by lazy { OeaDataStore.get(this) }
    private val io = Executors.newSingleThreadExecutor { task -> Thread(task, "OeaThemesIo").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var palette: OeaPalette
    private lateinit var kit: OeaKit
    private val stack = ArrayDeque<String>()
    private var current = SCREEN_HOME
    private var currentScroll: android.widget.ScrollView? = null
    private val scrollPositions = HashMap<String, Int>()
    private var overlay: View? = null
    private var draft: Draft? = null
    private var pendingPick: String? = null
    private var busy = false

    /** A theme being created or edited. */
    private data class Draft(
        val id: String,
        var name: String,
        var home: OeaImageRef?,
        var lock: OeaImageRef?,
        var sameLock: Boolean,
        var accentDark: Int,
        var accentLight: Int,
        var tint: Int,
        var autoColors: Boolean,
        var mode: String,
        var glass: String,
        var shape: String,
        var dock: String,
        var effect: String,
        val existing: Boolean,
    ) {
        fun toSpec(): OeaThemeSpec? {
            val homeRef = home ?: return null
            return OeaThemeSpec(
                id, name.trim().ifBlank { "My theme" }, "Your own theme", homeRef, if (sameLock) homeRef else (lock ?: homeRef),
                accentDark, accentLight, tint, mode, glass, shape, dock, effect, builtIn = false,
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.getStringExtra(EXTRA_SCREEN)?.let { if (it.isNotBlank()) current = it }
        show(current)
    }

    private var resumedOnce = false

    override fun onResume() {
        super.onResume()
        // Re-read state changed elsewhere (applied theme, deleted pictures);
        // the first resume follows onCreate, which already rendered.
        if (resumedOnce && overlay == null && current != SCREEN_EDITOR) rerender()
        resumedOnce = true
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Back is handled with an in-activity navigation stack")
    override fun onBackPressed() {
        if (closeOverlay()) return
        if (stack.isNotEmpty()) {
            current = stack.removeLast()
            show(current)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun navigate(screen: String) {
        currentScroll?.let { scrollPositions[current] = it.scrollY }
        stack.addLast(current)
        current = screen
        show(screen)
    }

    private fun back() {
        @Suppress("DEPRECATION")
        onBackPressed()
    }

    private fun rerender() {
        currentScroll?.let { scrollPositions[current] = it.scrollY }
        show(current)
    }

    private fun show(screen: String) {
        try {
            overlay = null
            palette = OeaTheme.resolve(this)
            kit = OeaKit(this, palette)
            OeaInsets.edgeToEdge(window, lightBars = !palette.dark)
            runCatching { window.decorView.setBackgroundColor(palette.background) }
            when {
                screen.startsWith("theme:") -> showTheme(screen.removePrefix("theme:"))
                screen.startsWith("wallpaper:") -> showWallpaper(screen.removePrefix("wallpaper:"))
                screen == SCREEN_EDITOR -> showEditor()
                else -> showHome()
            }
        } catch (error: Exception) {
            OeaScreenFailure.show(this, "Themes", error)
        }
    }

    private fun page(title: String, subtitle: String?): OeaKit.Page {
        val root = stack.isEmpty()
        val brand = ImageView(this).apply {
            setImageResource(R.drawable.oea_logo)
            contentDescription = "OEA"
        }
        return kit.page(title, subtitle, onBack = if (root) null else ({ back() }), trailing = if (root) brand else null).also {
            if (root) brand.layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        }
    }

    private fun present(page: OeaKit.Page) {
        setContentView(page.root)
        currentScroll = page.scroll
        val y = scrollPositions[current] ?: 0
        if (y > 0) page.scroll.post { page.scroll.scrollTo(0, y) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    // ===================================================================== home

    private fun showHome() {
        val page = page("Themes", "Coordinated Home and lock screen looks, your own themes and wallpapers")
        val box = page.content
        val applied = store.find(store.lastAppliedId())
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = kit.rounded(OeaTheme.blend(palette.card, palette.accent, 0.12f), 24, OeaTheme.alpha(palette.accent, 0x40), 1)
        }
        card.addView(kit.iconBadge(R.drawable.oic_palette, OeaTheme.alpha(palette.accent, 0x33), palette.accent, 44), LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(kit.text(if (applied != null) "Using ${applied.name}" else "Using your device wallpaper colours", 16f, palette.text, OeaType.medium))
        texts.addView(kit.text("Themes change wallpapers, colours, icon shape and motion. Your apps, folders, widgets and layout always stay as they are.", 12.5f, palette.textSecondary).apply {
            setPadding(0, dp(3), 0, 0)
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        card.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        if (applied != null || data.accentMode() == "theme") {
            box.addView(kit.quietButton("Use device colours instead", R.drawable.oic_wallpaper) {
                OeaThemeApplier.useDeviceColors(this)
                toast("OEA follows your wallpaper's colours again.")
                rerender()
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6); bottomMargin = dp(4) })
        }

        kit.sectionTitle(box, "Built-in themes")
        kit.note(box, "Every theme has its own Home picture and its own lock-screen picture — five of the lock screens are drawn by OEA itself — and no theme uses a wallpaper from the library below.")
        addThemeGrid(box, OeaThemeCatalog.builtIns, applied?.id, withCreate = false)

        kit.sectionTitle(box, "My themes")
        val mine = store.customThemes()
        if (mine.isEmpty()) kit.note(box, "Make a theme from your own pictures: the same picture for Home and lock, or a different one for each.")
        addThemeGrid(box, mine, applied?.id, withCreate = true)

        kit.sectionTitle(box, "OEA scenes")
        kit.note(box, "OEA's own live artwork. A live scene is drawn by Home itself, so Android's wallpaper — and your lock screen — stay exactly as they are.")
        addSceneGrid(box)

        kit.sectionTitle(box, "Wallpapers")
        kit.note(box, "Pictures drawn for Home: apply one on its own, or freeze a live scene into it. The lock screen has its own set below.")
        addWallpaperSetGrid(box, com.oea.launcher.wallpaper.OeaWallpaper.homeSet, lockOnly = false)

        kit.sectionTitle(box, "Lock screen wallpapers")
        kit.note(box, "A set of its own, drawn so the middle of the screen stays calm for the clock and your notifications. These pictures are never used on Home, so locking and unlocking shows something different.")
        addWallpaperSetGrid(box, com.oea.launcher.wallpaper.OeaWallpaper.lockSet, lockOnly = true)

        kit.sectionTitle(box, "Your pictures")
        val group = kit.group(box)
        kit.row(group, R.drawable.oic_photo_library, "Add a picture", "Copies it into OEA's wallpaper library") { pickImage("library") }
        val yours = store.libraryImages().filterIsInstance<OeaImageRef.Imported>()
        if (yours.isEmpty()) {
            kit.note(box, "Pictures you add appear here and can be used on Home, on the lock screen, or in a theme of your own.")
        } else {
            addImageGrid(box, yours)
        }
        kit.row(group, R.drawable.oic_wallpaper, "Android wallpaper picker", "Live wallpapers and your phone maker's own wallpapers") {
            runCatching { startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper")) }
                .onFailure { toast("No wallpaper picker is available.") }
        }
        present(page)
    }

    /**
     * OEA's scenes: the live ones Home draws itself (Tide, Depth, Ink, Aurora) and
     * the still ones it can hand to Android. Every tile shows the real artwork, a
     * live one drawn as a frame of itself, so nothing here is a coloured square.
     */
    private fun addSceneGrid(parent: LinearLayout) {
        val liveInUse = com.oea.launcher.wallpaper.OeaHomeScene.live(this)
        val tiles = mutableListOf<View>()
        com.oea.launcher.wallpaper.OeaLiveScenes.ids.forEach { id ->
            tiles += sceneTile(
                title = com.oea.launcher.wallpaper.OeaLiveScenes.label(id),
                subtitle = "Live",
                inUse = liveInUse == id,
                accent = com.oea.launcher.wallpaper.OeaLiveScenes.accent(id, true),
                render = { com.oea.launcher.wallpaper.OeaLiveScenes.still(id, dp(76), dp(160)) },
                onClick = {
                    sceneSheet(
                        title = com.oea.launcher.wallpaper.OeaLiveScenes.label(id),
                        subtitle = com.oea.launcher.wallpaper.OeaLiveScenes.blurb(id),
                        scene = null,
                        liveId = id,
                    )
                },
            )
        }
        tiles.chunked(3).forEach { chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEachIndexed { index, cell ->
                row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    leftMargin = if (index == 0) 0 else dp(5)
                    rightMargin = if (index == chunk.lastIndex) 0 else dp(5)
                })
            }
            repeat(3 - chunk.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(5) }) }
            parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }

    /**
     * One scene tile. [render] is called on the main thread with a small size and
     * draws whatever artwork the scene has — the renderers bound their own size, so
     * a tile is a few milliseconds.
     */
    private fun sceneTile(
        title: String,
        subtitle: String,
        inUse: Boolean,
        accent: Int,
        render: () -> Bitmap,
        onClick: () -> Unit,
    ): View {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            contentDescription = title
            foreground = kit.ripple(null, 16)
            setOnClickListener { onClick() }
        }
        val frame = FrameLayout(this)
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            runCatching { setImageBitmap(render()) }
            background = kit.rounded(OeaTheme.alpha(accent, 0x55), 14)
        }
        frame.addView(image, FrameLayout.LayoutParams(-1, dp(132)))
        if (inUse) {
            frame.addView(kit.text("In use", 10.5f, Color.WHITE, OeaType.medium).apply {
                setPadding(dp(7), dp(2), dp(7), dp(2))
                background = kit.rounded(OeaTheme.alpha(accent, 0xE6), 10)
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) })
        }
        tile.addView(frame, LinearLayout.LayoutParams(-1, dp(132)).apply { topMargin = dp(2) })
        tile.addView(kit.text(title, 13f, palette.text, OeaType.medium).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(5), 0, 0)
        })
        tile.addView(kit.text(subtitle, 11.5f, palette.textSecondary))
        return tile
    }

    /**
     * What a scene can do: Home can draw it (nothing is written to Android, and a
     * live scene keeps moving), or it can be handed to Android for Home, the lock
     * screen, or both. The two choices are explained rather than assumed, because
     * they leave the phone in genuinely different states.
     */
    private fun sceneSheet(title: String, subtitle: String, scene: com.oea.launcher.wallpaper.OeaWallpaper?, liveId: String?) {
        // The strip below is built inside a LinearLayout receiver, where "this" is
        // the layout: the Activity is captured first so the buttons still act on it.
        val host = this
        kit.sheet(title, subtitle) { panel, dialog ->
            val scroll = kit.hscroll(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(kit.primaryButton("Draw on Home", R.drawable.oic_wallpaper) {
                    dialog.dismiss()
                    val chosen = liveId ?: scene?.id
                    if (chosen != null) com.oea.launcher.wallpaper.OeaHomeScene.setLive(host, chosen)
                    host.toast("$title is drawn by Home; Android's wallpaper is untouched")
                    host.rerender()
                }, LinearLayout.LayoutParams(-2, dp(48)).apply { rightMargin = dp(8) })
                if (scene != null) {
                    com.oea.launcher.wallpaper.OeaWallpaperStore.Target.entries.forEach { target ->
                        addView(kit.tonalButton("Apply to ${target.label}", R.drawable.oic_wallpaper) {
                            dialog.dismiss()
                            host.applyScene(scene, target)
                        }, LinearLayout.LayoutParams(-2, dp(48)).apply { rightMargin = dp(8) })
                    }
                } else if (liveId != null) {
                    com.oea.launcher.wallpaper.OeaWallpaperStore.Target.entries.forEach { target ->
                        addView(kit.tonalButton("Freeze to ${target.label}", R.drawable.oic_refresh) {
                            dialog.dismiss()
                            host.freezeScene(liveId, target)
                        }, LinearLayout.LayoutParams(-2, dp(48)).apply { rightMargin = dp(8) })
                    }
                }
            })
            panel.addView(scroll)
            kit.note(panel, if (liveId != null)
                "Drawn by Home, the scene keeps moving and follows your pages. Frozen, one frame is handed to Android, so it also shows on the lock screen."
            else
                "Drawn by Home, the scene sits under your icons and Android's wallpaper is left alone. Applied to Android, it becomes the real wallpaper of the place you choose.")
        }
    }

    /**
     * The lock-screen sheet for a picture from the lock set.
     *
     * It offers the lock screen and nothing else, on purpose: these pictures are
     * drawn for that one place (a quiet band where the clock sits), and offering
     * "Home" here is how the two libraries started sharing pictures in the first
     * place. A preview of the lock screen is shown above the buttons.
     */
    private fun lockSceneSheet(scene: com.oea.launcher.wallpaper.OeaWallpaper) {
        val host = this
        val page = page(scene.label, "A lock-screen picture: shown before you unlock, never behind your Home icons")
        val box = page.content
        addPreviewPair(box, currentLookSpec(OeaImageRef.Generated(scene.id, scene.label)).copy(lock = OeaImageRef.Generated(scene.id, scene.label)))
        kit.sectionTitle(box, "Apply this picture")
        val group = kit.group(box)
        kit.row(group, R.drawable.oic_lock, "Set as the lock screen", scene.blurb) {
            host.applyScene(scene, com.oea.launcher.wallpaper.OeaWallpaperStore.Target.LOCK)
        }
        kit.row(group, R.drawable.oic_history, "Keep Android's current lock wallpaper", "Nothing is changed") { back() }
        kit.sectionTitle(box, "Also")
        val more = kit.group(box)
        kit.row(more, R.drawable.oic_wallpaper, "Use it on Home too", "Overrides the rule that keeps the two sets apart — your choice, not OEA's") {
            host.applyScene(scene, com.oea.launcher.wallpaper.OeaWallpaperStore.Target.HOME)
        }
        present(page)
    }

    private fun applyScene(scene: com.oea.launcher.wallpaper.OeaWallpaper, target: com.oea.launcher.wallpaper.OeaWallpaperStore.Target) {
        toast("Applying ${scene.label}…")
        com.oea.launcher.wallpaper.OeaWallpaperStore.apply(this, scene, target) { result ->
            if (!isFinishing) {
                toast(result.message)
                if (result is com.oea.launcher.wallpaper.OeaWallpaperStore.Result.Applied) rerender()
            }
        }
    }

    /** Renders one frame of a live scene and gives it to Android. */
    private fun freezeScene(id: String, target: com.oea.launcher.wallpaper.OeaWallpaperStore.Target) {
        toast("Rendering the scene…")
        val app = this
        val metrics = resources.displayMetrics
        Thread {
            val frame = runCatching {
                com.oea.launcher.wallpaper.OeaLiveScenes.still(id, metrics.widthPixels, metrics.heightPixels)
            }.getOrNull()
            runOnUiThread {
                if (isFinishing || frame == null) {
                    frame?.recycle()
                    if (!isFinishing) toast("The scene could not be rendered on this device")
                    return@runOnUiThread
                }
                com.oea.launcher.wallpaper.OeaWallpaperStore.applyBitmap(app, frame, target) { result ->
                    runCatching { frame.recycle() }
                    if (isFinishing) return@applyBitmap
                    toast(result.message)
                    if (result is com.oea.launcher.wallpaper.OeaWallpaperStore.Result.Applied) rerender()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun addThemeGrid(parent: LinearLayout, themes: List<OeaThemeSpec>, appliedId: String?, withCreate: Boolean) {
        val cells = themes.map { themeCard(it, it.id == appliedId) }.toMutableList<View>()
        if (withCreate) cells += createCard()
        cells.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { index, cell ->
                row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (index == 0) rightMargin = dp(6) else leftMargin = dp(6)
                })
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(6) })
            parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
    }

    private fun themeCard(theme: OeaThemeSpec, applied: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(12))
            background = kit.ripple(kit.rounded(palette.card, 22, if (applied) palette.accent else palette.stroke, if (applied) 2 else 1), 22)
            isClickable = true
            isFocusable = true
            contentDescription = "${theme.name} theme" + if (applied) ", applied" else ""
            setOnClickListener { navigate("theme:${theme.id}") }
        }
        val previews = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val home = OeaThemePreviewView(this, lockScreen = false).apply { this.theme = theme; cornerDp = 10f; setImage(theme.home) }
        val lock = OeaThemePreviewView(this, lockScreen = true).apply { this.theme = theme; cornerDp = 10f; setImage(theme.lock) }
        previews.addView(home, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(4) })
        previews.addView(lock, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4) })
        card.addView(previews, LinearLayout.LayoutParams(-1, -2))
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(kit.text(theme.name, 15f, palette.text, OeaType.medium).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (applied) titleRow.addView(kit.iconView(R.drawable.oic_check_circle_fill, palette.accent, 18), LinearLayout.LayoutParams(dp(18), dp(18)))
        card.addView(titleRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        card.addView(kit.text(if (theme.sharedImagery) "Same picture on Home & lock" else "Separate Home & lock pictures", 11.5f, palette.textSecondary).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        })
        card.addView(swatches(theme, 12), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        return card
    }

    private fun createCard(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        minimumHeight = dp(200)
        setPadding(dp(12), dp(18), dp(12), dp(18))
        background = kit.ripple(kit.rounded(palette.card, 22, OeaTheme.alpha(palette.accent, 0x66), 1), 22)
        isClickable = true
        contentDescription = "Create a theme"
        setOnClickListener { startEditor(null, null) }
        addView(FrameLayout(this@OeaThemesActivity).apply {
            background = kit.oval(OeaTheme.alpha(palette.accent, 0x26))
            addView(ImageView(this@OeaThemesActivity).apply { setImageDrawable(kit.icon(R.drawable.oic_add, palette.accent)) },
                FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        addView(kit.text("Create a theme", 15f, palette.text, OeaType.medium).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) })
        addView(kit.text("Use your own pictures", 12f, palette.textSecondary).apply { gravity = Gravity.CENTER })
    }

    private fun swatches(theme: OeaThemeSpec, sizeDp: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        listOf(theme.accentDark, theme.accentLight, theme.surfaceTint).forEach { color ->
            addView(View(this@OeaThemesActivity).apply { background = kit.oval(color, OeaTheme.alpha(Color.WHITE, 0x33), 1) },
                LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { rightMargin = dp(5) })
        }
    }

    /**
     * One row of pictures from a wallpaper set. Tiles show the real artwork (a
     * rendered frame of the scene), and the one in use carries a marker, so the
     * user can see at a glance what is on the phone rather than guessing.
     */
    private fun addWallpaperSetGrid(parent: LinearLayout, scenes: List<com.oea.launcher.wallpaper.OeaWallpaper>, lockOnly: Boolean) {
        val liveInUse = com.oea.launcher.wallpaper.OeaHomeScene.live(this)
        val drawsForHome = data.wallpaperSource() == com.oea.launcher.data.OeaDataStore.WALLPAPER_OEA
        val applied = com.oea.launcher.wallpaper.OeaWallpaperStore.lastApplied(this)
        val lastTarget = com.oea.launcher.wallpaper.OeaWallpaperStore.lastTarget(this)
        val tiles = scenes.map { scene ->
            val onLock = applied == scene.id && lastTarget == com.oea.launcher.wallpaper.OeaWallpaperStore.Target.LOCK
            val onHome = applied == scene.id && !onLock && !drawsForHome
            sceneTile(
                title = scene.label,
                subtitle = when {
                    onLock -> "On lock"
                    onHome -> "On Home"
                    lockOnly -> "Lock screen"
                    else -> "Wallpaper"
                },
                inUse = onHome || onLock || (lockOnly && liveInUse == scene.id),
                accent = scene.accent,
                render = { com.oea.launcher.wallpaper.OeaWallpaperRenderer.preview(scene, dp(76), dp(160)) },
                onClick = {
                    if (lockOnly) lockSceneSheet(scene)
                    else sceneSheet(scene.label, scene.blurb, scene = scene, liveId = null)
                },
            )
        }
        tiles.chunked(3).forEach { chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEachIndexed { index, cell ->
                row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    leftMargin = if (index == 0) 0 else dp(5)
                    rightMargin = if (index == chunk.lastIndex) 0 else dp(5)
                })
            }
            repeat(3 - chunk.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(5) }) }
            parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }

    private fun addImageGrid(parent: LinearLayout, images: List<OeaImageRef>) {
        images.chunked(3).forEach { chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEachIndexed { index, ref ->
                val tile = FrameLayout(this).apply {
                    isClickable = true
                    contentDescription = (ref as? OeaImageRef.Bundled)?.title ?: "Your picture"
                    foreground = kit.ripple(null, 14)
                    setOnClickListener { navigate("wallpaper:${ref.key}") }
                }
                val preview = OeaThemePreviewView(this, lockScreen = false).apply {
                    cornerDp = 12f
                    theme = currentLookSpec(ref)
                    setImage(ref)
                }
                tile.addView(preview, FrameLayout.LayoutParams(-1, -2))
                if (ref is OeaImageRef.Imported) {
                    tile.addView(kit.text("Yours", 10.5f, Color.WHITE, OeaType.medium).apply {
                        setPadding(dp(7), dp(2), dp(7), dp(2))
                        background = kit.rounded(OeaTheme.alpha(Color.BLACK, 0x88), 10)
                    }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(6), dp(6), 0, 0) })
                }
                row.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    leftMargin = if (index == 0) 0 else dp(5)
                    rightMargin = if (index == 2) 0 else dp(5)
                })
            }
            repeat(3 - chunk.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(5) }) }
            parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }

    /** Wallpaper previews use the user's current OEA look so they show exactly what applying changes. */
    private fun currentLookSpec(ref: OeaImageRef): OeaThemeSpec = OeaThemeSpec(
        "current", "Current", "", ref, ref,
        accentDark = palette.accent, accentLight = palette.accent, surfaceTint = palette.background,
        mode = "system", glassStyle = data.glassStyle(), iconShape = data.iconShape(), dockStyle = data.dockStyle(), pageEffect = data.pageEffect(),
    )

    // ============================================================ theme detail

    private fun showTheme(id: String) {
        val theme = store.find(id) ?: run {
            toast("This theme is no longer available.")
            current = SCREEN_HOME
            stack.clear()
            showHome()
            return
        }
        val page = page(theme.name, theme.tagline.ifBlank { null })
        val box = page.content
        addPreviewPair(box, theme)
        box.addView(kit.text("Tap a preview to see it full screen", 12f, palette.textTertiary).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(4))
        }, LinearLayout.LayoutParams(-1, -2))

        kit.sectionTitle(box, "In this theme")
        val details = kit.group(box)
        kit.row(details, R.drawable.oic_wallpaper, "Wallpapers", if (theme.sharedImagery) "The same picture on Home and the lock screen" else "A Home picture and a different lock screen picture", chevron = false)
        val colorRow = kit.row(details, R.drawable.oic_palette, "Colours", "Accent and panel tint" + when (theme.mode) {
            "dark" -> " • dark panels"
            "light" -> " • light panels"
            else -> " • keeps your light/dark choice"
        }, chevron = false)
        colorRow.addView(swatches(theme, 16))
        kit.row(details, R.drawable.oic_interests, "Icons & dock", "${OeaThemeCatalog.label(OeaThemeCatalog.ICON_SHAPES, theme.iconShape)} icons • ${OeaThemeCatalog.label(OeaThemeCatalog.DOCK_STYLES, theme.dockStyle).lowercase()} dock • ${OeaThemeCatalog.label(OeaThemeCatalog.GLASS_STYLES, theme.glassStyle).lowercase()}", chevron = false)
        kit.row(details, R.drawable.oic_animation, "Page motion", OeaThemeCatalog.label(OeaThemeCatalog.PAGE_EFFECTS, theme.pageEffect), chevron = false)

        kit.sectionTitle(box, "Apply")
        val choices = kit.group(box)
        var wallpapers = true
        var colors = true
        var style = true
        var target = OeaThemeApplier.Target.BOTH
        kit.switchRow(choices, R.drawable.oic_wallpaper, "Wallpapers", "Set this theme's pictures", true) { wallpapers = it }
        kit.switchRow(choices, R.drawable.oic_palette, "Colours", "Accent, panel tint and light/dark panels", true) { colors = it }
        kit.switchRow(choices, R.drawable.oic_interests, "Icon, dock and motion style", "Icon shape, dock, glass and page transition", true) { style = it }
        val targets = OeaThemeApplier.Target.values().toList()
        val targetBar = kit.segmented(listOf("Home & lock", "Home only", "Lock only"), 0) {}
        box.addView(targetBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(12) })
        rebindTargets(targetBar, targets) { target = it }

        val apply = kit.primaryButton("Apply theme", R.drawable.oic_check) {}
        apply.setOnClickListener {
            if (busy) return@setOnClickListener
            if (!wallpapers && !colors && !style) {
                toast("Choose at least one part of the theme to apply.")
                return@setOnClickListener
            }
            busy = true
            apply.text = "Applying…"
            apply.alpha = 0.7f
            OeaThemeApplier.applyTheme(this, theme, OeaThemeApplier.Options(wallpapers, colors, style, target)) { result ->
                busy = false
                if (isDestroyed) return@applyTheme
                toast(result.message)
                rerender()
            }
        }
        box.addView(apply, LinearLayout.LayoutParams(-1, dp(52)))
        kit.note(box, "Applying never changes your apps, folders, app pairs, widgets, grid or other settings. Some phone makers manage the lock screen picture themselves; OEA tells you if Android keeps its own.")
        if (!theme.builtIn) {
            box.addView(kit.buttonRow(
                kit.tonalButton("Edit", R.drawable.oic_edit) { startEditor(theme, null) },
                kit.quietButton("Delete", R.drawable.oic_delete) {
                    kit.confirm("Delete ${theme.name}?", "The theme and its private copies of your pictures are removed. Your current wallpaper stays as it is.", "Delete", destructive = true) {
                        store.deleteCustom(theme.id)
                        if (store.lastAppliedId() == theme.id) store.setLastApplied("")
                        back()
                    }
                },
            ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        } else {
            box.addView(kit.quietButton("Make my own version", R.drawable.oic_edit) { startEditor(null, theme) },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        }
        present(page)
    }

    /** Segmented selection for the apply target, redrawn on each pick. */
    private fun rebindTargets(bar: LinearLayout, targets: List<OeaThemeApplier.Target>, onPick: (OeaThemeApplier.Target) -> Unit) {
        fun paint(selected: Int) {
            for (i in 0 until bar.childCount) {
                val child = bar.getChildAt(i) as? TextView ?: continue
                val active = i == selected
                child.setTextColor(if (active) palette.onAccent else palette.textSecondary)
                child.background = if (active) kit.rounded(palette.accent, 18) else kit.ripple(null, 18)
                child.setOnClickListener {
                    onPick(targets[i])
                    paint(i)
                }
            }
        }
        paint(0)
    }

    private fun addPreviewPair(parent: LinearLayout, theme: OeaThemeSpec) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(false, true).forEachIndexed { index, lock ->
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val preview = OeaThemePreviewView(this, lockScreen = lock).apply {
                this.theme = theme
                cornerDp = 18f
                setImage(if (lock) theme.lock else theme.home)
                isClickable = true
                contentDescription = if (lock) "Lock screen preview" else "Home screen preview"
                setOnClickListener { showFullPreview(theme, lock) }
            }
            column.addView(preview, LinearLayout.LayoutParams(-1, -2))
            column.addView(kit.text(if (lock) "Lock screen" else "Home screen", 12.5f, palette.textSecondary, OeaType.medium).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            })
            row.addView(column, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (index == 0) rightMargin = dp(6) else leftMargin = dp(6)
            })
        }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    /** Full-screen preview with a Home/lock switch; Back or the close button returns. */
    private fun showFullPreview(theme: OeaThemeSpec, startOnLock: Boolean) {
        val root = window.decorView.findViewById<ViewGroup>(android.R.id.content) ?: return
        closeOverlay()
        val layer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
        }
        var lock = startOnLock
        val preview = OeaThemePreviewView(this, lockScreen = false)
        val lockPreview = OeaThemePreviewView(this, lockScreen = true)
        listOf(preview, lockPreview).forEach { view ->
            view.theme = theme
            view.cornerDp = 0f
        }
        preview.setImage(theme.home)
        lockPreview.setImage(theme.lock)
        layer.addView(preview, FrameLayout.LayoutParams(-1, -1))
        layer.addView(lockPreview, FrameLayout.LayoutParams(-1, -1))
        fun sync() {
            preview.visibility = if (lock) View.GONE else View.VISIBLE
            lockPreview.visibility = if (lock) View.VISIBLE else View.GONE
        }
        sync()
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = kit.rounded(OeaTheme.alpha(Color.BLACK, 0x99), 28)
        }
        val toggle = kit.segmented(listOf("Home", "Lock"), if (lock) 1 else 0) {}
        fun paintToggle() {
            for (i in 0 until toggle.childCount) {
                val child = toggle.getChildAt(i) as? TextView ?: continue
                val active = (i == 1) == lock
                child.setTextColor(if (active) palette.onAccent else Color.WHITE)
                child.background = if (active) kit.rounded(palette.accent, 18) else null
                child.setOnClickListener {
                    lock = i == 1
                    sync()
                    paintToggle()
                }
            }
        }
        toggle.background = kit.rounded(OeaTheme.alpha(Color.WHITE, 0x22), 22)
        paintToggle()
        controls.addView(toggle, LinearLayout.LayoutParams(dp(180), -2))
        controls.addView(kit.circleIconButton(R.drawable.oic_close, "Close preview", OeaTheme.alpha(Color.WHITE, 0x22), Color.WHITE) { closeOverlay() },
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(10) })
        layer.addView(controls, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(36) })
        layer.alpha = 0f
        root.addView(layer, FrameLayout.LayoutParams(-1, -1))
        layer.animate().alpha(1f).setDuration(180L).start()
        overlay = layer
    }

    private fun closeOverlay(): Boolean {
        val layer = overlay ?: return false
        overlay = null
        (layer.parent as? ViewGroup)?.removeView(layer)
        return true
    }

    // ============================================================== wallpaper

    private fun showWallpaper(key: String) {
        val ref = OeaImageRef.parse(key) ?: run {
            toast("This picture is no longer available.")
            back()
            return
        }
        val title = (ref as? OeaImageRef.Bundled)?.title ?: "Your picture"
        val page = page(title, "Preview it on Home or the lock screen, then apply")
        val box = page.content
        val spec = currentLookSpec(ref)
        addPreviewPair(box, spec)
        kit.sectionTitle(box, "Apply this picture")
        val group = kit.group(box)
        fun apply(target: OeaThemeApplier.Target) {
            if (busy) return
            busy = true
            toast("Applying wallpaper…")
            OeaThemeApplier.applyWallpaper(this, ref, target) { result ->
                busy = false
                if (!isDestroyed) toast(result.message)
            }
        }
        kit.row(group, R.drawable.oic_home, "Home screen", "Behind your apps and widgets") { apply(OeaThemeApplier.Target.HOME) }
        kit.row(group, R.drawable.oic_lock, "Lock screen", "Shown before you unlock") { apply(OeaThemeApplier.Target.LOCK) }
        kit.row(group, R.drawable.oic_wallpaper, "Home and lock screen", "The same picture on both") { apply(OeaThemeApplier.Target.BOTH) }
        kit.sectionTitle(box, "More")
        val more = kit.group(box)
        kit.row(more, R.drawable.oic_palette, "Create a theme with this picture", "Colours are matched to the picture automatically") { startEditor(null, null, ref) }
        if (ref is OeaImageRef.Imported) {
            kit.row(more, R.drawable.oic_delete, "Remove from library", "Themes that use it keep their own copy") {
                kit.confirm("Remove this picture?", "It's deleted from OEA's wallpaper library. A wallpaper already set on your phone is not changed.", "Remove", destructive = true) {
                    store.deleteImported(ref)
                    back()
                }
            }
        }
        present(page)
    }

    // ================================================================= editor

    private fun startEditor(existing: OeaThemeSpec?, basedOn: OeaThemeSpec?, picture: OeaImageRef? = null) {
        val source = existing ?: basedOn
        val id = existing?.id ?: "custom_${System.currentTimeMillis()}"
        val home = picture ?: source?.home
        draft = Draft(
            id = id,
            name = existing?.name ?: basedOn?.let { "${it.name} (mine)" } ?: "",
            home = home,
            lock = source?.lock ?: home,
            sameLock = source?.sharedImagery ?: true,
            accentDark = source?.accentDark ?: palette.accent,
            accentLight = source?.accentLight ?: palette.accent,
            tint = source?.surfaceTint ?: 0,
            autoColors = source == null,
            mode = source?.mode ?: "system",
            glass = source?.glassStyle ?: data.glassStyle(),
            shape = source?.iconShape ?: data.iconShape(),
            dock = source?.dockStyle ?: data.dockStyle(),
            effect = source?.pageEffect ?: data.pageEffect(),
            existing = existing != null,
        )
        if (home != null && source == null) matchColors(home)
        navigate(SCREEN_EDITOR)
    }

    private fun matchColors(ref: OeaImageRef) {
        val app = applicationContext
        io.execute {
            val bitmap = OeaThemeImages.thumbnail(app, ref) ?: return@execute
            val (dark, light, tint) = OeaThemeImages.colorsFrom(bitmap)
            main.post {
                val d = draft ?: return@post
                if (!d.autoColors) return@post
                d.accentDark = dark
                d.accentLight = light
                d.tint = tint
                if (current == SCREEN_EDITOR && overlay == null) rerender()
            }
        }
    }

    private fun showEditor() {
        val d = draft ?: run {
            back()
            return
        }
        val page = page(if (d.existing) "Edit theme" else "Create a theme", "Pick your pictures, colours and style")
        val box = page.content
        val previewSpec = d.toSpec()
        if (previewSpec != null) addPreviewPair(box, previewSpec)
        else kit.banner(box, R.drawable.oic_photo_library, "Choose a Home picture first", "Then pick whether the lock screen uses the same picture or a different one.", OeaKit.Tone.INFO)

        kit.sectionTitle(box, "Name")
        val name = kit.field("Theme name", d.name)
        name.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { d.name = s?.toString().orEmpty() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        box.addView(name, LinearLayout.LayoutParams(-1, -2))

        kit.sectionTitle(box, "Pictures")
        val pictures = kit.group(box)
        kit.row(pictures, R.drawable.oic_home, "Home screen picture", d.home?.let(::describe) ?: "Not chosen yet") { choosePicture("home") }
        kit.switchRow(pictures, R.drawable.oic_lock, "Same picture on the lock screen", "Turn off to choose a different lock screen picture", d.sameLock) {
            d.sameLock = it
            rerender()
        }
        if (!d.sameLock) {
            kit.row(pictures, R.drawable.oic_lock, "Lock screen picture", d.lock?.takeIf { it.key != d.home?.key }?.let(::describe) ?: "Not chosen yet") { choosePicture("lock") }
        }

        kit.sectionTitle(box, "Colours")
        val accents = listOf(
            Triple(Color.rgb(138, 180, 248), Color.rgb(26, 102, 220), Color.rgb(24, 38, 70)),
            Triple(Color.rgb(102, 217, 204), Color.rgb(0, 121, 107), Color.rgb(16, 56, 56)),
            Triple(Color.rgb(196, 168, 255), Color.rgb(103, 80, 164), Color.rgb(42, 31, 79)),
            Triple(Color.rgb(255, 178, 190), Color.rgb(176, 46, 84), Color.rgb(80, 36, 50)),
            Triple(Color.rgb(255, 198, 108), Color.rgb(140, 90, 0), Color.rgb(80, 60, 30)),
            Triple(Color.rgb(140, 214, 150), Color.rgb(35, 120, 55), Color.rgb(30, 64, 38)),
            Triple(Color.rgb(215, 220, 228), Color.rgb(60, 67, 80), Color.rgb(35, 38, 44)),
        )
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        strip.addView(kit.chip("Match picture", d.autoColors, R.drawable.oic_auto_awesome) {
            d.autoColors = true
            d.home?.let(::matchColors)
            rerender()
        }, LinearLayout.LayoutParams(-2, dp(38)).apply { rightMargin = dp(10) })
        accents.forEach { (dark, light, tint) ->
            val selected = !d.autoColors && d.accentDark == dark
            strip.addView(View(this).apply {
                background = kit.oval(dark, if (selected) palette.text else OeaTheme.alpha(palette.text, 0x22), if (selected) 3 else 1)
                isClickable = true
                contentDescription = "Accent colour"
                setOnClickListener {
                    d.autoColors = false
                    d.accentDark = dark
                    d.accentLight = light
                    d.tint = tint
                    rerender()
                }
            }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(10) })
        }
        box.addView(kit.hscroll(strip), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        box.addView(kit.segmented(OeaThemeCatalog.MODES.map { it.second }, OeaThemeCatalog.MODES.indexOfFirst { it.first == d.mode }.coerceAtLeast(0)) { index ->
            d.mode = OeaThemeCatalog.MODES[index].first
            rerender()
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })

        kit.sectionTitle(box, "Style")
        addChoiceChips(box, "Icon shape", OeaThemeCatalog.ICON_SHAPES, d.shape) { d.shape = it; rerender() }
        addChoiceChips(box, "Glass", OeaThemeCatalog.GLASS_STYLES, d.glass) { d.glass = it; rerender() }
        addChoiceChips(box, "Dock", OeaThemeCatalog.DOCK_STYLES, d.dock) { d.dock = it; rerender() }
        addChoiceChips(box, "Page motion", OeaThemeCatalog.PAGE_EFFECTS, d.effect) { d.effect = it; rerender() }

        box.addView(kit.primaryButton(if (d.existing) "Save changes" else "Save theme", R.drawable.oic_check) { saveDraft(d) },
            LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(16) })
        kit.note(box, "Your pictures are copied into OEA's private storage, so the theme keeps working if you move or delete the originals.")
        present(page)
    }

    private fun addChoiceChips(parent: LinearLayout, title: String, options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
        parent.addView(kit.text(title, 13f, palette.textSecondary, OeaType.medium).apply { setPadding(dp(6), dp(8), 0, dp(6)) })
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        options.forEach { (value, label) ->
            strip.addView(kit.chip(label, value == selected) { onPick(value) }, LinearLayout.LayoutParams(-2, dp(36)).apply { rightMargin = dp(8) })
        }
        parent.addView(kit.hscroll(strip), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
    }

    private fun describe(ref: OeaImageRef): String = (ref as? OeaImageRef.Bundled)?.title ?: "Your picture"

    /** Library sheet: every bundled and imported picture, plus "From your gallery". */
    private fun choosePicture(slot: String) {
        kit.sheet(if (slot == "home") "Home screen picture" else "Lock screen picture", null) { panel, dialog ->
            val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val images = store.libraryImages()
            images.chunked(4).forEach { chunk ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                chunk.forEach { ref ->
                    val preview = OeaThemePreviewView(this, lockScreen = slot == "lock").apply {
                        cornerDp = 10f
                        theme = draft?.toSpec() ?: currentLookSpec(ref)
                        setImage(ref)
                        isClickable = true
                        contentDescription = describe(ref)
                        setOnClickListener {
                            dialog.dismiss()
                            setDraftPicture(slot, ref)
                        }
                    }
                    row.addView(preview, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
                }
                repeat(4 - chunk.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
                grid.addView(row)
            }
            panel.addView(kit.primaryButton("From your gallery", R.drawable.oic_photo_library) {
                dialog.dismiss()
                pickImage(slot)
            }, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(10) })
            panel.addView(kit.boundedScroll(grid, 0.6f))
        }
    }

    private fun setDraftPicture(slot: String, ref: OeaImageRef) {
        val d = draft ?: return
        if (slot == "home") {
            d.home = ref
            if (d.sameLock) d.lock = ref
            if (d.autoColors) matchColors(ref)
        } else {
            d.lock = ref
        }
        rerender()
    }

    private fun saveDraft(d: Draft) {
        if (busy) return
        val spec = d.toSpec() ?: run {
            toast("Choose a Home screen picture first.")
            return
        }
        if (!d.sameLock && (d.lock == null || d.lock?.key == d.home?.key)) {
            toast("Choose a different lock screen picture, or turn on \"Same picture on the lock screen\".")
            return
        }
        busy = true
        val app = applicationContext
        io.execute {
            val home = store.copyForTheme(spec.home, spec.id, "home")
            val lock = if (spec.sharedImagery) home else store.copyForTheme(spec.lock, spec.id, "lock")
            val saved = spec.copy(home = home, lock = lock)
            OeaThemeStore.get(app).saveCustom(saved)
            main.post {
                busy = false
                if (isDestroyed) return@post
                toast("${saved.name} saved. Apply it whenever you like.")
                draft = null
                // Replace the editor with the saved theme's page.
                current = if (stack.isNotEmpty()) stack.removeLast() else SCREEN_HOME
                if (current == "theme:${saved.id}") show(current) else navigate("theme:${saved.id}")
            }
        }
    }

    // ================================================================ picking

    private fun pickImage(slot: String) {
        pendingPick = slot
        runCatching {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }, REQUEST_PICK)
        }.onFailure {
            pendingPick = null
            toast("No picture picker is available.")
        }
    }

    @Deprecated("Activity results are routed manually for this simple flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_PICK) {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val slot = pendingPick
        pendingPick = null
        val uri: Uri = data?.data ?: return
        if (resultCode != RESULT_OK || slot == null) return
        toast("Adding picture…")
        io.execute {
            val imported = store.importToLibrary(uri)
            main.post {
                if (isDestroyed) return@post
                if (imported == null) {
                    toast("That picture couldn't be read.")
                    return@post
                }
                when (slot) {
                    "home", "lock" -> setDraftPicture(slot, imported)
                    else -> {
                        toast("Added to your wallpapers.")
                        if (current == SCREEN_HOME) rerender() else navigate("wallpaper:${imported.key}")
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_SCREEN = "oea_themes_screen"
        private const val SCREEN_HOME = "home"
        private const val SCREEN_EDITOR = "editor"
        private const val REQUEST_PICK = 0x7E01
    }
}
