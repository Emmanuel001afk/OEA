package com.oea.launcher.miniapps

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.oea.launcher.R
import com.oea.launcher.data.OeaDataStore
import com.oea.launcher.themes.OeaImageRef
import com.oea.launcher.themes.OeaThemeApplier
import com.oea.launcher.themes.OeaThemeImages
import com.oea.launcher.themes.OeaThemeStore
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaTheme
import com.oea.launcher.ui.OeaType
import com.oea.launcher.wallpaper.OeaHomeScene
import com.oea.launcher.wallpaper.OeaLiveScenes
import com.oea.launcher.wallpaper.OeaWallpaper
import com.oea.launcher.wallpaper.OeaWallpaperRenderer
import com.oea.launcher.wallpaper.OeaWallpaperStore
import java.util.concurrent.Executors

/**
 * OEA's picture app: every image OEA has, in the sections they belong to.
 *
 * The sections are the point. A gallery that mixes live scenes, drawn wallpapers,
 * lock-screen pictures and the user's own photos into one strip is impossible to
 * reason about — and it is how wallpapers and themes ended up sharing pictures.
 * Here each set is listed as its own shelf, with what it is for and how many
 * pictures it holds, and each picture opens a viewer that says exactly what
 * applying it will change.
 */
object OeaGalleryApp {

    /** One picture in a shelf: how to draw it and what it is. */
    private class Item(
        val title: String,
        val subtitle: String,
        val badge: String?,
        val render: (width: Int, height: Int) -> Bitmap?,
        val onOpen: () -> Unit
)

    private val workers = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "OeaGallery").apply { isDaemon = true }
    }

    fun build(host: Activity, palette: OeaPalette): View {
        val kit = OeaKit(host, palette)
        val column = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        val state = OeaWallpaperStore.scene(host)
        val live = OeaHomeScene.live(host)
        val drawsForHome = OeaDataStore.get(host).wallpaperSource() == OeaDataStore.WALLPAPER_OEA

        // ---- 1. live scenes: drawn by Home itself, so nothing on Android's side changes
        shelf(
            host, kit, column,
            title = "Live scenes",
            blurb = "Drawn by Home frame by frame, so Android's wallpaper and your lock screen are never touched.",
            count = OeaLiveScenes.ids.size
) { add ->
            OeaLiveScenes.ids.forEach { id ->
                val on = live == id && drawsForHome
                add(item(
                    host, kit,
                    title = OeaLiveScenes.label(id),
                    subtitle = OeaLiveScenes.blurb(id),
                    badge = if (on) "On Home" else "Live",
                    render = { w, h -> OeaLiveScenes.still(id, w, h) }
) {
                    viewer(
                        host, kit, OeaLiveScenes.label(id), OeaLiveScenes.blurb(id), null, id,
                        render = { w, h -> OeaLiveScenes.still(id, w, h) }
)
                })
            }
        }

        // ---- 2. wallpapers drawn for Home
        shelf(
            host, kit, column,
            title = "Wallpapers",
            blurb = "Still pictures drawn for Home. Applying one hands Android the real wallpaper.",
            count = OeaWallpaper.homeSet.size
) { add ->
            OeaWallpaper.homeSet.forEach { scene ->
                val on = state == scene && !drawsForHome
                add(item(
                    host, kit,
                    title = scene.label,
                    subtitle = scene.blurb,
                    badge = if (on) "On Home" else "Wallpaper",
                    render = { w, h -> OeaWallpaperRenderer.preview(scene, w, h) }
) {
                    viewer(host, kit, scene.label, scene.blurb, scene, null, render = { w, h -> OeaWallpaperRenderer.preview(scene, w, h) })
                })
            }
        }

        // ---- 3. the lock-screen set: its own artwork, never used on Home
        shelf(
            host, kit, column,
            title = "Lock screen",
            blurb = "A set of its own, drawn with a calm middle band so the clock and notifications stay readable.",
            count = OeaWallpaper.lockSet.size
) { add ->
            OeaWallpaper.lockSet.forEach { scene ->
                val onLock = OeaWallpaperStore.lastApplied(host) == scene.id &&
                    OeaWallpaperStore.lastTarget(host) == OeaWallpaperStore.Target.LOCK
                add(item(
                    host, kit,
                    title = scene.label,
                    subtitle = scene.blurb,
                    badge = if (onLock) "On lock" else "Lock screen",
                    render = { w, h -> OeaWallpaperRenderer.preview(scene, w, h) }
) {
                    viewer(host, kit, scene.label, scene.blurb, scene, null, render = { w, h -> OeaWallpaperRenderer.preview(scene, w, h) })
                })
            }
        }

        // ---- 4. the user's own pictures
        val mine = OeaThemeStore.get(host).libraryImages().filterIsInstance<OeaImageRef.Imported>()
        shelf(
            host, kit, column,
            title = "Your pictures",
            blurb = if (mine.isEmpty()) "Nothing here yet. Add a photo and it appears in this shelf, ready for Home, the lock screen or a theme." else "Pictures you added to OEA, kept in OEA's own storage.",
            count = mine.size
) { add ->
            mine.forEach { ref ->
                add(item(
                    host, kit,
                    title = "Your picture",
                    subtitle = ref.path.substringAfterLast('/'),
                    badge = "Yours",
                    render = { w, h -> OeaThemeImages.thumbnail(host, ref, w, h) }
) { viewer(host, kit, "Your picture", ref.path.substringAfterLast('/'), scene = null, liveId = null, image = ref, render = { w, h -> OeaThemeImages.thumbnail(host, ref, w, h) }) })
            }
        }

        kit.note(
            column,
            "Themes live in the Themes app: OEA draws each theme its own Home and lock-screen picture, and those are deliberately kept out of this gallery so the two never share one."
)
        if (live != null) {
            kit.row(
                kit.group(column), R.drawable.oic_close, "Stop the live scene", "Home goes back to the still wallpaper behind it"
) {
                OeaHomeScene.clearLive(host)
                host.recreate()
            }
        }
        return column
    }

    // ------------------------------------------------------------------- shelves

    private fun shelf(
        host: Activity,
        kit: OeaKit,
        parent: LinearLayout,
        title: String,
        blurb: String,
        count: Int,
        add: (add: (View) -> Unit) -> Unit
) {
        val heading = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(
            kit.text(title, 22f, kit.palette.text, OeaType.medium).apply {
                setPadding(kit.dp(6), kit.dp(18), 0, 0)
            }
)
        heading.addView(
            kit.text("$count ${if (count == 1) "picture" else "pictures"}", 12f, kit.palette.accent, OeaType.medium).apply {
                setPadding(kit.dp(6), kit.dp(2), 0, 0)
            }
)
        heading.addView(
            kit.text(blurb, 12.5f, kit.palette.textSecondary).apply {
                setPadding(kit.dp(6), kit.dp(4), kit.dp(6), kit.dp(8))
                setLineSpacing(kit.dpf(2f), 1f)
            }
)
        parent.addView(heading, LinearLayout.LayoutParams(-1, -2))

        val strip = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        val scroll = kit.hscroll(strip)
        var added = 0
        add { view ->
            strip.addView(view, LinearLayout.LayoutParams(kit.dp(112), -2).apply {
                rightMargin = kit.dp(10)
            })
            added++
        }
        if (added == 0) {
            parent.addView(emptyCard(host, kit, blurb), LinearLayout.LayoutParams(-1, -2))
        } else {
            parent.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun emptyCard(host: Activity, kit: OeaKit, message: String): View {
        val card = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = kit.rounded(kit.palette.card, 18, kit.palette.stroke, 1)
            setPadding(kit.dp(14), kit.dp(14), kit.dp(14), kit.dp(14))
        }
        card.addView(kit.text("Nothing here yet", 14f, kit.palette.text, OeaType.medium))
        card.addView(kit.text(message, 12.5f, kit.palette.textSecondary).apply { setPadding(0, kit.dp(4), 0, 0) })
        return card
    }

    private fun item(
        host: Activity,
        kit: OeaKit,
        title: String,
        subtitle: String,
        badge: String?,
        render: (Int, Int) -> Bitmap?,
        onOpen: () -> Unit
): View {
        val column = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        val tile = FrameLayout(host).apply {
            isClickable = true
            background = kit.rounded(Color.TRANSPARENT, 16, kit.palette.stroke, 1)
            clipToOutline = true
            foreground = kit.ripple(null, 16)
            setOnClickListener { onOpen() }
        }
        val image = ImageView(host).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(kit.palette.cardHigh)
        }
        tile.addView(image, FrameLayout.LayoutParams(-1, kit.dp(150)))
        if (badge != null) {
            val chip = kit.text(badge, 10.5f, Color.WHITE, OeaType.medium).apply {
                setPadding(kit.dp(7), kit.dp(3), kit.dp(7), kit.dp(3))
                background = kit.rounded(OeaTheme.alpha(Color.BLACK, 0x99), 10)
            }
            tile.addView(chip, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                setMargins(kit.dp(6), kit.dp(6), 0, 0)
            })
        }
        column.addView(tile, LinearLayout.LayoutParams(-1, -2))
        column.addView(
            kit.text(title, 12.5f, kit.palette.text, OeaType.medium).apply {
                setPadding(kit.dp(2), kit.dp(6), 0, 0)
                maxLines = 1
            }
)
        // The picture itself is rendered off the main thread: a shelf of ten
        // scenes must not block the frame that draws them.
        val width = kit.dp(112)
        val height = kit.dp(150)
        image.tag = render
        load(host, image, render, width, height)
        return column
    }

    private fun load(host: Activity, image: ImageView, render: (Int, Int) -> Bitmap?, width: Int, height: Int) {
        workers.execute {
            val bitmap = runCatching { render(width, height) }.getOrNull() ?: return@execute
            host.runOnUiThread {
                if (image.tag === render && !host.isDestroyed) {
                    image.setImageBitmap(bitmap)
                    image.alpha = 0f
                    image.animate().alpha(1f).setDuration(180L).start()
                }
            }
        }
    }

    // ------------------------------------------------------------------- viewer

    private fun viewer(
        host: Activity,
        kit: OeaKit,
        title: String,
        subtitle: String,
        scene: OeaWallpaper?,
        liveId: String?,
        image: OeaImageRef? = null,
        render: (Int, Int) -> Bitmap?
) {
        val content = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        val preview = ImageView(host).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = kit.rounded(Color.TRANSPARENT, 20, kit.palette.stroke, 1)
            clipToOutline = true
        }
        content.addView(preview, LinearLayout.LayoutParams(-1, kit.dp(300)))
        workers.execute {
            val bitmap = runCatching { render(kit.dp(420), kit.dp(300)) }.getOrNull()
            if (bitmap != null) host.runOnUiThread { if (!host.isDestroyed) preview.setImageBitmap(bitmap) }
        }

        kit.sheet(title, subtitle) { panel, dialog ->
            panel.addView(content)
            panel.addView(kit.text("Apply", 12f, kit.palette.textTertiary, OeaType.medium).apply {
                setPadding(kit.dp(4), kit.dp(16), 0, kit.dp(6))
            })
            val actions = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
            val scroll = kit.hscroll(actions)
            if (liveId != null) {
                actions.addView(kit.primaryButton("Draw on Home", R.drawable.oic_wallpaper) {
                    dialog.dismiss()
                    OeaHomeScene.setLive(host, liveId)
                    refresh(host, kit, "$title is drawn by Home; Android's wallpaper is untouched")
                }, LinearLayout.LayoutParams(-2, kit.dp(48)).apply { rightMargin = kit.dp(8) })
                OeaWallpaperStore.Target.entries.forEach { target ->
                    actions.addView(kit.tonalButton("Freeze to ${target.label}", R.drawable.oic_refresh) {
                        dialog.dismiss()
                        freeze(host, liveId, target, kit)
                    }, LinearLayout.LayoutParams(-2, kit.dp(48)).apply { rightMargin = kit.dp(8) })
                }
            } else if (scene != null) {
                actions.addView(kit.primaryButton("Set on Home", R.drawable.oic_home) {
                    dialog.dismiss()
                    applyScene(host, scene, OeaWallpaperStore.Target.HOME, kit)
                }, LinearLayout.LayoutParams(-2, kit.dp(48)).apply { rightMargin = kit.dp(8) })
                actions.addView(kit.tonalButton("Set on lock screen", R.drawable.oic_lock) {
                    dialog.dismiss()
                    applyScene(host, scene, OeaWallpaperStore.Target.LOCK, kit)
                }, LinearLayout.LayoutParams(-2, kit.dp(48)).apply { rightMargin = kit.dp(8) })
                actions.addView(kit.tonalButton("Set on both", R.drawable.oic_layers) {
                    dialog.dismiss()
                    applyScene(host, scene, OeaWallpaperStore.Target.BOTH, kit)
                }, LinearLayout.LayoutParams(-2, kit.dp(48)).apply { rightMargin = kit.dp(8) })
            } else if (image != null) {
                actions.addView(kit.primaryButton("Set on Home", R.drawable.oic_home) {
                    dialog.dismiss()
                    OeaThemeApplier.applyWallpaper(host, image, OeaThemeApplier.Target.HOME) { result -> refresh(host, kit, result.message) }
                }, LinearLayout.LayoutParams(-2, kit.dp(48)).apply { rightMargin = kit.dp(8) })
                actions.addView(kit.tonalButton("Set on lock screen", R.drawable.oic_lock) {
                    dialog.dismiss()
                    OeaThemeApplier.applyWallpaper(host, image, OeaThemeApplier.Target.LOCK) { result -> refresh(host, kit, result.message) }
                }, LinearLayout.LayoutParams(-2, kit.dp(48)).apply { rightMargin = kit.dp(8) })
            }
            panel.addView(scroll)
            if (liveId != null) {
                kit.note(panel, "Drawn on Home stays live and follows your pages; freezing hands one frame to Android so it also shows on the lock screen.")
            } else if (scene != null) {
                kit.note(
                    panel,
                    if (scene.forLock) "This picture is part of the lock-screen set: its middle stays calm for the clock. Setting it on Home is allowed, but the sets are kept apart by design."
                    else "Setting it on Home leaves the lock screen as it is; the lock set above is drawn for the clock area."
)
            }
        }
    }

    private fun applyScene(host: Activity, scene: OeaWallpaper, target: OeaWallpaperStore.Target, kit: OeaKit) {
        say(host, "Applying ${scene.label}…")
        OeaWallpaperStore.apply(host, scene, target) { result -> refresh(host, kit, result.message) }
    }

    /** Renders one frame of a live scene and hands it to Android. */
    private fun freeze(host: Activity, id: String, target: OeaWallpaperStore.Target, kit: OeaKit) {
        val metrics = host.resources.displayMetrics
        say(host, "Rendering ${OeaLiveScenes.label(id)}…")
        workers.execute {
            val frame = runCatching { OeaLiveScenes.still(id, metrics.widthPixels, metrics.heightPixels) }.getOrNull()
            host.runOnUiThread {
                if (frame == null) {
                    say(host, "That scene could not be rendered on this device.")
                } else {
                    OeaWallpaperStore.applyBitmap(host, frame, target) { result ->
                        runCatching { frame.recycle() }
                        say(host, result.message)
                        (host as? OeaMiniAppActivity)?.refresh()
                    }
                }
            }
        }
    }

    /** Says what happened, then re-reads the shelves so the badges stay true. */
    private fun refresh(host: Activity, kit: OeaKit, message: String) {
        say(host, message)
        (host as? OeaMiniAppActivity)?.refresh()
    }

    private fun say(host: Activity, message: String) {
        runCatching { android.widget.Toast.makeText(host, message, android.widget.Toast.LENGTH_LONG).show() }
    }
}
