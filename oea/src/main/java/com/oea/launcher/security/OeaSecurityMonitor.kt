package com.oea.launcher.security

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobService
import android.app.job.JobWorkItem
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.util.concurrent.Executors

/** Android delivers install/update events; JobScheduler runs scans when allowed.
 * This is after installation, not a pre-install or continuous filesystem guard. */
class OeaSecurityPackageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_ADDED && intent.action != Intent.ACTION_PACKAGE_REPLACED) return
        val pkg = intent.data?.schemeSpecificPart ?: return
        if (pkg == context.packageName || !context.getSharedPreferences("security_scan", Context.MODE_PRIVATE).getBoolean("monitor", false)) return
        val scheduler = context.getSystemService(android.app.job.JobScheduler::class.java) ?: return
        val job = JobInfo.Builder(736821, ComponentName(context, OeaSecurityScanJob::class.java)).build()
        runCatching { scheduler.enqueue(job, JobWorkItem(Intent().putExtra("package", pkg))) }
    }
}

class OeaSecurityScanJob : JobService() {
    private val executor = Executors.newSingleThreadExecutor()
    private val generation = java.util.concurrent.atomic.AtomicInteger()
    override fun onStartJob(params: JobParameters): Boolean {
        val run = generation.incrementAndGet()
        fun stopped() = generation.get() != run
        executor.execute {
            var work = params.dequeueWork()
            while (work != null && !stopped()) {
                val item = work
                try {
                    if (getSharedPreferences("security_scan", MODE_PRIVATE).getBoolean("monitor", false)) {
                        item.intent.getStringExtra("package")?.let { scanPackage(it) { stopped() } }
                    }
                    if (!stopped()) params.completeWork(item)
                } catch (_: Exception) {
                    // Failure or interruption is surfaced, not treated as a clean result.
                    if (!stopped()) {
                        notifyResult("Scan could not finish", "An installed app could not be checked. Open Console to scan again.", null, 736823)
                        runCatching { params.completeWork(item) }
                    }
                }
                work = if (stopped()) null else params.dequeueWork()
            }
            if (!stopped()) jobFinished(params, false)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { generation.incrementAndGet(); return true }
    override fun onDestroy() { generation.incrementAndGet(); executor.shutdownNow(); super.onDestroy() }

    private fun scanPackage(pkg: String, stopped: () -> Boolean) {
        val database = File(filesDir, "security/signatures.txt")
        if (!database.isFile) {
            notifyResult("No malware signatures installed", "Automatic install checks cannot identify malware until you import a signature list.", null, 736824)
            return
        }
        val hashes = OeaSignatureEngine.parse(database.readText()).hashes
        if (hashes.isEmpty()) throw IllegalStateException("Empty signature list")
        val app = packageManager.getApplicationInfo(pkg, 0)
        if (app.flags and ApplicationInfo.FLAG_SYSTEM != 0) return
        val paths = listOfNotNull(app.sourceDir) + (app.splitSourceDirs?.toList() ?: emptyList())
        if (paths.isEmpty()) throw IllegalStateException("APK path unavailable")
        var matched = false
        for (path in paths) {
            if (stopped()) return
            if (File(path).inputStream().use { OeaSignatureEngine.scan(it, hashes) }.matched) matched = true
        }
        if (matched && !stopped()) {
            val label = packageManager.getApplicationLabel(app).toString()
            notifyResult("Signature match: $label", "A file matches your imported list. Verify the source and review this app.", pkg, pkg.hashCode())
        }
    }

    private fun notifyResult(title: String, text: String, pkg: String?, id: Int) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel("oea_security", "Security scan alerts", NotificationManager.IMPORTANCE_HIGH))
        val intent = if (pkg != null) Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            else Intent(this, OeaSecurityScanActivity::class.java)
        val pending = PendingIntent.getActivity(this, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = android.app.Notification.Builder(this, "oea_security")
            .setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle(title).setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text)).setContentIntent(pending).setAutoCancel(true).build()
        try { manager.notify(id, notification) } catch (_: SecurityException) { /* Notification permission was revoked. */ }
    }
}
