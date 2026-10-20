package com.oea.launcher.privilege

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import com.oea.launcher.assistant.OeaAssistant
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
    private var autoReadStartedAt = 0L
    private var autoCapturedCode: String? = null
    private var autoPairInFlight = false
    private var lastAutoPairKey: String? = null
    private val pairingListener: () -> Unit = { runOnUiThread { refreshCapturedValues() } }

    private val inkColor = Color.rgb(235, 240, 248)
    private val mutedColor = Color.rgb(164, 177, 196)
    private val accentColor = Color.rgb(104, 166, 255)
    private val panelColor = Color.rgb(28, 37, 52)
    private val backgroundColor = Color.rgb(14, 20, 30)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        OeaAssistant.addListener(pairingListener)
        refreshStatus()
        refreshCapturedValues()
    }

    override fun onResume() {
        super.onResume()
        refreshCapturedValues()
        refreshStatus()
    }

    private fun beginAutoRead() {
        autoReadStartedAt = android.os.SystemClock.elapsedRealtime()
        autoCapturedCode = null
        hostField.setText("")
        pairingPortField.setText("")
        codeField.setText("")
        connectPortField.setText("")
        OeaWirelessPairing.clear()
        OeaWirelessPairing.startWatching()
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
        lastAutoPairKey = null
        autoPairInFlight = false
        statusText.text = "Auto-read is active. Keep Wireless debugging open; OEA will pair automatically when it captures the fresh code and both ports."
        statusText.setTextColor(accentColor)
    }

    private fun refreshCapturedValues() {
        val pair = OeaWirelessPairing.freshPairing()
        val connection = OeaWirelessPairing.connection
        if (pair != null) {
            hostField.setTextIfDifferent(pair.host)
            pairingPortField.setTextIfDifferent(pair.port.toString())
            codeField.setTextIfDifferent(pair.code)
            autoCapturedCode = pair.code
        } else if (autoCapturedCode != null && codeField.text.toString() == autoCapturedCode) {
            // Never leave an expired automatically-read one-time code usable.
            codeField.setText("")
            autoCapturedCode = null
        }
        if (connection != null) {
            // Preserve the distinct main-page connection port. Automatic pairing
            // below refuses to combine endpoints if their captured hosts disagree.
            connectPortField.setTextIfDifferent(connection.port.toString())
            if (pair == null) hostField.setTextIfDifferent(connection.host)
        }

        if (pair != null && connection != null &&
            autoReadStartedAt != 0L && !pair.manual && !connection.manual &&
            pair.capturedAt >= autoReadStartedAt && connection.capturedAt >= autoReadStartedAt) {
            startAutomaticPairing(pair, connection)
            return
        }

        if (pair != null || connection != null) {
            val captured = buildList {
                if (pair != null) add("pairing endpoint and code")
                if (connection != null) add("connection port")
            }.joinToString(" + ")
            statusText.text = "Automatically captured $captured. Waiting for fresh matching values to pair automatically; manual Pair remains available as fallback."
            statusText.setTextColor(accentColor)
        } else if (autoReadStartedAt != 0L &&
            android.os.SystemClock.elapsedRealtime() - autoReadStartedAt > 5 * 60_000L) {
            statusText.text = "Auto-read timed out. Start Auto-read again, or enter the values manually."
            statusText.setTextColor(mutedColor)
        }
    }

    private fun startAutomaticPairing(
        pair: OeaWirelessPairing.Pairing,
        connection: OeaWirelessPairing.Connection
    ) {
        val key = "${pair.host}:${pair.port}|${pair.code}|${connection.host}:${connection.port}"
        if (autoPairInFlight || lastAutoPairKey == key) return
        if (pair.host != connection.host) {
            lastAutoPairKey = key
            statusText.text = "Automatic pairing blocked: Android exposed different hosts for the pairing dialog and connection page. Reopen Wireless debugging and capture both endpoints again, or use manual entry."
            statusText.setTextColor(Color.rgb(255, 180, 110))
            return
        }
        lastAutoPairKey = key
        autoPairInFlight = true
        setBusy(true, "Fresh code and ports detected. Pairing with Android and verifying the connection automatically…")
        work.launch {
            val result = runCatching {
                OeaAdbAuthority.pairAndConnect(
                    this@OeaAuthorityActivity,
                    pair.host,
                    pair.port,
                    pair.code,
                    connection.port
                )
            }
            autoPairInFlight = false
            val message = result.fold(
                { "Automatic pairing complete. $it" },
                { "Automatic pairing failed: ${it.message ?: it.javaClass.simpleName}. Reopen the pairing dialog for a fresh code, or use manual Pair." }
            )
            setBusy(false, message)
            codeField.setText("")
            autoCapturedCode = null
            refreshStatus(message)
            if (result.isSuccess) {
                toastShort("OEA pairing and connection verified.")
            }
        }
    }

    private fun EditText.setTextIfDifferent(value: String) {
        if (text.toString() != value) setText(value)
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(backgroundColor) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(text("OEA Authority", 27, inkColor, true))
        root.addView(text("Built-in Authority engine + Wireless Debugging", 15, accentColor, true).apply {
            setPadding(0, dp(4), 0, dp(16))
        })
        root.addView(card().apply {
            addView(text("One-time Android authorization", 17, inkColor, true))
            addView(text(
                "OEA contains its own ADB pairing and shell client. No Shizuku or companion app is needed. Android still requires Wireless Debugging to be enabled and the pairing code to be approved by you.",
                14, mutedColor, false
            ).apply { setPadding(0, dp(8), 0, dp(14)) })
            addView(button("Open Wireless debugging & auto-read") { beginAutoRead() })
        })

        root.addView(card().apply {
            addView(text("Pair this phone", 18, inkColor, true))
            addView(text(
                "Auto-read is preferred: tap the button above, open Wireless debugging, and let OEA capture the pairing dialog and the separate connection port. Manual entry remains available if Android does not expose the values to Accessibility.",
                14, mutedColor, false
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
            addView(text("Live verification", 17, inkColor, true))
            statusText = text("Checking saved endpoint…", 14, mutedColor, false).apply {
                setPadding(0, dp(8), 0, dp(12))
            }
            addView(statusText)
            addView(button("Run complete Access Doctor scan") { runCompleteAccessScan() })
            addView(button("Scan authority capabilities") { scanAuthorityCapabilities() })
            addView(button("Test real ADB authority") { testAuthority() })
            addView(button("Test app suspension + restore") { chooseSuspensionTestApp() })
            addView(button("Review permission status and repair") { reviewPermissionRepairs() })
            forgetButton = button("Forget endpoint in OEA") {
                OeaAdbAuthority.forget(this@OeaAuthorityActivity)
                codeField.setText("")
                refreshStatus()
                toast("OEA's saved endpoint was cleared. Android's paired-host list is managed in Developer options.")
            }.also { addView(it) }
        })

        root.addView(text(
            "OEA Authority provides a shared, typed API over the embedded Wireless Debugging bridge. It probes supported capabilities and returns real command results; it does not pretend unsupported Android operations succeeded. No free-form shell is exposed. To revoke Android's trust, use Wireless debugging → Paired devices / Revoke authorizations.",
            13, mutedColor, false
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
            val message = result.fold({ it }, { "Could not complete pairing: ${it.message ?: it.javaClass.simpleName}" })
            setBusy(false, message)
            codeField.setText("")
            refreshStatus(message)
        }
    }

    private fun reconnect() {
        val host = hostField.text.toString().trim()
        val port = connectPortField.text.toString().toIntOrNull()
        if (port == null) {
            toast("Enter the connection port shown on the main Wireless debugging screen.")
            return
        }
        setBusy(true, "Reconnecting and verifying the endpoint…")
        work.launch {
            val result = runCatching { OeaAdbAuthority.connect(this@OeaAuthorityActivity, host, port) }
            val message = result.fold({ it }, { "Reconnect failed: ${it.message ?: it.javaClass.simpleName}" })
            setBusy(false, message)
            refreshStatus(message)
        }
    }

    /** One pass over local permissions and live shell-backed capabilities. */
    private fun runCompleteAccessScan() {
        setBusy(true, "Checking Android permissions and OEA Authority capabilities together…")
        work.launch {
            val result = runCatching {
                val capabilities = OeaAuthorityEngine.inspect(this@OeaAuthorityActivity)
                val findings = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    OeaAccessDoctor.examine(this@OeaAuthorityActivity)
                }
                buildString {
                    append("OEA ACCESS DOCTOR — COMPLETE SCAN\n")
                    append("Android API: ").append(capabilities.androidApi).append("\n")
                    append("Checked: ").append(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(capabilities.checkedAtMillis))).append("\n")
                    append("ADB shell identity: ").append(capabilities.shellIdentity ?: "not connected / not verified").append("\n\n")
                    append("PERMISSIONS & FEATURE ACCESS\n")
                    findings.forEach { finding ->
                        append("• ").append(finding.privilege.name.replace('_', ' '))
                            .append(" — ").append(finding.state.name.replace('_', ' ')).append("\n")
                        append(finding.evidence).append("\nNext: ").append(finding.advice).append("\n\n")
                    }
                    append("AUTHORITY CAPABILITIES\n")
                    capabilities.capabilities.forEach { capability ->
                        append("• ").append(capability.id.name.replace('_', ' '))
                            .append(" — ").append(capability.level.name).append("\n")
                        append(capability.evidence).append("\n").append(capability.detail).append("\n\n")
                    }
                    append("Note: LIMITED/UNAVAILABLE/UNKNOWN are not successful grants. Android may require you to approve or enable some items in Settings. Device Admin is not Device Owner, and a successful ADB connection does not mean every privileged operation is allowed.")
                }
            }
            setBusy(false, if (result.isSuccess) "Complete scan finished. Review the combined report for each permission and capability." else "Complete scan failed: ${result.exceptionOrNull()?.message ?: "unknown error"}")
            result.onSuccess { report ->
                val reportView = TextView(this@OeaAuthorityActivity).apply {
                    text = report
                    textSize = 14f
                    setTextColor(inkColor)
                    setPadding(dp(18), dp(12), dp(18), dp(12))
                    setTextIsSelectable(true)
                }
                val reportScroll = ScrollView(this@OeaAuthorityActivity).apply {
                    addView(reportView)
                    isFillViewport = true
                }
                AlertDialog.Builder(this@OeaAuthorityActivity)
                    .setTitle("Complete Access Doctor report")
                    .setView(reportScroll)
                    .setPositiveButton("Close", null)
                    .show()
                refreshStatus("Complete Access Doctor scan finished. Review the combined report.")
            }.onFailure {
                refreshStatus("Complete scan failed: ${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    /** Live capability discovery; shows what this device actually permits. */
    private fun scanAuthorityCapabilities() {
        setBusy(true, "Probing OEA Authority capabilities on this device…")
        work.launch {
            val result = runCatching { OeaAuthorityEngine.inspect(this@OeaAuthorityActivity) }
            setBusy(false, "Authority capability scan finished.")
            result.onSuccess { snapshot ->
                val message = buildString {
                    append("Android API: ").append(snapshot.androidApi).append("\n")
                    append("Checked: ").append(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(snapshot.checkedAtMillis))).append("\n")
                    append("Shell identity: ").append(snapshot.shellIdentity ?: "not available").append("\n\n")
                    snapshot.capabilities.forEach { capability ->
                        append("• ").append(capability.id.name.replace('_', ' '))
                            .append(" — ").append(capability.level.name).append("\n")
                        append(capability.evidence).append("\n")
                        append(capability.detail).append("\n\n")
                    }
                }
                AlertDialog.Builder(this@OeaAuthorityActivity)
                    .setTitle("OEA Authority capability report")
                    .setMessage(message)
                    .setPositiveButton("Close", null)
                    .show()
                refreshStatus("Capability scan complete. Review the report for actual results.")
            }.onFailure {
                refreshStatus("Capability scan failed: ${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    /**
     * A successful pairing is not proof that later privileged operations work.
     * Run a fresh, read-only shell identity probe and show its actual output.
     */
    private fun testAuthority() {
        if (!OeaAdbAuthority.isSessionReady()) {
            refreshStatus("Test blocked: the shell session is not currently connected. Tap Reconnect first.")
            return
        }
        setBusy(true, "Testing the live ADB shell identity…")
        work.launch {
            val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                OeaAdbAuthority.runShell("id")
            }
            val message = if (result.first) {
                val identity = result.second.trim()
                when {
                    identity.contains("uid=2000") -> "ADB test passed. Android granted OEA the shell identity (uid=2000):\n$identity"
                    identity.contains("uid=0") -> "ADB test passed with root identity:\n$identity"
                    else -> "The command returned, but the identity is unexpected. No privileged action was attempted:\n$identity"
                }
            } else {
                "ADB test failed. OEA could not execute the read-only identity probe:\n${result.second}"
            }
            setBusy(false, message)
            refreshStatus(message)
        }
    }

    /** Tests a real Android package suspension and immediately restores the chosen app. */
    private fun chooseSuspensionTestApp() {
        if (!OeaAdbAuthority.isSessionReady()) {
            refreshStatus("Suspension test blocked: reconnect OEA Authority and pass the shell identity test first.")
            return
        }
        val pm = packageManager
        val apps = runCatching {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { app ->
                    app.packageName != packageName &&
                        (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                        pm.getLaunchIntentForPackage(app.packageName) != null
                }
                .sortedBy { pm.getApplicationLabel(it).toString().lowercase(java.util.Locale.ROOT) }
        }.getOrDefault(emptyList())
        if (apps.isEmpty()) {
            refreshStatus("Suspension test unavailable: no launchable user-installed apps were found.")
            return
        }
        val labels = apps.map { app ->
            "${pm.getApplicationLabel(app)}  •  ${app.packageName}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Choose a test app")
            .setMessage("OEA will suspend this app, verify Android's state, then immediately restore it. Choose a non-critical app.")
            .setItems(labels) { _, which ->
                val app = apps[which]
                setBusy(true, "Testing real suspension and restoration for ${app.packageName}…")
                work.launch {
                    val outcome = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        val pkg = app.packageName
                        val freezeCommand = OeaAdbAuthority.runShell("cmd package suspend --user 0 $pkg")
                        if (!freezeCommand.first) {
                            "ADB SUSPEND FAILED/BLOCKED\nApp: $pkg\n${freezeCommand.second}\nNo alternate backend was used; this test specifically checks OEA's Wireless Debugging bridge."
                        } else {
                            val freezeState = verifyAdbSuspended(pkg, expected = true)
                            if (freezeState == true) {
                                val restoreCommand = OeaAdbAuthority.runShell("cmd package unsuspend --user 0 $pkg")
                                val restoreState = if (restoreCommand.first) verifyAdbSuspended(pkg, expected = false) else null
                                when {
                                    restoreCommand.first && restoreState == true ->
                                        "PASS — OEA's ADB bridge suspended the app and Android's package state confirmed it; restore was also verified.\nApp: $pkg\nSuspend output: ${freezeCommand.second.ifBlank { "(no output)" }}\nRestore output: ${restoreCommand.second.ifBlank { "(no output)" }}"
                                    else ->
                                        "RESTORE FAILED — ADB suspension was accepted, but restoration was not verified. Open Android App info for $pkg and restore/unsuspend it now.\nRestore output: ${restoreCommand.second}\nState check: ${restoreState?.toString() ?: "unreadable"}"
                                }
                            } else {
                                val restoreCommand = OeaAdbAuthority.runShell("cmd package unsuspend --user 0 $pkg")
                                val restored = restoreCommand.first && verifyAdbSuspended(pkg, expected = false) == true
                                "VERIFICATION FAILED — Android did not confirm the suspended state. Immediate restore ${if (restored) "was verified" else "could not be verified"}.\nApp: $pkg\nSuspend output: ${freezeCommand.second}\nRestore output: ${restoreCommand.second}"
                            }
                        }
                    }
                    setBusy(false, outcome)
                    refreshStatus(outcome)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** null means Android's per-user package state could not be read; otherwise returns whether it matches. */
    private fun verifyAdbSuspended(packageName: String, expected: Boolean): Boolean? {
        val (success, output) = OeaAdbAuthority.runShell("dumpsys package $packageName")
        if (!success || !output.contains(packageName)) return null
        val userState = Regex("""(?m)^\s*User 0:.*$""").find(output)?.value ?: return null
        val suspended = Regex("""\bsuspended=true\b""").containsMatchIn(userState)
        return suspended == expected
    }

    /** Shows evidence from OEA's existing capability checks; selecting a row invokes its existing repair flow. */
    private fun reviewPermissionRepairs() {
        setBusy(true, "Checking permissions and supported repair paths…")
        work.launch {
            val findings = kotlinx.coroutines.withContext(Dispatchers.IO) {
                OeaAccessDoctor.examine(this@OeaAuthorityActivity)
            }
            setBusy(false, "Permission check complete. Select a finding to run its existing repair flow.")
            val labels = findings.map { finding ->
                "${finding.privilege.name.replace('_', ' ')} — ${finding.state.name.replace('_', ' ')}"
            }.toTypedArray()
            AlertDialog.Builder(this@OeaAuthorityActivity)
                .setTitle("OEA permission diagnostics")
                .setItems(labels) { _, which ->
                    val finding = findings[which]
                    AlertDialog.Builder(this@OeaAuthorityActivity)
                        .setTitle(finding.privilege.name.replace('_', ' '))
                        .setMessage("${finding.state.name.replace('_', ' ')}\n\nEvidence: ${finding.evidence}\n\nNext step: ${finding.advice}" +
                            (finding.command?.let { "\n\nSupported repair command: $it" } ?: "\n\nNo shell repair command is available for this permission."))
                        .setPositiveButton("Run repair / open Settings") { _, _ ->
                            OeaPrivileges.grant(this@OeaAuthorityActivity, finding.privilege) { result ->
                                val message = "${finding.privilege.name.replace('_', ' ')}: ${result.kind.name}\n${result.message}"
                                refreshStatus(message)
                                toast(message)
                            }
                        }
                        .setNegativeButton("Close", null)
                        .show()
                }
                .setPositiveButton("Close", null)
                .show()
        }
    }

    private fun refreshStatus(lastResult: String? = null) {
        val status = OeaAdbAuthority.status(this)
        statusText.text = if (lastResult.isNullOrBlank()) status.note else "$lastResult\n\n${status.note}"
        statusText.setTextColor(if (status.connected) Color.rgb(113, 220, 165) else if (!lastResult.isNullOrBlank()) inkColor else mutedColor)
        if (status.host != null && hostField.text.isNullOrBlank()) hostField.setText(status.host)
        if (status.port != null && connectPortField.text.isNullOrBlank()) connectPortField.setText(status.port.toString())
        reconnectButton.isEnabled = status.configured
        forgetButton.isEnabled = status.configured
    }

    private fun setBusy(busy: Boolean, message: String) {
        pairButton.isEnabled = !busy
        reconnectButton.isEnabled = !busy && OeaAdbAuthority.status(this).configured
        statusText.text = message
        statusText.setTextColor(if (busy) accentColor else inkColor)
    }

    private fun field(label: String, hint: String): EditText {
        val input = EditText(this).apply {
            this.hint = hint
            setHintTextColor(mutedColor)
            setTextColor(inkColor)
            textSize = 15f
            isSingleLine = true
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(panelColor)
            contentDescription = label
            setSelectAllOnFocus(false)
        }
        input.layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(9) }
        return input
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(15), dp(15), dp(15), dp(15))
        background = rounded(panelColor)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(inkColor)
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
    private fun toastShort(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        OeaAssistant.removeListener(pairingListener)
        work.cancel()
        super.onDestroy()
    }
}
