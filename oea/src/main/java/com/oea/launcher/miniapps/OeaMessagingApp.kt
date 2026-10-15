package com.oea.launcher.miniapps

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.oea.launcher.R
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * OEA Messaging: proper messaging mini-app with SMS functionality.
 * Checks Android requirements for SMS permissions and default role.
 */
object OeaMessagingApp {
    private val io = Executors.newSingleThreadExecutor { Thread(it, "OeaMessaging").apply { isDaemon = true } }

    data class SmsThread(val address: String, val snippet: String, val date: Long, val count: Int)

    private fun hasSmsPermission(ctx: Context): Boolean {
        return ctx.checkSelfPermission(android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun queryThreads(ctx: Context): List<SmsThread> {
        val list = mutableListOf<SmsThread>()
        try {
            val uri = Uri.parse("content://mms-sms/conversations?simple=true")
            val cursor: Cursor? = ctx.contentResolver.query(uri, null, null, null, "date DESC")
            cursor?.use { c ->
                val addrIdx = c.getColumnIndex("address")
                val snippetIdx = c.getColumnIndex("snippet")
                val dateIdx = c.getColumnIndex("date")
                while (c.moveToNext()) {
                    val addr = if (addrIdx >= 0) c.getString(addrIdx) ?: "Unknown" else "Unknown"
                    val snippet = if (snippetIdx >= 0) c.getString(snippetIdx) ?: "" else ""
                    val date = if (dateIdx >= 0) c.getLong(dateIdx) else 0L
                    list.add(SmsThread(addr, snippet, date, 1))
                    if (list.size >= 50) break
                }
            }
        } catch (_: Throwable) {
            // Fallback to inbox
            try {
                val cursor = ctx.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE), null, null, "date DESC")
                cursor?.use { c ->
                    while (c.moveToNext()) {
                        val addr = c.getString(0) ?: "Unknown"
                        val body = c.getString(1) ?: ""
                        val date = c.getLong(2)
                        list.add(SmsThread(addr, body.take(80), date, 1))
                        if (list.size >= 50) break
                    }
                }
            } catch (_: Throwable) {}
        }
        return list.distinctBy { it.address }
    }

    fun build(host: Activity, palette: OeaPalette): View {
        val kit = OeaKit(host, palette)
        val column = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(kit.text("OEA Messaging", 22f, palette.text, OeaType.medium), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(kit.circleIconButton(R.drawable.oic_refresh, "Refresh", palette.cardHigh, palette.text) {
            (host as? OeaMiniAppActivity)?.refresh()
        })
        column.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })

        if (!hasSmsPermission(host)) {
            kit.banner(column, R.drawable.oic_info, "SMS permission needed", "OEA needs READ_SMS and SEND_SMS to show messages. It also needs to be default SMS app to receive new messages reliably.", OeaKit.Tone.WARNING, "Allow") {
                try {
                    host.requestPermissions(arrayOf(android.Manifest.permission.READ_SMS, android.Manifest.permission.SEND_SMS, android.Manifest.permission.READ_CONTACTS), 1001)
                } catch (_: Throwable) {
                    try { host.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${host.packageName}"))) } catch (_: Throwable) {}
                }
            }
            kit.note(column, "Android requires SMS apps to be set as default to send/receive. OEA handles this via RoleManager on Android 10+ and explains fallback.")
            column.addView(kit.primaryButton("Set as default SMS", R.drawable.oic_edit) {
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        val rm = host.getSystemService(android.app.role.RoleManager::class.java)
                        if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_SMS)) {
                            host.startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_SMS), 1002)
                            return@primaryButton
                        }
                    }
                    host.startActivity(Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, host.packageName))
                } catch (_: Throwable) {
                    android.widget.Toast.makeText(host, "Open Settings › Apps › Default apps › SMS", android.widget.Toast.LENGTH_LONG).show()
                }
            }, LinearLayout.LayoutParams(-1, kit.dp(50)).apply { topMargin = kit.dp(12) })
            return column
        }

        val content = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        column.addView(content)
        content.addView(kit.text("Loading messages…", 13f, palette.textSecondary))

        io.execute {
            val threads = queryThreads(host)
            host.runOnUiThread {
                if (host.isDestroyed) return@runOnUiThread
                content.removeAllViews()
                if (threads.isEmpty()) {
                    kit.note(content, "No messages found.")
                } else {
                    val g = kit.group(content)
                    threads.forEach { t ->
                        val dateStr = SimpleDateFormat("MMM d HH:mm", Locale.getDefault()).format(Date(t.date))
                        kit.row(g, R.drawable.oic_person, t.address, "${t.snippet}\n$dateStr") {
                            openConversation(host, kit, t.address)
                        }
                    }
                }
                content.addView(kit.primaryButton("New message", R.drawable.oic_add) {
                    composeSms(host, kit, null)
                }, LinearLayout.LayoutParams(-1, kit.dp(50)).apply { topMargin = kit.dp(12) })
            }
        }
        return column
    }

    private fun openConversation(host: Activity, kit: OeaKit, address: String) {
        kit.sheet(address, "Conversation", build = { panel, dialog ->
            val msgs = mutableListOf<Pair<String, Long>>()
            try {
                val cursor = host.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.ADDRESS), "${Telephony.Sms.ADDRESS} = ?", arrayOf(address), "date ASC")
                cursor?.use { c ->
                    while (c.moveToNext()) {
                        msgs.add(c.getString(0) to c.getLong(1))
                        if (msgs.size >= 100) break
                    }
                }
            } catch (_: Throwable) {}
            if (msgs.isEmpty()) {
                panel.addView(kit.text("No messages in this conversation", 13f, kit.palette.textSecondary))
            } else {
                msgs.takeLast(20).forEach { (body, date) ->
                    val row = LinearLayout(host).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(kit.dp(12), kit.dp(8), kit.dp(12), kit.dp(8))
                        background = kit.rounded(kit.palette.card, 16, kit.palette.stroke, 1)
                    }
                    row.addView(kit.text(body, 14f, kit.palette.text).apply { setLineSpacing(kit.dpf(2f), 1f) })
                    row.addView(kit.text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(date)), 11f, kit.palette.textTertiary))
                    panel.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(6) })
                }
            }
            panel.addView(kit.buttonRow(
                kit.quietButton("Close") { dialog.dismiss() },
                kit.primaryButton("Reply") {
                    dialog.dismiss()
                    composeSms(host, kit, address)
                }
            ))
        })
    }

    private fun composeSms(host: Activity, kit: OeaKit, address: String?) {
        kit.sheet("New message", null) { panel, dialog ->
            val toField = kit.field("To", address ?: "")
            val bodyField = kit.field("Message")
            panel.addView(toField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            panel.addView(bodyField, LinearLayout.LayoutParams(-1, -2))
            panel.addView(kit.buttonRow(
                kit.quietButton("Cancel") { dialog.dismiss() },
                kit.primaryButton("Send") {
                    val to = toField.text.toString().trim()
                    val body = bodyField.text.toString().trim()
                    if (to.isEmpty() || body.isEmpty()) {
                        android.widget.Toast.makeText(host, "Enter recipient and message", android.widget.Toast.LENGTH_SHORT).show()
                        return@primaryButton
                    }
                    try {
                        val sm = android.telephony.SmsManager.getDefault()
                        sm.sendTextMessage(to, null, body, null, null)
                        android.widget.Toast.makeText(host, "Message sent", android.widget.Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        (host as? OeaMiniAppActivity)?.refresh()
                    } catch (e: Throwable) {
                        android.widget.Toast.makeText(host, "Failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            ))
        }
    }
}
