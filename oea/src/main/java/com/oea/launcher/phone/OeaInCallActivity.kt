package com.oea.launcher.phone

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Bundle
import android.telecom.Call
import android.telecom.VideoProfile
import android.view.Gravity
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class OeaInCallActivity : Activity() {
    private var observedCall: Call? = null
    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            runOnUiThread { render() }
        }
        override fun onDetailsChanged(call: Call, details: Call.Details) {
            runOnUiThread { render() }
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        render()
    }

    override fun onResume() {
        super.onResume()
        attachCall()
        render()
    }

    override fun onPause() {
        observedCall?.unregisterCallback(callback)
        observedCall = null
        super.onPause()
    }

    private fun attachCall() {
        val current = OeaCallState.current ?: return
        if (observedCall !== current) {
            observedCall?.unregisterCallback(callback)
            observedCall = current
            runCatching { current.registerCallback(callback) }
        }
    }

    private fun render() {
        val call = OeaCallState.current
        if (call == null) {
            finish()
            return
        }
        val audio = getSystemService(AudioManager::class.java)
        val stateLabel = when (call.state) {
            Call.STATE_NEW -> "Preparing call"
            Call.STATE_CONNECTING -> "Connecting…"
            Call.STATE_SELECT_PHONE_ACCOUNT -> "Choose SIM / phone account"
            Call.STATE_DIALING -> "Dialing…"
            Call.STATE_RINGING -> "Incoming call"
            Call.STATE_ACTIVE -> "Call connected"
            Call.STATE_HOLDING -> "On hold"
            Call.STATE_DISCONNECTED -> "Call ended"
            else -> "Call"
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(36), dp(24), dp(28))
            setBackgroundColor(0xFF0C0F15.toInt())
        }
        root.addView(TextView(this).apply {
            text = call.details.handle?.schemeSpecificPart ?: "Unknown caller"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            maxLines = 2
        }, LinearLayout.LayoutParams(-1, dp(100)))
        root.addView(TextView(this).apply {
            text = stateLabel
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xFF9AA2B1.toInt())
        }, LinearLayout.LayoutParams(-1, dp(56)))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        if (call.state == Call.STATE_RINGING) {
            controls.addView(button("ANSWER") {
                runCatching { call.answer(VideoProfile.STATE_AUDIO_ONLY) }.onFailure { toast("Unable to answer call.") }
                render()
            }, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(8) })
            controls.addView(button("REJECT") {
                runCatching { call.reject(false, null) }.onFailure { toast("Unable to reject call.") }
                finish()
            }, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(8) })
        } else if (call.state != Call.STATE_DISCONNECTED) {
            controls.addView(button(if (call.state == Call.STATE_HOLDING) "RESUME CALL" else "HOLD") {
                runCatching {
                    if (call.state == Call.STATE_HOLDING) call.unhold() else call.hold()
                }.onFailure { toast("Hold is not supported by this phone account.") }
                render()
            }, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
            controls.addView(button(if (audio?.isMicrophoneMute == true) "UNMUTE" else "MUTE") {
                runCatching { audio?.isMicrophoneMute = audio?.isMicrophoneMute != true }
                render()
            }, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
            controls.addView(button(if (audio?.isSpeakerphoneOn == true) "EARPIECE" else "SPEAKER") {
                runCatching { audio?.isSpeakerphoneOn = audio?.isSpeakerphoneOn != true }
                render()
            }, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
            controls.addView(button("HANG UP") {
                runCatching { call.disconnect() }.onFailure { toast("Unable to end call.") }
                finish()
            }, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(8) })
        } else {
            controls.addView(button("CLOSE") { finish() }, LinearLayout.LayoutParams(-1, dp(48)))
        }
        root.addView(controls, LinearLayout.LayoutParams(-1, -2))
        val mode = getSharedPreferences("oea_phone_prefs", MODE_PRIVATE).getString("call_ui_mode", "fullscreen")
        if (mode == "popup" || mode == "compact") {
            root.setPadding(dp(18), dp(22), dp(18), dp(18))
            root.background = GradientDrawable().apply {
                setColor(Color.rgb(12, 15, 21))
                cornerRadius = dp(26).toFloat()
                setStroke(dp(1), Color.rgb(55, 67, 84))
            }
            val scroll = ScrollView(this).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                addView(root, android.widget.ScrollView.LayoutParams(-1, -2))
            }
            setContentView(scroll)
        } else {
            setContentView(root)
        }
        applyCallWindowMode(mode)
    }

    private fun applyCallWindowMode(mode: String?) {
        if (mode == "popup" || mode == "compact") {
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val metrics = resources.displayMetrics
            val width = minOf(dp(if (mode == "compact") 320 else 360), metrics.widthPixels - dp(24))
            val height = minOf(dp(if (mode == "compact") 400 else 520), metrics.heightPixels - dp(72))
            window.setLayout(width.coerceAtLeast(dp(260)), height.coerceAtLeast(dp(300)))
            window.attributes = window.attributes.apply {
                gravity = Gravity.CENTER
                dimAmount = 0.42f
            }
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setBackgroundDrawable(ColorDrawable(Color.rgb(12, 15, 21)))
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
