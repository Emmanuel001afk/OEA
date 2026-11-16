package com.oea.launcher.security

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.AtomicFile
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.oea.launcher.R
import com.oea.launcher.fault.OeaFaultShield
import com.oea.launcher.ui.OeaAppKit
import com.oea.launcher.ui.OeaInsets
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaTheme
import com.oea.launcher.ui.OeaType
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InterruptedIOException
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/** On-demand local SHA-256 malware signature scanner. No files or hashes leave the device. */
class OeaSecurityScanActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "OeaSecurityScan").apply { isDaemon = true } }
    private lateinit var palette: OeaPalette
    private lateinit var kit: OeaAppKit
    private lateinit var content: LinearLayout
    private lateinit var reportCard: LinearLayout
    private lateinit var reportTitle: TextView
    private lateinit var reportBody: TextView
    private var lastReportTitle = ""
    private var lastReportBody = ""
    private val databaseFile get() = File(filesDir, "security/signatures.txt")
    private val prefs by lazy { getSharedPreferences("security_scan", MODE_PRIVATE) }
    @Volatile private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        OeaFaultShield.guard("security-scan:create") {
            super.onCreate(savedInstanceState)
            lastReportTitle = savedInstanceState?.getString("report_title").orEmpty()
            lastReportBody = savedInstanceState?.getString("report_body").orEmpty()
            buildShell()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("report_title", lastReportTitle)
        outState.putString("report_body", lastReportBody)
        super.onSaveInstanceState(outState)
    }

    private fun buildShell() {
        palette = OeaTheme.resolve(this)
        kit = OeaAppKit(this, palette)
        OeaInsets.edgeToEdge(window, lightBars = !palette.dark)

        val shell = kit.appShell(
            title = "Security Scan",
            subtitle = "On-device SHA-256 checks · never uploaded",
            onBack = { finish() }
        )
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        shell.body.addView(content, LinearLayout.LayoutParams(-1, -2))
        setContentView(shell.root)
        render()
    }

    private fun render() {
        content.removeAllViews()
        val count = prefs.getInt("count", 0)
        val imported = prefs.getLong("imported", 0L)
        val monitorOn = prefs.getBoolean("monitor", false)

        if (count == 0) {
            kit.banner(
                content,
                R.drawable.oic_shield,
                "No signature list imported yet",
                "Import a plain-text SHA-256 hash list from a trusted threat-intelligence source. Without one, only the EICAR test file is detected.",
                OeaKit.Tone.INFO,
                "Import list"
            ) { pick(DATABASE_REQUEST) }
        }

        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        stats.addView(
            kit.statTile("Signatures", if (count == 0) "EICAR" else count.toString(), if (count > 0) palette.success else palette.text),
            LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = kit.dp(8) }
        )
        stats.addView(
            kit.statTile("Install watch", if (monitorOn) "Active" else "Off", if (monitorOn) palette.accent else palette.textSecondary),
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        content.addView(stats, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(10) })

        val scanRow = kit.buttonRow(
            kit.primaryButton(if (busy) "Working…" else "Scan apps", R.drawable.oic_shield_fill) { scanApps() },
            kit.tonalButton("Scan file", R.drawable.oic_folder) { pick(FILE_REQUEST) }
        )
        scanRow.alpha = if (busy) 0.55f else 1f
        content.addView(scanRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })

        // Report card
        reportCard = kit.card(paddingDp = 16).apply {
            visibility = if (lastReportBody.isBlank()) View.GONE else View.VISIBLE
        }
        val reportHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        reportTitle = kit.text(lastReportTitle.ifBlank { "Scan output" }, 15f, palette.text, OeaType.medium)
        reportHeader.addView(reportTitle, LinearLayout.LayoutParams(0, -2, 1f))
        reportHeader.addView(kit.text("Copy", 13f, palette.accent, OeaType.medium).apply {
            isClickable = true
            setOnClickListener {
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Security scan", lastReportBody))
                Toast.makeText(this@OeaSecurityScanActivity, "Report copied", Toast.LENGTH_SHORT).show()
            }
        })
        reportCard.addView(reportHeader, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(6) })
        reportBody = kit.text(lastReportBody, 13f, palette.textSecondary).apply {
            setLineSpacing(kit.dpf(2.5f), 1f)
            setTextIsSelectable(true)
        }
        reportCard.addView(reportBody, LinearLayout.LayoutParams(-1, -2))
        content.addView(reportCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(4); bottomMargin = kit.dp(6) })

        kit.sectionTitle(content, "Protection")
        val protectGroup = kit.group(content)
        kit.switchRow(
            protectGroup,
            R.drawable.oic_shield,
            "Check new & updated apps",
            "Runs a delayed hash check after package installs (needs notifications & signatures)",
            monitorOn
        ) { checked ->
            prefs.edit().putBoolean("monitor", checked).apply()
            if (checked && Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7213)
            }
            if (checked) {
                showReport(
                    "Install checks enabled",
                    "Checks run after install when Android schedules them. Alerts require notification permission and an imported signature list; this is not real-time or pre-install protection."
                )
            } else {
                render()
            }
        }

        kit.sectionTitle(content, "Signature database")
        val dbGroup = kit.group(content)
        val importedSub = if (count > 0 && imported > 0L) {
            "Imported " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(imported))
        } else {
            "Select a plain-text SHA-256 hash list (max 20 MB)"
        }
        kit.row(dbGroup, R.drawable.oic_add, "Import SHA-256 signature list", importedSub, chevron = true) {
            pick(DATABASE_REQUEST)
        }
        kit.row(dbGroup, R.drawable.oic_open_in_new, "Where to get signatures", "Download plain-text SHA-256 exports (e.g. MalwareBazaar)", chevron = true) {
            showReport(
                "Obtaining signature lists",
                "Download a plain-text SHA-256 hash export from a trusted threat-intelligence provider (for example MalwareBazaar after obtaining an Auth-Key). Never download malware samples themselves. OEA does not verify or auto-update imported lists."
            )
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://bazaar.abuse.ch/export/"))) }
        }
        if (count > 0 || databaseFile.exists()) {
            kit.row(dbGroup, R.drawable.oic_delete, "Remove imported signatures", "Keeps only the harmless EICAR test signature", value = "Clear") {
                if (busy) return@row
                kit.confirm("Remove imported signatures?", "Clears all $count imported SHA-256 hashes from local storage.", "Remove", destructive = true) {
                    AtomicFile(databaseFile).delete()
                    prefs.edit().clear().apply()
                    showReport("Signatures removed", "Imported signatures removed. The harmless EICAR test signature remains.")
                }
            }
        }

        kit.note(
            content,
            "All SHA-256 comparisons run locally on your phone with zero uploads. Exact hash matching cannot catch modified, repacked or unknown threats — a clean scan does not guarantee safety."
        )
    }

    private fun showReport(title: String, body: String) {
        lastReportTitle = title
        lastReportBody = body
        render()
    }

    private fun pick(request: Int) {
        if (busy) return
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }, request)
        } catch (_: Exception) {
            showReport("Picker unavailable", "No document picker is available on this device.")
        }
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            DATABASE_REQUEST -> importDatabase(uri)
            FILE_REQUEST -> scanFile(uri)
        }
    }

    private fun runTask(title: String, workingMessage: String, task: () -> String) {
        if (busy) return
        busy = true
        showReport(title, workingMessage)
        worker.execute {
            val result = try {
                task()
            } catch (e: Exception) {
                "Could not complete: ${e.message ?: e.javaClass.simpleName}"
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    busy = false
                    showReport(title, result)
                }
            }
        }
    }

    private fun readDatabase(): OeaSignatureEngine.Database =
        if (databaseFile.exists()) OeaSignatureEngine.parse(databaseFile.readText()) else OeaSignatureEngine.Database(emptySet(), 0)

    private fun importDatabase(uri: Uri) = runTask("Signature import", "Importing SHA-256 signatures…") {
        val bytes = contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                if (buffer.size() + n > OeaSignatureEngine.MAX_DATABASE_BYTES) error("Signature list exceeds 20 MB")
                buffer.write(chunk, 0, n)
            }
            buffer.toByteArray()
        } ?: error("Cannot open signature list")
        val database = OeaSignatureEngine.parse(bytes.toString(Charsets.UTF_8))
        require(database.hashes.isNotEmpty()) { "No valid SHA-256 signatures found; existing list retained" }
        databaseFile.parentFile?.mkdirs()
        val atomic = AtomicFile(databaseFile)
        val stream = atomic.startWrite()
        try {
            stream.write(database.hashes.joinToString("\n").toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
        prefs.edit().putInt("count", database.hashes.size).putLong("imported", System.currentTimeMillis()).apply()
        "Imported ${database.hashes.size} SHA-256 hashes (${database.ignored} invalid lines skipped). Run a scan to check apps or files against them."
    }

    private fun scanFile(uri: Uri) = runTask("File scan", "Computing SHA-256 and checking signatures…") {
        val db = readDatabase()
        val result = contentResolver.openInputStream(uri)?.use { OeaSignatureEngine.scan(it, db.hashes) }
            ?: error("Cannot open selected file")
        describe(result) + "\nSHA-256: ${result.sha256}\n\n" + limitation(db.hashes.size)
    }

    private fun scanApps() = runTask("Installed apps scan", "Scanning installed APK packages — this may take a minute…") {
        val db = readDatabase()
        val apps = packageManager.getInstalledApplications(0).filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
        var files = 0
        var failed = 0
        val matches = mutableListOf<String>()
        apps.forEach { app ->
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Scan cancelled")
            val paths = listOfNotNull(app.sourceDir) + (app.splitSourceDirs?.toList() ?: emptyList())
            if (paths.isEmpty()) failed++
            paths.forEach { path ->
                try {
                    val result = File(path).inputStream().use { OeaSignatureEngine.scan(it, db.hashes) }
                    files++
                    if (result.testFile || result.matched) {
                        val label = runCatching { packageManager.getApplicationLabel(app).toString() }.getOrDefault(app.packageName)
                        matches.add("• $label (${app.packageName})\n  ${describe(result)}\n  ${result.sha256}\n  Open Android Settings › Apps to inspect or uninstall.")
                    }
                } catch (_: Exception) {
                    failed++
                }
            }
        }
        "Scanned $files APK files across ${apps.size} user apps" +
            (if (failed > 0) " ($failed unreadable)." else ".") +
            "\n\n" +
            (if (matches.isEmpty()) "No signature matches found." else "${matches.size} match(es) detected:\n\n" + matches.joinToString("\n\n")) +
            "\n\n" + limitation(db.hashes.size)
    }

    private fun describe(result: OeaSignatureEngine.Result) = when {
        result.testFile -> "EICAR antivirus test file detected (harmless test file)."
        result.matched -> "Exact SHA-256 match in your imported list. Verify with the list provider before acting."
        else -> "No signature match found."
    }

    private fun limitation(count: Int) = "Checked against $count imported signatures plus EICAR. System apps and private app data are not scanned."

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val FILE_REQUEST = 7211
        private const val DATABASE_REQUEST = 7212
    }
}
