package com.oea.launcher.miniapps

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Rational
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.oea.launcher.R
import com.oea.launcher.fault.OeaFaultShield
import kotlin.math.roundToInt

/**
 * VIP — the full-screen video and audio player.
 *
 * The player is its own activity so the Android behaviours that need a real
 * window work properly: Picture-in-Picture when you leave the app while a video
 * plays, rotation without restarting playback, and a window brightness that
 * belongs to the player alone.
 *
 * Controls: play/pause, ±10 seconds (buttons and double-tap on either side),
 * a seek bar with elapsed and total time, brightness and media volume sliders,
 * rotate, Picture-in-Picture and screen lock (locked: touches do nothing until
 * you long-press the lock to unlock). Audio files play with the same controls
 * over a large cover, and playback pauses when another app takes audio focus.
 */
class OeaVipPlayerActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var mediaUri: Uri? = null
    private var title: String = ""
    private var subtitle: String = ""
    private var isAudio = false
    private var durationMs = 0
    private var videoWidth = 0
    private var videoHeight = 0
    private var surfaceReady = false
    private var locked = false
    private var inPip = false
    private var userPaused = false
    private var rotationIndex = 0
    private var focusRequest: AudioFocusRequest? = null
    private lateinit var audio: AudioManager

    private lateinit var root: FrameLayout
    private lateinit var videoFrame: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var cover: ImageView
    private lateinit var controls: LinearLayout
    private lateinit var lockHint: TextView
    private lateinit var titleView: TextView
    private lateinit var elapsed: TextView
    private lateinit var total: TextView
    private lateinit var seek: SeekBar
    private lateinit var playPause: TextView
    private lateinit var brightnessBar: SeekBar
    private lateinit var volumeBar: SeekBar
    private var seeking = false

    private val hideControls = Runnable { if (!locked && player?.isPlaying == true) setControlsVisible(false) }
    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 250L)
        }
    }
    private val hideLockHint = Runnable { lockHint.visibility = View.GONE }

    private val gestures by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (locked) {
                    lockHint.visibility = View.VISIBLE
                    handler.removeCallbacks(hideLockHint)
                    handler.postDelayed(hideLockHint, 2500L)
                } else {
                    setControlsVisible(controls.visibility != View.VISIBLE)
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (locked) return true
                seekBy(if (e.x < root.width / 2f) -SEEK_MS else SEEK_MS)
                return true
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val parsed = intent?.data ?: intent?.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) }
        if (parsed == null) {
            Toast.makeText(this, "Nothing to play", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        mediaUri = parsed
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Media" }
        subtitle = intent.getStringExtra(EXTRA_SUBTITLE).orEmpty()
        isAudio = intent.getBooleanExtra(EXTRA_AUDIO, false)
        OeaFaultShield.guard("vip:build") { buildUi() }
        OeaFaultShield.guard("vip:open") { openMedia(parsed) }
    }

    // ------------------------------------------------------------------ layout

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun buildUi() {
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            setOnTouchListener { _, event -> gestures.onTouchEvent(event); true }
        }
        videoFrame = FrameLayout(this)
        surface = SurfaceView(this)
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                runCatching { player?.setDisplay(holder) }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                runCatching { player?.setDisplay(null) }
            }
        })
        videoFrame.addView(surface, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        root.addView(videoFrame, FrameLayout.LayoutParams(-1, -1))

        cover = ImageView(this).apply {
            setImageResource(R.drawable.ic_oea_video)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setBackgroundColor(Color.parseColor("#14161C"))
            visibility = if (isAudio) View.VISIBLE else View.GONE
        }
        root.addView(cover, FrameLayout.LayoutParams(dp(180), dp(180), Gravity.CENTER))

        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        controls.addView(buildTopBar(), LinearLayout.LayoutParams(-1, -2))
        controls.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        controls.addView(buildBottomBar(), LinearLayout.LayoutParams(-1, -2))
        root.addView(controls, FrameLayout.LayoutParams(-1, -1))

        lockHint = TextView(this).apply {
            text = "Locked • long-press the lock to unlock"
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(14), dp(8), dp(14), dp(8))
            visibility = View.GONE
            setOnLongClickListener {
                setLocked(false)
                true
            }
        }
        root.addView(lockHint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(40) })

        setContentView(root)
        applySystemWindow()
        // Start with the controls up so the player is discoverable.
        setControlsVisible(true)
        restoreBrightness()
    }

    private fun buildTopBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(0x88000000.toInt())
        setPadding(dp(6), dp(30), dp(10), dp(8))
        addView(iconButton(R.drawable.oic_arrow_back, "Back") { finish() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val info = LinearLayout(this@OeaVipPlayerActivity).apply { orientation = LinearLayout.VERTICAL }
        titleView = TextView(this@OeaVipPlayerActivity).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        info.addView(titleView)
        if (subtitle.isNotBlank()) {
            info.addView(TextView(this@OeaVipPlayerActivity).apply {
                text = subtitle
                setTextColor(0xB3FFFFFF.toInt())
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        addView(info, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4) })
        addView(iconButton(R.drawable.ic_vip_rotate, "Rotate") { rotateScreen() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        if (!isAudio) {
            addView(iconButton(R.drawable.ic_vip_pip, "Picture in picture") { enterPip() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        addView(iconButton(R.drawable.oic_lock, "Lock screen") { setLocked(true) }, LinearLayout.LayoutParams(dp(44), dp(44)))
    }

    private fun buildBottomBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(0x88000000.toInt())
        setPadding(dp(14), dp(8), dp(14), dp(18))

        val timeRow = LinearLayout(this@OeaVipPlayerActivity).apply { orientation = LinearLayout.HORIZONTAL }
        elapsed = label("0:00")
        total = label("0:00").apply { gravity = Gravity.END }
        timeRow.addView(elapsed, LinearLayout.LayoutParams(0, -2, 1f))
        timeRow.addView(total, LinearLayout.LayoutParams(0, -2, 1f))
        addView(timeRow)

        seek = SeekBar(this@OeaVipPlayerActivity).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && durationMs > 0) elapsed.text = format(durationMs * progress / 1000)
                }

                override fun onStartTrackingTouch(bar: SeekBar?) {
                    seeking = true
                    handler.removeCallbacks(hideControls)
                }

                override fun onStopTrackingTouch(bar: SeekBar?) {
                    seeking = false
                    val target = if (durationMs > 0) durationMs * (bar?.progress ?: 0) / 1000 else 0
                    runCatching { player?.seekTo(target) }
                    scheduleHide()
                }
            })
        }
        addView(seek)

        val transport = LinearLayout(this@OeaVipPlayerActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        transport.addView(textButton("−10s") { seekBy(-SEEK_MS) }, LinearLayout.LayoutParams(dp(72), dp(52)))
        playPause = textButton("Pause") { togglePlay() }.apply { textSize = 16f }
        transport.addView(playPause, LinearLayout.LayoutParams(dp(110), dp(52)).apply { leftMargin = dp(12); rightMargin = dp(12) })
        transport.addView(textButton("+10s") { seekBy(SEEK_MS) }, LinearLayout.LayoutParams(dp(72), dp(52)))
        addView(transport, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        brightnessBar = slider(this, R.drawable.ic_vip_brightness, "Brightness", 100) { value -> setBrightness(value) }
        volumeBar = slider(this, R.drawable.oic_volume_up, "Volume", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)) { value ->
            runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0) }
        }
        volumeBar.progress = runCatching { audio.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
    }

    private fun slider(parent: LinearLayout, icon: Int, description: String, top: Int, onChange: (Int) -> Unit): SeekBar {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(ImageView(this).apply {
            setImageResource(icon)
            setColorFilter(Color.WHITE)
            contentDescription = description
        }, LinearLayout.LayoutParams(dp(24), dp(24)))
        val bar = SeekBar(this).apply {
            max = top
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) onChange(progress)
                }

                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        row.addView(bar, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        return bar
    }

    private fun iconButton(icon: Int, description: String, onClick: () -> Unit): ImageView =
        ImageView(this).apply {
            setImageResource(icon)
            setColorFilter(Color.WHITE)
            contentDescription = description
            setPadding(dp(10), dp(10), dp(10), dp(10))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun textButton(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(26).toFloat()
            setColor(0x33FFFFFF)
        }
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(0xD9FFFFFF.toInt())
        textSize = 12f
    }

    // ------------------------------------------------------------------ playback

    private fun openMedia(uri: Uri) {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(if (isAudio) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        val created = MediaPlayer()
        player = created
        created.setAudioAttributes(attributes)
        created.setOnPreparedListener { mp ->
            durationMs = mp.duration.coerceAtLeast(0)
            total.text = format(durationMs)
            if (surfaceReady) runCatching { mp.setDisplay(surface.holder) }
            requestFocus()
            mp.start()
            userPaused = false
            onPlayStateChanged()
        }
        created.setOnVideoSizeChangedListener { _, w, h ->
            videoWidth = w
            videoHeight = h
            fitVideo()
        }
        created.setOnCompletionListener {
            playPause.text = "Play"
            handler.removeCallbacks(ticker)
            setControlsVisible(true)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        created.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "This file can't be played on this phone.", Toast.LENGTH_LONG).show()
            true
        }
        runCatching {
            created.setDataSource(this, uri)
            created.prepareAsync()
        }.onFailure {
            Toast.makeText(this, "This file can't be opened.", Toast.LENGTH_LONG).show()
        }
        handler.post(ticker)
    }

    private fun togglePlay() {
        val mp = player ?: return
        if (mp.isPlaying) {
            mp.pause()
            userPaused = true
        } else {
            requestFocus()
            mp.start()
            userPaused = false
        }
        onPlayStateChanged()
    }

    private fun onPlayStateChanged() {
        val playing = player?.isPlaying == true
        playPause.text = if (playing) "Pause" else "Play"
        if (playing) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            scheduleHide()
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (!inPip) setControlsVisible(true)
        }
    }

    private fun seekBy(deltaMs: Int) {
        val mp = player ?: return
        val now = runCatching { mp.currentPosition }.getOrDefault(0)
        val limit = if (durationMs > 0) durationMs else Int.MAX_VALUE
        runCatching { mp.seekTo((now + deltaMs).coerceIn(0, limit)) }
        setControlsVisible(true)
        scheduleHide()
    }

    private fun updateProgress() {
        val mp = player ?: return
        if (seeking) return
        val now = runCatching { mp.currentPosition }.getOrDefault(0)
        elapsed.text = format(now)
        if (durationMs > 0) seek.progress = (now.toLong() * 1000 / durationMs).toInt()
        val playing = mp.isPlaying
        if (playing != (playPause.text == "Pause")) onPlayStateChanged()
    }

    private fun requestFocus() {
        if (focusRequest != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(if (isAudio) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_MOVIE)
                .build())
            .setOnAudioFocusChangeListener { change ->
                // Another app took the sound (a call, a navigation prompt): pause and wait.
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    if (player?.isPlaying == true) togglePlay()
                }
            }
            .build()
        focusRequest = request
        runCatching { audio.requestAudioFocus(request) }
    }

    private fun releaseFocus() {
        focusRequest?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
        focusRequest = null
    }

    // ------------------------------------------------------------------ controls

    private fun setControlsVisible(visible: Boolean) {
        if (inPip) return
        controls.visibility = if (visible) View.VISIBLE else View.GONE
        handler.removeCallbacks(hideControls)
        if (visible) scheduleHide()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControls)
        if (!locked && player?.isPlaying == true) handler.postDelayed(hideControls, 3500L)
    }

    private fun setLocked(value: Boolean) {
        locked = value
        if (value) {
            setControlsVisible(false)
            lockHint.visibility = View.VISIBLE
            handler.removeCallbacks(hideLockHint)
            handler.postDelayed(hideLockHint, 2500L)
            Toast.makeText(this, "Screen locked. Long-press the lock hint to unlock.", Toast.LENGTH_SHORT).show()
        } else {
            lockHint.visibility = View.GONE
            setControlsVisible(true)
        }
    }

    private fun rotateScreen() {
        // Auto → landscape → portrait → auto. Config changes are handled in place,
        // so playback continues through the rotation.
        rotationIndex = (rotationIndex + 1) % 3
        requestedOrientation = when (rotationIndex) {
            1 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            2 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
        Toast.makeText(
            this,
            when (rotationIndex) { 1 -> "Rotation: landscape"; 2 -> "Rotation: portrait"; else -> "Rotation: automatic" },
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun enterPip() {
        if (isAudio) return
        if (Build.VERSION.SDK_INT < 26 || !packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            Toast.makeText(this, "This phone doesn't support picture-in-picture.", Toast.LENGTH_LONG).show()
            return
        }
        runCatching { enterPictureInPictureMode(pipParams()) }
            .onFailure { Toast.makeText(this, "Picture-in-picture isn't available right now.", Toast.LENGTH_LONG).show() }
    }

    private fun pipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        if (videoWidth > 0 && videoHeight > 0) {
            // Android accepts aspect ratios between 1:2.39 and 2.39:1 only.
            val ratio = videoWidth.toFloat() / videoHeight.toFloat()
            val clamped = ratio.coerceIn(1f / 2.39f, 2.39f)
            builder.setAspectRatio(Rational((clamped * 1000).roundToInt(), 1000))
        }
        return builder.build()
    }

    /** Leaving the player (Home, Recents) while a video plays moves it into a floating window. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isAudio && player?.isPlaying == true && Build.VERSION.SDK_INT >= 26) {
            runCatching { enterPictureInPictureMode(pipParams()) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration?) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        controls.visibility = if (inPip) View.GONE else View.VISIBLE
        lockHint.visibility = View.GONE
        if (!inPip) setControlsVisible(true)
    }

    override fun onStop() {
        // Pause when the player is hidden, unless it is floating in PIP.
        if (!inPip && !isChangingConfigurations && player?.isPlaying == true) togglePlay()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { player?.release() }
        player = null
        releaseFocus()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ screen

    private fun applySystemWindow() {
        // Edge to edge: the picture and the controls sit behind the bars, with
        // padding keeping the controls clear of the status bar and notch.
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    private fun fitVideo() {
        if (videoWidth <= 0 || videoHeight <= 0) return
        videoFrame.post {
            val frameW = videoFrame.width
            val frameH = videoFrame.height
            if (frameW <= 0 || frameH <= 0) return@post
            val scale = minOf(frameW / videoWidth.toFloat(), frameH / videoHeight.toFloat())
            surface.layoutParams = FrameLayout.LayoutParams(
                (videoWidth * scale).roundToInt(),
                (videoHeight * scale).roundToInt(),
                Gravity.CENTER
            )
            surface.requestLayout()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        fitVideo()
    }

    private fun setBrightness(percent: Int) {
        val value = (percent / 100f).coerceIn(0.01f, 1f)
        window.attributes = window.attributes.apply { screenBrightness = value }
    }

    private fun restoreBrightness() {
        val current = runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) * 100 / 255
        }.getOrDefault(50)
        brightnessBar.progress = current.coerceIn(1, 100)
        setBrightness(brightnessBar.progress)
    }

    private fun format(ms: Int): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    companion object {
        const val EXTRA_URI = "oea.vip.uri"
        const val EXTRA_TITLE = "oea.vip.title"
        const val EXTRA_SUBTITLE = "oea.vip.subtitle"
        const val EXTRA_AUDIO = "oea.vip.audio"
        private const val SEEK_MS = 10_000
    }
}
