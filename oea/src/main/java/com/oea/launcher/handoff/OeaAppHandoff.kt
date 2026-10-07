package com.oea.launcher.handoff

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.core.content.FileProvider
import java.io.File

object OeaAppHandoff {
    fun shareApp(context: Context, app: ApplicationInfo, label: String): Boolean {
        val sourcePaths = buildList {
            add(app.sourceDir)
            app.splitSourceDirs?.let { addAll(it) }
        }.distinct()
        val dir = File(context.cacheDir, "handoff").apply { mkdirs() }
        val uris = sourcePaths.mapIndexed { index, path ->
            val source = File(path)
            val safe = source.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val target = File(dir, index.toString() + "_" + safe)
            source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", target)
        }
        if (uris.isEmpty()) return false
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "application/octet-stream"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        intent.putExtra(Intent.EXTRA_TEXT, "OEA App Handoff • $label")
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Send $label with OEA"))
        return true
    }
}