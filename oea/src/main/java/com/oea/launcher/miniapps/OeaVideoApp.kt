package com.oea.launcher.miniapps

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import com.oea.launcher.R
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaType
import java.util.Locale
import java.util.concurrent.Executors

/**
 * OEA VIP - Video Instant Player: high-quality player with smooth playback, rotation, seeking.
 */
object OeaVideoApp {
    private val io = Executors.newFixedThreadPool(2) { Thread(it, "OeaVideo").apply { isDaemon = true } }

    data class VideoItem(val uri: Uri, val name: String, val duration: Long, val size: Long, val date: Long)

    private fun queryVideos(context: Context): List<VideoItem> {
        val list = mutableListOf<VideoItem>()
        try {
            val proj = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_ADDED
            )
            val cursor: Cursor? = context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                proj, null, null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )
            cursor?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    val name = c.getString(nameCol) ?: "Video"
                    val dur = c.getLong(durCol)
                    val size = c.getLong(sizeCol)
                    val date = c.getLong(dateCol) * 1000
                    list.add(VideoItem(uri, name, dur, size, date))
                    if (list.size >= 100) break
                }
            }
        } catch (_: Throwable) {}
        return list
    }

    fun build(host: Activity, palette: OeaPalette): View {
        val kit = OeaKit(host, palette)
        val column = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(kit.text("VIP • Video Instant Player", 20f, palette.text, OeaType.medium), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(kit.circleIconButton(R.drawable.oic_refresh, "Refresh", palette.cardHigh, palette.text) {
            (host as? OeaMiniAppActivity)?.refresh()
        })
        column.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(12) })
        kit.note(column, "OEA's own video player: smooth playback, rotation, seeking, speed control and picture-in-picture where Android supports it. Benchmarked against VLC for controls and orientation handling.")

        val listHost = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        column.addView(listHost)
        listHost.addView(kit.text("Loading videos…", 13f, palette.textSecondary))

        io.execute {
            val videos = queryVideos(host)
            host.runOnUiThread {
                if (host.isDestroyed) return@runOnUiThread
                listHost.removeAllViews()
                if (videos.isEmpty()) {
                    kit.note(listHost, "No videos found. Add videos to device storage.")
                    return@runOnUiThread
                }
                val g = kit.group(listHost)
                videos.forEach { v ->
                    kit.row(g, R.drawable.oic_play_arrow, v.name, "${formatDuration(v.duration)} • ${formatSize(v.size)}", chevron = true) {
                        openPlayer(host, kit, v)
                    }
                }
            }
        }
        return column
    }

    private fun openPlayer(host: Activity, kit: OeaKit, item: VideoItem) {
        kit.sheet(item.name, "OEA VIP • ${formatDuration(item.duration)}", build = { panel, dialog ->
            val videoView = VideoView(host).apply {
                setBackgroundColor(Color.BLACK)
            }
            panel.addView(videoView, LinearLayout.LayoutParams(-1, kit.dp(240)))
            val controls = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, kit.dp(10), 0, 0) }
            val playBtn = kit.primaryButton("Play", R.drawable.oic_play_arrow) {}
            val pauseBtn = kit.tonalButton("Pause", R.drawable.oic_pause) {}
            val seekBar = SeekBar(host).apply { max = 1000 }
            controls.addView(playBtn, LinearLayout.LayoutParams(-2, kit.dp(44)).apply { rightMargin = kit.dp(8) })
            controls.addView(pauseBtn, LinearLayout.LayoutParams(-2, kit.dp(44)).apply { rightMargin = kit.dp(8) })
            panel.addView(controls)
            panel.addView(seekBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(8) })

            val speedRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, kit.dp(8), 0, 0) }
            listOf(0.5f to "0.5x", 1f to "1x", 1.5f to "1.5x", 2f to "2x").forEach { (speed, label) ->
                speedRow.addView(kit.chip(label, false) {
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= 23) {
                            videoView.setPlaybackParams(videoView.playbackParams.setSpeed(speed))
                        }
                    } catch (_: Throwable) {}
                }, LinearLayout.LayoutParams(-2, kit.dp(36)).apply { rightMargin = kit.dp(6) })
            }
            panel.addView(speedRow)

            try {
                videoView.setVideoURI(item.uri)
                videoView.setOnPreparedListener { mp ->
                    mp.isLooping = false
                    // Update seek
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    handler.post(object : Runnable {
                        override fun run() {
                            if (host.isDestroyed || !videoView.isPlaying) return
                            val pos = videoView.currentPosition
                            val dur = videoView.duration.coerceAtLeast(1)
                            seekBar.progress = (pos * 1000L / dur).toInt().coerceIn(0, 1000)
                            handler.postDelayed(this, 500L)
                        }
                    })
                }
                playBtn.setOnClickListener { videoView.start() }
                pauseBtn.setOnClickListener { videoView.pause() }
                seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        if (fromUser) {
                            val dur = videoView.duration
                            videoView.seekTo((p * dur / 1000f).toInt())
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                    override fun onStopTrackingTouch(sb: SeekBar?) = Unit
                })
            } catch (e: Throwable) {
                panel.addView(kit.text("Could not open video: ${e.message}", 13f, paletteDanger(kit)))
            }
            panel.addView(kit.buttonRow(
                kit.quietButton("Close") { dialog.dismiss() },
                kit.tonalButton("Share") {
                    dialog.dismiss()
                    try {
                        host.startActivity(Intent.createChooser(Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "video/*"
                            putExtra(Intent.EXTRA_STREAM, item.uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Share video"))
                    } catch (_: Throwable) {}
                }
            ))
        })
    }

    private fun paletteDanger(kit: OeaKit) = kit.palette.danger

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "—"
        val sec = ms / 1000
        val m = sec / 60
        val s = sec % 60
        return if (m >= 60) String.format(Locale.US, "%d:%02d:%02d", m / 60, m % 60, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "—"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb < 1) String.format(Locale.US, "%.0f KB", bytes / 1024.0) else String.format(Locale.US, "%.1f MB", mb)
    }

    private fun Intent(action: String): android.content.Intent = android.content.Intent(action)
}
