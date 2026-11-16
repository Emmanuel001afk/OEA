package com.oea.launcher.security

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

/** On-demand local scanning. No files or hashes are uploaded. */
class OeaSecurityScanActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var output: TextView
    private lateinit var status: TextView
    private lateinit var actions: LinearLayout
    private val databaseFile get() = File(filesDir, "security/signatures.txt")
    private val prefs by lazy { getSharedPreferences("security_scan", MODE_PRIVATE) }
    @Volatile private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(24)) }
        content.addView(TextView(this).apply { text = "Malware signature scan"; textSize = 24f })
        content.addView(TextView(this).apply {
            text = "Scans installed APK files or a file you choose using exact SHA-256 matches. No uploads. Imported lists are not verified by OEA; use only a source you trust. A clean result does not prove safety."
            textSize = 14f; setPadding(0, dp(12), 0, dp(12))
        })
        status = TextView(this).apply { textSize = 15f; setPadding(0, dp(8), 0, dp(8)) }
        content.addView(status)
        actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun button(title: String, action: () -> Unit) {
            actions.addView(Button(this).apply { text = title; isAllCaps = false; setOnClickListener { action() } })
        }
        val monitor = android.widget.Switch(this).apply {
            text = "Check newly installed and updated apps"
            isChecked = prefs.getBoolean("monitor", false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("monitor", checked).apply()
                if (checked && android.os.Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7213)
                }
                if (checked) output.text = "Checks run after install when Android schedules them. Alerts require notification permission and an imported malware list; this is not continuous or pre-install protection."
            }
        }
        actions.addView(monitor)
        button("Scan installed apps") { scanApps() }
        button("Scan a file") { pick(FILE_REQUEST) }
        button("Import SHA-256 signature list") { pick(DATABASE_REQUEST) }
        button("Where to get signatures") {
            output.text = "Get a plain-text SHA-256 hash export from a trusted threat intelligence provider. For example, MalwareBazaar offers exports after you obtain an Auth-Key from its website. Download the SHA-256 list to your phone, then choose Import. Do not download malware samples. OEA does not verify the list or update it automatically."
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://bazaar.abuse.ch/export/"))) }
        }
        button("Remove imported signatures") {
            android.util.AtomicFile(databaseFile).delete()
            prefs.edit().clear().apply()
            updateStatus()
            output.text = "Imported signatures removed. The harmless EICAR test signature remains."
        }
        content.addView(actions)
        output = TextView(this).apply { textSize = 14f; setPadding(0, dp(12), 0, dp(12)); setTextIsSelectable(true) }
        content.addView(output)
        content.addView(Button(this).apply { text = "Back to Console"; isAllCaps = false; gravity = Gravity.CENTER; setOnClickListener { finish() } })
        setContentView(ScrollView(this).apply { addView(content) })
        updateStatus()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    private fun updateStatus() {
        val count = prefs.getInt("count", 0)
        val imported = prefs.getLong("imported", 0L)
        status.text = if (count == 0) "No malware signatures installed. Only the EICAR test file can be detected."
        else "$count imported signatures • imported ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(imported))}. No automatic updates."
    }

    private fun pick(request: Int) {
        if (busy) return
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            }, request)
        } catch (_: Exception) { output.text = "No document picker is available." }
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            DATABASE_REQUEST -> importDatabase(uri)
            FILE_REQUEST -> scanFile(uri)
        }
    }

    private fun runTask(message: String, task: () -> String) {
        if (busy) return
        busy = true
        for (i in 0 until actions.childCount) actions.getChildAt(i).isEnabled = false
        output.text = message
        worker.execute {
            val result = try { task() } catch (e: Exception) { "Could not complete: ${e.message ?: e.javaClass.simpleName}" }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    busy = false
                    for (i in 0 until actions.childCount) actions.getChildAt(i).isEnabled = true
                    updateStatus()
                    output.text = result
                }
            }
        }
    }

    private fun readDatabase(): OeaSignatureEngine.Database =
        if (databaseFile.exists()) OeaSignatureEngine.parse(databaseFile.readText()) else OeaSignatureEngine.Database(emptySet(), 0)

    private fun importDatabase(uri: Uri) = runTask("Importing signatures…") {
        val bytes = contentResolver.openInputStream(uri)?.use { input ->
            val buffer = java.io.ByteArrayOutputStream()
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
        require(database.hashes.isNotEmpty()) { "No valid SHA-256 signatures found; existing list was retained" }
        databaseFile.parentFile?.mkdirs()
        val atomic = android.util.AtomicFile(databaseFile)
        val stream = atomic.startWrite()
        try {
            stream.write(database.hashes.joinToString("\n").toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) { atomic.failWrite(stream); throw e }
        prefs.edit().putInt("count", database.hashes.size).putLong("imported", System.currentTimeMillis()).apply()
        "Imported ${database.hashes.size} hashes; ${database.ignored} invalid lines ignored. Source and accuracy are your responsibility. Run a scan to use them."
    }

    private fun scanFile(uri: Uri) = runTask("Scanning selected file…") {
        val db = readDatabase()
        val result = contentResolver.openInputStream(uri)?.use { OeaSignatureEngine.scan(it, db.hashes) }
            ?: error("Cannot open selected file")
        describe(result) + "\nSHA-256: ${result.sha256}\n" + limitation(db.hashes.size)
    }

    private fun scanApps() = runTask("Scanning installed app APKs. This may take several minutes…") {
        val db = readDatabase()
        val apps = packageManager.getInstalledApplications(0).filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
        var files = 0
        var failed = 0
        val matches = mutableListOf<String>()
        apps.forEach { app ->
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Scan cancelled")
            val paths = listOfNotNull(app.sourceDir) + (app.splitSourceDirs?.toList() ?: emptyList())
            if (paths.isEmpty()) failed++
            paths.forEach { path ->
                try {
                    val result = File(path).inputStream().use { OeaSignatureEngine.scan(it, db.hashes) }
                    files++
                    if (result.testFile || result.matched) {
                        val label = runCatching { packageManager.getApplicationLabel(app).toString() }.getOrDefault(app.packageName)
                        matches.add("$label (${app.packageName})\n${describe(result)}\n${result.sha256}\nOpen Android Settings › Apps to inspect or uninstall.")
                    }
                } catch (_: Exception) { failed++ }
            }
        }
        "Scanned $files APK files from ${apps.size} non-system apps; $failed files unavailable. System apps and private app data are not scanned.\n\n" +
            (if (matches.isEmpty()) "No signature matches." else "${matches.size} matches:\n\n" + matches.joinToString("\n\n")) + "\n\n" + limitation(db.hashes.size)
    }

    private fun describe(result: OeaSignatureEngine.Result) = when {
        result.testFile -> "EICAR antivirus test file detected (harmless, not malware)."
        result.matched -> "Exact hash match in your imported signature list. Verify with the list provider before taking action."
        else -> "No signature match."
    }
    private fun limitation(count: Int) = "Compared with $count imported signatures plus the EICAR test. Exact hashes miss variants, packed content and unknown threats. Install checks are delayed; no continuous protection or automatic updates."

    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
    companion object { private const val FILE_REQUEST = 7211; private const val DATABASE_REQUEST = 7212 }
}
