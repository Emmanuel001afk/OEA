package com.oea.launcher.miniapps

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.oea.launcher.R
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** OEA's local SMS inbox and composer. Android's provider remains the source of truth. */
object OeaMessagingApp {
    private val io = Executors.newSingleThreadExecutor { Thread(it, "OeaMessaging").apply { isDaemon = true } }

    data class SmsThread(val address: String, val snippet: String, val date: Long, val count: Int, val read: Boolean)
    data class SmsMessage(val id: Long, val address: String, val body: String, val date: Long, val type: Int)

    private const val PREFS = "oea_messaging_v2"
    private const val KEY_ARCHIVED = "archived_threads"
    private const val KEY_BLOCKED = "blocked_addresses"
    private const val KEY_SPAM_KEYWORDS = "spam_keywords"
    private const val REQUEST_CONTACT = 1003
    private const val REQUEST_SMS_ROLE = 1004
    private const val REQUEST_SMS_PERMISSION = 1005

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun hasPermission(ctx: Context, permission: String) =
        ctx.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    private fun hasReadSmsPermission(ctx: Context) = hasPermission(ctx, android.Manifest.permission.READ_SMS)
    private fun hasSendSmsPermission(ctx: Context) = hasPermission(ctx, android.Manifest.permission.SEND_SMS)
    private fun isDefaultSmsApp(ctx: Context) = Telephony.Sms.getDefaultSmsPackage(ctx) == ctx.packageName

    private fun getContactName(ctx: Context, address: String): String {
        if (!hasPermission(ctx, android.Manifest.permission.READ_CONTACTS)) return address
        return try {
            val uri = Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
            val cursor = ctx.contentResolver.query(uri, arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            cursor?.use { c -> if (c.moveToFirst()) c.getString(0) else address } ?: address
        } catch (_: Throwable) { address }
    }

    /** Reads every SMS provider row and groups by sender/recipient, not by a non-public MMS-SMS cursor column. */
    private fun queryThreads(ctx: Context): List<SmsThread> {
        val index = OeaSmsThreadIndex()
        val projection = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.READ)
        val cursor: Cursor = ctx.contentResolver.query(Telephony.Sms.CONTENT_URI, projection, null, null, "${Telephony.Sms.DATE} DESC")
            ?: throw IllegalStateException("SMS provider returned no cursor")
        cursor.use { c ->
            val addressIndex = c.getColumnIndex(Telephony.Sms.ADDRESS)
            val bodyIndex = c.getColumnIndex(Telephony.Sms.BODY)
            val dateIndex = c.getColumnIndex(Telephony.Sms.DATE)
            val readIndex = c.getColumnIndex(Telephony.Sms.READ)
            if (addressIndex < 0 || bodyIndex < 0 || dateIndex < 0 || readIndex < 0) return@use
            while (c.moveToNext()) {
                index.add(c.getString(addressIndex), c.getString(bodyIndex), c.getLong(dateIndex), c.getInt(readIndex) != 0)
            }
        }
        return index.threads()
    }

    /** Searches message bodies as well as the latest-message snippet and recipient. */
    private fun queryMatchingAddresses(ctx: Context, query: String): Set<String> {
        if (query.isBlank()) return emptySet()
        val addresses = linkedSetOf<String>()
        val needle = "%${query.trim()}%"
        val cursor = ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS),
            "${Telephony.Sms.BODY} LIKE ? OR ${Telephony.Sms.ADDRESS} LIKE ?",
            arrayOf(needle, needle),
            null
        )
        cursor?.use { c -> while (c.moveToNext()) c.getString(0)?.trim()?.takeIf { it.isNotEmpty() }?.let(addresses::add) }
        return addresses
    }

    private fun queryConversation(ctx: Context, address: String): List<SmsMessage> {
        val list = mutableListOf<SmsMessage>()
        val cursor = ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
            "${Telephony.Sms.ADDRESS} = ?",
            arrayOf(address),
            "${Telephony.Sms.DATE} ASC"
        )
        cursor?.use { c ->
            while (c.moveToNext()) {
                list.add(SmsMessage(c.getLong(0), c.getString(1) ?: address, c.getString(2) ?: "", c.getLong(3), c.getInt(4)))
            }
        }
        return list
    }

    private fun markConversationReadIfDefault(ctx: Context, address: String) {
        if (!isDefaultSmsApp(ctx)) return
        val values = ContentValues().apply {
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
        }
        runCatching {
            ctx.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                "${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.READ} = 0",
                arrayOf(address)
            )
        }
    }

    fun build(host: Activity, palette: OeaPalette): View {
        val kit = OeaKit(host, palette)
        var currentTab = "all"
        var searchQuery = ""
        var refresh: () -> Unit = {}
        val sendToRequest = host.intent?.takeIf { it.action == Intent.ACTION_SENDTO }?.let { intent ->
            (intent.data?.schemeSpecificPart?.trim().orEmpty().ifBlank { null }) to intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        }
        if (sendToRequest != null) host.setIntent(Intent(Intent.ACTION_MAIN))

        host.miniResultHook = { requestCode, resultCode, data ->
            when (requestCode) {
                REQUEST_SMS_ROLE -> {
                    if (host is OeaMiniAppActivity) host.refresh() else refresh()
                    true
                }
                REQUEST_CONTACT -> {
                    if (resultCode != Activity.RESULT_OK) false
                    else {
                        val number = readPickedNumber(host, data?.data)
                        val sink = contactPicked
                        contactPicked = null
                        if (number != null && sink != null) sink(number)
                        else android.widget.Toast.makeText(
                            host,
                            if (number == null) "That contact has no number OEA could read" else "No compose sheet is open",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        true
                    }
                }
                else -> false
            }
        }

        val column = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(kit.text("Messaging", 22f, palette.text, OeaType.medium), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(kit.circleIconButton(R.drawable.oic_search, "Search", palette.cardHigh, palette.text) {
            kit.prompt("Search all SMS", "Number or message text", "Search") { q ->
                searchQuery = q.trim()
                refresh()
                null
            }
        }, LinearLayout.LayoutParams(kit.dp(40), kit.dp(40)).apply { rightMargin = kit.dp(8) })
        header.addView(kit.circleIconButton(R.drawable.oic_refresh, "Refresh", palette.cardHigh, palette.text) { refresh() })
        column.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })

        val access = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        column.addView(access, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
        val readGranted = hasReadSmsPermission(host)
        val sendGranted = hasSendSmsPermission(host)
        val defaultSms = isDefaultSmsApp(host)
        if (!readGranted) {
            kit.banner(access, R.drawable.oic_info, "Allow message history", "READ_SMS lets OEA show the SMS already stored on this phone. OEA does not upload message contents.", OeaKit.Tone.WARNING, "Allow") {
                requestSmsPermission(host, android.Manifest.permission.READ_SMS)
            }
        }
        if (!sendGranted) {
            kit.banner(access, R.drawable.oic_info, "Allow sending from OEA", "SEND_SMS is only needed when you choose to send a text from this app.", OeaKit.Tone.INFO, "Allow") {
                requestSmsPermission(host, android.Manifest.permission.SEND_SMS)
            }
        }
        if (!hasPermission(host, android.Manifest.permission.READ_CONTACTS)) {
            kit.banner(access, R.drawable.oic_person, "Contact names are optional", "Without READ_CONTACTS, OEA still shows numbers. Grant this only if you want saved contact names beside them.", OeaKit.Tone.INFO, "Allow") {
                requestSmsPermission(host, android.Manifest.permission.READ_CONTACTS)
            }
        }
        if (defaultSms && android.os.Build.VERSION.SDK_INT >= 33 && !hasPermission(host, android.Manifest.permission.POST_NOTIFICATIONS)) {
            kit.banner(access, R.drawable.oic_info, "Message alerts are optional", "Allow notifications if you want OEA to show an alert when a new SMS arrives.", OeaKit.Tone.INFO, "Allow") {
                requestSmsPermission(host, android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (!defaultSms) {
            kit.banner(access, R.drawable.oic_edit, "OEA is not the default SMS app", "Existing SMS history can be read with READ_SMS. Making OEA default routes new SMS and MMS to OEA; MMS download and attachment handling are not complete yet, so keep your current app default if you rely on MMS.", OeaKit.Tone.WARNING, "Review") {
                kit.confirm("Before making OEA default", "OEA can receive and store SMS, but this version does not yet retrieve MMS content or attachments. If you rely on picture/group MMS, keep your current Messages app as default. Continue to Android's system confirmation anyway?", "Continue", onConfirm = { requestDefaultSmsRole(host) })
            }
        } else {
            kit.note(access, "OEA is default: SMS is stored and alerted here. MMS content and attachments are not yet downloaded by OEA; use your previous Messages app as default if you rely on MMS. Android may still ask separately for runtime SMS permissions.")
        }
        if (!readGranted) {
            kit.note(column, "You can set OEA as default separately, but SMS history cannot be read until Android grants READ_SMS. Contact names are optional and use READ_CONTACTS only when available.")
            sendToRequest?.let { request -> column.post { composeSms(host, kit, request.first, request.second) {} } }
            return column
        }

        val tabBar = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        column.addView(kit.hscroll(tabBar), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(10) })
        val content = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        column.addView(content)

        refresh = {
            tabBar.removeAllViews()
            listOf("all" to "All", "unread" to "Unread", "archived" to "Archived", "blocked" to "Blocked", "spam" to "Spam").forEach { (id, label) ->
                tabBar.addView(kit.chip(label, id == currentTab) { currentTab = id; refresh() }, LinearLayout.LayoutParams(-2, kit.dp(36)).apply { rightMargin = kit.dp(6) })
            }

            content.removeAllViews()
            content.addView(kit.text("Loading SMS…", 13f, palette.textSecondary))
            io.execute {
                val threadQuery = runCatching { queryThreads(host) }
                val threads = threadQuery.getOrDefault(emptyList())
                val bodyMatches = runCatching { queryMatchingAddresses(host, searchQuery) }.getOrDefault(emptySet())
                host.runOnUiThread {
                    if (host.isDestroyed) return@runOnUiThread
                    content.removeAllViews()
                    if (threadQuery.isFailure) {
                        kit.banner(content, R.drawable.oic_warning_fill, "Couldn't read SMS history", "Android rejected the SMS provider query. Confirm SMS access above, then retry; OEA will not substitute another app's private message data.", OeaKit.Tone.WARNING)
                        return@runOnUiThread
                    }
                    val archived = prefs(host).getStringSet(KEY_ARCHIVED, emptySet()) ?: emptySet()
                    val blocked = prefs(host).getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet()
                    val spamKeywords = (prefs(host).getString(KEY_SPAM_KEYWORDS, "lottery,win,prize,urgent") ?: "lottery,win,prize,urgent").split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
                    val filtered = threads.filter { thread ->
                        val isArchived = archived.contains(thread.address)
                        val isBlocked = blocked.contains(thread.address)
                        val isSpam = spamKeywords.any { word -> thread.snippet.lowercase().contains(word) || thread.address.lowercase().contains(word) }
                        val matchesSearch = searchQuery.isBlank() || thread.address.contains(searchQuery, true) || thread.snippet.contains(searchQuery, true) || bodyMatches.contains(thread.address)
                        val tabMatch = when (currentTab) {
                            "unread" -> !thread.read
                            "archived" -> isArchived
                            "blocked" -> isBlocked
                            "spam" -> isSpam
                            else -> !isArchived && !isBlocked
                        }
                        tabMatch && matchesSearch
                    }
                    if (filtered.isEmpty()) {
                        kit.note(content, when (currentTab) {
                            "unread" -> "No unread SMS threads."
                            "archived" -> "No archived threads."
                            "blocked" -> "No blocked addresses."
                            "spam" -> "No spam detected. Spam keywords: ${spamKeywords.joinToString()}"
                            else -> if (searchQuery.isNotBlank()) "No SMS matches \"$searchQuery\"" else "No SMS messages found. Check SMS access and the currently selected default app."
                        })
                    } else {
                        val group = kit.group(content)
                        filtered.forEach { thread ->
                            val contactName = getContactName(host, thread.address)
                            val date = SimpleDateFormat("MMM d HH:mm", Locale.getDefault()).format(Date(thread.date))
                            val isSpam = spamKeywords.any { word -> thread.snippet.lowercase().contains(word) }
                            val title = if (contactName != thread.address) "$contactName • ${thread.address}" else thread.address
                            val subtitle = "${thread.snippet}\n$date • ${thread.count} SMS${if (!thread.read) " • Unread" else ""}${if (isSpam) " • Spam?" else ""}"
                            kit.row(group, R.drawable.oic_person, title, subtitle) { openConversation(host, kit, thread.address) }
                        }
                    }

                    val actions = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, kit.dp(12), 0, 0) }
                    actions.addView(kit.primaryButton("New message", R.drawable.oic_add) { composeSms(host, kit, null) { refresh() } }, LinearLayout.LayoutParams(0, kit.dp(50), 1f).apply { rightMargin = kit.dp(8) })
                    actions.addView(kit.tonalButton("Manage spam keywords", R.drawable.oic_shield) {
                        val current = prefs(host).getString(KEY_SPAM_KEYWORDS, "lottery,win,prize,urgent") ?: ""
                        kit.prompt("Spam keywords", "Comma separated", "Keywords", current) { input ->
                            prefs(host).edit().putString(KEY_SPAM_KEYWORDS, input).apply()
                            refresh()
                            null
                        }
                    }, LinearLayout.LayoutParams(0, kit.dp(50), 1f))
                    content.addView(actions)
                    content.addView(kit.primaryButton("Export SMS list", R.drawable.oic_share_filled) {
                        val json = filtered.joinToString("\n") { "${it.address}: ${it.snippet} @ ${Date(it.date)}" }
                        try { host.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, json), "Export SMS list")) } catch (_: Throwable) {}
                    }, LinearLayout.LayoutParams(-1, kit.dp(46)).apply { topMargin = kit.dp(12) })
                    kit.note(content, "OEA reads the complete SMS provider history and searches message text. This first pass displays SMS; MMS attachment retrieval is a separate Android/APN-dependent feature.")
                }
            }
        }
        refresh()
        sendToRequest?.let { request -> column.post { composeSms(host, kit, request.first, request.second) { refresh() } } }
        return column
    }

    private fun requestSmsPermission(host: Activity, permission: String) {
        try { host.requestPermissions(arrayOf(permission), REQUEST_SMS_PERMISSION) }
        catch (_: Throwable) {
            try { host.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${host.packageName}"))) } catch (_: Throwable) {}
        }
    }

    private fun requestDefaultSmsRole(host: Activity) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val manager = host.getSystemService(android.app.role.RoleManager::class.java)
                if (manager != null && manager.isRoleAvailable(android.app.role.RoleManager.ROLE_SMS)) {
                    host.startActivityForResult(manager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_SMS), REQUEST_SMS_ROLE)
                    return
                }
            }
            host.startActivityForResult(
                Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                    .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, host.packageName),
                REQUEST_SMS_ROLE
            )
        } catch (_: Throwable) {
            android.widget.Toast.makeText(host, "Open Settings › Apps › Default apps › SMS and select OEA.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** Filled by the compose sheet, fired when Android hands a contact back. */
    private var contactPicked: ((String) -> Unit)? = null

    private fun openConversation(host: Activity, kit: OeaKit, address: String) {
        kit.sheet(address, "SMS conversation • ${getContactName(host, address)}") { panel, dialog ->
            val msgsHost = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
            panel.addView(msgsHost)
            msgsHost.addView(kit.text("Loading all messages…", 13f, kit.palette.textSecondary))
            io.execute {
                markConversationReadIfDefault(host, address)
                val messages = runCatching { queryConversation(host, address) }.getOrDefault(emptyList())
                host.runOnUiThread {
                    if (host.isDestroyed) return@runOnUiThread
                    msgsHost.removeAllViews()
                    if (messages.isEmpty()) {
                        kit.note(msgsHost, "No SMS messages in this conversation.")
                    } else {
                        var lastDate = ""
                        messages.forEach { message ->
                            val dateStr = SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(message.date))
                            if (dateStr != lastDate) {
                                msgsHost.addView(kit.text(dateStr, 12f, kit.palette.textTertiary, OeaType.medium).apply { gravity = Gravity.CENTER; setPadding(0, kit.dp(8), 0, kit.dp(4)) })
                                lastDate = dateStr
                            }
                            val sent = message.type != Telephony.Sms.MESSAGE_TYPE_INBOX
                            val status = when (message.type) {
                                Telephony.Sms.MESSAGE_TYPE_DRAFT -> "Draft"
                                Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED -> "Sending"
                                Telephony.Sms.MESSAGE_TYPE_FAILED -> "Failed to send"
                                else -> ""
                            }
                            val bubble = LinearLayout(host).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(kit.dp(12), kit.dp(8), kit.dp(12), kit.dp(8))
                                background = kit.rounded(if (sent) kit.palette.accent else kit.palette.card, 16, kit.palette.stroke, 1)
                            }
                            bubble.addView(kit.text(message.body, 14f, if (sent) kit.palette.onAccent else kit.palette.text).apply { setLineSpacing(kit.dpf(2f), 1f) })
                            val timeAndStatus = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.date)) + if (status.isNotEmpty()) " • $status" else ""
                            bubble.addView(kit.text(timeAndStatus, 11f, if (sent) kit.palette.onAccent else kit.palette.textTertiary))
                            val wrapper = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = if (sent) Gravity.END else Gravity.START }
                            wrapper.addView(bubble, LinearLayout.LayoutParams(-2, -2, if (sent) 0f else 1f).apply {
                                if (sent) leftMargin = kit.dp(40) else rightMargin = kit.dp(40)
                            })
                            msgsHost.addView(wrapper, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(6) })
                            bubble.setOnLongClickListener {
                                kit.sheet("Message", message.body.take(100)) { actionPanel, actionDialog ->
                                    actionPanel.addView(kit.buttonRow(
                                        kit.quietButton("Copy") {
                                            actionDialog.dismiss()
                                            val clipboard = host.getSystemService(android.content.ClipboardManager::class.java) as android.content.ClipboardManager
                                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("SMS", message.body))
                                            android.widget.Toast.makeText(host, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                        kit.tonalButton("Forward") {
                                            actionDialog.dismiss()
                                            composeSms(host, kit, null, message.body) {}
                                        },
                                        kit.quietButton("Delete", R.drawable.oic_delete) {
                                            actionDialog.dismiss()
                                            if (!isDefaultSmsApp(host)) {
                                                android.widget.Toast.makeText(host, "Set OEA as default SMS app before deleting provider messages.", android.widget.Toast.LENGTH_LONG).show()
                                                return@quietButton
                                            }
                                            kit.confirm("Delete message?", message.body.take(80), "Delete", destructive = true) {
                                                runCatching { host.contentResolver.delete(Telephony.Sms.CONTENT_URI, "${Telephony.Sms._ID} = ?", arrayOf(message.id.toString())) }
                                                dialog.dismiss()
                                                (host as? OeaMiniAppActivity)?.refresh()
                                            }
                                        }
                                    ))
                                }
                                true
                            }
                        }
                    }
                    panel.addView(kit.buttonRow(
                        kit.quietButton("Close") { dialog.dismiss() },
                        kit.tonalButton("Archive") {
                            val archived = prefs(host).getStringSet(KEY_ARCHIVED, emptySet())?.toMutableSet() ?: mutableSetOf()
                            archived.add(address)
                            prefs(host).edit().putStringSet(KEY_ARCHIVED, archived).apply()
                            dialog.dismiss()
                            (host as? OeaMiniAppActivity)?.refresh()
                        },
                        kit.primaryButton("Reply") {
                            dialog.dismiss()
                            composeSms(host, kit, address) { (host as? OeaMiniAppActivity)?.refresh() }
                        }
                    ))
                    panel.addView(kit.buttonRow(
                        kit.quietButton("Block", R.drawable.oic_block) {
                            kit.confirm("Block ${address}?", "OEA hides this address from the default list. This does not block delivery at carrier level.", "Block") {
                                val blocked = prefs(host).getStringSet(KEY_BLOCKED, emptySet())?.toMutableSet() ?: mutableSetOf()
                                blocked.add(address)
                                prefs(host).edit().putStringSet(KEY_BLOCKED, blocked).apply()
                                dialog.dismiss()
                                (host as? OeaMiniAppActivity)?.refresh()
                            }
                        },
                        kit.tonalButton("Call") {
                            try { host.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$address"))) } catch (_: Throwable) {}
                        }
                    ))
                }
            }
        }
    }

    /** Reads a picked number; the picker grants access to the selected phone row. */
    private fun readPickedNumber(host: Activity, uri: Uri?): String? {
        if (uri == null) return null
        val projection = arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
        return runCatching {
            host.contentResolver.query(uri, projection, null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).trim() else null }
        }.getOrNull() ?: runCatching {
            host.contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                "${android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(uri.lastPathSegment),
                null
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).trim() else null }
        }.getOrNull()
    }

    private fun composeSms(host: Activity, kit: OeaKit, address: String?, initialBody: String? = null, onSent: () -> Unit) {
        kit.sheet("New message", null) { panel, dialog ->
            dialog.setOnDismissListener { contactPicked = null }
            val toField = kit.field("To", address ?: "")
            val bodyField = kit.field("Message", initialBody ?: "")
            val contactButton = kit.tonalButton("Pick contact", R.drawable.oic_person) {
                contactPicked = { number -> toField.setText(number); toField.setSelection(toField.text.length) }
                val opened = runCatching {
                    host.startActivityForResult(Intent(Intent.ACTION_PICK, android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI), REQUEST_CONTACT)
                    true
                }.getOrDefault(false)
                if (!opened) {
                    contactPicked = null
                    android.widget.Toast.makeText(host, "Android would not open the contacts list", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            panel.addView(toField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            panel.addView(contactButton, LinearLayout.LayoutParams(-1, kit.dp(44)).apply { bottomMargin = kit.dp(8) })
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
                    if (!hasSendSmsPermission(host)) {
                        requestSmsPermission(host, android.Manifest.permission.SEND_SMS)
                        return@primaryButton
                    }
                    try {
                        val manager = android.telephony.SmsManager.getDefault()
                        val parts = manager.divideMessage(body)
                        if (parts.size <= 1) manager.sendTextMessage(to, null, body, null, null)
                        else manager.sendMultipartTextMessage(to, null, parts, null, null)
                        android.widget.Toast.makeText(host, "SMS submitted to Android for sending", android.widget.Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        onSent()
                    } catch (error: Throwable) {
                        android.widget.Toast.makeText(host, "Could not send: ${error.message ?: "Android rejected the request"}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            ))
        }
    }
}
