package com.oea.launcher.phone

import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.oea.launcher.R
import com.oea.launcher.callblocker.OeaCallBlockRules
import com.oea.launcher.ui.OeaInsets
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaTheme
import java.util.Locale

/**
 * OEA's call screen for incoming and outgoing calls.
 *
 * Three presentations, chosen in Phone › Call display:
 * - full screen: over the real wallpaper with a dark scrim, so any wallpaper
 *   stays readable;
 * - popup: a centred card over the app you were using;
 * - floating card: a compact card at the top that leaves the rest of the
 *   screen usable (touches outside it go to the app underneath).
 * Locked phones always use full screen. During a call you can minimise to
 * the floating card or expand back to full screen.
 *
 * Mute and audio routes go through Telecom (InCallService), which is what
 * actually controls call audio; AudioManager switches are overridden during
 * calls. Earpiece calls turn the screen off near the ear (proximity sensor).
 */
open class OeaInCallActivity : Activity() {
    private enum class Phase { SELECT_ACCOUNT, INCOMING, ONGOING, ENDED }

    private var presentation = OeaCallUi.FULLSCREEN
    private var observedCall: Call? = null
    private var builtPhase: Phase? = null
    private var builtPresentation: String? = null
    private var builtKeypad = false
    private var keypadVisible = false
    private var dtmfDigits = ""
    private var activeSinceMs = 0L
    private var endedAtMs = 0L
    private var lastDurationText = ""
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var palette: OeaPalette
    private var proximityLock: PowerManager.WakeLock? = null
    private var photoNumber: String? = null
    private var photo: Bitmap? = null
    private var nameNumber: String? = null
    private var cachedName: String? = null

    // Bound views of the current layout.
    private var statusView: TextView? = null
    private var nameView: TextView? = null
    private var numberView: TextView? = null
    private var simView: TextView? = null
    private var avatarView: ImageView? = null
    private var avatarInitial: TextView? = null
    private var dtmfView: TextView? = null
    private var controlsHost: LinearLayout? = null
    private var controlsSignature = ""

    private val ticker = object : Runnable {
        override fun run() {
            val call = OeaCallState.current ?: return
            bindStatus(call)
            if (runCatching { call.state }.getOrNull() == Call.STATE_ACTIVE) handler.postDelayed(this, 1000L)
        }
    }

    private val stateListener: () -> Unit = { render() }

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            runOnUiThread {
                if (state == Call.STATE_ACTIVE && activeSinceMs == 0L) activeSinceMs = System.currentTimeMillis()
                render()
            }
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            runOnUiThread { render() }
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        presentation = intent.getStringExtra(OeaCallUi.EXTRA_PRESENTATION) ?: defaultPresentation()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        palette = OeaTheme.resolve(this)
        render()
    }

    /** The full-screen class; the popup class overrides this. */
    protected open fun defaultPresentation(): String = OeaCallUi.FULLSCREEN

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val requested = intent.getStringExtra(OeaCallUi.EXTRA_PRESENTATION) ?: return
        if (requested != presentation) {
            presentation = requested
            builtPhase = null
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        OeaCallUi.screenVisible = true
        attachCall()
        OeaCallState.addListener(stateListener)
        OeaCallState.refreshNotification()
        render()
    }

    override fun onPause() {
        OeaCallUi.screenVisible = false
        handler.removeCallbacks(ticker)
        observedCall?.unregisterCallback(callback)
        observedCall = null
        OeaCallState.removeListener(stateListener)
        setProximity(false)
        OeaCallState.refreshNotification()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        setProximity(false)
        super.onDestroy()
    }

    @Deprecated("Back is handled by switching to the floating card")
    override fun onBackPressed() {
        val call = OeaCallState.current
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        when {
            call == null -> finish()
            keypadVisible -> {
                keypadVisible = false
                render()
            }
            presentation != OeaCallUi.COMPACT && !locked -> switchPresentation(OeaCallUi.COMPACT)
            else -> moveTaskToBack(true)
        }
    }

    private fun attachCall() {
        val current = OeaCallState.current ?: return
        if (runCatching { current.state }.getOrNull() == Call.STATE_ACTIVE && activeSinceMs == 0L) {
            activeSinceMs = System.currentTimeMillis()
        }
        if (observedCall !== current) {
            observedCall?.unregisterCallback(callback)
            observedCall = current
            runCatching { current.registerCallback(callback) }
        }
    }

    /**
     * Moves the call between full screen, popup and floating card. Popup and
     * floating card share one translucent activity, so switching between them
     * happens in place; full screen is its own (wallpaper) activity.
     */
    private fun switchPresentation(target: String) {
        if (target == presentation) return
        val next = OeaCallUi.intent(this, target)
        val effective = next.getStringExtra(OeaCallUi.EXTRA_PRESENTATION) ?: target
        if (next.component?.className == javaClass.name) {
            if (effective == presentation) return
            presentation = effective
            builtPhase = null
            render()
            return
        }
        if (runCatching { startActivity(next) }.isFailure) return
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    // ================================================================ render

    private fun render() {
        handler.removeCallbacks(ticker)
        val call = OeaCallState.current
        if (call == null) {
            if (builtPhase == Phase.ENDED) return
            setProximity(false)
            finish()
            return
        }
        attachCall()
        val state = runCatching { call.state }.getOrNull() ?: Call.STATE_DISCONNECTED
        val phase = when (state) {
            Call.STATE_SELECT_PHONE_ACCOUNT -> Phase.SELECT_ACCOUNT
            Call.STATE_RINGING -> Phase.INCOMING
            Call.STATE_DISCONNECTED -> Phase.ENDED
            else -> Phase.ONGOING
        }
        if (phase != builtPhase || presentation != builtPresentation || keypadVisible != builtKeypad) {
            build(phase, call)
        }
        bind(call, phase)
        if (state == Call.STATE_ACTIVE) handler.postDelayed(ticker, 1000L)
        if (phase == Phase.ENDED) {
            if (endedAtMs == 0L) endedAtMs = System.currentTimeMillis()
            handler.postDelayed({ if (!isFinishing) finish() }, 1400L)
        }
    }

    private fun build(phase: Phase, call: Call) {
        builtPhase = phase
        builtPresentation = presentation
        builtKeypad = keypadVisible
        controlsSignature = ""
        configureWindow()
        val compact = presentation == OeaCallUi.COMPACT
        val content = if (compact) buildCompact(phase, call) else buildFull(phase, call)
        setContentView(content)
        if (presentation == OeaCallUi.POPUP || compact) {
            content.alpha = 0f
            content.translationY = (if (compact) -dp(24) else dp(16)).toFloat()
            content.animate().alpha(1f).translationY(0f).setDuration(220L).start()
        }
    }

    private fun configureWindow() {
        when (presentation) {
            OeaCallUi.FULLSCREEN -> {
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
                // Transparent window over the wallpaper (theme shows the wallpaper).
                window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                window.attributes = window.attributes.apply { gravity = Gravity.CENTER; y = 0 }
                OeaInsets.edgeToEdge(window, lightBars = false)
            }
            OeaCallUi.POPUP -> {
                window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
                window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.attributes = window.attributes.apply {
                    gravity = Gravity.CENTER
                    y = 0
                    dimAmount = 0.5f
                }
            }
            else -> {
                // Floating card: only the card is our window; the rest of the
                // screen keeps working for the app underneath.
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
                window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                val width = minOf(resources.displayMetrics.widthPixels - dp(16), dp(480))
                window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
                window.attributes = window.attributes.apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = statusBarHeight() + dp(6)
                }
            }
        }
    }

    // ---------------------------------------------------------- full / popup

    private fun buildFull(phase: Phase, call: Call): View {
        val popup = presentation == OeaCallUi.POPUP
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(if (popup) 22 else 28), dp(24), dp(if (popup) 20 else 28))
        }
        simView = text("", 12.5f, 0xB3FFFFFF.toInt()).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        column.addView(simView, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(6) })
        statusView = text("", 14f, 0xE6FFFFFF.toInt(), bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = rounded(0x33FFFFFF, 18)
        }
        column.addView(statusView, LinearLayout.LayoutParams(-2, -2))
        if (!popup) column.addView(View(this), LinearLayout.LayoutParams(-1, 0, 0.6f))
        val avatarSize = dp(if (popup) 72 else 112)
        if (!(keypadVisible && popup)) {
            column.addView(buildAvatar(avatarSize), LinearLayout.LayoutParams(avatarSize, avatarSize).apply {
                topMargin = dp(if (popup) 18 else 8)
                bottomMargin = dp(16)
            })
        }
        nameView = text("", if (popup) 24f else 32f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        column.addView(nameView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = if (keypadVisible && popup) dp(14) else 0 })
        numberView = text("", 15f, 0xB3FFFFFF.toInt()).apply { gravity = Gravity.CENTER; maxLines = 1 }
        column.addView(numberView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        dtmfView = text("", 22f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            visibility = View.GONE
            letterSpacing = 0.08f
        }
        column.addView(dtmfView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        column.addView(View(this), if (popup) LinearLayout.LayoutParams(-1, dp(18)) else LinearLayout.LayoutParams(-1, 0, 1f))
        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        controlsHost = host
        column.addView(host, LinearLayout.LayoutParams(-1, -2))
        buildActions(phase, call, host, compact = false)

        if (!popup) {
            // Wallpaper stays visible; the scrim keeps text readable on any image.
            val root = FrameLayout(this).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0xB3000000.toInt(), 0x80000000.toInt(), 0xE6000000.toInt()))
            }
            root.addView(column, FrameLayout.LayoutParams(-1, -1))
            root.setOnApplyWindowInsetsListener { _, insets ->
                val bars = OeaInsets.bars(insets)
                column.setPadding(dp(24) + bars[0], dp(28) + bars[1], dp(24) + bars[2], dp(28) + bars[3])
                insets
            }
            return root
        }
        val scrim = FrameLayout(this).apply {
            isClickable = true
            // Tapping outside the popup tucks it into the floating card.
            setOnClickListener { if (OeaCallState.current != null) switchPresentation(OeaCallUi.COMPACT) }
        }
        val card = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(OeaTheme.blend(Color.rgb(18, 22, 30), palette.accent, 0.10f), Color.rgb(14, 17, 24))).apply {
                cornerRadius = dp(30).toFloat()
                setStroke(maxOf(1, dp(1)), 0x22FFFFFF)
            }
            elevation = dp(16).toFloat()
            isClickable = true
            addView(column, FrameLayout.LayoutParams(-1, -2))
        }
        scrim.addView(card, FrameLayout.LayoutParams(minOf(resources.displayMetrics.widthPixels - dp(32), dp(380)), -2, Gravity.CENTER))
        return scrim
    }

    private fun buildAvatar(size: Int): View {
        val frame = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(OeaTheme.blend(Color.rgb(30, 36, 48), palette.accent, 0.55f), Color.rgb(26, 32, 44))).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), OeaTheme.alpha(palette.accent, 0xAA))
            }
        }
        avatarInitial = text("", size / resources.displayMetrics.density * 0.38f, Color.WHITE).apply { gravity = Gravity.CENTER }
        frame.addView(avatarInitial, FrameLayout.LayoutParams(-1, -1))
        avatarView = ImageView(this).apply { visibility = View.GONE }
        frame.addView(avatarView, FrameLayout.LayoutParams(-1, -1))
        return frame
    }

    // ------------------------------------------------------------- compact

    private fun buildCompact(phase: Phase, call: Call): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                setColor(0xF2101620.toInt())
                cornerRadius = dp(26).toFloat()
                setStroke(maxOf(1, dp(1)), OeaTheme.alpha(palette.accent, 0x55))
            }
            elevation = dp(10).toFloat()
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            // Tap the card to open the full call screen.
            setOnClickListener { switchPresentation(OeaCallUi.FULLSCREEN) }
        }
        row.addView(buildAvatar(dp(44)), LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(10) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        nameView = text("", 16f, Color.WHITE, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        statusView = text("", 12.5f, 0xB3FFFFFF.toInt()).apply { maxLines = 1 }
        texts.addView(nameView)
        texts.addView(statusView)
        numberView = null
        simView = null
        dtmfView = null
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val host = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        controlsHost = host
        row.addView(host, LinearLayout.LayoutParams(-2, -2))
        card.addView(row, LinearLayout.LayoutParams(-1, -2))
        buildActions(phase, call, host, compact = true)
        return card
    }

    // ------------------------------------------------------------- actions

    private fun buildActions(phase: Phase, call: Call, host: LinearLayout, compact: Boolean) {
        host.removeAllViews()
        when (phase) {
            Phase.SELECT_ACCOUNT -> buildAccountChoice(call, host)
            Phase.INCOMING -> if (compact) buildIncomingCompact(call, host) else buildIncoming(call, host)
            Phase.ONGOING -> if (compact) buildOngoingCompact(call, host) else buildOngoing(call, host)
            Phase.ENDED -> if (!compact) host.addView(text("", 1f, Color.TRANSPARENT), LinearLayout.LayoutParams(1, dp(72)))
        }
        controlsSignature = signature(call)
    }

    private fun buildIncoming(call: Call, host: LinearLayout) {
        val number = OeaCallUi.displayNumber(call)
        val secondary = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        secondary.addView(pillButton("Silence", R.drawable.oic_notifications_off) { OeaCallUi.silenceRinger(this) },
            LinearLayout.LayoutParams(-2, dp(40)).apply { rightMargin = dp(8) })
        if (number != "Unknown caller" && number.any(Char::isDigit)) {
            secondary.addView(pillButton("Block", R.drawable.oic_block) { blockAndReject(call, number) }, LinearLayout.LayoutParams(-2, dp(40)))
        }
        host.addView(secondary, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(28) })
        val main = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        main.addView(roundAction(R.drawable.oic_call_end, "Decline", DECLINE_RED, dp(72)) {
            runCatching { call.reject(false, null) }.onFailure { toast("Unable to decline this call.") }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        main.addView(roundAction(R.drawable.oic_call, "Answer", ANSWER_GREEN, dp(72)) {
            runCatching { call.answer(VideoProfile.STATE_AUDIO_ONLY) }.onFailure { toast("Unable to answer this call.") }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        host.addView(main, LinearLayout.LayoutParams(-1, -2))
    }

    private fun buildIncomingCompact(call: Call, host: LinearLayout) {
        host.addView(circle(R.drawable.oic_call_end, DECLINE_RED, dp(44), "Decline") {
            runCatching { call.reject(false, null) }
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(8) })
        host.addView(circle(R.drawable.oic_call, ANSWER_GREEN, dp(44), "Answer") {
            runCatching { call.answer(VideoProfile.STATE_AUDIO_ONLY) }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
    }

    private fun buildOngoing(call: Call, host: LinearLayout) {
        if (keypadVisible) {
            host.addView(buildDtmfPad(call), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        } else {
            val muted = OeaCallState.isMuted()
            val holding = runCatching { call.state }.getOrNull() == Call.STATE_HOLDING
            val canHold = runCatching {
                ((call.details?.callCapabilities ?: 0) and Call.Details.CAPABILITY_HOLD) != 0
            }.getOrDefault(false)
            val route = OeaCallState.route()
            val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val rowOne = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowOne.addView(toggleAction(if (muted) R.drawable.oic_mic_off else R.drawable.oic_mic, if (muted) "Unmute" else "Mute", muted) {
                if (!OeaCallState.setMuted(!muted)) toast("Mute isn't available for this call.")
            }, LinearLayout.LayoutParams(0, -2, 1f))
            rowOne.addView(toggleAction(R.drawable.oic_dialpad, "Keypad", false) {
                keypadVisible = true
                render()
            }, LinearLayout.LayoutParams(0, -2, 1f))
            rowOne.addView(toggleAction(routeIcon(route), routeLabel(route), route == CallAudioState.ROUTE_SPEAKER) { chooseRoute() },
                LinearLayout.LayoutParams(0, -2, 1f))
            val rowTwo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowTwo.addView(toggleAction(if (holding) R.drawable.oic_play_arrow else R.drawable.oic_pause, if (holding) "Resume" else "Hold", holding, enabled = canHold || holding) {
                runCatching { if (holding) call.unhold() else call.hold() }.onFailure { toast("Hold isn't supported by this call.") }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            rowTwo.addView(toggleAction(R.drawable.oic_expand_more, "Minimise", false) {
                switchPresentation(OeaCallUi.COMPACT)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            rowTwo.addView(toggleAction(R.drawable.oic_open_in_full, if (presentation == OeaCallUi.POPUP) "Full screen" else "Popup", false) {
                switchPresentation(if (presentation == OeaCallUi.POPUP) OeaCallUi.FULLSCREEN else OeaCallUi.POPUP)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            grid.addView(rowOne, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
            grid.addView(rowTwo, LinearLayout.LayoutParams(-1, -2))
            host.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(26) })
        }
        host.addView(roundAction(R.drawable.oic_call_end, null, DECLINE_RED, dp(72)) {
            runCatching { call.disconnect() }.onFailure { toast("Unable to end this call.") }
        }, LinearLayout.LayoutParams(-2, -2))
    }

    private fun buildOngoingCompact(call: Call, host: LinearLayout) {
        val muted = OeaCallState.isMuted()
        val speaker = OeaCallState.route() == CallAudioState.ROUTE_SPEAKER
        host.addView(circle(if (muted) R.drawable.oic_mic_off else R.drawable.oic_mic, if (muted) palette.accent else 0x26FFFFFF,
            dp(40), if (muted) "Unmute" else "Mute", tint = if (muted) palette.onAccent else Color.WHITE) {
            OeaCallState.setMuted(!muted)
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(6) })
        host.addView(circle(R.drawable.oic_volume_up, if (speaker) palette.accent else 0x26FFFFFF,
            dp(40), if (speaker) "Speaker off" else "Speaker", tint = if (speaker) palette.onAccent else Color.WHITE) {
            OeaCallState.toggleSpeaker()
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(6) })
        host.addView(circle(R.drawable.oic_call_end, DECLINE_RED, dp(44), "End call") {
            runCatching { call.disconnect() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
    }

    private fun buildAccountChoice(call: Call, host: LinearLayout) {
        val telecom = getSystemService(TelecomManager::class.java)
        val accounts = runCatching { telecom?.callCapablePhoneAccounts.orEmpty() }.getOrDefault(emptyList())
        host.addView(text("Choose a SIM for this call", 15f, 0xCCFFFFFF.toInt()).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        accounts.forEachIndexed { index, handle ->
            val label = runCatching { telecom?.getPhoneAccount(handle)?.label?.toString().orEmpty() }.getOrDefault("").ifBlank { "SIM ${index + 1}" }
            host.addView(wideButton(label, palette.accent, palette.onAccent) {
                runCatching { call.phoneAccountSelected(handle, false) }
            }, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(8) })
        }
        host.addView(wideButton("Cancel", 0x33FFFFFF, Color.WHITE) {
            runCatching { call.disconnect() }
            finish()
        }, LinearLayout.LayoutParams(-1, dp(52)))
    }

    private fun buildDtmfPad(call: Call): View {
        val pad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val keySize = if (presentation == OeaCallUi.POPUP) dp(56) else dp(66)
        val keys = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("*", "0", "#"))
        keys.forEach { rowKeys ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            rowKeys.forEach { key ->
                row.addView(TextView(this).apply {
                    text = key
                    textSize = 26f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = rippleOval(0x1FFFFFFF)
                    isClickable = true
                    setOnClickListener {
                        dtmfDigits += key
                        dtmfView?.apply {
                            text = dtmfDigits
                            visibility = View.VISIBLE
                        }
                        runCatching {
                            call.playDtmfTone(key[0])
                            handler.postDelayed({ runCatching { call.stopDtmfTone() } }, 160L)
                        }
                    }
                }, LinearLayout.LayoutParams(keySize, keySize).apply { setMargins(dp(10), dp(4), dp(10), dp(4)) })
            }
            pad.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        pad.addView(pillButton("Hide keypad", R.drawable.oic_expand_more) {
            keypadVisible = false
            render()
        }, LinearLayout.LayoutParams(-2, dp(40)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(8)
        })
        return pad
    }

    private fun blockAndReject(call: Call, number: String) {
        val norm = OeaCallBlockRules.normalizeNumber(number)
        if (norm.isNotEmpty()) {
            val exact = OeaCallBlockRules.getExact(this).toMutableSet()
            exact.add(norm)
            OeaCallBlockRules.setRules(this, exact, OeaCallBlockRules.getPrefix(this), OeaCallBlockRules.getSuffix(this))
            OeaCallBlockRules.setEnabled(this, true)
            OeaCallBlockRules.recordBlocked(this, norm)
        }
        runCatching { call.reject(false, null) }
        toast("Caller blocked and declined.")
    }

    /** Speaker toggles; with Bluetooth or a headset connected, pick the route. */
    private fun chooseRoute() {
        val options = listOf(
            CallAudioState.ROUTE_EARPIECE to "Phone",
            CallAudioState.ROUTE_SPEAKER to "Speaker",
            CallAudioState.ROUTE_BLUETOOTH to "Bluetooth",
            CallAudioState.ROUTE_WIRED_HEADSET to "Wired headset",
        ).filter { OeaCallState.hasRoute(it.first) }
        if (options.size <= 2) {
            if (!OeaCallState.toggleSpeaker()) toast("Speaker isn't available right now.")
            return
        }
        val current = options.indexOfFirst { it.first == OeaCallState.route() }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Audio output")
            .setSingleChoiceItems(options.map { it.second }.toTypedArray(), current) { dialog, which ->
                OeaCallState.setRoute(options[which].first)
                dialog.dismiss()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ================================================================= bind

    private fun bind(call: Call, phase: Phase) {
        val number = OeaCallUi.displayNumber(call)
        if (nameNumber != number) {
            nameNumber = number
            cachedName = OeaCallUi.contactName(this, number)
        }
        val name = cachedName
        nameView?.text = name ?: number
        numberView?.apply {
            text = if (name != null) number else ""
            visibility = if (name != null) View.VISIBLE else View.GONE
        }
        dtmfView?.apply {
            text = dtmfDigits
            visibility = if (dtmfDigits.isNotEmpty() && phase == Phase.ONGOING) View.VISIBLE else View.GONE
        }
        bindAvatar(name ?: number, number)
        bindSim(call)
        bindStatus(call)
        // Rebuild the controls only when their state actually changed.
        val sig = signature(call)
        if (sig != controlsSignature) controlsHost?.let { buildActions(phase, call, it, presentation == OeaCallUi.COMPACT) }
        val earpiece = OeaCallState.route() == CallAudioState.ROUTE_EARPIECE
        setProximity(phase == Phase.ONGOING && presentation == OeaCallUi.FULLSCREEN && earpiece && !keypadVisible)
    }

    private fun signature(call: Call): String {
        val state = runCatching { call.state }.getOrNull()
        val caps = runCatching { call.details?.callCapabilities }.getOrNull()
        return "$state|${OeaCallState.isMuted()}|${OeaCallState.route()}|${OeaCallState.supportedRoutes()}|$caps|$keypadVisible|$presentation"
    }

    private fun bindStatus(call: Call) {
        val state = runCatching { call.state }.getOrNull()
        val number = OeaCallUi.displayNumber(call)
        val text = when (state) {
            Call.STATE_NEW -> "Preparing call…"
            Call.STATE_CONNECTING -> "Connecting…"
            Call.STATE_SELECT_PHONE_ACCOUNT -> "Choose SIM"
            Call.STATE_DIALING -> if (number.endsWith("#")) "Running code…" else "Calling…"
            Call.STATE_RINGING -> "Incoming call"
            Call.STATE_ACTIVE -> duration(call).also { lastDurationText = it }
            Call.STATE_HOLDING -> "On hold"
            Call.STATE_DISCONNECTING -> "Ending…"
            Call.STATE_DISCONNECTED -> if (lastDurationText.isNotEmpty()) "Call ended • $lastDurationText" else "Call ended"
            else -> "Call"
        }
        statusView?.text = text
        statusView?.setTextColor(when (state) {
            Call.STATE_ACTIVE -> 0xFF8CF0B8.toInt()
            Call.STATE_RINGING -> Color.WHITE
            Call.STATE_DISCONNECTED -> 0xFFFF9AA2.toInt()
            else -> 0xE6FFFFFF.toInt()
        })
    }

    private fun duration(call: Call): String {
        val connect = runCatching { call.details?.connectTimeMillis }.getOrNull()?.takeIf { it > 0L } ?: activeSinceMs
        val seconds = ((System.currentTimeMillis() - connect).coerceAtLeast(0L)) / 1000L
        val hours = seconds / 3600L
        return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, (seconds % 3600L) / 60L, seconds % 60L)
        else String.format(Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L)
    }

    private fun bindSim(call: Call) {
        val view = simView ?: return
        val telecom = getSystemService(TelecomManager::class.java)
        val accounts = runCatching { telecom?.callCapablePhoneAccounts.orEmpty() }.getOrDefault(emptyList())
        val handle = runCatching { call.details?.accountHandle }.getOrNull()
        val label = if (accounts.size > 1 && handle != null) {
            runCatching { telecom?.getPhoneAccount(handle)?.label?.toString() }.getOrNull()
        } else null
        view.text = label.orEmpty()
        view.visibility = if (label.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun bindAvatar(title: String, number: String) {
        avatarInitial?.text = title.firstOrNull { it.isLetter() }?.uppercaseChar()?.toString() ?: "☏"
        if (photoNumber != number) {
            photoNumber = number
            photo = loadContactPhoto(number)
        }
        val bitmap = photo
        avatarView?.apply {
            if (bitmap != null) {
                setImageBitmap(bitmap)
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }
        avatarInitial?.visibility = if (bitmap != null) View.GONE else View.VISIBLE
    }

    private fun loadContactPhoto(number: String): Bitmap? {
        if (number.isBlank() || number == "Unknown caller") return null
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        return runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            val photoUri = contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.PHOTO_URI), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: return null
            contentResolver.openInputStream(Uri.parse(photoUri))?.use { BitmapFactory.decodeStream(it) }?.let { circleCrop(it) }
        }.getOrNull()
    }

    private fun circleCrop(source: Bitmap): Bitmap {
        val size = minOf(source.width, source.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                val matrix = android.graphics.Matrix()
                matrix.setTranslate(-(source.width - size) / 2f, -(source.height - size) / 2f)
                setLocalMatrix(matrix)
            }
        }
        Canvas(output).drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return output
    }

    // ============================================================ proximity

    /** Turns the screen off near the ear during earpiece calls, like the system dialer. */
    private fun setProximity(enabled: Boolean) {
        if (enabled) {
            if (proximityLock?.isHeld == true) return
            val power = getSystemService(PowerManager::class.java) ?: return
            if (!power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
            proximityLock = runCatching {
                power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "OEA:call-proximity").apply {
                    setReferenceCounted(false)
                    acquire(60 * 60 * 1000L)
                }
            }.getOrNull()
        } else {
            proximityLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
            proximityLock = null
        }
    }

    // ============================================================== widgets

    private fun routeIcon(route: Int) = when (route) {
        CallAudioState.ROUTE_BLUETOOTH -> R.drawable.oic_bluetooth
        CallAudioState.ROUTE_WIRED_HEADSET -> R.drawable.oic_headset
        CallAudioState.ROUTE_SPEAKER -> R.drawable.oic_volume_up
        else -> R.drawable.oic_volume_up
    }

    private fun routeLabel(route: Int): String {
        val multi = OeaCallState.hasRoute(CallAudioState.ROUTE_BLUETOOTH) || OeaCallState.hasRoute(CallAudioState.ROUTE_WIRED_HEADSET)
        return when (route) {
            CallAudioState.ROUTE_BLUETOOTH -> "Bluetooth"
            CallAudioState.ROUTE_WIRED_HEADSET -> "Headset"
            CallAudioState.ROUTE_SPEAKER -> "Speaker on"
            else -> if (multi) "Audio" else "Speaker"
        }
    }

    private fun text(value: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = true
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun rippleOval(color: Int) = android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(0x33FFFFFF),
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) },
        null,
    )

    private fun circle(icon: Int, color: Int, size: Int, description: String, tint: Int = Color.WHITE, onClick: () -> Unit): View =
        FrameLayout(this).apply {
            background = rippleOval(color)
            contentDescription = description
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(ImageView(this@OeaInCallActivity).apply {
                setImageDrawable(getDrawable(icon)?.mutate()?.apply { setTint(tint) })
            }, FrameLayout.LayoutParams(size * 11 / 24, size * 11 / 24, Gravity.CENTER))
        }

    /** Big round action with an optional caption (Answer, Decline, End). */
    private fun roundAction(icon: Int, label: String?, color: Int, size: Int, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(circle(icon, color, size, label ?: "End call", onClick = onClick), LinearLayout.LayoutParams(size, size))
            if (label != null) addView(text(label, 13f, 0xD9FFFFFF.toInt()).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        }

    /** Control tile: icon in a circle that fills with the accent when active, caption below. */
    private fun toggleAction(icon: Int, label: String, active: Boolean, enabled: Boolean = true, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            alpha = if (enabled) 1f else 0.4f
            val size = dp(60)
            addView(circle(icon, if (active) palette.accent else 0x26FFFFFF, size, label,
                tint = if (active) palette.onAccent else Color.WHITE) { if (enabled) onClick() },
                LinearLayout.LayoutParams(size, size))
            addView(text(label, 12.5f, 0xD9FFFFFF.toInt()).apply { gravity = Gravity.CENTER; maxLines = 1 },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        }

    private fun pillButton(label: String, icon: Int, onClick: () -> Unit): View = TextView(this).apply {
        text = label
        textSize = 13.5f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(16), 0, dp(18), 0)
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x33FFFFFF), rounded(0x26FFFFFF, 20), null,
        )
        compoundDrawablePadding = dp(8)
        getDrawable(icon)?.mutate()?.apply {
            setTint(Color.WHITE)
            setBounds(0, 0, dp(18), dp(18))
        }?.let { setCompoundDrawablesRelative(it, null, null, null) }
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun wideButton(label: String, bg: Int, fg: Int, onClick: () -> Unit): View = TextView(this).apply {
        text = label
        textSize = 15f
        setTextColor(fg)
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x33FFFFFF), rounded(bg, 26), null,
        )
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        val ANSWER_GREEN = Color.rgb(36, 178, 106)
        val DECLINE_RED = Color.rgb(232, 64, 76)
    }
}

/**
 * The popup and floating-card presentations. A separate, translucent activity
 * so the app underneath stays visible (an opaque activity would leave a black
 * screen around the card).
 */
class OeaInCallPopupActivity : OeaInCallActivity() {
    override fun defaultPresentation(): String =
        OeaCallUi.mode(this).takeIf { it != OeaCallUi.FULLSCREEN } ?: OeaCallUi.POPUP
}
