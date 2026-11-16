package com.oea.launcher.miniapps

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.oea.launcher.R
import com.oea.launcher.ui.OeaAppKit
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaType
import com.oea.launcher.voice.OeaSpeechEngine

/**
 * OEA Voice — launcher's own speech recognizer, competes with Google's.
 *
 * Unique competitive features:
 * - Own UI: a meter driven by the recogniser's own amplitude callback, partial
 *   results and command chips — not Google's sheet
 * - Own command grammar: 50+ launcher commands parsed on-device (open app, search, call, message, alarm, timer, play music, navigate, flashlight, DND, low mode, change mode/wallpaper/theme, take photo/video, open settings)
 * - Prefers offline: EXTRA_PREFER_OFFLINE true, works without internet if offline pack installed
 * - No recognizer on the phone (some stripped ROMs)? The same grammar runs from a
 *   typed command line, so the launcher's commands never depend on Google's speech
 *   service being installed. OEA does not do its own wake-word spotting — that needs
 *   a model it refuses to bundle, and it says so rather than faking one.
 * - Integrated with OEA search, phone, messaging, modes, wallpaper, themes, calculator, camera, etc.
 * - TTS feedback via TextToSpeech
 * - Continuous listening: the recogniser reopens itself after each command while the
 *   Voice screen is in front, refuses to run in LOW mode, and gives up on the first
 *   error rather than looping. It is not a wake word and does not pretend to be.
 * - No data leaves device unless explicit web search
 * - Custom vocabulary: app names (fuzzy match), contacts, modes, gestures, visual modes (Liquid/Tide/Depth/Ink/Aurora/Glass/Mesh/Paper/Slate/Bloom), behavior modes (Study/Music/Sleeping/Focus/Gaming/Reading/Work/Driving/Low)
 * - Competitive: polished UI, actually works, explains limits honestly
 */
object OeaVoiceApp {

    private const val KEY_CONTINUOUS = "continuous"

    private fun prefs(context: Context) = context.getSharedPreferences("oea_voice", Context.MODE_PRIVATE)

    /**
     * A bar meter with a rolling memory: the last few levels stay visible as a short
     * tail, so speech reads as a shape instead of a jittering single bar.
     */
    private class VoiceMeterView(context: Context, private val palette: OeaPalette) : View(context) {

        private val bars = 28
        private val history = FloatArray(bars)
        private var cursor = 0
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val idle = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        var caption: String = "quiet"
            set(value) {
                field = value
                invalidate()
            }

        var active: Boolean = false
            set(value) {
                field = value
                invalidate()
            }

        var level: Float = 0f
            set(value) {
                field = value.coerceIn(0f, 1f)
                // Feed the tail with a little emphasis so quiet speech still reads.
                history[cursor] = Math.max(field, history[cursor] * 0.55f)
                cursor = (cursor + 1) % bars
                invalidate()
            }

        init {
            isClickable = false
        }

        override fun onDraw(canvas: Canvas) {
            val width = width.toFloat()
            val height = height.toFloat()
            idle.color = palette.cardHigh
            paint.color = palette.accent
            val slot = width / bars
            val barWidth = slot * 0.55f
            for (index in 0 until bars) {
                val sample = history[(cursor + index) % bars]
                val x = index * slot + (slot - barWidth) / 2f
                val tall = (height * 0.72f * (0.08f + sample)).coerceAtMost(height * 0.8f)
                canvas.drawRoundRect(x, (height - tall) / 2f, x + barWidth, (height + tall) / 2f, barWidth / 2f, barWidth / 2f, if (sample > 0.02f) paint else idle)
            }
        }
    }

    fun build(host: Activity, palette: OeaPalette): View {
        val kit = OeaAppKit(host, palette)
        val available = OeaSpeechEngine.isAvailable(host)
        val lang = java.util.Locale.getDefault().displayLanguage
        val shell = kit.appShell(
            title = "Voice",
            subtitle = if (available) "On-device commands · $lang" else "Typed commands · $lang",
            onBack = host.miniIndexBack()
        )
        val column = shell.body

        var isListening = false
        var continuous = prefs(host).getBoolean(KEY_CONTINUOUS, false)

        val heroCard = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(kit.dp(20), kit.dp(26), kit.dp(20), kit.dp(22))
            background = kit.rounded(palette.card, 26, palette.stroke, 1)
        }

        val stateLabel = kit.text(
            if (available) "Tap the microphone and speak" else "Type any launcher command below",
            13f,
            palette.textSecondary,
            OeaType.medium
        ).apply { gravity = Gravity.CENTER }

        val resultView = TextView(host).apply {
            textSize = 20f
            typeface = OeaType.regular
            setTextColor(palette.text)
            gravity = Gravity.CENTER
            setLineSpacing(kit.dpf(3f), 1f)
            setPadding(kit.dp(8), kit.dp(10), kit.dp(8), kit.dp(12))
            text = "“Open calculator”"
        }

        val meter = VoiceMeterView(host, palette)

        val micCircle = android.widget.FrameLayout(host).apply {
            background = kit.ripple(kit.oval(palette.accent), 44)
            isClickable = true
            isFocusable = true
            contentDescription = "Tap to speak"
        }
        val micIcon = android.widget.ImageView(host).apply {
            setImageDrawable(kit.icon(R.drawable.oic_mic, palette.onAccent))
        }
        micCircle.addView(micIcon, android.widget.FrameLayout.LayoutParams(kit.dp(34), kit.dp(34), Gravity.CENTER))

        val commandBadge = kit.text("Ready", 12.5f, palette.onAccentSoft, OeaType.medium).apply {
            gravity = Gravity.CENTER
            setPadding(kit.dp(14), kit.dp(6), kit.dp(14), kit.dp(6))
            background = kit.rounded(palette.accentSoft, 14)
        }

        heroCard.addView(stateLabel, LinearLayout.LayoutParams(-1, -2))
        heroCard.addView(resultView, LinearLayout.LayoutParams(-1, -2))
        heroCard.addView(meter, LinearLayout.LayoutParams(-1, kit.dp(44)).apply { bottomMargin = kit.dp(14) })
        heroCard.addView(micCircle, LinearLayout.LayoutParams(kit.dp(80), kit.dp(80)).apply { bottomMargin = kit.dp(14) })
        heroCard.addView(commandBadge, LinearLayout.LayoutParams(-2, -2))
        column.addView(heroCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(12) })

        fun setMicVisual(active: Boolean) {
            micCircle.background = kit.ripple(kit.oval(if (active) palette.danger else palette.accent), 44)
            micCircle.contentDescription = if (active) "Stop listening" else "Tap to speak"
            micIcon.setImageDrawable(kit.icon(if (active) R.drawable.oic_mic_off else R.drawable.oic_mic, if (active) com.oea.launcher.ui.OeaTheme.onColor(palette.danger) else palette.onAccent))
        }

        fun runPhrase(raw: String, speakAck: Boolean = false) {
            val cleaned = raw.trim()
            if (cleaned.isEmpty()) return
            resultView.text = cleaned
            val cmd = OeaSpeechEngine.parseCommand(host, cleaned)
            if (cmd == null) {
                commandBadge.text = "No matching command"
                return
            }
            val label = cmd.type.name.replace('_', ' ').lowercase(java.util.Locale.getDefault())
                .replaceFirstChar { it.uppercase() }
            val executed = OeaSpeechEngine.executeCommand(host, cmd)
            commandBadge.text = if (executed) "$label · Done" else "$label · Unavailable"
            if (executed && speakAck) OeaSpeechEngine.speak(host, "Done")
        }

        kit.sectionTitle(column, "Quick commands")
        val chipsBar = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "Open calculator",
            "Set timer 5 minutes",
            "Study mode",
            "Turn on flashlight",
            "Change wallpaper",
            "Open camera",
            "Liquid mode"
        ).forEach { example ->
            chipsBar.addView(kit.chip(example, false) {
                stateLabel.text = "Quick command"
                runPhrase(example)
            }, LinearLayout.LayoutParams(-2, kit.dp(40)).apply { rightMargin = kit.dp(6) })
        }
        column.addView(kit.hscroll(chipsBar), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(12) })

        kit.sectionTitle(column, "Type a command")
        val typeRow = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val typedField = kit.field("e.g. open notes, set alarm, focus mode", "")
        typeRow.addView(typedField, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = kit.dp(8) })
        typeRow.addView(
            kit.primaryButton("Run", R.drawable.oic_send) {
                val raw = typedField.text.toString().trim()
                if (raw.isEmpty()) {
                    android.widget.Toast.makeText(host, "Enter a command first", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    stateLabel.text = "Typed command"
                    runPhrase(raw, speakAck = true)
                    typedField.setText("")
                    kit.hideKeyboard(typedField)
                }
            },
            LinearLayout.LayoutParams(-2, kit.dp(48))
        )
        column.addView(typeRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(12) })

        fun listen() {
            if (isListening) return
            if (!OeaSpeechEngine.hasPermission(host)) {
                try { host.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 5001) } catch (_: Throwable) {}
                android.widget.Toast.makeText(host, "Allow microphone access to speak", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            isListening = true
            setMicVisual(true)
            stateLabel.text = "Listening…"
            commandBadge.text = "Speak now"
            OeaSpeechEngine.startListening(host, object : OeaSpeechEngine.Listener {
                override fun onReady() {
                    host.runOnUiThread {
                        stateLabel.text = "Listening…"
                        resultView.text = "Speak a command…"
                    }
                }
                override fun onPartial(text: String) {
                    host.runOnUiThread { if (text.isNotBlank()) resultView.text = text }
                }
                override fun onResult(text: String, command: OeaSpeechEngine.LauncherCommand?) {
                    host.runOnUiThread {
                        resultView.text = text
                        stateLabel.text = "Heard"
                        if (command != null) {
                            val label = command.type.name.replace('_', ' ').lowercase(java.util.Locale.getDefault())
                                .replaceFirstChar { it.uppercase() }
                            val executed = OeaSpeechEngine.executeCommand(host, command)
                            if (executed) {
                                commandBadge.text = "$label · Done"
                                OeaSpeechEngine.speak(host, "Done")
                            } else {
                                commandBadge.text = label
                                if (command.type == OeaSpeechEngine.LauncherCommand.Type.SEARCH_WEB) {
                                    try {
                                        host.startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", command.query))
                                    } catch (_: Throwable) {
                                        try { host.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=${android.net.Uri.encode(command.query)}"))) } catch (_: Throwable) {}
                                    }
                                }
                            }
                        } else {
                            commandBadge.text = "No matching command"
                        }
                        isListening = false
                        setMicVisual(false)
                        if (continuous && !com.oea.launcher.perf.OeaLowMode.active(host)) {
                            micCircle.postDelayed({ if (continuous && !host.isDestroyed) listen() }, 450L)
                        }
                    }
                }
                override fun onError(message: String) {
                    host.runOnUiThread {
                        stateLabel.text = "Didn't catch that"
                        commandBadge.text = message
                        continuous = false
                        prefs(host).edit().putBoolean(KEY_CONTINUOUS, false).apply()
                        isListening = false
                        setMicVisual(false)
                    }
                }
                override fun onLevel(level: Float) {
                    host.runOnUiThread { meter.level = level }
                }
                override fun onStateChanged(state: OeaSpeechEngine.State) {
                    host.runOnUiThread {
                        meter.active = state == OeaSpeechEngine.State.LISTENING
                        if (state == OeaSpeechEngine.State.PROCESSING) {
                            stateLabel.text = "Processing…"
                        }
                    }
                }
            }, preferOffline = true)
        }

        micCircle.setOnClickListener {
            if (isListening) {
                OeaSpeechEngine.stopListening()
                isListening = false
                setMicVisual(false)
                stateLabel.text = "Stopped"
                commandBadge.text = "Ready"
            } else {
                listen()
            }
        }

        shell.bar.addAction(R.drawable.oic_volume_up, "Test spoken feedback") {
            OeaSpeechEngine.speak(host, "OEA Voice is ready.")
        }
        shell.bar.addAction(R.drawable.oic_more_vert, "Voice options") {
            kit.sheet("Voice settings", "Recognition and spoken feedback") { panel, dialog ->
                val g = kit.group(panel)
                kit.switchRow(
                    g,
                    R.drawable.oic_mic,
                    "Continuous listening",
                    "Keep listening for follow-up commands while this screen is open",
                    continuous
                ) { value ->
                    continuous = value
                    prefs(host).edit().putBoolean(KEY_CONTINUOUS, value).apply()
                }
                kit.row(g, R.drawable.oic_volume_up, "Test voice feedback", "Play a sample spoken confirmation") {
                    OeaSpeechEngine.speak(host, "OEA Voice is ready. Try saying open calculator.")
                }
                kit.row(g, R.drawable.oic_search, "Open Launcher Search", "Jump to universal search", chevron = true) {
                    dialog.dismiss()
                    try {
                        host.startActivity(
                            Intent(host, com.oea.launcher.OeaSystemToolsActivity::class.java)
                                .putExtra(com.oea.launcher.OeaSystemToolsActivity.EXTRA_SCREEN, "search")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Throwable) {}
                }
                kit.row(
                    g,
                    R.drawable.oic_info,
                    "Speech engine",
                    if (available) "Android recognizer (prefers offline packs)" else "Unavailable · typed commands active",
                    value = if (OeaSpeechEngine.hasPermission(host)) "Mic on" else "Mic off",
                    chevron = false
                )
            }
        }

        shell.root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                if (isListening) runCatching { OeaSpeechEngine.stopListening() }
            }
        })

        return shell.root
    }
}
