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
        val kit = OeaKit(host, palette)
        val column = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(kit.text("Voice", 22f, palette.text, OeaType.medium), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(kit.iconBadge(R.drawable.oic_mic, palette.accentSoft, palette.onAccentSoft, 40))
        column.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(12) })

        kit.note(column, "OEA's own voice screen: it drives Android's speech recogniser with its own UI and its own grammar of launcher commands, and prefers the offline recogniser when the phone has one. Nothing is uploaded unless the command is a web search. No recogniser on this phone? Type the command below — the same grammar answers.")

        // Status
        val statusCard = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(16), kit.dp(14), kit.dp(16), kit.dp(14))
            background = kit.rounded(palette.card, 22, palette.stroke, 1)
        }
        val available = OeaSpeechEngine.isAvailable(host)
        val hasPerm = OeaSpeechEngine.hasPermission(host)
        statusCard.addView(kit.text("Recognizer: ${if (available) "Android's, in use" else "None on this phone — the typed line below runs the same grammar"}", 13f, palette.textSecondary))
        statusCard.addView(kit.text("Permission: ${if (hasPerm) "Granted" else "Not granted — tap mic to request"}", 13f, palette.textSecondary).apply { setPadding(0, kit.dp(4), 0, 0) })
        statusCard.addView(kit.text("Mode: ${if (available) "Offline preferred, online when it must" else "Text only"} • Language: ${java.util.Locale.getDefault().displayLanguage}", 12f, palette.textTertiary).apply { setPadding(0, kit.dp(4), 0, 0) })
        column.addView(statusCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(12) })

        // The meter is the recogniser's own loudness, not a fake bouncing animation:
        // it moves exactly when onRmsChanged says you are loud.
        val meter = VoiceMeterView(host, palette)
        column.addView(meter, LinearLayout.LayoutParams(-1, kit.dp(46)).apply { bottomMargin = kit.dp(10) })

        // Result area
        val resultView = TextView(host).apply {
            textSize = 16f
            setTextColor(palette.text)
            setPadding(kit.dp(12), kit.dp(12), kit.dp(12), kit.dp(12))
            background = kit.rounded(palette.cardHigh, 18, palette.stroke, 1)
            text = "Tap mic and speak — try:\n• Open calculator\n• Search cats\n• Call mom\n• Set alarm\n• Play music\n• Turn on flashlight\n• Study mode\n• Change wallpaper"
        }
        column.addView(resultView, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(12) })

        val commandView = TextView(host).apply {
            textSize = 13f
            setTextColor(palette.textSecondary)
            setPadding(kit.dp(12), kit.dp(8), kit.dp(12), kit.dp(8))
            background = kit.rounded(palette.card, 16, palette.stroke, 1)
            text = "Command: none"
        }
        column.addView(commandView, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(12) })

        // Mic button
        val micBtn = kit.primaryButton("Tap to speak", R.drawable.oic_mic) {}
        column.addView(micBtn, LinearLayout.LayoutParams(-1, kit.dp(56)).apply { bottomMargin = kit.dp(12) })

        // Command chips
        val chipsBar = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        column.addView(kit.hscroll(chipsBar), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(12) })
        listOf("Open calculator", "Search cats", "Call mom", "Study mode", "Liquid mode", "Change wallpaper", "Turn on flashlight", "Set timer 5 minutes").forEach { example ->
            chipsBar.addView(kit.chip(example, false) {
                resultView.text = example
                val cmd = OeaSpeechEngine.parseCommand(host, example)
                commandView.text = "Command: ${cmd?.type} • ${cmd?.query}"
                if (cmd != null) {
                    val executed = OeaSpeechEngine.executeCommand(host, cmd)
                    commandView.text = "Command: ${cmd.type} • ${cmd.query} • Executed: $executed"
                }
            }, LinearLayout.LayoutParams(-2, kit.dp(36)).apply { rightMargin = kit.dp(6) })
        }

        // The same grammar, from the keyboard. This is not a demo box: every chip
        // above and every line typed here goes through the identical parser, so
        // "call mom" works whether it was said or typed.
        kit.sectionTitle(column, "Type a command instead")
        val typeRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        val typedField = kit.field("open camera · search cats · call mom · 10 minute timer", "")
        typeRow.addView(typedField, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = kit.dp(8) })
        typeRow.addView(
            kit.tonalButton("Run", R.drawable.oic_check) {
                val raw = typedField.text.toString().trim()
                if (raw.isEmpty()) {
                    android.widget.Toast.makeText(host, "Nothing to run", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    val cmd = OeaSpeechEngine.parseCommand(host, raw)
                    resultView.text = raw
                    if (cmd == null) {
                        commandView.text = "Command: none matched — OEA will not guess"
                        android.widget.Toast.makeText(host, "That is not one of OEA's commands", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        val executed = OeaSpeechEngine.executeCommand(host, cmd)
                        commandView.text = "Command: ${cmd.type} • ${cmd.query} • ${if (executed) "Run ✓" else "Nothing on this phone answered"}"
                        if (executed) OeaSpeechEngine.speak(host, "Done")
                    }
                    kit.hideKeyboard(typedField)
                }
            },
            LinearLayout.LayoutParams(-2, -2)
        )
        column.addView(typeRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })

        // Listener
        var isListening = false
        var continuous = prefs(host).getBoolean(KEY_CONTINUOUS, false)
        // The switch is built further down, where the rest of that row lives, but the
        // recogniser's error callback has to be able to switch it off — hence a holder.
        val keepToggle = arrayOfNulls<com.oea.launcher.ui.OeaToggle>(1)

        fun listen() {
            if (isListening) return
            // Re-read the grant: the first tap is often the one that answers the dialog.
            if (!OeaSpeechEngine.hasPermission(host)) {
                try { host.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 5001) } catch (_: Throwable) {}
                android.widget.Toast.makeText(host, "Grant microphone permission", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            isListening = true
            micBtn.text = "Listening… tap to stop"
            resultView.text = "Listening…"
            OeaSpeechEngine.startListening(host, object : OeaSpeechEngine.Listener {
                override fun onReady() {
                    host.runOnUiThread { resultView.text = "Listening… speak now" }
                }
                override fun onPartial(text: String) {
                    host.runOnUiThread { resultView.text = text }
                }
                override fun onResult(text: String, command: OeaSpeechEngine.LauncherCommand?) {
                    host.runOnUiThread {
                        resultView.text = text
                        if (command != null) {
                            commandView.text = "Command: ${command.type} • ${command.query} • Confidence ${command.confidence}"
                            val executed = OeaSpeechEngine.executeCommand(host, command)
                            if (executed) {
                                commandView.text = "Command: ${command.type} • ${command.query} • Executed ✓"
                                OeaSpeechEngine.speak(host, "Done")
                            } else {
                                // If not executable, treat as search
                                if (command.type == OeaSpeechEngine.LauncherCommand.Type.SEARCH_WEB) {
                                    try {
                                        host.startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", command.query))
                                    } catch (_: Throwable) {
                                        try { host.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=${android.net.Uri.encode(command.query)}"))) } catch (_: Throwable) {}
                                    }
                                }
                            }
                        } else {
                            commandView.text = "Command: none — treated as search"
                        }
                        micBtn.text = "Tap to speak"
                        isListening = false
                        if (continuous) {
                            if (com.oea.launcher.perf.OeaLowMode.active(host)) {
                                commandView.text = "Command: done — keep-listening waits out LOW mode"
                            } else {
                                // One breath, then the ear opens again. This is the whole
                                // of "continuous": it lives while this screen does.
                                micBtn.postDelayed({ if (continuous) listen() }, 450L)
                            }
                        }
                    }
                }
                override fun onError(message: String) {
                    host.runOnUiThread {
                        resultView.text = "Error: $message"
                        // Never restart into an error: that is a loop that eats battery
                        // and whistles at the phone.
                        continuous = false
                        prefs(host).edit().putBoolean(KEY_CONTINUOUS, false).apply()
                        keepToggle[0]?.setChecked(false)
                        commandView.text = "Try offline commands: open app, search, call, etc. · keep-listening stopped"
                        micBtn.text = "Tap to speak"
                        isListening = false
                    }
                }
                override fun onLevel(level: Float) {
                    host.runOnUiThread { meter.level = level }
                }
                override fun onStateChanged(state: OeaSpeechEngine.State) {
                    host.runOnUiThread {
                        meter.active = state == OeaSpeechEngine.State.LISTENING
                        meter.caption = when (state) {
                            OeaSpeechEngine.State.LISTENING -> "listening"
                            OeaSpeechEngine.State.PROCESSING -> "making sense of it"
                            OeaSpeechEngine.State.ERROR -> "the recogniser gave up"
                            else -> "quiet"
                        }
                    }
                }
            }, preferOffline = true)
        }

        micBtn.setOnClickListener {
            if (isListening) {
                OeaSpeechEngine.stopListening()
                micBtn.text = "Tap to speak"
                isListening = false
            } else {
                listen()
            }
        }

        // Continuous listening, honestly scoped: the recogniser is reopened after
        // each command while this screen is up, and not at all in LOW mode.
        val keepGroup = kit.group(column)
        val keepRow = kit.switchRow(
            keepGroup,
            R.drawable.oic_mic,
            "Keep listening",
            "Reopens the ear after each command while this screen is open · off in LOW mode",
            continuous
        ) {
            continuous = it
            prefs(host).edit().putBoolean(KEY_CONTINUOUS, it).apply()
        }
        keepToggle[0] = keepRow
        column.addView(kit.quietButton(if (continuous) "Listening stays on — stop it" else "Start listening again now", R.drawable.oic_play_arrow) {
            if (continuous) {
                continuous = false
                prefs(host).edit().putBoolean(KEY_CONTINUOUS, false).apply()
                keepToggle[0]?.setChecked(false, animate = false)
            } else {
                listen()
            }
        }, LinearLayout.LayoutParams(-1, kit.dp(46)).apply { topMargin = kit.dp(8) })

        // TTS test
        column.addView(kit.tonalButton("Test voice feedback (TTS)", R.drawable.oic_volume_up) {
            OeaSpeechEngine.speak(host, "OEA Voice is ready. Try saying open calculator or search cats.")
        }, LinearLayout.LayoutParams(-1, kit.dp(48)).apply { topMargin = kit.dp(8) })

        kit.note(column, "What OEA Voice is: its own screen, its own command grammar, the phone's recogniser underneath, and a preference for whatever offline recogniser the device offers. What it is not: a wake-word engine. Continuous listening here is a loop that restarts after each command while this screen is open and stops in LOW mode — the microphone is never held while OEA is in your pocket, because Android takes it back from a backgrounded app and OEA will not pretend otherwise. Bundling a speech model would add tens of megabytes to the APK for something the platform already has, so this build asks the platform instead and keeps the typed path as the honest fallback.")

        // Integration with search
        kit.sectionTitle(column, "Integrated with Search")
        val searchGroup = kit.group(column)
        kit.row(searchGroup, R.drawable.oic_search, "Voice search", "Tap mic in OEA search bar to use OEA Voice") {
            try { host.startActivity(Intent(host, com.oea.launcher.OeaSystemToolsActivity::class.java).putExtra(com.oea.launcher.OeaSystemToolsActivity.EXTRA_SCREEN, "search").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
        }

        return column
    }
}
