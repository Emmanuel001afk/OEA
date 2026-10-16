package com.oea.launcher.privilege

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** OEA's first-party UI for pairing its embedded Wireless Debugging bridge. */
class OeaAuthorityActivity : Activity() {
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var hostField: EditText
    private lateinit var pairingPortField: EditText
    private lateinit var codeField: EditText
    private lateinit var connectPortField: EditText
    private lateinit var statusText: TextView
    private lateinit var pairButton: Button
    private lateinit var reconnectButton: Button
    private lateinit var forgetButton: Button

    private val ink = Color.rgb(235, 240, 248)
    private val muted = Color.rgb(164, 177, 196)
    private val accent = Color.rgb(104, 166, 255)
    private val panel = Color.rgb(28, 37, 52)
    private val background = Color.rgb(14, 20, 30)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        refreshStatus()
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(background) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(text("OEA Authority", 27, ink, true))
        root.addView(text("Built-in Wireless Debugging bridge", 15, accent, true).apply {
            setPadding(0, dp(4), 0, dp(16))
        })
        root.addView(card().apply {
            addView(text("One-time Android authorization", 17, ink, true))
            addView(text(
                "OEA contains its own ADB pairing and shell client. No Shizuku or companion app is needed. Android still requires Wireless Debugging to be enabled and the pairing code to be approved by you.",
                14, muted, false
            ).apply { setPadding(0, dp(8), 0, dp(14)) })
            addView(button("Open Developer options") {
                runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                    .onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
            })
        })

        root.addView(card().apply {
            addView(text("Pair this phone", 18, ink, true))
            addView(text(
                "On this phone, open Developer options → Wireless debugging → Pair device with pairing code. Enter that screen's IP/port and six-digit code below. Then use the separate connection port shown on the main Wireless debugging screen.",
                14, muted, false
            ).apply { setPadding(0, dp(8), 0, dp(12)) })

            hostField = field("Phone local IP address", "192.168.1.10").also { addView(it) }
            pairingPortField = field("Pairing port (temporary)", "").also { addView(it) }
            codeField = field("Six-digit pairing code", "").apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
                filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            }.also { addView(it) }
            connectPortField = field("Wireless Debugging connection port", "").also { addView(it) }

            pairButton = button("Pair and verify connection") { pair() }.also { addView(it) }
            reconnectButton = button("Reconnect saved endpoint") { reconnect() }.also { addView(it) }
        })

        root.addView(card().apply {
            addView(text("Live verification", 17, ink, true))
            statusText = text("Checking saved endpoint…", 14, muted, false).apply {
                setPadding(0, dp(8), 0, dp(12))
            }
            addView(statusText)
            forgetButton = button("Forget endpoint in OEA") {
                OeaAdbAuthority.forget(this@OeaAuthorityActivity)
                codeField.setText("")
                refreshStatus()
                toast("OEA's saved endpoint was cleared. Android's paired-host list is managed in Developer options.")
            }.also { addView(it) }
        })

        root.addView(text(
            "Safety: OEA does not expose a free-form shell. The bridge is restricted to the verified package-freeze operations it needs. To revoke Android's trust, use Wireless debugging → Paired devices / Revoke authorizations.",
            13, muted, false
        ).apply { setPadding(dp(2), dp(4), dp(2), 0) })
    }

    private fun pair() {
        val host = hostField.text.toString().trim()
        val pairingPort = pairingPortField.text.toString().toIntOrNull()
        val code = codeField.text.toString().trim()
        val connectPort = connectPortField.text.toString().toIntOrNull()
        if (pairingPort == null || connectPort == null) {
            toast("Enter both ports shown by Android.")
            return
        }
        setBusy(true, "Pairing with Android and verifying the shell identity…")
        work.launch {
            val result = runCatching {
                OeaAdbAuthority.pairAndConnect(this@OeaAuthorityActivity, host, pairingPort, code, connectPort)
            }
            setBusy(false, result.fold({ it }, { "Could not complete pairing: ${it.message ?: it.javaClass.simpleName}" }))
            codeField.setText("")
            refreshStatus()
        }
    }

    private fun reconnect() {
        setBusy(true, "Reconnecting and verifying the saved endpoint…")
        work.launch {
            val result = runCatching { OeaAdbAuthority.reconnect(this@OeaAuthorityActivity) }
            setBusy(false, result.fold({ it }, { "Reconnect failed: ${it.message ?: it.javaClass.simpleName}" }))
            refreshStatus()
        }
    }

    private fun refreshStatus() {
        val status = OeaAdbAuthority.status(this)
        statusText.text = status.note
        statusText.setTextColor(if (status.connected) Color.rgb(113, 220, 165) else muted)
        if (status.host != null && hostField.text.isNullOrBlank()) hostField.setText(status.host)
        if (status.port != null && connectPortField.text.isNullOrBlank()) connectPortField.setText(status.port.toString())
        reconnectButton.isEnabled = status.configured
        forgetButton.isEnabled = status.configured
    }

    private fun setBusy(busy: Boolean, message: String) {
        pairButton.isEnabled = !busy
        reconnectButton.isEnabled = !busy && OeaAdbAuthority.status(this).configured
        statusText.text = message
        statusText.setTextColor(if (busy) accent else ink)
    }

    private fun field(label: String, hint: String): EditText {
        val input = EditText(this).apply {
            this.hint = hint
            setHintTextColor(muted)
            setTextColor(ink)
            textSize = 15f
            singleLine = true
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(panel)
            contentDescription = label
            setSelectAllOnFocus(false)
        }
        input.layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(9) }
        return input
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(15), dp(15), dp(15), dp(15))
        background = rounded(panel)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(ink)
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(48, 77, 115)))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(5) }
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean): TextView = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.START
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun rounded(color: Int): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), Color.rgb(47, 60, 79))
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        work.cancel()
        super.onDestroy()
    }
}
