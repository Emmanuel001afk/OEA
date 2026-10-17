package com.oea.launcher.miniapps

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.oea.launcher.R
import com.oea.launcher.ui.OeaAppKit
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import java.util.Locale

/**
 * "What is actually inside this file", for VIP.
 *
 * Container, the real codec of every track, resolution, frame rate, channel
 * layout, rotation, bitrate and where the file lives. Everything is read from the
 * file with [android.media.MediaExtractor] — nothing is inferred from a filename,
 * and a phone that cannot decode the clip is told so in this same sheet, with the
 * reason, instead of being handed a black screen.
 */
internal object OeaVipDetails {

    private val main = Handler(Looper.getMainLooper())
    private val PROBE = java.util.concurrent.Executors.newFixedThreadPool(2) { task ->
        Thread(task, "OeaVipProbe").apply { isDaemon = true }
    }

    fun sheet(host: Activity, palette: OeaPalette, item: OeaMediaLibrary.Item) {
        val kit = OeaAppKit(host, palette)
        val panels = arrayOfNulls<LinearLayout>(1)
        val dialog: Dialog = kit.sheet(item.name.substringBeforeLast('.'), "${item.album} • ${OeaMediaLibrary.formatSize(item.size)}") { panel, _ ->
            panels[0] = panel
            render(host, kit, panel, item, null, null)
        }
        runCatching { dialog.show() }
        PROBE.execute {
            val app = host.applicationContext
            val clip = runCatching { OeaMediaProbe.probe(app, item.uri) }.getOrNull()
            val playable = runCatching {
                val media = android.media.MediaMetadataRetriever()
                media.setDataSource(app, item.uri)
                val ok = media.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != null ||
                    media.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != null
                media.release()
                ok
            }.getOrDefault(false)
            main.post {
                val panel = panels[0] ?: return@post
                if (host.isDestroyed || !dialog.isShowing) return@post
                panel.removeAllViews()
                render(host, kit, panel, item, clip, playable)
            }
        }
    }

    private fun render(
        host: Activity,
        kit: OeaAppKit,
        panel: LinearLayout,
        item: OeaMediaLibrary.Item,
        clip: OeaMediaProbe.Clip?,
        playable: Boolean?
    ) {
        val kindLabel = when (item.kind) {
            OeaMediaLibrary.Kind.VIDEO -> "Video"
            OeaMediaLibrary.Kind.AUDIO -> "Audio"
            OeaMediaLibrary.Kind.IMAGE -> "Picture"
        }
        val facts = kit.group(panel)
        kit.row(facts, R.drawable.oic_info, "Container",
            clip?.container?.uppercase(Locale.getDefault()) ?: "reading the file…", chevron = false)
        kit.row(facts, R.drawable.oic_schedule, "Length",
            OeaMediaLibrary.formatDuration(if (clip != null && clip.durationMs > 0L) clip.durationMs else item.durationMs), chevron = false)
        kit.row(
            facts, R.drawable.oic_high_quality, "Picture",
            if (item.hasRealResolution) "${item.width}×${item.height}" else clip?.resolution ?: "not recorded",
            value = clip?.frameRateLabel()?.takeIf { it != "—" }, chevron = false
        )
        kit.row(facts, R.drawable.oic_volume_up, "Sound", clip?.audioLabel()?.takeIf { it != "—" } ?: "reading the file…", chevron = false)
        if (clip != null && clip.bitrate > 0L) {
            kit.row(facts, R.drawable.oic_show_chart, "Bitrate", "%.1f Mbit/s".format(clip.bitrate / 1_000_000.0), chevron = false)
        }
        if (clip != null && clip.rotationDeg != 0) {
            kit.row(facts, R.drawable.ic_vip_rotate, "Stored rotation", "${clip.rotationDeg}° — VIP turns the picture itself", chevron = false)
        }
        kit.row(facts, R.drawable.oic_memory, "Size",
            OeaMediaLibrary.formatSize(if (clip != null && clip.sizeBytes > 0L) clip.sizeBytes else item.size), chevron = false)
        if (playable == false) {
            kit.banner(
                panel, R.drawable.oic_warning_fill, "This phone cannot decode it",
                "The file is readable, but no decoder on this device handles its codec. VIP only uses the decoders the phone ships, which is why nothing is uploaded to make it play.",
                OeaKit.Tone.WARNING
            )
        }
        if (clip != null && clip.tracks.size > 1) {
            panel.addView(
                kit.listHeader("Tracks in the file"),
                LinearLayout.LayoutParams(-1, -2)
            )
            val tracks = kit.group(panel)
            clip.tracks.forEach { track ->
                kit.row(
                    tracks, iconFor(track.kind), track.label,
                    listOfNotNull(track.kind, track.language.takeIf { it.isNotBlank() }).joinToString(" • "),
                    value = when (track.kind) {
                        "audio" -> if (track.channels > 0) "${track.channels}ch • ${track.sampleRate / 1000} kHz" else null
                        "video" -> if (track.frameRate > 0f) "%.2f fps".format(track.frameRate) else "${track.width}×${track.height}"
                        else -> if (track.bitrate > 0L) "%.1f Mbit/s".format(track.bitrate / 1_000_000.0) else null
                    },
                    chevron = false
                )
            }
            kit.note(panel, "Android's player takes one video track and one audio track at a time. A player that switched between them would have to ship its own decoder, and OEA does not bundle one.")
        }
        val sidecar = runCatching { OeaMediaProbe.siblingFile(item, OeaSubtitles.SUBTITLE_EXTENSIONS) }.getOrNull()
        val file = kit.group(panel)
        kit.row(file, R.drawable.oic_folder, "Where it lives", pathOf(item).ifBlank { "Private to the app that wrote it" }, chevron = false) {
            copyPath(host, pathOf(item))
        }
        kit.row(file, R.drawable.oic_subtitles, "Subtitles beside it",
            sidecar?.name ?: "none — VIP looks for .srt, .vtt, .ass and .ssa",
            value = if (sidecar != null) "ready" else null, chevron = false)
        val actions = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(kit.dp(4), kit.dp(6), kit.dp(4), 0)
        }
        actions.addView(
            kit.tonalButton("Share", R.drawable.oic_share_filled) { OeaMediaLibrary.share(host, listOf(item)) },
            LinearLayout.LayoutParams(0, kit.dp(46), 1f).apply { marginEnd = kit.dp(8) }
        )
        actions.addView(
            kit.tonalButton("Open with", R.drawable.oic_open_in_new) { OeaMediaLibrary.openExternally(host, item) },
            LinearLayout.LayoutParams(0, kit.dp(46), 1f)
        )
        panel.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(12) })
    }

    private fun iconFor(kind: String): Int = when (kind) {
        "video" -> R.drawable.ic_oea_video
        "audio" -> R.drawable.oic_music_note
        "text" -> R.drawable.oic_subtitles
        else -> R.drawable.oic_layers
    }

    private fun pathOf(item: OeaMediaLibrary.Item): String =
        if (item.local) OeaMediaLibrary.pathOf(item)
        else item.relativePath.trimEnd('/').ifBlank { OeaMediaLibrary.pathOf(item) }

    private fun copyPath(host: Activity, path: String) {
        if (path.isBlank()) {
            OeaMediaLibrary.toast(host, "This clip has no file path")
            return
        }
        runCatching {
            host.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("Path", path))
        }
        OeaMediaLibrary.toast(host, "Path copied")
    }

    /** Starts the player from anywhere that only knows a [Uri]. */
    fun playIn(host: Activity, uri: Uri, title: String, audio: Boolean) {
        runCatching {
            host.startActivity(
                Intent(host, OeaVipPlayerActivity::class.java).apply {
                    data = uri
                    putExtra(OeaVipPlayerActivity.EXTRA_URI, uri.toString())
                    putExtra(OeaVipPlayerActivity.EXTRA_TITLE, title)
                    putExtra(OeaVipPlayerActivity.EXTRA_AUDIO, audio)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        }.onFailure { OeaMediaLibrary.toast(host, "VIP could not open that file") }
    }
}
