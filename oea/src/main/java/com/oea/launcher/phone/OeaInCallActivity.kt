package com.oea.launcher.phone

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.oea.launcher.callblocker.OeaCallBlockRules
import java.util.Locale

class OeaInCallActivity : Activity() {
    private var observedCall: Call? = null
    private var keypadVisible = false
    private var dtmfDigits = ""
    private var activeSinceMs = 0L
    private var timerView: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

    private val durationTicker = object : Runnable {
        override fun run() {
            val call = OeaCallState.current
            if (call != null && call.state == Call.STATE_ACTIVE) {
                val connectMs = call.details?.connectTimeMillis?.takeIf { it > 0L } ?: activeSinceMs
                val elapsedSec = ((System.currentTimeMillis() - connectMs).coerceAtLeast(0L)) / 1000L
                val mins = elapsedSec / 60L
                val secs = elapsedSec % 60L
                timerView?.text = String.format(Locale.US, "Call connected • %02d:%02d", mins, secs)
                handler.postDelayed(this, 1000L)
            }
        }
    }

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            runOnUiThread {
                if (state == Call.STATE_ACTIVE && activeSinceMs == 0L) {
                    activeSinceMs = System.currentTimeMillis()
                }
                render()
            }
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            runOnUiThread { render() }
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
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
        render()
    }

    override fun onResume() {
        super.onResume()
        attachCall()
        render()
    }

    override fun onPause() {
        handler.removeCallbacks(durationTicker)
        observedCall?.unregisterCallback(callback)
        observedCall = null
        super.onPause()
    }

    private fun attachCall() {
        val current = OeaCallState.current ?: return
        if (current.state == Call.STATE_ACTIVE && activeSinceMs == 0L) {
            activeSinceMs = System.currentTimeMillis()
        }
        if (observedCall !== current) {
            observedCall?.unregisterCallback(callback)
            observedCall = current
            runCatching { current.registerCallback(callback) }
        }
    }

    private fun extractDisplayNumber(call: Call): String {
        val handle = call.details?.handle ?: return "Unknown caller"
        val ssp = handle.schemeSpecificPart.orEmpty()
        val frag = handle.fragment
        val full = if (frag != null) "$ssp#$frag" else ssp
        return full.ifBlank { "Unknown caller" }
    }

    private fun lookupContactName(number: String): String? {
        if (number.isBlank() || number == "Unknown caller" || number.startsWith("*") || number.startsWith("#")) return null
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c: Cursor ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        }.getOrNull()
    }

    private fun render() {
        handler.removeCallbacks(durationTicker)
        val call = OeaCallState.current
        if (call == null) {
            finish()
            return
        }
        val mode = getSharedPreferences("oea_phone_prefs", MODE_PRIVATE).getString("call_ui_mode", "fullscreen")
        val isCompact = mode == "popup" || mode == "compact"
        val audio = getSystemService(AudioManager::class.java)
        val rawNumber = extractDisplayNumber(call)
        val contactName = lookupContactName(rawNumber)
        val primaryTitle = contactName ?: rawNumber
        val secondaryNumber = if (contactName != null) rawNumber else null

        val stateLabel = when (call.state) {
            Call.STATE_NEW -> "Preparing call…"
            Call.STATE_CONNECTING -> "Connecting…"
            Call.STATE_SELECT_PHONE_ACCOUNT -> "Choose SIM / phone account"
            Call.STATE_DIALING -> if (rawNumber.endsWith("#")) "Running USSD / dialing…" else "Dialing…"
            Call.STATE_RINGING -> "Incoming call"
            Call.STATE_ACTIVE -> "Call connected"
            Call.STATE_HOLDING -> "Call on hold"
            Call.STATE_DISCONNECTED -> "Call ended"
            else -> "Call"
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(if (isCompact) 20 else 36), dp(22), dp(if (isCompact) 18 else 28))
            setBackgroundColor(0xFF0C0F15.toInt())
        }

        // Top status pill
        val statusPill = TextView(this).apply {
            text = stateLabel
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(
                when (call.state) {
                    Call.STATE_ACTIVE -> 0xFF7BE0B1.toInt()
                    Call.STATE_RINGING -> 0xFF8AB4F8.toInt()
                    else -> 0xFFB5C0D0.toInt()
                }
            )
            setPadding(dp(16), dp(7), dp(16), dp(7))
            background = rounded(0xFF171E29.toInt(), 20)
        }
        timerView = statusPill
        root.addView(statusPill, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(16) })

        // Avatar circle
        if (!isCompact || !keypadVisible) {
            val avatarSize = dp(if (isCompact) 58 else 78)
            val initial = primaryTitle.firstOrNull { it.isLetter() }?.uppercaseChar()?.toString() ?: "☏"
            root.addView(TextView(this).apply {
                text = initial
                textSize = if (isCompact) 22f else 30f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF1E293B.toInt())
                    setStroke(dp(2), 0xFF2D8C74.toInt())
                }
            }, LinearLayout.LayoutParams(avatarSize, avatarSize).apply { bottomMargin = dp(14) })
        }

        // Caller Name / Number
        root.addView(TextView(this).apply {
            text = primaryTitle
            textSize = if (isCompact) 22f else 28f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            maxLines = 2
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })

        if (secondaryNumber != null) {
            root.addView(TextView(this).apply {
                text = secondaryNumber
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(0xFF9AA2B1.toInt())
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }

        if (dtmfDigits.isNotEmpty()) {
            root.addView(TextView(this).apply {
                text = dtmfDigits
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(0xFF7BE0B1.toInt())
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = rounded(0xFF171E29.toInt(), 12)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(8) })
        }

        if (!isCompact) {
            root.addView(android.view.View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            root.addView(android.view.View(this), LinearLayout.LayoutParams(-1, dp(12)))
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        when {
            call.state == Call.STATE_SELECT_PHONE_ACCOUNT -> {
                val telecom = getSystemService(TelecomManager::class.java)
                val accounts = runCatching { telecom?.callCapablePhoneAccounts.orEmpty() }.getOrDefault(emptyList())
                accounts.forEachIndexed { idx, handle ->
                    val label = runCatching { telecom?.getPhoneAccount(handle)?.label?.toString().orEmpty() }.getOrDefault("")
                        .ifBlank { "SIM ${idx + 1}" }
                    controls.addView(
                        styledButton("Call with $label", 0xFF2D8C74.toInt()) {
                            runCatching { call.phoneAccountSelected(handle, false) }
                            render()
                        },
                        LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) }
                    )
                }
                controls.addView(
                    styledButton("Cancel Call", 0xFFD8434E.toInt()) {
                        runCatching { call.disconnect() }
                        finish()
                    },
                    LinearLayout.LayoutParams(-1, dp(48))
                )
            }
            call.state == Call.STATE_RINGING -> {
                val incomingRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                incomingRow.addView(
                    styledButton("DECLINE", 0xFFD8434E.toInt()) {
                        runCatching { call.reject(false, null) }.onFailure { toast("Unable to reject call.") }
                        finish()
                    },
                    LinearLayout.LayoutParams(0, dp(56), 1f).apply { rightMargin = dp(8) }
                )
                incomingRow.addView(
                    styledButton("ANSWER", 0xFF249E78.toInt()) {
                        runCatching { call.answer(VideoProfile.STATE_AUDIO_ONLY) }.onFailure { toast("Unable to answer call.") }
                        render()
                    },
                    LinearLayout.LayoutParams(0, dp(56), 1f).apply { leftMargin = dp(8) }
                )
                controls.addView(incomingRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

                if (rawNumber != "Unknown caller" && rawNumber.any(Char::isDigit)) {
                    controls.addView(
                        styledButton("Block & Reject Caller", 0xFF232B3A.toInt()) {
                            val norm = OeaCallBlockRules.normalizeNumber(rawNumber)
                            if (norm.isNotEmpty()) {
                                val exact = OeaCallBlockRules.getExact(this).toMutableSet()
                                exact.add(norm)
                                OeaCallBlockRules.setRules(this, exact, OeaCallBlockRules.getPrefix(this), OeaCallBlockRules.getSuffix(this))
                                OeaCallBlockRules.setEnabled(this, true)
                                OeaCallBlockRules.recordBlocked(this, norm)
                            }
                            runCatching { call.reject(false, null) }
                            toast("Caller blocked and rejected.")
                            finish()
                        },
                        LinearLayout.LayoutParams(-1, dp(44))
                    )
                }
            }
            call.state != Call.STATE_DISCONNECTED -> {
                if (keypadVisible) {
                    controls.addView(buildDtmfPad(call), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
                }

                val isMuted = audio?.isMicrophoneMute == true
                val isSpeaker = audio?.isSpeakerphoneOn == true
                val isHolding = call.state == Call.STATE_HOLDING

                val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                row1.addView(
                    styledButton(if (isMuted) "UNMUTE" else "MUTE", if (isMuted) 0xFF2D8C74.toInt() else 0xFF1E2636.toInt()) {
                        runCatching { audio?.isMicrophoneMute = !isMuted }
                        render()
                    },
                    LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) }
                )
                row1.addView(
                    styledButton(if (isSpeaker) "EARPIECE" else "SPEAKER", if (isSpeaker) 0xFF2D8C74.toInt() else 0xFF1E2636.toInt()) {
                        runCatching { audio?.isSpeakerphoneOn = !isSpeaker }
                        render()
                    },
                    LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) }
                )
                controls.addView(row1, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

                val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                row2.addView(
                    styledButton(if (isHolding) "RESUME" else "HOLD", if (isHolding) 0xFF2D8C74.toInt() else 0xFF1E2636.toInt()) {
                        runCatching {
                            if (isHolding) call.unhold() else call.hold()
                        }.onFailure { toast("Hold is not supported by this phone account.") }
                        render()
                    },
                    LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) }
                )
                row2.addView(
                    styledButton(if (keypadVisible) "HIDE PAD" else "KEYPAD", if (keypadVisible) 0xFF2D8C74.toInt() else 0xFF1E2636.toInt()) {
                        keypadVisible = !keypadVisible
                        render()
                    },
                    LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) }
                )
                controls.addView(row2, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

                controls.addView(
                    styledButton("END CALL", 0xFFD8434E.toInt()) {
                        runCatching { call.disconnect() }.onFailure { toast("Unable to end call.") }
                        finish()
                    },
                    LinearLayout.LayoutParams(-1, dp(54))
                )
            }
            else -> {
                controls.addView(
                    styledButton("CLOSE", 0xFF242D3B.toInt()) { finish() },
                    LinearLayout.LayoutParams(-1, dp(50))
                )
            }
        }

        root.addView(controls, LinearLayout.LayoutParams(-1, -2))

        if (isCompact) {
            root.background = GradientDrawable().apply {
                setColor(Color.rgb(12, 15, 21))
                cornerRadius = dp(26).toFloat()
                setStroke(dp(1), Color.rgb(55, 67, 84))
            }
            val scroll = ScrollView(this).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                addView(root, android.widget.FrameLayout.LayoutParams(-1, -2))
            }
            setContentView(scroll)
        } else {
            val scroll = ScrollView(this).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                addView(root, android.widget.FrameLayout.LayoutParams(-1, -1))
            }
            setContentView(scroll)
        }
        applyCallWindowMode(mode)

        if (call.state == Call.STATE_ACTIVE) {
            handler.post(durationTicker)
        }
    }

    private fun buildDtmfPad(call: Call): LinearLayout {
        val pad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = rounded(0xFF141A24.toInt(), 18)
        }
        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("*", "0", "#")
        )
        rows.forEach { rowDigits ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            rowDigits.forEach { d ->
                row.addView(Button(this).apply {
                    text = d
                    textSize = 18f
                    setTypeface(typeface, Typeface.BOLD)
                    isAllCaps = false
                    setPadding(0, 0, 0, 0)
                    minHeight = 0
                    minimumHeight = 0
                    setTextColor(0xFFFFFFFF.toInt())
                    background = rounded(0xFF1F2736.toInt(), 14)
                    setOnClickListener {
                        dtmfDigits += d
                        runCatching {
                            call.playDtmfTone(d[0])
                            handler.postDelayed({ runCatching { call.stopDtmfTone() } }, 160L)
                        }
                        render()
                    }
                }, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                })
            }
            pad.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        return pad
    }

    private fun applyCallWindowMode(mode: String?) {
        if (mode == "popup" || mode == "compact") {
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val metrics = resources.displayMetrics
            val width = minOf(dp(if (mode == "compact") 320 else 360), metrics.widthPixels - dp(24))
            val height = minOf(dp(if (mode == "compact") 430 else 540), metrics.heightPixels - dp(72))
            window.setLayout(width.coerceAtLeast(dp(260)), height.coerceAtLeast(dp(300)))
            window.attributes = window.attributes.apply {
                gravity = Gravity.CENTER
                dimAmount = 0.45f
            }
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setBackgroundDrawable(ColorDrawable(Color.rgb(12, 15, 21)))
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }
    }

    private fun styledButton(label: String, bgColor: Int, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(0xFFFFFFFF.toInt())
        background = rounded(bgColor, 18)
        setOnClickListener { action() }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
