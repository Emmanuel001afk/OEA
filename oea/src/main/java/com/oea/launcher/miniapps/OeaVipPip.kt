package com.oea.launcher.miniapps

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.widget.Toast
import com.oea.launcher.multitask.pip.OeaPipWindow
import java.util.Locale

/**
 * VIP's picture-in-picture, for phones Android never gave one.
 *
 * Android's PiP is a device feature (`FEATURE_PICTURE_IN_PICTURE`): the
 * platform moves an activity into a special root task and draws it in a
 * corner. Without it there is no PiP for any app at all, and nothing OEA does
 * inside its own process can conjure that task — which is why the player
 * simply said "this phone has no picture-in-picture" until now.
 *
 * So OEA draws its own. The window comes from the multitasking family
 * ([OeaPipWindow] — same overlay types, same drag/fling-to-edge, same corner
 * resize), and because VIP is OEA's *own* player the picture does not have to
 * take the detour a live window takes: no virtual display, no trampoline, no
 * launch ladder. OEA already owns the decoder, so the video is fed straight
 * into the window's own SurfaceView. On a device that refuses secondary
 * displays outright, this still works.
 *
 * The player activity hands the clip over and finishes; the window then owns
 * playback until the user expands it again or closes it. Position is saved
 * continuously, so expanding returns to the full player at the same second.
 */
object OeaVipPip {

    private data class Clip(val uri: Uri, val title: String)

    private val handler = Handler(Looper.getMainLooper())

    /** The application context the window was opened with; outlives the player. */
    private var app: Context? = null
    private var window: OeaPipWindow? = null
    private var player: MediaPlayer? = null
    private var holder: SurfaceHolder? = null
    private var surfaceReady = false
    private var prepared = false

    private var clips: List<Clip> = emptyList()
    private var index = 0
    private var startAtMs = 0L
    private var durationMs = 0L
    private var positionMs = 0L
    private var failed: String? = null

    private var audio: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    /** True while a PiP window is up. */
    fun showing(): Boolean = window?.showing == true

    /**
     * Why a PiP window cannot be shown right now, or null when it can. The
     * player uses this to say something useful instead of failing silently.
     */
    fun blocked(context: Context): String? {
        val available = OeaPipWindow(context, noControls).availability()
        return if (available.ok) null else available.reason
    }

    /**
     * Opens the PiP window for the clip described by [source] — the same Intent
     * shape the full player understands — starting at [atMs].
     *
     * @return null on success, or the reason nothing was shown.
     */
    fun start(context: Context, source: Intent, atMs: Long): String? {
        val application = context.applicationContext
        val uris = source.getStringArrayListExtra(OeaVipPlayerActivity.EXTRA_QUEUE)
        val names = source.getStringArrayListExtra(OeaVipPlayerActivity.EXTRA_QUEUE_TITLES)
        val single = source.data
            ?: source.getStringExtra(OeaVipPlayerActivity.EXTRA_URI)?.let { Uri.parse(it) }
        val list = when {
            !uris.isNullOrEmpty() -> uris.mapIndexed { i, raw ->
                Clip(Uri.parse(raw), names?.getOrNull(i) ?: raw.substringAfterLast('/'))
            }
            single != null -> listOf(
                Clip(
                    single,
                    source.getStringExtra(OeaVipPlayerActivity.EXTRA_TITLE).orEmpty()
                        .ifBlank { single.lastPathSegment ?: "Media" }
                )
            )
            else -> emptyList()
        }
        if (list.isEmpty()) return "There is nothing to play in a picture-in-picture window."
        val at = source.getIntExtra(OeaVipPlayerActivity.EXTRA_INDEX, 0).coerceIn(0, list.lastIndex)
        if (isAudioOnly(list[at].title)) return "A picture-in-picture window needs a video."

        stop()
        app = application
        clips = list
        index = at
        startAtMs = atMs.coerceAtLeast(0L)
        audio = application.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        val (x, y, width) = OeaPipWindow.restore(application, OeaPipWindow.defaultWidth(application))
        val pip = OeaPipWindow(application, controls)
        val error = pip.show(list[at].title, width, (width * 9f / 16f).toInt(), x, y)
        if (error != null) {
            app = null
            clips = emptyList()
            return error
        }
        window = pip
        openCurrent()
        return null
    }

    /** Closes the window and stops playback, saving where the clip got to. */
    fun stop() {
        handler.removeCallbacks(ticker)
        saveProgress()
        releaseFocus()
        runCatching {
            player?.let { mp ->
                if (runCatching { mp.isPlaying }.getOrDefault(false)) mp.stop()
                mp.reset()
                mp.release()
            }
        }
        player = null
        holder = null
        surfaceReady = false
        prepared = false
        window?.dismiss()
        window = null
        clips = emptyList()
        index = 0
        startAtMs = 0L
        durationMs = 0L
        positionMs = 0L
        failed = null
        app = null
    }

    /** Remembers where the window was left, so it comes back there next time. */
    fun rememberPlacement() {
        val context = app ?: return
        val (x, y, width) = window?.placement() ?: return
        OeaPipWindow.remember(context, x, y, width)
    }

    fun togglePlay() {
        val mp = player ?: return
        if (!prepared) return
        if (runCatching { mp.isPlaying }.getOrDefault(false)) {
            runCatching { mp.pause() }
        } else {
            requestFocus()
            runCatching { mp.start() }
        }
        publishPlayState()
    }

    fun step(delta: Int) {
        if (clips.size < 2) return
        val target = (index + delta).coerceIn(0, clips.lastIndex)
        if (target == index) return
        saveProgress()
        index = target
        startAtMs = 0L
        openCurrent()
    }

    /** Reopens the clip in the full player at the second it reached in PiP. */
    private fun expand() {
        val context = app ?: return
        val clip = clips.getOrNull(index) ?: return
        val at = runCatching { player?.currentPosition?.toLong() }.getOrNull() ?: positionMs
        val intent = Intent(context, OeaVipPlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(OeaVipPlayerActivity.EXTRA_URI, clip.uri.toString())
            putExtra(OeaVipPlayerActivity.EXTRA_TITLE, clip.title)
            putExtra(OeaVipPlayerActivity.EXTRA_START_AT, at)
            putExtra(OeaVipPlayerActivity.EXTRA_QUEUE, ArrayList(clips.map { it.uri.toString() }))
            putExtra(OeaVipPlayerActivity.EXTRA_QUEUE_TITLES, ArrayList(clips.map { it.title }))
            putExtra(OeaVipPlayerActivity.EXTRA_INDEX, index)
        }
        stop()
        runCatching { context.startActivity(intent) }
    }

    // ---------------------------------------------------------------- playback

    private fun openCurrent() {
        val context = app ?: return
        val clip = clips.getOrNull(index) ?: return
        failed = null
        durationMs = 0L
        positionMs = startAtMs
        prepared = false

        runCatching {
            player?.let { mp ->
                if (runCatching { mp.isPlaying }.getOrDefault(false)) mp.stop()
                mp.reset()
                mp.release()
            }
        }
        player = null

        val created = MediaPlayer()
        player = created
        created.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
        )
        // Deliberately NOT setScreenOnWhilePlaying: a picture-in-picture window
        // sits on the screen while the user does something else, and it must
        // not hold the whole phone awake for that.
        if (surfaceReady) runCatching { created.setDisplay(holder) }
        created.setOnPreparedListener { mp ->
            prepared = true
            durationMs = runCatching { mp.duration.toLong() }.getOrDefault(0L).coerceAtLeast(0L)
            if (startAtMs > 3_000L && durationMs > startAtMs + 15_000L) {
                runCatching { mp.seekTo(startAtMs.toInt()) }
                positionMs = startAtMs
            }
            applySpeed(mp)
            requestFocus()
            runCatching { mp.start() }
            publishPlayState()
        }
        created.setOnCompletionListener { onFinished() }
        created.setOnErrorListener { _, what, extra ->
            failed = "Android's decoder stopped on this file (code $what/$extra)."
            prepared = false
            publishFailed()
            true
        }
        val opened = runCatching {
            created.setDataSource(context, clip.uri)
            created.prepareAsync()
            true
        }.getOrDefault(false)
        if (!opened) {
            failed = "VIP could not open this file in a picture-in-picture window."
            publishFailed()
        }
        window?.setTitle(clip.title)
        window?.setQueueControls(index > 0, index < clips.lastIndex)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun onFinished() {
        saveProgress()
        if (index < clips.lastIndex && OeaVipStore.autoNext(app ?: return)) {
            index += 1
            startAtMs = 0L
            openCurrent()
            return
        }
        publishPlayState()
    }

    private fun applySpeed(mp: MediaPlayer) {
        val context = app ?: return
        val value = OeaVipStore.speedOf(context)
        runCatching {
            val params = mp.playbackParams
            params.speed = value
            mp.playbackParams = params
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val mp = player
            if (mp != null && prepared) {
                positionMs = runCatching { mp.currentPosition.toLong() }.getOrDefault(positionMs)
            }
            handler.postDelayed(this, 1_000L)
        }
    }

    private fun saveProgress() {
        val context = app ?: return
        val clip = clips.getOrNull(index) ?: return
        if (durationMs <= 0L) return
        OeaVipStore.rememberPosition(context, clip.uri, positionMs, durationMs)
    }

    private fun publishPlayState() {
        val playing = runCatching { player?.isPlaying }.getOrDefault(false) ?: false
        window?.setPlaying(playing)
        if (!playing) saveProgress()
    }

    private fun publishFailed() {
        window?.setPlaying(false)
        failed?.let { toast(it) }
    }

    private fun toast(message: String) {
        val context = app ?: return
        handler.post { runCatching { Toast.makeText(context, message, Toast.LENGTH_LONG).show() } }
    }

    // ------------------------------------------------------------------- focus

    private fun requestFocus() {
        val manager = audio ?: return
        if (focusRequest != null) {
            runCatching { manager.requestAudioFocus(focusRequest!!) }
            return
        }
        if (Build.VERSION.SDK_INT < 26) {
            @Suppress("DEPRECATION")
            runCatching {
                manager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }
            return
        }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setOnAudioFocusChangeListener { change ->
                val playing = runCatching { player?.isPlaying }.getOrDefault(false) ?: false
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (playing) {
                        runCatching { player?.pause() }
                        publishPlayState()
                    }

                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                        runCatching { player?.setVolume(0.35f, 0.35f) }

                    AudioManager.AUDIOFOCUS_GAIN ->
                        runCatching { player?.setVolume(1f, 1f) }
                }
            }
            .build()
        focusRequest = request
        runCatching { manager.requestAudioFocus(request) }
    }

    private fun releaseFocus() {
        val manager = audio ?: return
        val request = focusRequest ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            runCatching { manager.abandonAudioFocusRequest(request) }
        } else {
            @Suppress("DEPRECATION")
            runCatching { manager.abandonAudioFocus(null) }
        }
        focusRequest = null
    }

    // ------------------------------------------------------------- window glue

    private val noControls = object : OeaPipWindow.Controls {
        override fun onSurfaceCreated(holder: SurfaceHolder) = Unit
        override fun onSurfaceDestroyed() = Unit
        override fun onPlayPause() = Unit
        override fun onPrevious(): Boolean = false
        override fun onNext(): Boolean = false
        override fun onExpand() = Unit
        override fun onClose() = Unit
        override fun isPlaying(): Boolean = false
    }

    private val controls = object : OeaPipWindow.Controls {
        override fun onSurfaceCreated(holder: SurfaceHolder) {
            surfaceReady = true
            this@OeaVipPip.holder = holder
            runCatching { player?.setDisplay(holder) }
        }

        override fun onSurfaceDestroyed() {
            surfaceReady = false
            this@OeaVipPip.holder = null
            runCatching { player?.setDisplay(null as SurfaceHolder?) }
        }

        override fun onPlayPause() = togglePlay()

        override fun onPrevious(): Boolean {
            if (clips.size < 2 || index == 0) return false
            step(-1)
            return true
        }

        override fun onNext(): Boolean {
            if (clips.size < 2 || index >= clips.lastIndex) return false
            step(1)
            return true
        }

        override fun onExpand() {
            rememberPlacement()
            expand()
        }

        override fun onClose() {
            rememberPlacement()
            stop()
        }

        override fun isPlaying(): Boolean =
            runCatching { player?.isPlaying }.getOrDefault(false) ?: false
    }

    private fun isAudioOnly(title: String): Boolean =
        title.substringAfterLast('.', "").lowercase(Locale.getDefault()) in AUDIO_ONLY

    private val AUDIO_ONLY = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "amr", "mid")
}
