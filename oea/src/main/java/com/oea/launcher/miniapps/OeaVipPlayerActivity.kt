package com.oea.launcher.miniapps

import android.app.Activity
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Rational
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.oea.launcher.R
import com.oea.launcher.fault.OeaFaultShield
import com.oea.launcher.ui.OeaIconToggle
import com.oea.launcher.ui.OeaInsets
import com.oea.launcher.ui.OeaScrubBar
import com.oea.launcher.ui.OeaTheme
import com.oea.launcher.ui.OeaThumbnails
import com.oea.launcher.ui.OeaType
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * VIP — the player.
 *
 * It is its own activity because the behaviours that matter here need a real
 * window: Picture-in-Picture with its own transport buttons, rotation without
 * restarting playback, a brightness override that belongs to this window only, and
 * audio that keeps playing when you leave.
 *
 * What it does, all of it wired to the file on disk:
 *  * a **queue** — VIP hands over the list you were looking at, so previous, next,
 *    "play the rest when this one ends" and repeat-all are real;
 *  * resume — a clip you stopped is reopened at the second you left it, with the
 *    choice to start over;
 *  * subtitles from a sidecar `.srt` / `.vtt` / `.ass` file, with offset and size;
 *  * speed 0.25×–4×, five aspect modes, three repeat modes, a sleep timer, and the
 *    phone's own equaliser, bass boost, loudness and reverb on the audio session;
 *  * drag the left half for brightness, the right half for the media volume,
 *    hold to run at double speed, double-tap either side for ten seconds;
 *  * a scrub preview: the frame at your finger, grabbed from the file itself.
 */
class OeaVipPlayerActivity : Activity(), OeaVipAudioService.Control {

    private data class Entry(val uri: Uri, val title: String)

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private var focusRequest: AudioFocusRequest? = null

    private var entries: List<Entry> = listOf()
    private var index = 0
    private var title: String = ""
    private var album: String = ""
    private var isAudio = false
    private var startAtMs = 0L
    private var sidecar: String? = null

    private var player: MediaPlayer? = null
    private var fx: OeaVipAudioFx? = null
    private var retriever: MediaMetadataRetriever? = null
    private var durationMs = 0L
    private var positionMs = 0L
    private var videoWidth = 0
    private var videoHeight = 0
    private var surfaceReady = false
    private var prepared = false
    private var locked = false
    private var inPip = false
    private var chromeUp = true
    private var seeking = false
    private var boosting = false
    private var failed: String? = null
    private var sleepDeadline = 0L
    private var sleepAfterClip = false
    private var brightness = -1f
    private var lastSavedAt = 0L
    private var previewSeq = 0

    private var aspect = OeaVipStore.Aspect.FIT
    private var loop = OeaVipStore.Loop.OFF
    private var speed = 1f
    private var subtitlesOn = true
    private var subtitleTrack: OeaSubtitles.Track? = null
    private var subtitleOffset = 0L
    private var subtitleScale = 1f

    private lateinit var root: FrameLayout
    private lateinit var videoFrame: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var poster: ImageView
    private lateinit var posterScrim: View
    private lateinit var posterTitle: TextView
    private lateinit var errorCard: LinearLayout
    private lateinit var chrome: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var albumView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var subtitleParams: FrameLayout.LayoutParams
    private lateinit var scrub: OeaScrubBar
    private lateinit var elapsed: TextView
    private lateinit var remaining: TextView
    private lateinit var thumbPreview: ImageView
    private lateinit var playToggle: OeaIconToggle
    private lateinit var speedPill: TextView
    private lateinit var aspectPill: TextView
    private lateinit var ccPill: TextView
    private lateinit var sleepPill: TextView
    private var queuePill: TextView? = null
    private lateinit var lockPill: View
    private lateinit var hud: LinearLayout
    private lateinit var hudLabel: TextView
    private lateinit var hudBar: View
    private lateinit var resumeBar: LinearLayout
    private lateinit var seekHint: TextView
    private lateinit var guard: View

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 240L)
        }
    }

    private val hideChrome = Runnable { if (!locked && player?.isPlaying == true && !seeking) setChrome(false) }
    private val hideHud = Runnable { hud.visibility = View.GONE }
    private val hideSeekHint = Runnable { seekHint.visibility = View.GONE }
    private val hideLockPill = Runnable { if (locked) lockPill.visibility = View.GONE }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PIP_PLAY -> togglePlay()
                ACTION_PIP_PREV -> step(-1)
                ACTION_PIP_NEXT -> step(1)
            }
        }
    }

    private val touch by lazy {
        android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (locked) {
                    lockPill.visibility = View.VISIBLE
                    handler.removeCallbacks(hideLockPill)
                    handler.postDelayed(hideLockPill, 2600L)
                } else {
                    setChrome(!chromeUp)
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (locked) return true
                val forward = e.x >= root.width / 2f
                seekBy(if (forward) SEEK_MS else -SEEK_MS)
                flashSeek(forward)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (locked || isAudio || speed >= 2f) return
                boosting = true
                applySpeed()
                flash("Double speed while you hold")
            }

            override fun onScroll(down: MotionEvent?, event: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (locked || inPip) return false
                val from = down ?: return false
                if (Math.abs(event.y - from.y) < Math.abs(event.x - from.x)) return false
                val delta = (down.y - event.y) / root.height.toFloat()
                if (down.x < root.width / 2f) adjustBrightness(delta) else adjustVolume(delta)
                return true
            }
        })
    }

    // ------------------------------------------------------------------- setup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        window.setStatusBarColor(Color.TRANSPARENT)
        window.setNavigationBarColor(Color.TRANSPARENT)
        OeaInsets.edgeToEdge(window, lightBars = false)
        if (!readIntent()) return
        OeaFaultShield.guard("vip:player:build") { buildUi() }
        OeaFaultShield.guard("vip:player:open") { openEntry(index, startAtMs) }
        handler.post(ticker)
        runCatching {
            val filter = IntentFilter().apply {
                addAction(ACTION_PIP_PLAY)
                addAction(ACTION_PIP_PREV)
                addAction(ACTION_PIP_NEXT)
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(pipReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(pipReceiver, filter)
        }
    }

    /** VIP is `singleTask`, so a second clip arrives here instead of in onCreate. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val before = current()?.uri
        if (!readIntent()) return
        if (current()?.uri == before) {
            // The same clip was requested again: bring the player forward.
            setChrome(true)
            return
        }
        openEntry(index, startAtMs)
    }

    private fun current(): Entry? = entries.getOrNull(index)

    /** @return false when the Intent carries nothing playable and the activity is finishing. */
    private fun readIntent(): Boolean {
        val data = intent?.data
        val single = intent?.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) }
        val uri = data ?: single
        val uris = intent?.getStringArrayListExtra(EXTRA_QUEUE)
        val names = intent?.getStringArrayListExtra(EXTRA_QUEUE_TITLES)
        entries = if (!uris.isNullOrEmpty()) {
            uris.mapIndexed { position, raw ->
                Entry(Uri.parse(raw), names?.getOrNull(position) ?: raw.substringAfterLast('/'))
            }
        } else if (uri != null) {
            listOf(Entry(uri, intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { uri.lastPathSegment ?: "Media" }))
        } else {
            emptyList()
        }
        index = (intent?.getIntExtra(EXTRA_INDEX, 0) ?: 0).coerceIn(0, (entries.size - 1).coerceAtLeast(0))
        if (entries.isEmpty()) {
            toast("There is nothing to play")
            finish()
            return false
        }
        title = entries[index].title
        album = intent.getStringExtra(EXTRA_SUBTITLE).orEmpty()
        isAudio = intent.getBooleanExtra(EXTRA_AUDIO, false) ||
            title.substringAfterLast('.', "").lowercase(Locale.getDefault()) in AUDIO_ONLY
        startAtMs = intent.getLongExtra(EXTRA_START_AT, 0L)
        durationMs = intent.getLongExtra(EXTRA_DURATION, 0L).coerceAtLeast(0L)
        sidecar = intent.getStringExtra(EXTRA_SIDECAR)
        aspect = OeaVipStore.aspectOf(this)
        loop = OeaVipStore.loopOf(this)
        speed = OeaVipStore.speedOf(this)
        subtitlesOn = OeaVipStore.subtitlesOn(this)
        subtitleOffset = OeaVipStore.subtitleOffset(this)
        subtitleScale = OeaVipStore.subtitleSize(this)
        sleepDeadline = 0L
        sleepAfterClip = false
        return true
    }

    private fun buildUi() {
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    if (boosting) {
                        boosting = false
                        applySpeed()
                    }
                }
                runCatching { touch.onTouchEvent(event) }
                true
            }
        }
        videoFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        surface = SurfaceView(this).apply {
            holder.setFormat(PixelFormat.TRANSPARENT)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    surfaceReady = true
                    runCatching { player?.setDisplay(holder) }
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    surfaceReady = false
                    runCatching { player?.setDisplay(null as SurfaceHolder?) }
                }
            })
        }
        videoFrame.addView(surface, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        root.addView(videoFrame, FrameLayout.LayoutParams(-1, -1))

        poster = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF0B0D12.toInt())
        }
        posterScrim = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00000000, 0x99000000.toInt())
            )
        }
        posterTitle = text(title, 16f, 0xF2FFFFFF.toInt(), OeaType.medium).apply {
            maxLines = 2
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        val posterColumn = FrameLayout(this).apply {
            addView(poster, FrameLayout.LayoutParams(-1, -1))
            addView(posterScrim, FrameLayout.LayoutParams(-1, dp(120), Gravity.BOTTOM))
            addView(posterTitle, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        root.addView(posterColumn, FrameLayout.LayoutParams(-1, -1))
        posterHolder = posterColumn

        errorCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(26), dp(26), dp(26), dp(26))
            background = GradientDrawable().apply {
                setColor(0xF214161C.toInt())
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), 0x33FFFFFF)
            }
            visibility = View.GONE
        }
        root.addView(errorCard, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        subtitleView = text("", 19f, Color.WHITE, OeaType.medium).apply {
            gravity = Gravity.CENTER
            maxLines = 3
            setTextColor(Color.WHITE)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                setColor(0xAA0A0B0F.toInt())
                cornerRadius = dp(12).toFloat()
            }
            visibility = View.GONE
        }
        subtitleParams = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(132)
        }
        root.addView(subtitleView, subtitleParams)

        hud = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                setColor(0xCC0A0B0F.toInt())
                cornerRadius = dp(18).toFloat()
            }
            visibility = View.GONE
        }
        hudLabel = text("", 13.5f, Color.WHITE, OeaType.medium)
        hudBar = View(this).apply {
            background = GradientDrawable().apply {
                setColor(0xFF8AB4F8.toInt())
                cornerRadius = dp(3).toFloat()
            }
            pivotX = 0f
        }
        hud.addView(hudLabel)
        hud.addView(
            hudBar,
            LinearLayout.LayoutParams(dp(120), dp(5)).apply { topMargin = dp(7) }
        )
        root.addView(hud, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        seekHint = text("", 14f, Color.WHITE, OeaType.medium).apply {
            maxWidth = dp(300)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = GradientDrawable().apply {
                setColor(0xB30A0B0F.toInt())
                cornerRadius = dp(16).toFloat()
            }
            visibility = View.GONE
        }
        root.addView(seekHint, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        chrome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x00000000)
        }
        topBar = buildTopBar()
        bottomBar = buildBottomBar()
        chrome.addView(topBar, LinearLayout.LayoutParams(-1, -2))
        chrome.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        chrome.addView(bottomBar, LinearLayout.LayoutParams(-1, -2))
        root.addView(chrome, FrameLayout.LayoutParams(-1, -1))

        resumeBar = buildResumeBar()
        root.addView(resumeBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(150) })

        guard = View(this).apply {
            isClickable = true
            visibility = View.GONE
            setOnClickListener {
                lockPill.visibility = if (lockPill.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                handler.removeCallbacks(hideLockPill)
                handler.postDelayed(hideLockPill, 2600L)
            }
        }
        root.addView(guard, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitVideo() }
        lockPill = buildLockPill()
        setChrome(true)
    }

    private var posterHolder: View? = null

    private fun buildLockPill(): View {
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = GradientDrawable().apply {
                setColor(0xCC0A0B0F.toInt())
                cornerRadius = dp(22).toFloat()
            }
            isClickable = true
            setOnClickListener { setLocked(false) }
            visibility = View.GONE
        }
        pill.addView(
            iconView(R.drawable.oic_lock_open, Color.WHITE, 20),
            LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) }
        )
        pill.addView(text("Unlock", 13.5f, Color.WHITE, OeaType.medium))
        root.addView(pill, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(28)
            marginEnd = dp(16)
        })
        return pill
    }

    private fun buildTopBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xB3000000.toInt(), 0x00000000)
            )
            setPadding(dp(4), dp(30), dp(6), dp(12))
        }
        bar.addView(iconButton(R.drawable.oic_arrow_back, "Close the player") { finish() })
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleView = text(title, 15.5f, Color.WHITE, OeaType.medium).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        albumView = text(album, 11.5f, 0xB3FFFFFF.toInt()).apply {
            maxLines = 1
            setPadding(0, dp(1), 0, 0)
            visibility = if (album.isBlank()) View.GONE else View.VISIBLE
        }
        info.addView(titleView)
        info.addView(albumView)
        bar.addView(info, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4); marginEnd = dp(6) })
        bar.addView(iconButton(R.drawable.oic_skip_previous, "Previous clip") { step(-1) }, buttonSize())
        bar.addView(iconButton(R.drawable.oic_skip_next, "Next clip") { step(1) }, buttonSize())
        sleepPill = pill("Sleep", R.drawable.oic_timer, null) { showSleepSheet() }
        bar.addView(sleepPill, LinearLayout.LayoutParams(-2, dp(32)).apply { marginStart = dp(4) })
        if (!isAudio) bar.addView(iconButton(R.drawable.ic_vip_pip, "Picture in picture") { enterPip() }, buttonSize())
        // Rotation lives up here, not buried in a sheet: tilting the phone
        // is how people expect a video to turn, and this is the one-tap
        // answer when it does not.
        bar.addView(iconButton(R.drawable.ic_vip_rotate, "Screen rotation") { cycleRotation() }, buttonSize())
        bar.addView(iconButton(R.drawable.oic_lock, "Lock the screen controls") { setLocked(true) }, buttonSize())
        bar.addView(iconButton(R.drawable.oic_more_vert, "Everything else") { showMoreSheet() }, buttonSize())
        return bar
    }

    private fun buttonSize() = LinearLayout.LayoutParams(dp(44), dp(44))

    private fun buildBottomBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xCC000000.toInt(), 0x00000000)
            )
            setPadding(dp(14), dp(14), dp(14), dp(16))
        }
        thumbPreview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(0xFF181B22.toInt())
                cornerRadius = dp(8).toFloat()
            }
            visibility = View.GONE
        }
        bar.addView(thumbPreview, LinearLayout.LayoutParams(dp(148), dp(84)).apply { gravity = Gravity.END; bottomMargin = dp(6) })

        val timeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        elapsed = text("0:00", 12.5f, 0xE6FFFFFF.toInt(), OeaType.medium)
        remaining = text("0:00", 12.5f, 0xB3FFFFFF.toInt(), OeaType.medium).apply { gravity = Gravity.END }
        timeRow.addView(elapsed, LinearLayout.LayoutParams(0, -2, 1f))
        timeRow.addView(remaining, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(timeRow)

        scrub = OeaScrubBar(this).apply {
            accentColor = 0xFF8AB4F8.toInt()
            durationMs = 0L
            format = { pos, dur -> "%s / %s".format(clock(pos), clock(dur)) }
            onSeek = { target, final ->
                if (!final) {
                    seeking = true
                    showPreview(target)
                    handler.removeCallbacks(hideChrome)
                } else {
                    seeking = false
                    thumbPreview.visibility = View.GONE
                    seekTo(target)
                    scheduleHide()
                }
            }
        }
        bar.addView(scrub, LinearLayout.LayoutParams(-1, dp(34)).apply { topMargin = dp(2) })

        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        transport.addView(iconButton(R.drawable.oic_fast_rewind, "Back ten seconds") { seekBy(-SEEK_MS) }, buttonSize())
        playToggle = OeaIconToggle(this).apply {
            circleSizeDp = 56
            icon = drawable(R.drawable.oic_pause, Color.BLACK)
            selectedBg = Color.WHITE
            idleBg = 0x33FFFFFF
            tint = Color.WHITE
            selectedTint = Color.BLACK
            contentDescription = "Play or pause"
            isClickable = true
            setOnClickListener { togglePlay() }
        }
        transport.addView(playToggle, LinearLayout.LayoutParams(dp(56), dp(56)).apply {
            marginStart = dp(14); marginEnd = dp(14)
        })
        transport.addView(iconButton(R.drawable.oic_fast_forward, "Forward ten seconds") { seekBy(SEEK_MS) }, buttonSize())
        bar.addView(transport, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        val pills = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        speedPill = pill(OeaVipStore.speedLabel(speed), R.drawable.oic_speed, null) { showSpeedSheet() }
        aspectPill = pill(aspect.label, R.drawable.oic_aspect_ratio, null) { cycleAspect() }
        ccPill = pill("Subtitles", R.drawable.oic_subtitles, null) { showSubtitleSheet() }
        pills.addView(speedPill, LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(7) })
        pills.addView(aspectPill, LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(7) })
        pills.addView(ccPill, LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(7) })
        val queue = pill(queueLabel(), R.drawable.oic_queue_music, null) { showQueueSheet() }
        queuePill = queue
        pills.addView(queue, LinearLayout.LayoutParams(0, dp(34), 1f))
        bar.addView(pills, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        return bar
    }

    private fun buildResumeBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xE60A0B0F.toInt(), 0x990A0B0F.toInt())
        )
        visibility = View.GONE
        addView(
            text("", 13f, Color.WHITE, OeaType.medium).apply { maxLines = 2 },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(10) }
        )
        addView(
            TextView(context).apply {
                text = "Start over"
                setTextColor(0xFF8AB4F8.toInt())
                textSize = 13.5f
                typeface = OeaType.medium
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = GradientDrawable().apply {
                    setColor(0x22FFFFFF)
                    cornerRadius = dp(16).toFloat()
                }
                isClickable = true
                setOnClickListener {
                    seekTo(0L)
                    resumeBar.visibility = View.GONE
                }
            }
        )
    }

    private fun queueLabel(): String = if (entries.size > 1) "${index + 1}/${entries.size}" else "Queue"

    private fun iconButton(res: Int, description: String, onClick: () -> Unit): ImageView = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(Color.WHITE)
        contentDescription = description
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(10), dp(10), dp(10), dp(10))
        isClickable = true
        background = GradientDrawable().apply {
            setColor(0x00000000)
            cornerRadius = dp(22).toFloat()
        }
        setOnClickListener { runCatching { onClick() } }
    }

    private fun pill(label: String, iconRes: Int, tint: Int?, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 12.5f
        typeface = OeaType.medium
        setTextColor(tint ?: Color.WHITE)
        gravity = Gravity.CENTER
        maxLines = 1
        setPadding(dp(11), 0, dp(11), 0)
        background = GradientDrawable().apply {
            setColor(0x33000000.toInt())
            cornerRadius = dp(17).toFloat()
            setStroke(dp(1), 0x33FFFFFF)
        }
        isClickable = true
        setOnClickListener { runCatching { onClick() } }
        if (iconRes != 0) {
            // The icon rides inside the pill as a compound drawable, so the label
            // stays centred and the pill never clips a child view.
            val badge = drawable(iconRes, tint ?: Color.WHITE)
            if (badge != null) {
                badge.setBounds(0, 0, dp(15), dp(15))
                setCompoundDrawablesRelativeWithIntrinsicBounds(badge, null, null, null)
                compoundDrawablePadding = dp(6)
            }
        }
    }

    private fun iconView(res: Int, tint: Int, sizeDp: Int): ImageView = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(tint)
        layoutParams = android.widget.FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun drawable(res: Int, tint: Int) = runCatching {
        getDrawable(res)?.mutate()?.apply { setTint(tint) }
    }.getOrNull()

    private fun text(value: CharSequence, size: Float, color: Int, face: android.graphics.Typeface = OeaType.regular) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = face
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    // ----------------------------------------------------------------- playback

    private fun openEntry(at: Int, resumeAt: Long) {
        val entry = entries.getOrNull(at) ?: return
        index = at
        saveProgress()
        stopFx()
        runCatching { retriever?.release() }
        retriever = null
        player?.let { old ->
            runCatching { old.reset(); old.release() }
        }
        player = null
        prepared = false
        failed = null
        durationMs = 0L
        positionMs = 0L
        videoWidth = 0
        videoHeight = 0
        errorCard.visibility = View.GONE

        title = entry.title
        isAudio = intent.getBooleanExtra(EXTRA_AUDIO, false) ||
            title.substringAfterLast('.', "").lowercase(Locale.getDefault()) in AUDIO_ONLY
        titleView.text = title
        posterTitle.text = title
        albumView.text = album
        playToggle.selected = false
        albumView.visibility = if (album.isBlank()) View.GONE else View.VISIBLE
        updateQueueUi()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            runCatching { setRecentsScreenshotEnabled(false) }
        }

        val created = MediaPlayer()
        player = created
        created.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(if (isAudio) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
        )
        created.setScreenOnWhilePlaying(true)
        if (surfaceReady) runCatching { created.setDisplay(surface.holder) }
        created.setOnVideoSizeChangedListener { _, width, height ->
            videoWidth = width
            videoHeight = height
            fitVideo()
        }
        created.setOnPreparedListener { mp ->
            prepared = true
            durationMs = runCatching { mp.duration.toLong() }.getOrDefault(durationMs).coerceAtLeast(0L)
            scrub.durationMs = durationMs
            if (resumeAt > 3_000L && durationMs > resumeAt + 15_000L) {
                runCatching { mp.seekTo(resumeAt.toInt()) }
                positionMs = resumeAt
                offerResume(resumeAt)
            } else {
                resumeBar.visibility = View.GONE
            }
            fx = OeaVipAudioFx(mp.audioSessionId)
            applySpeed()
            requestFocus()
            runCatching { mp.start() }
            onPlayStateChanged()
            loadSidecar()
            startRetriever()
        }
        created.setOnInfoListener { _, what, _ ->
            // MEDIA_INFO_VIDEO_RENDERING_START: the decoder has a frame on screen,
            // so the poster stops being the picture and can get out of the way.
            if (what == 3 && !isAudio) hidePoster()
            false
        }
        created.setOnCompletionListener { onFinished() }
        created.setOnErrorListener { _, what, extra ->
            failed = "Android's decoder stopped on this file (code $what/$extra)."
            prepared = false
            showError()
            true
        }
        val opened = runCatching {
            created.setDataSource(this, entry.uri)
            created.prepareAsync()
            true
        }.getOrDefault(false)
        if (!opened) {
            failed = "VIP could not open this file. It may have been moved, or another app still holds it."
            showError()
        }
        posterHolder?.visibility = View.VISIBLE
        poster.alpha = 1f
        OeaThumbnails.loadFull(poster, entry.uri, dp(720), true) { ok ->
            if (!ok) poster.setImageResource(if (isAudio) R.drawable.oic_music_note else R.drawable.ic_oea_video)
        }
        if (!isAudio) handler.postDelayed(
            { if (runCatching { player?.isPlaying == true }.getOrDefault(false)) hidePoster() }, 900L
        )
    }

    private fun hidePoster() {
        val holder = posterHolder ?: return
        if (holder.visibility != View.VISIBLE) return
        holder.animate().alpha(0f).setDuration(220L).withEndAction {
            holder.visibility = View.GONE
            holder.alpha = 1f
        }.start()
    }

    private fun startRetriever() {
        val entry = current() ?: return
        if (isAudio) return
        retriever = runCatching {
            MediaMetadataRetriever().apply { setDataSource(this@OeaVipPlayerActivity, entry.uri) }
        }.getOrNull()
    }

    private fun showPreview(at: Long) {
        val grabber = retriever ?: return
        if (inPip || isAudio) return
        val sequence = ++previewSeq
        PREVIEW.execute {
            val wanted = dp(150) * 16 / 9
            val bitmap = runCatching {
                if (Build.VERSION.SDK_INT >= 27) {
                    grabber.getScaledFrameAtTime(at * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, wanted, dp(150))
                } else {
                    grabber.getFrameAtTime(at * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
            }.getOrNull()
            handler.post {
                if (sequence != previewSeq || bitmap == null) return@post
                thumbPreview.setImageBitmap(bitmap)
                thumbPreview.visibility = View.VISIBLE
            }
        }
    }

    /**
     * Subtitles for this clip, in the order they can actually be found: the file
     * VIP remembered for it, a sidecar beside it on disk, then nothing.
     */
    private fun loadSidecar() {
        base = null
        subtitleName = null
        subtitleTrack = null
        subtitleView.visibility = View.GONE
        ccPill.text = "Subtitles"
        val clip = current()?.uri ?: return
        val remembered = OeaVipStore.subtitleFor(this, clip)
        val from = sidecar
        PROBE.execute {
            val app = applicationContext
            val found: Pair<String, OeaSubtitles.Track>? = runCatching {
                remembered?.let { uri ->
                    OeaSubtitles.load(app, uri, uri.lastPathSegment?.substringAfterLast('/') ?: "subtitles")
                        ?.let { (uri.lastPathSegment?.substringAfterLast('/') ?: "subtitles") to it }
                } ?: run {
                    val beside = from?.let { File(it) }?.takeIf { it.isFile } ?: run {
                        val path = clip.path ?: return@run null
                        val file = File(path)
                        val parent = file.parentFile ?: return@run null
                        OeaSubtitles.SUBTITLE_EXTENSIONS
                            .map { extension -> File(parent, file.name.substringBeforeLast('.') + ".$extension") }
                            .firstOrNull { it.isFile }
                    } ?: return@run null
                    runCatching { OeaSubtitles.loadFile(beside) }.getOrNull()?.let { beside.name to it }
                }
            }.getOrNull()
            handler.post {
                if (isDestroyed) return@post
                if (found == null) return@post
                val (name, track) = found
                base = track
                subtitleTrack = OeaSubtitles.shifted(track, subtitleOffset)
                subtitleName = name
                ccPill.text = if (subtitlesOn) "${track.count} cues" else "Subtitles off"
                paintSubtitle()
            }
        }
    }

    private fun offerResume(at: Long) {
        val label = resumeBar.getChildAt(0) as? TextView ?: return
        label.text = "Picking up at ${clock(at)}"
        resumeBar.visibility = View.VISIBLE
        handler.postDelayed({ resumeBar.visibility = View.GONE }, 5200L)
    }

    private fun showError() {
        errorCard.removeAllViews()
        errorCard.addView(
            iconView(R.drawable.oic_warning_fill, 0xFFFF8A80.toInt(), 30),
            LinearLayout.LayoutParams(dp(30), dp(30)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        )
        errorCard.addView(
            text("This one will not play", 16f, Color.WHITE, OeaType.medium).apply {
                setPadding(0, dp(10), 0, 0)
                gravity = Gravity.CENTER
            }
        )
        errorCard.addView(
            text(failed ?: "The file is unreadable.", 12.5f, 0xB3FFFFFF.toInt()).apply {
                setPadding(0, dp(6), 0, 0)
                maxWidth = dp(280)
                gravity = Gravity.CENTER
            }
        )
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(
            pill("Try another app", R.drawable.oic_open_in_new, 0xFF8AB4F8.toInt()) {
                current()?.let { openExternally(it.uri) }
            },
            LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(8) }
        )
        row.addView(pill("Dismiss", 0, null) { errorCard.visibility = View.GONE }, LinearLayout.LayoutParams(-2, dp(38)))
        errorCard.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        errorCard.visibility = View.VISIBLE
    }

    private fun togglePlay() {
        val mp = player ?: return
        if (!prepared) return
        if (mp.isPlaying) {
            mp.pause()
        } else {
            requestFocus()
            runCatching { mp.start() }
        }
        onPlayStateChanged()
    }

    private fun onPlayStateChanged() {
        val playing = runCatching { player?.isPlaying == true }.getOrDefault(false)
        playToggle.selected = playing
        playToggle.icon = drawable(
            if (playing) R.drawable.oic_pause else R.drawable.oic_play_arrow,
            if (playing) Color.BLACK else Color.WHITE
        )
        if (playing) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            scheduleHide()
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            saveProgress()
            if (!inPip) setChrome(true)
        }
        syncService()
    }

    private fun onFinished() {
        when (loop) {
            OeaVipStore.Loop.ONE -> {
                runCatching { player?.seekTo(0) }
                runCatching { player?.start() }
                onPlayStateChanged()
                return
            }

            OeaVipStore.Loop.ALL -> {
                step(1)
                return
            }

            OeaVipStore.Loop.OFF -> Unit
        }
        if (sleepAfterClip) {
            sleepAfterClip = false
            pauseForSleep("Finished on your cue, at the end of the clip")
            return
        }
        if (index < entries.lastIndex && OeaVipStore.autoNext(this)) {
            saveProgress()
            openEntry(index + 1, 0L)
            return
        }
        clearProgressMark()
        setChrome(true)
    }

    private fun clearProgressMark() {
        current()?.let { OeaVipStore.rememberPosition(this, it.uri, durationMs, durationMs) }
    }

    private fun step(delta: Int) {
        if (entries.size < 2) {
            toast(if (delta > 0) "This is the last clip in the queue" else "This is the first clip")
            return
        }
        val target = when {
            loop == OeaVipStore.Loop.ALL -> (index + delta + entries.size) % entries.size
            else -> (index + delta).coerceIn(0, entries.lastIndex)
        }
        if (target == index) {
            toast(if (delta > 0) "This is the last clip in the queue" else "This is the first clip")
            return
        }
        saveProgress()
        openEntry(target, 0L)
    }

    private fun seekBy(delta: Long) {
        if (!prepared) return
        val now = runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)
        val limit = if (durationMs > 0L) durationMs else Int.MAX_VALUE.toLong()
        seekTo((now + delta).coerceIn(0L, limit))
    }

    private fun seekTo(target: Long) {
        if (!prepared) return
        val mp = player ?: return
        runCatching { if (Build.VERSION.SDK_INT >= 26) mp.seekTo(target, MediaPlayer.SEEK_CLOSEST) else mp.seekTo(target.toInt()) }
        positionMs = target
        scrub.positionMs = target
        elapsed.text = clock(target)
        saveProgress()
    }

    private fun applySpeed() {
        val mp = player ?: return
        val value = if (boosting) (speed * 2f).coerceAtMost(4f) else speed
        runCatching {
            val params = mp.playbackParams
            params.speed = value
            mp.playbackParams = params
        }
        speedPill.text = OeaVipStore.speedLabel(value) + if (boosting) " held" else ""
    }

    private fun saveProgress() {
        val entry = current() ?: return
        if (durationMs <= 0L) return
        lastSavedAt = SystemClock.elapsedRealtime()
        OeaVipStore.rememberPosition(this, entry.uri, positionMs, durationMs)
    }

    private fun tick() {
        val mp = player ?: return
        if (!seeking && prepared) {
            positionMs = runCatching { mp.currentPosition.toLong() }.getOrDefault(positionMs)
            scrub.positionMs = positionMs
            elapsed.text = clock(positionMs)
            remaining.text = if (durationMs > 0L) "− ${clock((durationMs - positionMs).coerceAtLeast(0L))} left" else "unknown length"
            paintSubtitle()
        }
        if (runCatching { mp.isPlaying }.getOrDefault(false) != playToggle.selected) onPlayStateChanged()
        if (sleepDeadline > 0L) {
            val left = sleepDeadline - SystemClock.elapsedRealtime()
            if (left <= 0L) {
                sleepDeadline = 0L
                pauseForSleep("Sleep timer reached")
            } else {
                sleepPill.text = "${(left / 60_000L) + 1}m"
            }
        }
        if (OeaVipAudioService.running && SystemClock.elapsedRealtime() - lastSavedAt > 4_000L) syncService()
    }

    private fun pauseForSleep(reason: String) {
        runCatching { player?.pause() }
        onPlayStateChanged()
        setChrome(true)
        flash(reason)
        sleepPill.text = "Sleep"
    }

    private fun paintSubtitle() {
        val track = subtitleTrack
        if (track == null || !subtitlesOn || track.isEmpty) {
            if (subtitleView.visibility != View.GONE) subtitleView.visibility = View.GONE
            return
        }
        val line = track.at(positionMs + subtitleOffset)
        if (line == null) {
            subtitleView.visibility = View.GONE
            return
        }
        if (subtitleView.text != line) {
            subtitleView.text = line
            subtitleView.textSize = 19f * subtitleScale
        }
        subtitleView.visibility = View.VISIBLE
    }

    /** Keeps the strip of pills describing what is actually happening. */
    private fun updateQueueUi() {
        queuePill?.text = queueLabel()
        aspectPill.text = aspect.label
        playToggle.contentDescription = if (runCatching { player?.isPlaying == true }.getOrDefault(false)) "Pause" else "Play"
    }

    // ---------------------------------------------------------------- gestures

    private fun adjustBrightness(delta: Float) {
        val from = if (brightness < 0f) systemBrightness() else brightness
        val value = (from + delta * 0.9f).coerceIn(0.01f, 1f)
        brightness = value
        window.attributes = window.attributes.apply { screenBrightness = value }
        showHud("Brightness", (value * 100).roundToInt() / 100f, "${(value * 100).roundToInt()}%")
    }

    private fun adjustVolume(delta: Float) {
        val max = runCatching { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(15).coerceAtLeast(1)
        val from = runCatching { audio.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0).toFloat() / max
        val value = (from + delta * 1.1f).coerceIn(0f, 1f)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, (value * max).roundToInt(), 0) }
        showHud("Volume", value, "${(value * max).roundToInt()} of $max")
    }

    private fun systemBrightness(): Float = runCatching {
        (Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f).coerceIn(0.02f, 1f)
    }.getOrDefault(0.5f)

    private fun clearBrightnessOverride() {
        if (brightness < 0f) return
        brightness = -1f
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
    }

    private fun showHud(label: String, fraction: Float, value: String) {
        hudLabel.text = "$label  •  $value"
        hudBar.scaleX = fraction.coerceIn(0.04f, 1f)
        hudBar.alpha = 1f
        hud.visibility = View.VISIBLE
        handler.removeCallbacks(hideHud)
        handler.postDelayed(hideHud, 1100L)
    }

    private fun flashSeek(forward: Boolean) {
        seekHint.text = if (forward) "+10s" else "−10s"
        seekHint.alpha = 1f
        seekHint.translationX = if (forward) dp(70).toFloat() else -dp(70).toFloat()
        seekHint.visibility = View.VISIBLE
        seekHint.animate().translationX(0f).setDuration(150L).start()
        handler.removeCallbacks(hideSeekHint)
        handler.postDelayed(hideSeekHint, 700L)
    }

    private fun flash(message: String) {
        seekHint.text = message
        seekHint.visibility = View.VISIBLE
        handler.removeCallbacks(hideSeekHint)
        handler.postDelayed(hideSeekHint, 1800L)
    }

    // ------------------------------------------------------------------ chrome

    private fun setChrome(visible: Boolean) {
        if (inPip) return
        chromeUp = visible
        if (::subtitleParams.isInitialized) {
            subtitleParams.bottomMargin = if (visible) dp(132) else dp(30)
            subtitleView.layoutParams = subtitleParams
        }
        chrome.animate().cancel()
        if (visible) {
            chrome.visibility = View.VISIBLE
            chrome.alpha = 1f
            scheduleHide()
        } else {
            chrome.animate().alpha(0f).setDuration(160L).withEndAction {
                if (!chromeUp) chrome.visibility = View.GONE
            }.start()
        }
        if (locked) chrome.visibility = View.GONE
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideChrome)
        if (!locked && player?.isPlaying == true) {
            handler.postDelayed(hideChrome, OeaVipStore.hideControlsAfter(this))
        }
    }

    private fun setLocked(value: Boolean) {
        locked = value
        guard.visibility = if (value) View.VISIBLE else View.GONE
        if (value) {
            setChrome(false)
            lockPill.visibility = View.VISIBLE
            handler.removeCallbacks(hideLockPill)
            handler.postDelayed(hideLockPill, 2600L)
        } else {
            lockPill.visibility = View.GONE
            setChrome(true)
        }
    }

    private fun cycleAspect() {
        val values = OeaVipStore.Aspect.values()
        val next = values[(values.indexOf(aspect) + 1) % values.size]
        aspect = next
        OeaVipStore.setAspect(this, next)
        aspectPill.text = next.label
        fitVideo()
        flash(next.label + " — " + next.blurb)
    }

    // ------------------------------------------------------------------- video

    private fun fitVideo() {
        if (videoWidth <= 0 || videoHeight <= 0) return
        videoFrame.post {
            val frameW = videoFrame.width
            val frameH = videoFrame.height
            if (frameW <= 0 || frameH <= 0) return@post
            val sourceAspect = when (aspect) {
                OeaVipStore.Aspect.SIXTEEN_NINE -> 16f / 9f
                OeaVipStore.Aspect.FOUR_THREE -> 4f / 3f
                else -> videoWidth.toFloat() / videoHeight.toFloat()
            }
            val fitScale = minOf(frameW / videoWidth.toFloat(), frameH / videoHeight.toFloat())
            val fillScale = maxOf(frameW / videoWidth.toFloat(), frameH / videoHeight.toFloat())
            val width: Int
            val height: Int
            when (aspect) {
                OeaVipStore.Aspect.STRETCH -> {
                    width = frameW
                    height = frameH
                }

                OeaVipStore.Aspect.FILL -> {
                    width = (videoWidth * fillScale).roundToInt()
                    height = (videoHeight * fillScale).roundToInt()
                }

                OeaVipStore.Aspect.SIXTEEN_NINE, OeaVipStore.Aspect.FOUR_THREE -> {
                    val byWidth = frameW
                    val byHeight = (frameW / sourceAspect).roundToInt()
                    if (byHeight <= frameH) {
                        width = byWidth
                        height = byHeight
                    } else {
                        height = frameH
                        width = (frameH * sourceAspect).roundToInt()
                    }
                }

                OeaVipStore.Aspect.FIT -> {
                    width = (videoWidth * fitScale).roundToInt()
                    height = (videoHeight * fitScale).roundToInt()
                }
            }
            surface.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
            surface.requestLayout()
            if (inPip) updatePipParams()
        }
    }

    // --------------------------------------------------------------------- PiP

    private fun enterPip() {
        if (isAudio) {
            toast("A picture-in-picture window needs a video")
            return
        }
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            toast("This phone has no picture-in-picture")
            return
        }
        runCatching { enterPictureInPictureMode(pipParams()) }
            .onFailure { toast("Picture-in-picture is not available right now") }
    }

    private fun pipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        if (videoWidth > 0 && videoHeight > 0) {
            val ratio = videoWidth.toFloat() / videoHeight.toFloat()
            builder.setAspectRatio(Rational((ratio.coerceIn(1f / 2.39f, 2.39f) * 1000).roundToInt(), 1000))
        }
        val actions = ArrayList<RemoteAction>(3)
        if (entries.size > 1) {
            actions.add(remote(R.drawable.oic_skip_previous, "Previous clip", ACTION_PIP_PREV))
        }
        actions.add(
            remote(
                if (runCatching { player?.isPlaying == true }.getOrDefault(false)) R.drawable.oic_pause else R.drawable.oic_play_arrow,
                if (runCatching { player?.isPlaying == true }.getOrDefault(false)) "Pause" else "Play",
                ACTION_PIP_PLAY
            )
        )
        if (entries.size > 1) {
            actions.add(remote(R.drawable.oic_skip_next, "Next clip", ACTION_PIP_NEXT))
        }
        runCatching { builder.setActions(actions) }
        return builder.build()
    }

    private fun remote(icon: Int, label: String, action: String): RemoteAction {
        val intent = Intent(action).setPackage(packageName)
        val pending = android.app.PendingIntent.getBroadcast(
            this, action.hashCode(), intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        // The notification shade tints action icons itself, so the vector goes in
        // as the system drew it rather than with a hand-picked colour.
        val badge = android.graphics.drawable.Icon.createWithResource(packageName, icon)
        return RemoteAction(badge, label, label, pending)
    }

    private fun updatePipParams() {
        if (!inPip) return
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isAudio && !locked && runCatching { player?.isPlaying == true }.getOrDefault(false)) {
            runCatching { enterPictureInPictureMode(pipParams()) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration?) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (inPip) {
            chrome.visibility = View.GONE
            subtitleView.visibility = View.GONE
            resumeBar.visibility = View.GONE
            guard.visibility = View.GONE
        } else {
            setChrome(true)
            fitVideo()
        }
        updatePipParams()
    }

    // ------------------------------------------------------------------ audio

    private fun requestFocus() {
        if (focusRequest != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(if (isAudio) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setOnAudioFocusChangeListener { change ->
                val playing = runCatching { player?.isPlaying == true }.getOrDefault(false)
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (playing) {
                        runCatching { player?.pause() }
                        onPlayStateChanged()
                    }

                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> if (playing) {
                        runCatching { player?.setVolume(0.35f, 0.35f) }
                    }

                    AudioManager.AUDIOFOCUS_GAIN -> runCatching { player?.setVolume(1f, 1f) }
                }
            }
            .build()
        focusRequest = request
        runCatching { audio.requestAudioFocus(request) }
    }

    private fun stopFx() {
        runCatching { fx?.release() }
        fx = null
    }

    private fun syncService() {
        val keep = isAudio && OeaVipStore.backgroundAudio(this)
        if (!keep) {
            if (OeaVipAudioService.running) OeaVipAudioService.stop(this)
            return
        }
        if (OeaVipAudioService.running) OeaVipAudioService.update(this, status()) else OeaVipAudioService.start(this, this, status())
    }

    // -------------------------------------------------- OeaVipAudioService.Control

    override fun status(): OeaVipAudioService.Status = OeaVipAudioService.Status(
        title = title.substringBeforeLast('.'),
        subtitle = listOfNotNull(album.takeIf { it.isNotBlank() }, queueLabel().takeIf { entries.size > 1 }).joinToString("  •  "),
        playing = runCatching { player?.isPlaying == true }.getOrDefault(false),
        positionMs = positionMs,
        durationMs = durationMs,
        art = null
    )

    override fun togglePlayPause() = togglePlay()

    override fun next() = step(1)

    override fun previous() = step(-1)

    override fun skipBy(deltaMs: Long) = seekBy(deltaMs)

    override fun dismiss() {
        saveProgress()
        OeaVipAudioService.stop(this)
        finishAndRemoveTask()
    }

    // -------------------------------------------------------------------- sheets

    private fun sheet(title: String, subtitle: String? = null, build: (OeaVipSheet) -> Unit) {
        val sheet = OeaVipSheet.build(this, OeaTheme.resolve(this), title, subtitle)
        build(sheet)
        sheet.show()
    }

    private fun showSpeedSheet() {
        sheet("Speed", "Long-press the picture for a temporary 2×") { panel ->
            panel.heading("Playback rate")
            val labels = OeaVipStore.SPEEDS.map { OeaVipStore.speedLabel(it) }
            val current = OeaVipStore.SPEEDS.indexOfFirst { Math.abs(it - speed) < 0.01f }.coerceAtLeast(0)
            panel.chips(panel.panel, labels, current) { pick ->
                speed = OeaVipStore.SPEEDS[pick]
                OeaVipStore.setSpeed(this, speed)
                applySpeed()
            }
            panel.heading("Repeat")
            val group = panel.group()
            OeaVipStore.Loop.values().forEachIndexed { position, mode ->
                panel.row(group, R.drawable.oic_repeat, mode.label, loopBlurb(mode),
                    selected = loop == mode, onClick = {
                        loop = mode
                        OeaVipStore.setLoop(this, mode)
                        panel.dismiss()
                        flash(mode.label)
                    })
            }
            panel.note("Speed is kept for the next clip too. Audio pitch follows the rate, because that is what Android's player does.")
        }
    }

    private fun loopBlurb(mode: OeaVipStore.Loop): String = when (mode) {
        OeaVipStore.Loop.OFF -> if (OeaVipStore.autoNext(this)) "Move to the next clip in the queue" else "Stop when this clip ends"
        OeaVipStore.Loop.ONE -> "Start this clip over, forever"
        OeaVipStore.Loop.ALL -> "Round the whole queue"
    }

    private fun showSleepSheet() {
        sheet("Sleep timer", if (sleepDeadline > 0L) "Pauses in ${((sleepDeadline - SystemClock.elapsedRealtime()) / 60_000L) + 1} min" else "Nothing scheduled") { panel ->
            val group = panel.group()
            listOf(10L, 20L, 30L, 45L, 60L, 90L).forEach { minutes ->
                panel.row(group, R.drawable.oic_timer, "$minutes minutes", "Pause when the time is up", onClick = {
                    sleepDeadline = SystemClock.elapsedRealtime() + minutes * 60_000L
                    sleepAfterClip = false
                    sleepPill.text = "$minutes m"
                    panel.dismiss()
                })
            }
            panel.row(group, R.drawable.oic_bedtime, "After this clip", "Finish the clip you are on, then stop",
                selected = sleepAfterClip, onClick = {
                    sleepAfterClip = !sleepAfterClip
                    sleepDeadline = 0L
                    sleepPill.text = if (sleepAfterClip) "1 clip" else "Sleep"
                    panel.dismiss()
                })
            panel.row(group, R.drawable.oic_close, "Turn it off", selected = false, onClick = {
                sleepDeadline = 0L
                sleepAfterClip = false
                sleepPill.text = "Sleep"
                panel.dismiss()
            })
        }
    }

    private fun showSubtitleSheet() {
        sheet("Subtitles", subtitleTrack?.let { "${it.name} • ${it.count} cues" } ?: "No file loaded") { panel ->
            val group = panel.group()
            panel.switch(group, R.drawable.oic_subtitles, "Show them", "Drawn over the picture, above the controls", subtitlesOn) { value ->
                subtitlesOn = value
                OeaVipStore.setSubtitlesOn(this, value)
                paintSubtitle()
                ccPill.text = if (value) (subtitleTrack?.let { "${it.count} cues" } ?: "Subtitles") else "Subtitles off"
            }
            panel.slider(
                panel.panel, "Text size", 0.6f, 2.4f, 0, subtitleScale,
                { value -> "${(value * 100).roundToInt()}%" },
                { value, final ->
                    subtitleScale = value
                    subtitleView.textSize = 19f * value
                    if (final) OeaVipStore.setSubtitleSize(this, value)
                }
            )
            panel.slider(
                panel.panel, "Time offset", -10_000f, 10_000f, 0, subtitleOffset.toFloat(),
                { value -> "%+.1f s".format(value / 1000f) },
                { value, final ->
                    subtitleOffset = value.toLong()
                    base?.let { subtitleTrack = OeaSubtitles.shifted(it, subtitleOffset) }
                    paintSubtitle()
                    if (final) OeaVipStore.setSubtitleOffset(this, subtitleOffset)
                }
            )
            panel.heading("Load a file")
            val pick = panel.group()
            panel.row(pick, R.drawable.oic_folder, "Pick a subtitle file",
                "A .srt, .vtt or .ass anywhere on this phone", onClick = {
                    panel.dismiss()
                    pickSubtitleFile()
                })
            val remembered = subtitleName
            if (remembered != null) {
                panel.row(pick, R.drawable.oic_check, remembered, "Kept for this clip only", onClick = {
                    forgetSubtitles()
                    panel.dismiss()
                })
            }
            panel.note("VIP reads the cues itself and draws them, so the style, size and delay are yours. Subtitles embedded in the file stay with whoever plays it next.")
        }
    }

    /** The un-shifted cues, so the offset slider never stacks up on itself. */
    private var base: OeaSubtitles.Track? = null
    private var subtitleName: String? = null

    private fun showAspectSheet() {
        sheet("Picture", "${videoWidth}×${videoHeight} in the file") { panel ->
            val group = panel.group()
            OeaVipStore.Aspect.values().forEach { mode ->
                panel.row(group, if (mode == aspect) R.drawable.oic_check else R.drawable.oic_aspect_ratio, mode.label, mode.blurb,
                    selected = mode == aspect, onClick = {
                        aspect = mode
                        OeaVipStore.setAspect(this, mode)
                        aspectPill.text = mode.label
                        fitVideo()
                        panel.dismiss()
                    })
            }
        }
    }

    private fun showEqualizerSheet() {
        val fx = fx
        sheet("Sound", if (fx == null || !fx.equalizerAvailable) "This phone exposes no equaliser" else "Android's own audio effects") { panel ->
            if (fx == null) {
                panel.note("The effects attach to a playing audio session, so play the clip first.")
                return@sheet
            }
            val presets = fx.presets()
            if (presets.isNotEmpty()) {
                panel.heading("Device presets")
                panel.chips(panel.panel, presets, fx.currentPreset()) { pick ->
                    fx.usePreset(pick)
                    flash("Equaliser: ${presets.getOrNull(pick).orEmpty()}")
                }
            }
            val bands = fx.bands()
            if (bands.isNotEmpty()) {
                panel.heading("Bands")
                bands.forEach { band ->
                    panel.slider(
                        panel.panel, band.label, band.minMilliBel.toFloat(), band.maxMilliBel.toFloat(), 0, band.level.toFloat(),
                        { value -> "%+.1f dB".format(value / 100f) },
                        { value, final ->
                            fx.setBand(band.index, value.toInt())
                            if (final) flash("${band.label} %+.1f dB".format(value / 100f))
                        }
                    )
                }
                panel.buttons("Reset to flat" to { fx.resetBands() })
            }
            if (fx.bassAvailable) {
                panel.heading("Bass")
                panel.slider(panel.panel, "Bass boost", 0f, 1000f, 0, fx.bassStrength().toFloat(),
                    { value -> "${(value / 10).roundToInt()}%" },
                    { value, _ -> fx.setBassStrength(value.toInt()) })
            }
            if (fx.loudnessAvailable) {
                panel.heading("Loudness")
                panel.slider(panel.panel, "Boost", 0f, 6000f, 0, fx.loudnessGain().toFloat(),
                    { value -> "%+.1f dB".format(value / 100f) },
                    { value, _ -> fx.setLoudnessGain(value.toInt()) })
            }
            panel.heading("Room")
            panel.chips(panel.panel, fx.reverbs, fx.currentReverb()) { pick ->
                fx.setReverb(pick)
            }
            panel.note("These are this phone's own effects. If a slider does nothing here, the device has no implementation for it — VIP says so instead of faking a change.")
        }
    }

    private fun showQueueSheet() {
        sheet("Queue", "${entries.size} clips • playing ${index + 1}") { panel ->
            val group = panel.group()
            entries.forEachIndexed { position, entry ->
                panel.row(
                    group,
                    if (position == index) R.drawable.oic_play_arrow else R.drawable.oic_queue_music,
                    entry.title.substringBeforeLast('.'),
                    if (position == index) "Now playing" else "Clip ${position + 1}",
                    selected = position == index,
                    onClick = {
                        panel.dismiss()
                        if (position != index) {
                            saveProgress()
                            openEntry(position, 0L)
                        }
                    }
                )
            }
            panel.buttons(
                if (entries.size > 1) "Take this clip out" to {
                    saveProgress()
                    entries = entries.filterIndexed { position, _ -> position != index }
                    index = index.coerceAtMost(entries.lastIndex)
                    if (entries.isEmpty()) {
                        finish()
                    } else {
                        openEntry(index, 0L)
                    }
                } else "Nothing else in the queue" to { },
                "Close" to { }
            )
        }
    }

    private fun showMoreSheet() {
        sheet(title.substringBeforeLast('.'), album.ifBlank { null } ?: "Clip ${index + 1} of ${entries.size}") { panel ->
            val primary = panel.group()
            panel.row(primary, R.drawable.oic_aspect_ratio, "Picture", aspect.label + " — " + aspect.blurb, onClick = {
                panel.dismiss()
                showAspectSheet()
            })
            panel.row(primary, R.drawable.oic_speed, "Speed and repeat", "${OeaVipStore.speedLabel(speed)} • ${loop.label}", onClick = {
                panel.dismiss()
                showSpeedSheet()
            })
            panel.row(primary, R.drawable.oic_tune, "Sound", "Equaliser, bass, loudness, room", onClick = {
                panel.dismiss()
                showEqualizerSheet()
            })
            panel.row(primary, R.drawable.oic_subtitles, "Subtitles",
                subtitleTrack?.let { "${it.name} • ${it.count} cues" } ?: "none beside this file", onClick = {
                    panel.dismiss()
                    showSubtitleSheet()
                })
            panel.row(primary, R.drawable.oic_timer, "Sleep timer",
                if (sleepDeadline > 0L) "on" else if (sleepAfterClip) "after this clip" else "off", onClick = {
                    panel.dismiss()
                    showSleepSheet()
                })
            panel.row(primary, R.drawable.ic_vip_rotate, "Rotation", rotationLabel(), onClick = {
                panel.dismiss()
                cycleRotation()
            })
            if (!isAudio) {
                panel.row(primary, R.drawable.ic_vip_pip, "Picture in picture", "Shrink the player into a corner window", onClick = {
                    panel.dismiss()
                    enterPip()
                })
            }
            val library = panel.group()
            panel.row(library, R.drawable.oic_layers, "Add to a playlist", "VIP keeps it in the library", onClick = {
                panel.dismiss()
                addToPlaylist()
            })
            panel.row(library, R.drawable.oic_history, "Save my place",
                "Bookmarked at ${clock(positionMs)} so VIP's library can put you back here", onClick = {
                    saveProgress()
                    panel.dismiss()
                    flash("Saved at ${clock(positionMs)}")
                })
            panel.row(library, R.drawable.oic_queue_music, "Queue", "${entries.size} clips", onClick = {
                panel.dismiss()
                showQueueSheet()
            })
            val info = panel.group()
            panel.row(info, R.drawable.oic_info, "File details", "Codec, tracks, bitrate, path", onClick = {
                panel.dismiss()
                showDetails()
            })
            panel.row(info, R.drawable.oic_share_filled, "Share the file", onClick = { shareCurrent() })
            panel.row(info, R.drawable.oic_open_in_new, "Play with the phone's player",
                "The device's own video app takes over, the way the phone plays video anywhere else", onClick = {
                    current()?.let { openExternally(it.uri) }
                })
            panel.switch(info, R.drawable.oic_music_note, "Audio keeps playing", "Music continues when you leave VIP",
                OeaVipStore.backgroundAudio(this)) { value ->
                OeaVipStore.setBackgroundAudio(this, value)
                syncService()
            }
            panel.switch(info, R.drawable.oic_play_arrow, "Next clip automatically", "When one ends, start the next in the queue",
                OeaVipStore.autoNext(this)) { value -> OeaVipStore.setAutoNext(this, value) }
            panel.slider(
                info, "Controls stay up", 1200f, 10_000f, 0, OeaVipStore.hideControlsAfter(this).toFloat(),
                { value -> "%.1f s".format(value / 1000f) },
                { value, final ->
                    OeaVipStore.setHideControlsAfter(this, value.toLong())
                    if (final) scheduleHide()
                }
            )
        }
    }

    private fun showDetails() {
        val entry = current() ?: return
        sheet(entry.title, "Reading the file…") { panel ->
            val group = panel.group()
            panel.row(group, R.drawable.oic_info, "Container", "reading…")
            PROBE.execute {
                val clip = runCatching { OeaMediaProbe.probe(applicationContext, entry.uri) }.getOrNull()
                handler.post {
                    if (clip == null) panel.titleView?.text = "Could not read the file"
                    else panel.titleView?.text = "${clip.container.uppercase(Locale.getDefault())} • ${clip.resolution}"
                    renderDetails(panel, group, clip, entry)
                }
            }
        }
    }

    private fun renderDetails(sheet: OeaVipSheet, group: LinearLayout, clip: OeaMediaProbe.Clip?, entry: Entry) {
        if (clip == null) {
            sheet.note("This file is private to the app that wrote it, or it has gone. VIP can still play it while the owner allows access.")
            return
        }
        group.removeAllViews()
        sheet.row(group, R.drawable.oic_schedule, "Length", clock(if (clip.durationMs > 0L) clip.durationMs else durationMs))
        sheet.row(group, R.drawable.oic_high_quality, "Picture", clip.resolution + if (clip.videoTracks.isNotEmpty()) " • ${clip.frameRateLabel()}" else "")
        sheet.row(group, R.drawable.oic_volume_up, "Sound", clip.audioLabel())
        if (clip.bitrate > 0L) sheet.row(group, R.drawable.oic_show_chart, "Bitrate", "%.1f Mbit/s".format(clip.bitrate / 1_000_000.0))
        if (clip.sizeBytes > 0L) sheet.row(group, R.drawable.oic_memory, "Size", OeaMediaLibrary.formatSize(clip.sizeBytes))
        if (clip.rotationDeg != 0) sheet.row(group, R.drawable.ic_vip_rotate, "Stored rotation", "${clip.rotationDeg}°")
        if (clip.tracks.size > 1) {
            clip.tracks.forEach { track ->
                sheet.row(group, R.drawable.oic_layers, track.label, listOfNotNull(track.kind, track.language.takeIf { it.isNotBlank() }).joinToString(" • "))
            }
            sheet.note("Android's player takes one video and one audio track at a time; the rest stay in the file.")
        }
        sheet.row(group, R.drawable.oic_folder, "Path", entry.uri.path ?: "hidden inside the media provider")
    }

    private fun cycleRotation() {
        val modes = listOf(
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR to "automatic",
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE to "landscape",
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT to "portrait",
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE to "upside down"
        )
        val at = modes.indexOfFirst { it.first == requestedOrientation }
        val next = modes[(at + 1 + modes.size) % modes.size]
        requestedOrientation = next.first
        flash("Rotation: ${next.second}")
    }

    private fun rotationLabel(): String = when (requestedOrientation) {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE -> "landscape"
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT -> "portrait"
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE -> "upside down"
        else -> "automatic"
    }

    private fun addToPlaylist() {
        val entry = current() ?: return
        val uri = entry.uri
        sheet("Playlists", "${OeaVipStore.playlists(this).size} in your library") { panel ->
            val group = panel.group()
            OeaVipStore.playlists(this).forEach { data ->
                panel.row(group, R.drawable.oic_layers, data.name, "${data.size} clips",
                    value = if (data.uris.contains(uri.toString())) "in it" else null, onClick = {
                        val added = OeaVipStore.addToPlaylist(this, data.name, uri)
                        panel.dismiss()
                        flash(if (added) "Added to ${data.name}" else "It is already in ${data.name}")
                    })
            }
            panel.row(group, R.drawable.oic_add, "New playlist", "It appears in VIP's library", onClick = {
                panel.dismiss()
                namePlaylist(uri)
            })
        }
    }

    private fun namePlaylist(uri: Uri) {
        sheet("New playlist", "It shows up in VIP's library straight away") { panel ->
            val field = panel.input(panel.panel, "Playlist name", "")
            panel.row(panel.group(), R.drawable.oic_info, "What goes in it", "This clip, and anything you add later")
            panel.buttons("Create" to {
                val name = field.text.toString().trim()
                if (name.isEmpty()) {
                    flash("Give it a name")
                } else if (!OeaVipStore.createPlaylist(this, name)) {
                    flash("That name is taken")
                } else {
                    OeaVipStore.addToPlaylist(this, name, uri)
                    flash("Playlist created")
                }
            }, "Cancel" to { })
        }
    }

    private fun pickSubtitleFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/x-subrip", "text/plain", "text/vtt", "text/x-ssa", "application/octet-stream"))
        }
        val started = runCatching {
            startActivityForResult(intent, REQUEST_SUBTITLE)
            true
        }.getOrDefault(false)
        if (!started) toast("Android would not open the file picker")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SUBTITLE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val name = uri.lastPathSegment ?: "subtitles"
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        PROBE.execute {
            val track = OeaSubtitles.load(applicationContext, uri, name)
            handler.post {
                if (track == null) {
                    toast("No cues could be read from that file")
                    return@post
                }
                applySubtitles(name, track)
                OeaVipStore.rememberSubtitle(this, current()?.uri, uri)
                toast("Subtitles loaded — ${track.count} cues")
            }
        }
    }

    private fun applySubtitles(name: String, track: OeaSubtitles.Track) {
        base = track
        subtitleTrack = OeaSubtitles.shifted(track, subtitleOffset)
        subtitleName = name
        ccPill.text = "${track.count} cues"
        paintSubtitle()
    }

    private fun forgetSubtitles() {
        base = null
        subtitleTrack = null
        subtitleName = null
        subtitleView.visibility = View.GONE
        ccPill.text = "Subtitles"
        OeaVipStore.rememberSubtitle(this, current()?.uri, null)
        toast("Subtitles removed from this clip")
    }

    private fun shareCurrent() {
        val entry = current() ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (isAudio) "audio/*" else "video/*"
            putExtra(Intent.EXTRA_STREAM, entry.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, "Share")) }
            .onFailure { toast("Nothing on this phone accepts that file") }
    }

    private fun openExternally(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, if (isAudio) "audio/*" else "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, "Open with")) }
            .onFailure { toast("No other app can open this") }
    }

    // -------------------------------------------------------------------- life

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
        setChrome(true)
    }

    override fun onPause() {
        saveProgress()
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onStop() {
        val keepPlaying = isAudio && OeaVipStore.backgroundAudio(this)
        if (!inPip && !isChangingConfigurations && !keepPlaying) {
            if (runCatching { player?.isPlaying == true }.getOrDefault(false)) {
                runCatching { player?.pause() }
                onPlayStateChanged()
            }
        }
        if (keepPlaying) syncService()
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        fitVideo()
        if (!inPip) setChrome(true)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        saveProgress()
        runCatching { unregisterReceiver(pipReceiver) }
        stopFx()
        runCatching { retriever?.release() }
        retriever = null
        runCatching { player?.release() }
        player = null
        runCatching { focusRequest?.let { audio.abandonAudioFocusRequest(it) } }
        focusRequest = null
        clearBrightnessOverride()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (OeaVipAudioService.running) OeaVipAudioService.stop(this)
        OeaVipAudioService.pendingControl = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        saveProgress()
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun toast(message: String) {
        runCatching { android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show() }
    }

    private fun clock(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        return if (h > 0L) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    companion object {
        const val EXTRA_URI = "oea.vip.uri"
        const val EXTRA_TITLE = "oea.vip.title"
        const val EXTRA_SUBTITLE = "oea.vip.subtitle"
        const val EXTRA_AUDIO = "oea.vip.audio"
        const val EXTRA_START_AT = "oea.vip.start_at"
        const val EXTRA_DURATION = "oea.vip.duration"
        const val EXTRA_SIDECAR = "oea.vip.sidecar"
        const val EXTRA_QUEUE = "oea.vip.queue"
        const val EXTRA_QUEUE_TITLES = "oea.vip.queue_titles"
        const val EXTRA_INDEX = "oea.vip.index"

        /** Set when VIP was started from a VIEW intent, so it does not reopen the same clip. */
        const val EXTRA_HANDLED = "oea.vip.view_handled"

        private const val SEEK_MS = 10_000L
        private const val REQUEST_SUBTITLE = 4321
        private const val ACTION_PIP_PLAY = "com.oea.launcher.vip.PIP_PLAY"
        private const val ACTION_PIP_PREV = "com.oea.launcher.vip.PIP_PREV"
        private const val ACTION_PIP_NEXT = "com.oea.launcher.vip.PIP_NEXT"
        private val AUDIO_ONLY = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "amr", "mid")

        private val PROBE = java.util.concurrent.Executors.newFixedThreadPool(2) { task ->
            Thread(task, "OeaVipInfo").apply { isDaemon = true }
        }

        private val PREVIEW = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "OeaVipPreview").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }
    }
}
