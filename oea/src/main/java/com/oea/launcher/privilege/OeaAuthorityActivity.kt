package com.oea.launcher.privilege

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.oea.launcher.R
import com.oea.launcher.assistant.OeaAssistant
import com.oea.launcher.ui.OeaAppKit
import com.oea.launcher.ui.OeaInsets
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaTheme
import com.oea.launcher.ui.OeaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** OEA's first-party UI for pairing its embedded Wireless Debugging bridge. */
class OeaAuthorityActivity : Activity() {
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var palette: OeaPalette
    private lateinit var kit: OeaAppKit
    private lateinit var hostField: EditText
    private lateinit var pairingPortField: EditText
    private lateinit var codeField: EditText
    private lateinit var connectPortField: EditText
    private lateinit var statusText: TextView
    private lateinit var pairButton: View
    private lateinit var reconnectButton: View
    private lateinit var forgetButton: View
    private var autoReadStartedAt = 0L
    private var autoCapturedCode: String? = null
    private var autoPairInFlight = false
    private var lastAutoPairKey: String? = null
    private val pairingListener: () -> Unit = { runOnUiThread { refreshCapturedValues() } }

    private val inkColor get() = palette.text
    private val mutedColor get() = palette.textSecondary
    private val accentColor get() = palette.accent

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
            statusText.setTextColor(palette.warning)
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
        palette = OeaTheme.resolve(this)
        kit = OeaAppKit(this, palette)
        OeaInsets.edgeToEdge(window, lightBars = !palette.dark)

        val shell = kit.appShell(
            title = "OEA Authority",
            subtitle = "Built-in Wireless Debugging bridge",
            onBack = { finish() }
        )
        val root = shell.body
        setContentView(shell.root)

        val autoCard = kit.card(paddingDp = 16)
        autoCard.addView(kit.text("One-time authorization", 16.5f, palette.text, OeaType.medium))
        autoCard.addView(kit.text(
            "OEA includes its own ADB pairing and shell client — no Shizuku or companion app needed. Enable Wireless Debugging and approve the pairing code once.",
            13.5f, palette.textSecondary
        ).apply { setPadding(0, kit.dp(6), 0, kit.dp(14)); setLineSpacing(kit.dpf(2f), 1f) })
        autoCard.addView(
            kit.primaryButton("Open Wireless debugging & auto-read", R.drawable.oic_developer_mode) { beginAutoRead() },
            LinearLayout.LayoutParams(-1, kit.dp(48))
        )
        root.addView(autoCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })

        kit.sectionTitle(root, "Manual pairing & endpoint")
        val pairCard = kit.card(paddingDp = 16)
        hostField = authField("Phone local IP address", "Phone IP (e.g. 192.168.1.10)").also { pairCard.addView(it) }
        pairingPortField = authField("Pairing port (temporary)", "Pairing port (temporary)", numeric = true).also { pairCard.addView(it) }
        codeField = authField("Six-digit pairing code", "6-digit pairing code", numeric = true, password = true).apply {
            filters = arrayOf(InputFilter.LengthFilter(6))
        }.also { pairCard.addView(it) }
        connectPortField = authField("Wireless Debugging connection port", "Connection port", numeric = true).also { pairCard.addView(it) }

        pairButton = kit.primaryButton("Pair & verify") { pair() }
        reconnectButton = kit.tonalButton("Reconnect") { reconnect() }
        pairCard.addView(kit.buttonRow(reconnectButton, pairButton))
        root.addView(pairCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })

        kit.sectionTitle(root, "Status")
        val statusCard = kit.card(paddingDp = 16)
        statusText = kit.text("Checking saved endpoint…", 13.5f, palette.textSecondary).apply {
            setLineSpacing(kit.dpf(2f), 1f)
            setTextIsSelectable(true)
        }
        statusCard.addView(statusText)
        root.addView(statusCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })

        kit.sectionTitle(root, "Verification & diagnostics")
        val diagGroup = kit.group(root)
        kit.row(diagGroup, R.drawable.oic_shield_fill, "Complete Access Doctor scan", "Inspect Android permissions and live shell capabilities") {
            runCompleteAccessScan()
        }
        kit.row(diagGroup, R.drawable.oic_insights, "Scan authority capabilities", "Check supported bridge operations on this device") {
            scanAuthorityCapabilities()
        }
        kit.row(diagGroup, R.drawable.oic_developer_mode, "Test ADB shell identity", "Run a read-only identity check") {
            testAuthority()
        }
        kit.row(diagGroup, R.drawable.oic_ac_unit, "Test app suspension & restore", "Suspend a chosen app and immediately restore it") {
            chooseSuspensionTestApp()
        }
        kit.row(diagGroup, R.drawable.oic_tune, "Permission diagnostics & repair", "Inspect each permission and open its repair step") {
            reviewPermissionRepairs()
        }
        forgetButton = kit.row(diagGroup, R.drawable.oic_delete, "Forget saved endpoint", "Clears OEA's saved host & port", value = "Clear") {
            kit.confirm("Forget saved endpoint?", "Clears OEA's stored endpoint. To revoke Android trust, open Wireless debugging › Paired devices.", "Forget", destructive = true) {
                OeaAdbAuthority.forget(this@OeaAuthorityActivity)
                codeField.setText("")
                refreshStatus()
                toast("Saved endpoint cleared.")
            }
        }

        kit.note(root, "OEA Authority runs typed commands over its local Wireless Debugging bridge with no free-form shell. Revoke trust anytime in Developer options › Wireless debugging.")
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
                showReportSheet("Access Doctor report", report)
                refreshStatus("Complete Access Doctor scan finished.")
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
                showReportSheet("Authority capabilities", message)
                refreshStatus("Capability scan complete.")
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
        val names = apps.map { pm.getApplicationLabel(it).toString() }
        val pkgs = apps.map { it.packageName }
        kit.choose(
            "Choose a test app",
            "OEA will suspend this app, verify Android's state, and immediately restore it. Pick a non-critical app.",
            names,
            -1,
            pkgs
        ) { which ->
            val app = apps[which]
            setBusy(true, "Testing real suspension and restoration for ${app.packageName}…")
            work.launch {
                val outcome = withContext(Dispatchers.IO) {
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
            val labels = findings.map { finding -> finding.privilege.name.replace('_', ' ') }
            val subs = findings.map { finding -> finding.state.name.replace('_', ' ') }
            kit.choose("Permission diagnostics", "Select any permission to inspect and run its repair flow", labels, -1, subs) { which ->
                val finding = findings[which]
                val detail = "${finding.state.name.replace('_', ' ')}\n\nEvidence: ${finding.evidence}\n\nNext step: ${finding.advice}" +
                    (finding.command?.let { "\n\nRepair command: $it" } ?: "")
                kit.confirm(finding.privilege.name.replace('_', ' '), detail, "Repair / Settings", negative = "Close") {
                    OeaPrivileges.grant(this@OeaAuthorityActivity, finding.privilege) { result ->
                        val message = "${finding.privilege.name.replace('_', ' ')}: ${result.kind.name}\n${result.message}"
                        refreshStatus(message)
                        toast(message)
                    }
                }
            }
        }
    }

    private fun refreshStatus(lastResult: String? = null) {
        val status = OeaAdbAuthority.status(this)
        statusText.text = if (lastResult.isNullOrBlank()) status.note else "$lastResult\n\n${status.note}"
        statusText.setTextColor(if (status.connected) palette.success else if (!lastResult.isNullOrBlank()) palette.text else palette.textSecondary)
        if (status.host != null && hostField.text.isNullOrBlank()) hostField.setText(status.host)
        if (status.port != null && connectPortField.text.isNullOrBlank()) connectPortField.setText(status.port.toString())
        reconnectButton.isEnabled = status.configured
        reconnectButton.alpha = if (status.configured) 1f else 0.5f
        forgetButton.isEnabled = status.configured
        forgetButton.alpha = if (status.configured) 1f else 0.5f
    }

    private fun setBusy(busy: Boolean, message: String) {
        pairButton.isEnabled = !busy
        pairButton.alpha = if (busy) 0.55f else 1f
        val canReconnect = !busy && OeaAdbAuthority.status(this).configured
        reconnectButton.isEnabled = canReconnect
        reconnectButton.alpha = if (canReconnect) 1f else 0.5f
        statusText.text = message
        statusText.setTextColor(if (busy) palette.accent else palette.text)
    }

    private fun authField(label: String, hint: String, numeric: Boolean = false, password: Boolean = false): EditText =
        kit.field(hint, "", numeric = numeric, password = password).apply {
            background = kit.rounded(palette.background, 16, palette.stroke, 1)
            contentDescription = label
            setSelectAllOnFocus(false)
            if (!numeric && !password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(9) }
        }

    private fun showReportSheet(title: String, report: String) {
        kit.sheet(title, null) { panel, dialog ->
            val body = kit.text(report, 12.5f, palette.text).apply {
                setTextIsSelectable(true)
                setLineSpacing(kit.dpf(2f), 1f)
                setPadding(kit.dp(14), kit.dp(12), kit.dp(14), kit.dp(14))
                background = kit.rounded(palette.card, 18, palette.stroke, 1)
            }
            panel.addView(kit.boundedScroll(body, 0.58f), LinearLayout.LayoutParams(-1, -2))
            panel.addView(kit.buttonRow(
                kit.quietButton("Close") { dialog.dismiss() },
                kit.primaryButton("Copy") {
                    dialog.dismiss()
                    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(title, report))
                    toastShort("Report copied")
                }
            ))
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun toastShort(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        OeaAssistant.removeListener(pairingListener)
        work.cancel()
        super.onDestroy()
    }
}
