package com.oea.launcher.miniapps

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.database.Cursor
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.oea.launcher.R
import com.oea.launcher.ui.OeaAppKit
import com.oea.launcher.ui.OeaInsets
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaType
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * Messages — OEA's SMS app, rebuilt to work the way a messaging app works.
 *
 * The old surface was a settings page: conversation rows inside a scrolling
 * settings column, a chat stuffed into a bottom sheet, and a reply button that
 * opened a *second* sheet to type in. This is a full-bleed app in the shape of
 * Google Messages and its peers:
 *
 *  * a virtualised conversation list with coloured initial avatars, saved
 *    contact names, bold unread rows with a count badge, and dates that are a
 *    time today, a weekday this week and a real (year-carrying) date after;
 *  * a real conversation screen — bubbles, dated day pills, an inline
 *    composer with a part counter, drafts that survive leaving the chat, and
 *    live updates from a provider observer instead of a refresh button;
 *  * **dual-SIM honesty**: Android records the subscription that handled every
 *    SMS in the provider's `sub_id` column, so long-pressing any message opens
 *    its details — "Received on SIM 1 • MTN Nigeria" for incoming, "Sent with
 *    SIM 2 • Airtel" for outgoing, plus the network, the other party and the
 *    full times. Replies default to the SIM the conversation last used, and a
 *    dual-SIM phone gets a SIM chip in the composer to change it;
 *  * pinning, archiving, muting, blocking, spam keywords and export, kept from
 *    the previous version and put behind the same long-press people expect.
 *
 * Android's SMS provider remains the only source of truth. Nothing here
 * invents a message, a delivery state or a SIM: where the provider recorded
 * nothing, the details sheet says exactly that.
 */
object OeaMessagingApp {

    data class SmsThread(
        val address: String,
        val snippet: String,
        val date: Long,
        val count: Int,
        val read: Boolean,
        val unreadCount: Int = 0,
        val latestSubscriptionId: Int = -1,
        val latestType: Int = 0
    )

    data class SmsMessage(
        val id: Long,
        val address: String,
        val body: String,
        val date: Long,
        val dateSent: Long,
        val type: Int,
        val read: Boolean,
        val status: Int,
        val subscriptionId: Int
    ) {
        val incoming: Boolean get() = type == Telephony.Sms.MESSAGE_TYPE_INBOX
    }

    internal const val PREFS = "oea_messaging_v2"
    internal const val KEY_ARCHIVED = "archived_threads"
    internal const val KEY_BLOCKED = "blocked_addresses"
    internal const val KEY_MUTED = "muted_addresses"
    internal const val KEY_PINNED = "pinned_threads"
    internal const val KEY_SPAM_KEYWORDS = "spam_keywords"

    internal val io = Executors.newSingleThreadExecutor { Thread(it, "OeaMessaging").apply { isDaemon = true } }

    private var active: WeakReference<MessagingScreen>? = null

    fun build(host: Activity, palette: OeaPalette): View {
        val screen = MessagingScreen(host, palette)
        active = WeakReference(screen)
        screen.attach()
        return screen.root
    }

    internal fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    internal fun hasPermission(ctx: Context, permission: String) =
        ctx.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    internal fun hasReadSmsPermission(ctx: Context) = hasPermission(ctx, android.Manifest.permission.READ_SMS)
    internal fun hasSendSmsPermission(ctx: Context) = hasPermission(ctx, android.Manifest.permission.SEND_SMS)
    internal fun isDefaultSmsApp(ctx: Context) = Telephony.Sms.getDefaultSmsPackage(ctx) == ctx.packageName

    /** Saved contact name for [address], or the address itself. Call off the main thread. */
    internal fun contactName(ctx: Context, address: String): String {
        if (!hasPermission(ctx, android.Manifest.permission.READ_CONTACTS)) return address
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
            val cursor = ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            cursor?.use { c -> if (c.moveToFirst()) c.getString(0)?.ifBlank { address } ?: address else address } ?: address
        } catch (_: Throwable) {
            address
        }
    }

    // ------------------------------------------------------------ provider reads

    /**
     * Queries the SMS provider asking for everything modern Android stores —
     * including `sub_id`, the subscription (SIM) that handled the message.
     * Some OEM providers reject a projection naming a column they renamed, so
     * a rejected full query falls back to the basic columns instead of an
     * empty inbox. Column reads all go by name and tolerate absence.
     */
    private fun querySms(
        ctx: Context,
        selection: String?,
        args: Array<String>?,
        sort: String?
    ): Cursor? {
        val full = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE,
            Telephony.Sms.DATE_SENT, Telephony.Sms.TYPE, Telephony.Sms.READ, Telephony.Sms.STATUS, "sub_id"
        )
        val basic = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
            Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ
        )
        return try {
            ctx.contentResolver.query(Telephony.Sms.CONTENT_URI, full, selection, args, sort)
        } catch (error: SecurityException) {
            throw error
        } catch (_: RuntimeException) {
            ctx.contentResolver.query(Telephony.Sms.CONTENT_URI, basic, selection, args, sort)
        }
    }

    private fun Cursor.longAt(index: Int, fallback: Long): Long =
        if (index >= 0 && !isNull(index)) getLong(index) else fallback

    private fun Cursor.intAt(index: Int, fallback: Int): Int =
        if (index >= 0 && !isNull(index)) getInt(index) else fallback

    private fun Cursor.stringAt(index: Int): String =
        if (index >= 0 && !isNull(index)) getString(index).orEmpty() else ""

    /** Every SMS provider row grouped by sender/recipient — the conversation list. */
    internal fun queryThreads(ctx: Context): List<SmsThread> {
        val index = OeaSmsThreadIndex()
        val cursor = querySms(ctx, null, null, "${Telephony.Sms.DATE} DESC")
            ?: throw IllegalStateException("SMS provider returned no cursor")
        cursor.use { c ->
            val addressIndex = c.getColumnIndex(Telephony.Sms.ADDRESS)
            val bodyIndex = c.getColumnIndex(Telephony.Sms.BODY)
            val dateIndex = c.getColumnIndex(Telephony.Sms.DATE)
            val readIndex = c.getColumnIndex(Telephony.Sms.READ)
            val typeIndex = c.getColumnIndex(Telephony.Sms.TYPE)
            val subIndex = c.getColumnIndex("sub_id")
            if (addressIndex < 0 || bodyIndex < 0 || dateIndex < 0) return@use
            while (c.moveToNext()) {
                // Unread means an unread *incoming* message; my own sent rows
                // carry read=1 in practice, but never let one fake a badge.
                val type = c.intAt(typeIndex, Telephony.Sms.MESSAGE_TYPE_INBOX)
                val isRead = c.intAt(readIndex, 1) != 0 || type != Telephony.Sms.MESSAGE_TYPE_INBOX
                index.add(
                    c.stringAt(addressIndex), c.stringAt(bodyIndex), c.longAt(dateIndex, 0L),
                    isRead, c.intAt(subIndex, -1), type
                )
            }
        }
        return index.threads()
    }

    /** Addresses whose message *bodies* (not just snippets) match a search. */
    internal fun queryMatchingAddresses(ctx: Context, query: String): Set<String> {
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

    internal fun queryConversation(ctx: Context, address: String): List<SmsMessage> {
        val list = mutableListOf<SmsMessage>()
        val cursor = querySms(ctx, "${Telephony.Sms.ADDRESS} = ?", arrayOf(address), "${Telephony.Sms.DATE} ASC")
        cursor?.use { c ->
            val idIndex = c.getColumnIndex(Telephony.Sms._ID)
            val addressIndex = c.getColumnIndex(Telephony.Sms.ADDRESS)
            val bodyIndex = c.getColumnIndex(Telephony.Sms.BODY)
            val dateIndex = c.getColumnIndex(Telephony.Sms.DATE)
            val sentIndex = c.getColumnIndex(Telephony.Sms.DATE_SENT)
            val typeIndex = c.getColumnIndex(Telephony.Sms.TYPE)
            val readIndex = c.getColumnIndex(Telephony.Sms.READ)
            val statusIndex = c.getColumnIndex(Telephony.Sms.STATUS)
            val subIndex = c.getColumnIndex("sub_id")
            while (c.moveToNext()) {
                list.add(
                    SmsMessage(
                        id = c.longAt(idIndex, 0L),
                        address = c.stringAt(addressIndex).ifBlank { address },
                        body = c.stringAt(bodyIndex),
                        date = c.longAt(dateIndex, 0L),
                        dateSent = c.longAt(sentIndex, 0L),
                        type = c.intAt(typeIndex, Telephony.Sms.MESSAGE_TYPE_INBOX),
                        read = c.intAt(readIndex, 1) != 0,
                        status = c.intAt(statusIndex, Telephony.Sms.STATUS_NONE),
                        subscriptionId = c.intAt(subIndex, -1)
                    )
                )
            }
        }
        return list
    }

    internal fun markConversationReadIfDefault(ctx: Context, address: String, read: Boolean = true) {
        if (!isDefaultSmsApp(ctx)) return
        val values = ContentValues().apply {
            put(Telephony.Sms.READ, if (read) 1 else 0)
            if (read) put(Telephony.Sms.SEEN, 1)
        }
        runCatching {
            ctx.contentResolver.update(
                Telephony.Sms.CONTENT_URI, values,
                "${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.READ} = ?",
                arrayOf(address, if (read) "0" else "1")
            )
        }
    }

    internal fun deleteConversationIfDefault(ctx: Context, address: String): Boolean {
        if (!isDefaultSmsApp(ctx)) return false
        return runCatching {
            ctx.contentResolver.delete(Telephony.Sms.CONTENT_URI, "${Telephony.Sms.ADDRESS} = ?", arrayOf(address)) >= 0
        }.getOrDefault(false)
    }

    internal fun deleteMessageIfDefault(ctx: Context, id: Long): Boolean {
        if (!isDefaultSmsApp(ctx)) return false
        return runCatching {
            ctx.contentResolver.delete(Telephony.Sms.CONTENT_URI, "${Telephony.Sms._ID} = ?", arrayOf(id.toString())) > 0
        }.getOrDefault(false)
    }

    /**
     * Records a message OEA just sent. Only the default SMS app may write to
     * the provider, and when OEA *is* default the platform does not file a
     * sent copy for it — filing it (with the SIM that sent it) is the app's
     * job, exactly as it is for Google Messages or Samsung Messages.
     */
    internal fun recordSentIfDefault(ctx: Context, address: String, body: String, subscriptionId: Int) {
        if (!isDefaultSmsApp(ctx)) return
        val now = System.currentTimeMillis()
        fun values(withSub: Boolean) = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, now)
            put(Telephony.Sms.DATE_SENT, now)
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
            put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_NONE)
            if (withSub && subscriptionId >= 0) put("sub_id", subscriptionId)
        }
        runCatching { ctx.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values(true)) }
            .onFailure { runCatching { ctx.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values(false)) } }
    }

    /** The manager bound to one SIM, or the system default when [subscriptionId] is unknown. */
    internal fun smsManagerFor(ctx: Context, subscriptionId: Int): SmsManager {
        if (subscriptionId >= 0) {
            if (Build.VERSION.SDK_INT >= 31) {
                runCatching {
                    ctx.getSystemService(SmsManager::class.java)?.createForSubscriptionId(subscriptionId)
                }.getOrNull()?.let { return it }
            } else {
                @Suppress("DEPRECATION")
                runCatching { SmsManager.getSmsManagerForSubscriptionId(subscriptionId) }.getOrNull()?.let { return it }
            }
        }
        @Suppress("DEPRECATION")
        return SmsManager.getDefault()
    }
}

/** The conversation-list screen. Owns its state so a host rebuild forgets nothing it shouldn't. */
internal class MessagingScreen(val host: Activity, val palette: OeaPalette) {

    private val kit = OeaAppKit(host, palette)
    val root: FrameLayout = kit.overlay()
    private val column = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
    private val bar = kit.appBar("Messages", "Reading your messages…", onBack = { runCatching { host.finish() } })
    private val searchBox = kit.searchField("Search messages", onQuery = { value ->
        query = value.trim()
        scheduleSearch()
    })
    private val searchRow = FrameLayout(host).apply {
        setPadding(kit.dp(14), 0, kit.dp(14), kit.dp(8))
        addView(searchBox.root, FrameLayout.LayoutParams(-1, -2))
        visibility = View.GONE
    }
    private val setupHost = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(kit.dp(14), 0, kit.dp(14), 0)
    }
    private val tabs = kit.chipBar()
    private val content = FrameLayout(host)
    private val listView = kit.list(0).apply { setPadding(kit.dp(8), kit.dp(2), kit.dp(8), kit.dp(116)) }
    private val snack = kit.snack(root)
    private val handler = Handler(Looper.getMainLooper())

    private var alive = true
    private var tab = "all"
    private var query = ""
    private var allThreads: List<OeaMessagingApp.SmsThread> = emptyList()
    private var names: Map<String, String> = emptyMap()
    private var bodyMatches: Set<String> = emptySet()
    private var visible: List<ThreadRow> = emptyList()
    private var loadFailed = false
    private var stateCard: View? = null
    private var conversation: Conversation? = null
    private var contactPicked: ((String) -> Unit)? = null
    private var observer: ContentObserver? = null
    private var searchPending: Runnable? = null
    private var reloadPending: Runnable? = null

    /** One row of a conversation. Here, not in [Conversation]: a sealed class may not live in an inner class. */
    private sealed class ChatRow {
        data class Earlier(val hidden: Int) : ChatRow()
        data class Day(val label: String) : ChatRow()
        data class Bubble(val message: OeaMessagingApp.SmsMessage, val tightTop: Boolean) : ChatRow()
    }

    /** A thread plus everything its row paints, resolved off the main thread. */
    private data class ThreadRow(
        val thread: OeaMessagingApp.SmsThread,
        val displayName: String,
        val draft: String?,
        val pinned: Boolean,
        val muted: Boolean,
        val spam: Boolean
    )

    private val threadsAdapter = ThreadsAdapter()

    init {
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        column.addView(bar.root, LinearLayout.LayoutParams(-1, -2))
        column.addView(searchRow, LinearLayout.LayoutParams(-1, -2))
        column.addView(setupHost, LinearLayout.LayoutParams(-1, -2))
        column.addView(kit.hscroll(tabs.root), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(2) })
        column.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(listView, FrameLayout.LayoutParams(-1, -1))
        listView.adapter = threadsAdapter
        with(kit) {
            bar.addAction(R.drawable.oic_search, "Search messages") { toggleSearch() }
            bar.addAction(R.drawable.oic_more_vert, "More options") { showMore() }
        }
        tabs.onPick = { id ->
            tab = id
            applyFilter()
        }
        tabs.setOptions(TABS, tab)
        val fab = kit.primaryButton("Start chat", R.drawable.oic_edit) { composeSms(null) }
        fab.elevation = kit.dpf(6f)
        root.addView(fab, FrameLayout.LayoutParams(-2, kit.dp(56), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, kit.dp(16), kit.dp(20))
        })
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = OeaInsets.bars(insets)
            column.setPadding(bars[0], 0, bars[2], 0)
            (fab.layoutParams as? FrameLayout.LayoutParams)?.let { it.bottomMargin = bars[3] + kit.dp(20) }
            insets
        }
    }

    // -------------------------------------------------------------- lifecycle

    fun attach() {
        val resultHook: (Int, Int, Intent?) -> Boolean = { requestCode, resultCode, data ->
            handleResult(requestCode, resultCode, data)
        }
        host.miniResultHook = resultHook
        val resumeHook: () -> Unit = {
            renderSetup()
            reloadThreads()
            conversation?.reload()
        }
        host.miniResumeHook = resumeHook
        var disposed = false
        lateinit var cleanup: () -> Unit
        cleanup = {
            if (!disposed) {
                disposed = true
                alive = false
                handler.removeCallbacksAndMessages(null)
                observer?.let { runCatching { host.contentResolver.unregisterContentObserver(it) } }
                observer = null
                conversation?.dismissQuietly()
                conversation = null
                if (host.miniResultHook === resultHook) host.miniResultHook = null
                if (host.miniResumeHook === resumeHook) host.miniResumeHook = null
                if (host.miniDestroyHook === cleanup) host.miniDestroyHook = null
            }
        }
        host.miniDestroyHook = cleanup
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) = cleanup()
        })
        val contentObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = scheduleReload()
        }
        observer = contentObserver
        runCatching { host.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, contentObserver) }
        renderSetup()
        reloadThreads()
        openIncoming()
    }

    private fun handleResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean = when (requestCode) {
        REQUEST_SMS_ROLE -> {
            renderSetup()
            reloadThreads()
            true
        }
        REQUEST_CONTACT -> {
            if (resultCode != Activity.RESULT_OK) true
            else {
                val number = readPickedNumber(data?.data)
                val sink = contactPicked
                contactPicked = null
                if (number != null && sink != null) sink(number)
                else toast(if (number == null) "That contact has no number OEA could read" else "No compose sheet is open")
                true
            }
        }
        else -> false
    }

    /** An sms:/smsto: hand-off opens that conversation, text already in the composer. */
    private fun openIncoming() {
        val intent = host.intent ?: return
        if (intent.action != Intent.ACTION_SENDTO && intent.action != Intent.ACTION_VIEW) return
        val address = intent.data?.schemeSpecificPart?.substringBefore('?')?.trim()?.let { Uri.decode(it) }.orEmpty()
        val body = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: intent.getStringExtra("sms_body")
        host.setIntent(Intent(Intent.ACTION_MAIN))
        root.post {
            if (!alive) return@post
            if (address.isNotBlank()) openConversation(address, body) else composeSms(null, body)
        }
    }

    // ------------------------------------------------------------------ data

    private fun scheduleReload() {
        reloadPending?.let { handler.removeCallbacks(it) }
        val task = Runnable {
            reloadThreads()
            conversation?.reload()
        }
        reloadPending = task
        handler.postDelayed(task, 450L)
    }

    private fun scheduleSearch() {
        applyFilter()
        searchPending?.let { handler.removeCallbacks(it) }
        if (query.isBlank()) {
            bodyMatches = emptySet()
            applyFilter()
            return
        }
        val wanted = query
        val task = Runnable {
            OeaMessagingApp.io.execute {
                val matches = runCatching { OeaMessagingApp.queryMatchingAddresses(host, wanted) }.getOrDefault(emptySet())
                host.runOnUiThread {
                    if (alive && !host.isDestroyed && query == wanted) {
                        bodyMatches = matches
                        applyFilter()
                    }
                }
            }
        }
        searchPending = task
        handler.postDelayed(task, 280L)
    }

    private fun reloadThreads() {
        if (!OeaMessagingApp.hasReadSmsPermission(host)) {
            allThreads = emptyList()
            loadFailed = false
            renderSetup()
            applyFilter()
            return
        }
        OeaMessagingApp.io.execute {
            val result = runCatching { OeaMessagingApp.queryThreads(host) }
            val threads = result.getOrDefault(emptyList())
            val resolved = HashMap<String, String>(threads.size)
            threads.forEach { resolved[it.address] = OeaMessagingApp.contactName(host, it.address) }
            host.runOnUiThread {
                if (!alive || host.isDestroyed) return@runOnUiThread
                allThreads = threads
                names = resolved
                loadFailed = result.isFailure
                applyFilter()
            }
        }
    }

    private fun spamKeywords(): List<String> =
        (OeaMessagingApp.prefs(host).getString(OeaMessagingApp.KEY_SPAM_KEYWORDS, "lottery,win,prize,urgent")
            ?: "lottery,win,prize,urgent")
            .split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }

    private fun applyFilter() {
        val prefs = OeaMessagingApp.prefs(host)
        val archived = prefs.getStringSet(OeaMessagingApp.KEY_ARCHIVED, emptySet()) ?: emptySet()
        val blocked = prefs.getStringSet(OeaMessagingApp.KEY_BLOCKED, emptySet()) ?: emptySet()
        val muted = prefs.getStringSet(OeaMessagingApp.KEY_MUTED, emptySet()) ?: emptySet()
        val pinned = prefs.getStringSet(OeaMessagingApp.KEY_PINNED, emptySet()) ?: emptySet()
        val keywords = spamKeywords()
        fun OeaMessagingApp.SmsThread.isSpam() =
            keywords.any { word -> snippet.lowercase().contains(word) || address.lowercase().contains(word) }

        visible = allThreads.filter { thread ->
            val tabMatch = when (tab) {
                "unread" -> !thread.read && !archived.contains(thread.address) && !blocked.contains(thread.address)
                "archived" -> archived.contains(thread.address)
                "blocked" -> blocked.contains(thread.address)
                "spam" -> thread.isSpam()
                else -> !archived.contains(thread.address) && !blocked.contains(thread.address)
            }
            val name = names[thread.address] ?: thread.address
            val searchMatch = query.isBlank() ||
                thread.address.contains(query, true) ||
                name.contains(query, true) ||
                thread.snippet.contains(query, true) ||
                bodyMatches.contains(thread.address)
            tabMatch && searchMatch
        }.map { thread ->
            ThreadRow(
                thread = thread,
                displayName = names[thread.address] ?: thread.address,
                draft = prefs.getString(draftKey(thread.address), null)?.ifBlank { null },
                pinned = pinned.contains(thread.address),
                muted = muted.contains(thread.address),
                spam = thread.isSpam()
            )
        }.sortedWith(compareByDescending<ThreadRow> { it.pinned }.thenByDescending { it.thread.date })

        val unreadThreads = allThreads.count { !it.read }
        tabs.setOptions(
            listOf(
                "all" to "All",
                "unread" to if (unreadThreads > 0) "Unread • $unreadThreads" else "Unread",
                "archived" to "Archived",
                "blocked" to "Blocked",
                "spam" to "Spam"
            ),
            tab
        )
        val totalUnread = allThreads.sumOf { it.unreadCount }
        bar.setSubtitle(
            when {
                !OeaMessagingApp.hasReadSmsPermission(host) -> "SMS on this phone, in OEA"
                loadFailed -> "Couldn't read message history"
                query.isNotBlank() -> "${visible.size} matching conversation${if (visible.size == 1) "" else "s"}"
                allThreads.isEmpty() -> "No messages yet"
                totalUnread > 0 -> "${allThreads.size} conversations • $totalUnread unread"
                else -> "${allThreads.size} conversation${if (allThreads.size == 1) "" else "s"}"
            }
        )
        threadsAdapter.notifyDataSetChanged()
        renderEmptyState()
    }

    private fun renderEmptyState() {
        stateCard?.let { content.removeView(it) }
        stateCard = null
        listView.visibility = View.VISIBLE
        if (visible.isNotEmpty()) return
        val card = when {
            !OeaMessagingApp.hasReadSmsPermission(host) -> kit.emptyState(
                R.drawable.oic_info, "Allow message history",
                "READ_SMS lets OEA show the messages already stored on this phone. Nothing is uploaded; the history never leaves the device.",
                "Allow access"
            ) { requestPermission(android.Manifest.permission.READ_SMS) }
            loadFailed -> kit.emptyState(
                R.drawable.oic_warning_fill, "Couldn't read SMS history",
                "Android rejected the SMS provider query. Confirm SMS access, then try again — OEA will not substitute another app's private message data.",
                "Try again"
            ) { reloadThreads() }
            query.isNotBlank() -> kit.emptyState(
                R.drawable.oic_search, "No matches for “$query”",
                "Search covers numbers, contact names and message text. Try a shorter word or part of the number.",
                "Clear search"
            ) {
                searchBox.setText("")
                query = ""
                bodyMatches = emptySet()
                applyFilter()
            }
            tab == "unread" -> kit.emptyState(R.drawable.oic_check_circle_fill, "All caught up", "No unread conversations. New messages appear here on their own.")
            tab == "archived" -> kit.emptyState(R.drawable.oic_folder, "Nothing archived", "Long-press a conversation and choose Archive to tuck it away without deleting it.")
            tab == "blocked" -> kit.emptyState(R.drawable.oic_block, "Nobody blocked", "Blocked addresses are hidden from All. Blocking here does not stop delivery at carrier level.")
            tab == "spam" -> kit.emptyState(R.drawable.oic_shield, "No spam detected", "OEA flags conversations matching your spam keywords: ${spamKeywords().joinToString()}.")
            else -> kit.emptyState(
                R.drawable.oic_edit, "No messages yet",
                "When this phone has SMS — or when you send your first text — conversations appear here.",
                "Start chat"
            ) { composeSms(null) }
        }
        val wrap = FrameLayout(host).apply {
            setPadding(kit.dp(16), kit.dp(20), kit.dp(16), 0)
            addView(card, FrameLayout.LayoutParams(-1, -2))
        }
        stateCard = wrap
        content.addView(wrap, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        listView.visibility = View.GONE
    }

    // ------------------------------------------------------------------ setup

    /** One slim line when setup is incomplete, instead of a wall of banners. */
    private fun renderSetup() {
        setupHost.removeAllViews()
        val missing = mutableListOf<String>()
        if (!OeaMessagingApp.hasReadSmsPermission(host)) missing += "message history"
        if (!OeaMessagingApp.hasSendSmsPermission(host)) missing += "sending"
        if (!OeaMessagingApp.isDefaultSmsApp(host)) missing += "default SMS app"
        if (missing.isEmpty()) return
        kit.banner(
            setupHost, R.drawable.oic_info, "Finish setting up Messages",
            "Still off: ${missing.joinToString(", ")}. Each switch says what it unlocks — nothing is requested until you tap it.",
            OeaKit.Tone.INFO, "Review"
        ) { setupSheet() }
    }

    private fun setupSheet() {
        kit.sheet("Messages setup", "What OEA may do with SMS on this phone") { panel, _ ->
            val bodyCol = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
            panel.addView(kit.boundedScroll(bodyCol, 0.68f), LinearLayout.LayoutParams(-1, -2))
            val group = kit.group(bodyCol)
            fun state(on: Boolean) = if (on) "On" else "Off"
            kit.row(
                group, R.drawable.oic_history, "Read message history",
                "Shows the SMS already stored on this phone", value = state(OeaMessagingApp.hasReadSmsPermission(host))
            ) { requestPermission(android.Manifest.permission.READ_SMS) }
            kit.row(
                group, R.drawable.oic_send, "Send messages",
                "Only used when you press Send", value = state(OeaMessagingApp.hasSendSmsPermission(host))
            ) { requestPermission(android.Manifest.permission.SEND_SMS) }
            kit.row(
                group, R.drawable.oic_person, "Contact names",
                "Optional: names beside numbers; numbers still show without it",
                value = state(OeaMessagingApp.hasPermission(host, android.Manifest.permission.READ_CONTACTS))
            ) { requestPermission(android.Manifest.permission.READ_CONTACTS) }
            kit.row(
                group, R.drawable.oic_sim_card, "Name the SIM a message used",
                "Optional: lets long-press details say \"SIM 1 • MTN\" instead of just that a SIM was recorded",
                value = state(OeaSimDirectory.hasPhonePermission(host))
            ) { requestSimPermissions() }
            kit.row(
                group, R.drawable.oic_edit, "OEA as default SMS app",
                "Routes new SMS to OEA, and lets OEA mark read, delete and file sent messages with their SIM",
                value = if (OeaMessagingApp.isDefaultSmsApp(host)) "Default" else "Not default"
            ) {
                kit.confirm(
                    "Before making OEA default",
                    "OEA receives, stores and alerts SMS, but this version does not yet download MMS pictures or group MMS content. If you rely on MMS, keep your current Messages app as default. Continue to Android's confirmation?",
                    "Continue"
                ) { requestDefaultSmsRole() }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                kit.row(
                    group, R.drawable.oic_notifications, "New-message alerts",
                    "Optional: a notification when OEA (as default) receives an SMS",
                    value = state(OeaMessagingApp.hasPermission(host, android.Manifest.permission.POST_NOTIFICATIONS))
                ) { requestPermission(android.Manifest.permission.POST_NOTIFICATIONS) }
            }
            kit.note(
                bodyCol,
                "Long-press any message in a conversation to see its details — including which SIM and network handled it. " +
                    "Android records that per message; where it recorded nothing, OEA says so rather than guessing."
            )
        }
    }

    private fun requestPermission(permission: String) {
        try {
            host.requestPermissions(arrayOf(permission), REQUEST_PERMISSIONS)
        } catch (_: Throwable) {
            runCatching {
                host.startActivity(
                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${host.packageName}"))
                )
            }
        }
    }

    private fun requestSimPermissions() {
        try {
            host.requestPermissions(
                arrayOf(android.Manifest.permission.READ_PHONE_STATE, android.Manifest.permission.READ_PHONE_NUMBERS),
                REQUEST_PERMISSIONS
            )
        } catch (_: Throwable) {
            snack.show("Open Settings › Apps › OEA › Permissions › Phone", OeaKit.Tone.WARNING)
        }
    }

    private fun requestDefaultSmsRole() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
            snack.show("Open Settings › Apps › Default apps › SMS and select OEA", OeaKit.Tone.WARNING)
        }
    }

    // ------------------------------------------------------------- list actions

    private fun toggleSearch() {
        if (searchRow.visibility == View.VISIBLE) {
            searchRow.visibility = View.GONE
            searchBox.close()
            if (query.isNotEmpty()) {
                query = ""
                searchBox.setText("")
                bodyMatches = emptySet()
                applyFilter()
            }
            return
        }
        searchRow.visibility = View.VISIBLE
        searchBox.focus()
    }

    private fun showMore() {
        kit.actions(
            "Messages", bar.subtitle.text?.toString(),
            listOf(
                OeaAppKit.Action(R.drawable.oic_edit, "Start chat", "Write to a new number or contact") { composeSms(null) },
                OeaAppKit.Action(R.drawable.oic_tune, "Setup & permissions", "SMS access, default app, SIM naming") { setupSheet() },
                OeaAppKit.Action(R.drawable.oic_shield, "Spam keywords", spamKeywords().joinToString()) {
                    val current = OeaMessagingApp.prefs(host).getString(OeaMessagingApp.KEY_SPAM_KEYWORDS, "lottery,win,prize,urgent") ?: ""
                    kit.prompt("Spam keywords", "Comma separated. A conversation matching any of these is flagged as possible spam.", "Keywords", current) { input ->
                        OeaMessagingApp.prefs(host).edit().putString(OeaMessagingApp.KEY_SPAM_KEYWORDS, input).apply()
                        applyFilter()
                        null
                    }
                },
                OeaAppKit.Action(R.drawable.oic_refresh, "Reload from Android", "Reads the SMS provider again") { reloadThreads() },
                OeaAppKit.Action(R.drawable.oic_share_filled, "Export visible list", "Shares the conversations in this view as text") {
                    val text = visible.joinToString("\n") { row ->
                        "${row.displayName} (${row.thread.address}): ${row.thread.snippet} @ ${OeaMessageDates.fullLabel(row.thread.date)}"
                    }
                    shareText(text, "Export SMS list")
                }
            )
        )
    }

    private fun threadActions(row: ThreadRow) {
        val address = row.thread.address
        val prefs = OeaMessagingApp.prefs(host)
        val archived = (prefs.getStringSet(OeaMessagingApp.KEY_ARCHIVED, emptySet()) ?: emptySet()).contains(address)
        val blocked = (prefs.getStringSet(OeaMessagingApp.KEY_BLOCKED, emptySet()) ?: emptySet()).contains(address)
        val isDefault = OeaMessagingApp.isDefaultSmsApp(host)
        fun toggleSet(key: String, on: Boolean) {
            val values = (prefs.getStringSet(key, emptySet()) ?: emptySet()).toMutableSet()
            if (on) values.add(address) else values.remove(address)
            prefs.edit().putStringSet(key, values).apply()
            applyFilter()
        }
        kit.actions(
            row.displayName,
            "${row.thread.count} message${if (row.thread.count == 1) "" else "s"} • ${OeaMessageDates.fullLabel(row.thread.date)}",
            listOf(
                OeaAppKit.Action(R.drawable.oic_open_in_new, "Open conversation") { openConversation(address) },
                OeaAppKit.Action(
                    R.drawable.oic_push_pin, if (row.pinned) "Unpin" else "Pin to top",
                    if (row.pinned) "This conversation sorts by date again" else "Keeps this conversation above the rest"
                ) { toggleSet(OeaMessagingApp.KEY_PINNED, !row.pinned) },
                OeaAppKit.Action(
                    R.drawable.oic_check_circle_fill, if (row.thread.read) "Mark as unread" else "Mark as read",
                    if (isDefault) null else "Needs OEA as the default SMS app", disabled = !isDefault
                ) {
                    OeaMessagingApp.io.execute {
                        OeaMessagingApp.markConversationReadIfDefault(host, address, read = !row.thread.read)
                        host.runOnUiThread { if (alive) reloadThreads() }
                    }
                },
                OeaAppKit.Action(
                    R.drawable.oic_notifications_off, if (row.muted) "Unmute" else "Mute notifications",
                    "OEA stays quiet for this address when it is the default SMS app"
                ) { toggleSet(OeaMessagingApp.KEY_MUTED, !row.muted) },
                OeaAppKit.Action(
                    R.drawable.oic_folder, if (archived) "Unarchive" else "Archive",
                    if (archived) "Back into the main list" else "Hides it from All without deleting anything"
                ) { toggleSet(OeaMessagingApp.KEY_ARCHIVED, !archived) },
                OeaAppKit.Action(
                    R.drawable.oic_block, if (blocked) "Unblock" else "Block",
                    "Hides this address from All; delivery itself is the carrier's, not OEA's",
                    destructive = !blocked
                ) { toggleSet(OeaMessagingApp.KEY_BLOCKED, !blocked) },
                OeaAppKit.Action(
                    R.drawable.oic_delete, "Delete conversation",
                    if (isDefault) "Removes every SMS with this address" else "Needs OEA as the default SMS app",
                    destructive = true, disabled = !isDefault
                ) {
                    kit.confirm("Delete this conversation?", "Every SMS with $address is removed from this phone. This cannot be undone.", "Delete", destructive = true) {
                        OeaMessagingApp.io.execute {
                            val done = OeaMessagingApp.deleteConversationIfDefault(host, address)
                            host.runOnUiThread {
                                if (!alive) return@runOnUiThread
                                if (done) {
                                    snack.show("Conversation deleted", OeaKit.Tone.SUCCESS)
                                    reloadThreads()
                                } else snack.show("Android would not delete that conversation", OeaKit.Tone.WARNING)
                            }
                        }
                    }
                }
            )
        )
    }

    // --------------------------------------------------------------- list rows

    private inner class ThreadsAdapter : RecyclerView.Adapter<ThreadsAdapter.Holder>() {

        inner class Holder(val rowRoot: LinearLayout) : RecyclerView.ViewHolder(rowRoot) {
            val avatarHost: FrameLayout = rowRoot.getChildAt(0) as FrameLayout
            val name: TextView
            val time: TextView
            val snippet: TextView
            val unreadBadge: TextView
            val muteIcon: ImageView
            val pinIcon: ImageView

            init {
                val middle = rowRoot.getChildAt(1) as LinearLayout
                val topRow = middle.getChildAt(0) as LinearLayout
                name = topRow.getChildAt(0) as TextView
                pinIcon = topRow.getChildAt(1) as ImageView
                time = topRow.getChildAt(2) as TextView
                val bottomRow = middle.getChildAt(1) as LinearLayout
                snippet = bottomRow.getChildAt(0) as TextView
                muteIcon = bottomRow.getChildAt(1) as ImageView
                unreadBadge = bottomRow.getChildAt(2) as TextView
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val rowRoot = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(kit.dp(12), kit.dp(10), kit.dp(12), kit.dp(10))
                layoutParams = RecyclerView.LayoutParams(-1, -2)
                background = kit.ripple(kit.rounded(Color.TRANSPARENT, 20), 20)
                isClickable = true
                isFocusable = true
            }
            val avatarHost = FrameLayout(host)
            rowRoot.addView(avatarHost, LinearLayout.LayoutParams(kit.dp(50), kit.dp(50)).apply { marginEnd = kit.dp(12) })
            val middle = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
            val topRow = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            topRow.addView(
                kit.text("", 16f, palette.text, OeaType.medium).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            topRow.addView(
                ImageView(host).apply { setImageDrawable(kit.icon(R.drawable.oic_push_pin, palette.textTertiary)) },
                LinearLayout.LayoutParams(kit.dp(14), kit.dp(14)).apply { marginStart = kit.dp(6); marginEnd = kit.dp(2) }
            )
            topRow.addView(kit.text("", 11.5f, palette.textTertiary, OeaType.medium).apply { maxLines = 1 })
            middle.addView(topRow, LinearLayout.LayoutParams(-1, -2))
            val bottomRow = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            bottomRow.addView(
                kit.text("", 13.5f, palette.textSecondary).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setLineSpacing(kit.dpf(1.5f), 1f)
                },
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            bottomRow.addView(
                ImageView(host).apply { setImageDrawable(kit.icon(R.drawable.oic_notifications_off, palette.textTertiary)) },
                LinearLayout.LayoutParams(kit.dp(15), kit.dp(15)).apply { marginStart = kit.dp(8) }
            )
            bottomRow.addView(kit.badge(""), LinearLayout.LayoutParams(-2, -2).apply { marginStart = kit.dp(8) })
            middle.addView(bottomRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(2) })
            rowRoot.addView(middle, LinearLayout.LayoutParams(0, -2, 1f))
            return Holder(rowRoot)
        }

        override fun getItemCount(): Int = visible.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = visible.getOrNull(position) ?: return
            val thread = row.thread
            val unread = !thread.read
            holder.avatarHost.removeAllViews()
            holder.avatarHost.addView(avatarView(row.displayName, thread.address), FrameLayout.LayoutParams(-1, -1))
            val showNumber = row.displayName != thread.address
            holder.name.text = if (showNumber) row.displayName else thread.address
            holder.name.typeface = if (unread) OeaType.medium else OeaType.regular
            holder.name.setTextColor(palette.text)
            holder.time.text = OeaMessageDates.threadLabel(thread.date, System.currentTimeMillis())
            holder.time.setTextColor(if (unread) palette.accent else palette.textTertiary)
            holder.time.typeface = if (unread) OeaType.medium else OeaType.regular
            holder.pinIcon.visibility = if (row.pinned) View.VISIBLE else View.GONE
            val snippetText = when {
                row.draft != null -> "Draft: ${row.draft}"
                thread.latestType == Telephony.Sms.MESSAGE_TYPE_FAILED -> "Not sent: ${thread.snippet}"
                thread.latestType != Telephony.Sms.MESSAGE_TYPE_INBOX && thread.latestType != 0 -> "You: ${thread.snippet}"
                else -> thread.snippet
            }
            holder.snippet.text = snippetText
            holder.snippet.setTextColor(
                when {
                    row.draft != null -> palette.accent
                    thread.latestType == Telephony.Sms.MESSAGE_TYPE_FAILED -> palette.danger
                    unread -> palette.text
                    else -> palette.textSecondary
                }
            )
            holder.snippet.typeface = if (unread || row.draft != null) OeaType.medium else OeaType.regular
            holder.muteIcon.visibility = if (row.muted) View.VISIBLE else View.GONE
            holder.unreadBadge.visibility = if (unread) View.VISIBLE else View.GONE
            if (unread) holder.unreadBadge.text = if (thread.unreadCount > 99) "99+" else thread.unreadCount.coerceAtLeast(1).toString()
            holder.rowRoot.contentDescription = buildString {
                append(row.displayName)
                if (unread) append(", ${thread.unreadCount} unread")
                append(", ${thread.snippet.take(90)}")
            }
            holder.rowRoot.setOnClickListener {
                visible.getOrNull(holder.bindingAdapterPosition)?.let { openConversation(it.thread.address) }
            }
            holder.rowRoot.setOnLongClickListener {
                visible.getOrNull(holder.bindingAdapterPosition)?.let { threadActions(it) }
                true
            }
        }
    }

    /** The coloured circle every modern messaging app leads a row with. */
    private fun avatarView(displayName: String, address: String): View {
        val frame = FrameLayout(host).apply { background = kit.oval(avatarColor(address)) }
        val named = displayName != address
        val letters = when {
            named -> displayName.split(" ").mapNotNull { it.firstOrNull()?.uppercaseChar() }.let {
                when {
                    it.isEmpty() -> ""
                    it.size == 1 -> it[0].toString()
                    else -> "${it.first()}${it.last()}"
                }
            }
            address.any { it.isLetter() } -> address.firstOrNull { it.isLetter() }?.uppercaseChar()?.toString().orEmpty()
            else -> ""
        }
        if (letters.isNotEmpty()) {
            frame.addView(
                kit.text(letters, 17f, Color.WHITE, OeaType.medium).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(-1, -1)
            )
        } else {
            frame.addView(
                ImageView(host).apply { setImageDrawable(kit.icon(R.drawable.oic_person, Color.WHITE)) },
                FrameLayout.LayoutParams(kit.dp(26), kit.dp(26), Gravity.CENTER)
            )
        }
        return frame
    }

    private fun avatarColor(address: String): Int {
        val paletteColors = intArrayOf(
            0xFF5B8DEF.toInt(), 0xFF7C5CD6.toInt(), 0xFF0FA3A3.toInt(), 0xFFE93D82.toInt(),
            0xFFED7B40.toInt(), 0xFF3E9B4F.toInt(), 0xFFC2185B.toInt(), 0xFF00796B.toInt(),
            0xFF5D6AC0.toInt(), 0xFF8D6E00.toInt()
        )
        return paletteColors[(address.hashCode() and Int.MAX_VALUE) % paletteColors.size]
    }

    // ----------------------------------------------------------------- compose

    internal fun draftKey(address: String) = "draft:$address"
    private fun simKey(address: String) = "sim:$address"

    /** The "Start chat" sheet: a new conversation with any number or contact. */
    fun composeSms(initialAddress: String?, initialBody: String? = null) {
        kit.sheet("New message", null) { panel, dialog ->
            dialog.setOnDismissListener { contactPicked = null }
            val toField = kit.field("To (phone number)", initialAddress ?: "").apply {
                inputType = InputType.TYPE_CLASS_PHONE
            }
            val bodyField = kit.field("Message", initialBody ?: "").apply {
                setSingleLine(false)
                minLines = 2
                maxLines = 5
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            }
            panel.addView(toField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            panel.addView(kit.tonalButton("Pick contact", R.drawable.oic_person) {
                contactPicked = { number -> toField.setText(number); toField.setSelection(toField.text.length) }
                val opened = runCatching {
                    host.startActivityForResult(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), REQUEST_CONTACT)
                    true
                }.getOrDefault(false)
                if (!opened) {
                    contactPicked = null
                    toast("Android would not open the contacts list")
                }
            }, LinearLayout.LayoutParams(-1, kit.dp(48)).apply { bottomMargin = kit.dp(8) })
            var chosenSub = initialSubFor(initialAddress, emptyList())
            val lines = OeaSimDirectory.activeLines(host)
            if (lines.isNotEmpty() && chosenSub >= 0 && lines.none { it.subscriptionId == chosenSub }) {
                chosenSub = lines.first().subscriptionId
            }
            if (lines.size >= 2) {
                lateinit var simButton: TextView
                simButton = kit.quietButton("Send with ${simLabelFor(lines, chosenSub)}", R.drawable.oic_sim_card) {
                    chooseSim(lines, chosenSub) { picked ->
                        chosenSub = picked
                        simButton.text = "Send with ${simLabelFor(lines, picked)}"
                    }
                }
                panel.addView(simButton, LinearLayout.LayoutParams(-1, kit.dp(48)).apply { bottomMargin = kit.dp(8) })
            }
            panel.addView(bodyField, LinearLayout.LayoutParams(-1, -2))
            fun send() {
                val to = toField.text.toString().trim()
                val body = bodyField.text.toString().trim()
                if (to.isEmpty() || body.isEmpty()) {
                    toast("Enter a recipient and a message")
                    return
                }
                sendSms(to, body, chosenSub) { sent ->
                    if (sent) {
                        dialog.dismiss()
                        openConversation(to)
                    }
                }
            }
            bodyField.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    send()
                    true
                } else false
            }
            panel.addView(
                kit.buttonRow(
                    kit.quietButton("Cancel") { dialog.dismiss() },
                    kit.primaryButton("Send", R.drawable.oic_send) { send() }
                )
            )
        }
    }

    private fun simLabelFor(lines: List<OeaSimDirectory.Line>, subscriptionId: Int): String =
        lines.firstOrNull { it.subscriptionId == subscriptionId }?.let { OeaSimDirectory.shortLabel(it) }
            ?: lines.firstOrNull()?.let { OeaSimDirectory.shortLabel(it) } ?: "SIM"

    private fun chooseSim(lines: List<OeaSimDirectory.Line>, current: Int, onPick: (Int) -> Unit) {
        val options = lines.map { OeaSimDirectory.shortLabel(it) }
        val descriptions = lines.map { line ->
            buildString {
                OeaSimLabels.network(line.displayName, line.carrierName)?.let { append(it) }
                line.number?.let { number ->
                    if (isNotEmpty()) append(" • ")
                    append(number)
                }
            }.ifBlank { null }
        }
        val selected = lines.indexOfFirst { it.subscriptionId == current }.coerceAtLeast(0)
        kit.choose("Send with which SIM?", "The reply will come back to this SIM", options, selected, descriptions) { index ->
            lines.getOrNull(index)?.let { onPick(it.subscriptionId) }
        }
    }

    /**
     * Picks the SIM a conversation should start on: the one saved for this
     * address, else the one that handled its latest message (so a reply goes
     * back out on the SIM the sender texted), else Android's default SMS SIM.
     */
    internal fun initialSubFor(address: String?, messages: List<OeaMessagingApp.SmsMessage>): Int {
        val prefs = OeaMessagingApp.prefs(host)
        if (!address.isNullOrBlank()) {
            val saved = prefs.getInt(simKey(address), -1)
            if (saved >= 0) return saved
        }
        messages.lastOrNull { it.subscriptionId >= 0 }?.let { return it.subscriptionId }
        return runCatching { android.telephony.SubscriptionManager.getDefaultSmsSubscriptionId() }.getOrDefault(-1)
    }

    internal fun rememberSub(address: String, subscriptionId: Int) {
        if (subscriptionId < 0) return
        OeaMessagingApp.prefs(host).edit().putInt(simKey(address), subscriptionId).apply()
    }

    /** Sends one SMS through one SIM. [onDone] always runs on the UI thread. */
    internal fun sendSms(to: String, body: String, subscriptionId: Int, onDone: (Boolean) -> Unit) {
        if (!OeaMessagingApp.hasSendSmsPermission(host)) {
            requestPermission(android.Manifest.permission.SEND_SMS)
            onDone(false)
            return
        }
        OeaMessagingApp.io.execute {
            val sent = runCatching {
                val manager = OeaMessagingApp.smsManagerFor(host, subscriptionId)
                val parts = manager.divideMessage(body)
                if (parts == null || parts.size <= 1) manager.sendTextMessage(to, null, body, null, null)
                else manager.sendMultipartTextMessage(to, null, parts, null, null)
                true
            }
            if (sent.isSuccess) {
                rememberSub(to, subscriptionId)
                OeaMessagingApp.recordSentIfDefault(host, to, body, subscriptionId)
            }
            host.runOnUiThread {
                if (!alive || host.isDestroyed) return@runOnUiThread
                if (sent.isSuccess) {
                    onDone(true)
                    scheduleReload()
                } else {
                    toast("Could not send: ${sent.exceptionOrNull()?.message ?: "Android rejected the request"}")
                    onDone(false)
                }
            }
        }
    }

    private fun readPickedNumber(uri: Uri?): String? {
        if (uri == null) return null
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        return runCatching {
            host.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.trim() else null
            }
        }.getOrNull() ?: runCatching {
            host.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection,
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(uri.lastPathSegment), null
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0)?.trim() else null }
        }.getOrNull()
    }

    internal fun shareText(text: String, title: String) {
        runCatching {
            host.startActivity(
                Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), title)
            )
        }.onFailure { snack.show("No app can receive that share", OeaKit.Tone.WARNING) }
    }

    internal fun say(message: String, tone: OeaKit.Tone = OeaKit.Tone.INFO) = snack.show(message, tone)

    /** Feedback that stays visible while a dialog (chat, sheet, picker) is on top. */
    internal fun toast(message: String) {
        runCatching { android.widget.Toast.makeText(host, message, android.widget.Toast.LENGTH_LONG).show() }
    }

    internal fun openConversation(address: String, initialText: String? = null) {
        conversation?.dismissQuietly()
        val opened = Conversation(address, initialText)
        conversation = opened
        opened.show()
    }

    internal fun conversationClosed(closed: Conversation) {
        if (conversation === closed) conversation = null
        reloadThreads()
    }

    internal fun displayNameFor(address: String): String = names[address] ?: address

    // ============================================================== conversation

    /**
     * One conversation, full-screen: bubbles, day pills, an inline composer
     * with a SIM chip on dual-SIM phones, and long-press details naming the
     * SIM and network behind every single message.
     */
    internal inner class Conversation(
        private val address: String,
        private val initialText: String?
    ) {
        private val dialog = Dialog(host, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        private val displayName: String get() = displayNameFor(address)
        private var messages: List<OeaMessagingApp.SmsMessage> = emptyList()
        private var rows: List<ChatRow> = emptyList()
        private var visibleCount = 100
        private var selectedSub = -1
        private var simLines: List<OeaSimDirectory.Line> = emptyList()
        private var firstLoad = true
        private var dismissed = false

        private val bubbleMaxWidth: Int
            get() = (host.resources.displayMetrics.widthPixels * 0.78f).toInt()

        private lateinit var recycler: RecyclerView
        private lateinit var editor: EditText
        private lateinit var counter: TextView
        private lateinit var sendButton: View
        private lateinit var simChip: TextView
        private lateinit var subtitleView: TextView
        private val adapter = ChatAdapter()

        fun show() {
            selectedSub = initialSubFor(address, emptyList())
            simLines = OeaSimDirectory.activeLines(host)
            val rootLayout = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(palette.background)
            }
            // ---- top bar
            val topBar = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(kit.dp(8), kit.dp(8), kit.dp(8), kit.dp(8))
                setBackgroundColor(palette.card)
                elevation = kit.dpf(2f)
            }
            topBar.addView(
                kit.circleIconButton(R.drawable.oic_arrow_back, "Back", Color.TRANSPARENT, palette.text) { dialog.dismiss() },
                LinearLayout.LayoutParams(kit.dp(48), kit.dp(48))
            )
            topBar.addView(avatarView(displayName, address), LinearLayout.LayoutParams(kit.dp(42), kit.dp(42)).apply { marginStart = kit.dp(2); marginEnd = kit.dp(10) })
            val titles = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
            titles.addView(kit.text(displayName, 17f, palette.text, OeaType.medium).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            subtitleView = kit.text(if (displayName != address) address else "SMS", 12f, palette.textSecondary).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            titles.addView(subtitleView)
            titles.isClickable = true
            titles.setOnClickListener { conversationInfo() }
            topBar.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
            topBar.addView(
                kit.circleIconButton(R.drawable.oic_call, "Call $address", Color.TRANSPARENT, palette.text) {
                    runCatching { host.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$address"))) }
                },
                LinearLayout.LayoutParams(kit.dp(48), kit.dp(48))
            )
            topBar.addView(
                kit.circleIconButton(R.drawable.oic_more_vert, "Conversation options", Color.TRANSPARENT, palette.text) { moreOptions() },
                LinearLayout.LayoutParams(kit.dp(48), kit.dp(48))
            )
            rootLayout.addView(topBar, LinearLayout.LayoutParams(-1, -2))

            // ---- messages
            recycler = RecyclerView(host).apply {
                layoutManager = LinearLayoutManager(host)
                adapter = this@Conversation.adapter
                itemAnimator = null
                setPadding(0, kit.dp(8), 0, kit.dp(10))
                clipToPadding = false
                overScrollMode = View.OVER_SCROLL_NEVER
            }
            rootLayout.addView(recycler, LinearLayout.LayoutParams(-1, 0, 1f))

            // ---- composer
            val composer = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                setPadding(kit.dp(10), kit.dp(8), kit.dp(10), kit.dp(8))
                setBackgroundColor(palette.card)
            }
            simChip = kit.chip("", false, R.drawable.oic_sim_card) { pickSim() }
            paintSimChip()
            composer.addView(simChip, LinearLayout.LayoutParams(-2, kit.dp(48)).apply { marginEnd = kit.dp(8) })
            val fieldHost = FrameLayout(host).apply { background = kit.rounded(palette.background, 24, palette.stroke, 1) }
            editor = EditText(host).apply {
                hint = "Text message"
                setText(initialText ?: OeaMessagingApp.prefs(host).getString(draftKey(address), null).orEmpty())
                setSelection(text.length)
                setTextColor(palette.text)
                setHintTextColor(palette.textTertiary)
                textSize = 16f
                typeface = OeaType.regular
                background = null
                setPadding(kit.dp(16), kit.dp(12), kit.dp(14), kit.dp(12))
                minLines = 1
                maxLines = 5
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            }
            fieldHost.addView(editor, FrameLayout.LayoutParams(-1, -2))
            counter = kit.text("", 10.5f, palette.textTertiary).apply {
                gravity = Gravity.END
                setPadding(0, 0, kit.dp(12), kit.dp(5))
                visibility = View.GONE
            }
            fieldHost.addView(counter, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))
            composer.addView(fieldHost, LinearLayout.LayoutParams(0, -2, 1f))
            sendButton = kit.circleIconButton(R.drawable.oic_send, "Send", palette.accent, palette.onAccent) { sendCurrent() }
            composer.addView(sendButton, LinearLayout.LayoutParams(kit.dp(48), kit.dp(48)).apply { marginStart = kit.dp(8) })
            rootLayout.addView(composer, LinearLayout.LayoutParams(-1, -2))

            editor.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    paintComposerState(s?.toString().orEmpty())
                }
            })
            editor.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendCurrent()
                    true
                } else false
            }
            paintComposerState(editor.text.toString())

            rootLayout.setOnApplyWindowInsetsListener { _, insets ->
                val bars = OeaInsets.bars(insets)
                topBar.setPadding(kit.dp(8) + bars[0], bars[1] + kit.dp(6), kit.dp(8) + bars[2], kit.dp(8))
                composer.setPadding(kit.dp(10) + bars[0], kit.dp(8), kit.dp(10) + bars[2], bars[3] + kit.dp(8))
                insets
            }
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setContentView(rootLayout)
            dialog.setOnDismissListener {
                dismissed = true
                saveDraft()
                conversationClosed(this)
            }
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(palette.background))
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
                OeaInsets.edgeToEdge(this, lightBars = !palette.dark)
            }
            dialog.show()
            reload()
        }

        fun dismissQuietly() {
            if (!dismissed && dialog.isShowing) runCatching { dialog.dismiss() }
        }

        fun reload() {
            if (dismissed) return
            OeaMessagingApp.io.execute {
                OeaMessagingApp.markConversationReadIfDefault(host, address)
                val loaded = runCatching { OeaMessagingApp.queryConversation(host, address) }.getOrDefault(emptyList())
                host.runOnUiThread {
                    if (dismissed || host.isDestroyed) return@runOnUiThread
                    val wasAtBottom = !::recycler.isInitialized || !recycler.canScrollVertically(1) || firstLoad
                    messages = loaded
                    if (selectedSub < 0) selectedSub = initialSubFor(address, loaded)
                    simLines = OeaSimDirectory.activeLines(host)
                    // A remembered SIM that has been swapped out must not become
                    // the sending SIM: fall back to a SIM actually in the phone.
                    if (simLines.isNotEmpty() && selectedSub >= 0 && simLines.none { it.subscriptionId == selectedSub }) {
                        selectedSub = OeaSimDirectory.defaultSmsLine(host)?.subscriptionId ?: simLines.first().subscriptionId
                    }
                    paintSimChip()
                    paintSubtitle()
                    buildRows()
                    adapter.notifyDataSetChanged()
                    if (wasAtBottom && rows.isNotEmpty()) {
                        recycler.post { (recycler.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(rows.lastIndex, 0) }
                    }
                    firstLoad = false
                }
            }
        }

        private fun paintSubtitle() {
            val simsUsed = messages.map { it.subscriptionId }.filter { it >= 0 }.distinct()
            val simPart = simsUsed.singleOrNull()?.let { sub ->
                simLines.firstOrNull { it.subscriptionId == sub }?.let { " • ${OeaSimDirectory.shortLabel(it)}" }.orEmpty()
            }.orEmpty()
            subtitleView.text = buildString {
                append(if (displayName != address) address else "SMS")
                append(simPart)
                if (messages.isNotEmpty()) append(" • ${messages.size} message${if (messages.size == 1) "" else "s"}")
            }
        }

        private fun paintSimChip() {
            if (!::simChip.isInitialized) return
            if (simLines.size < 2) {
                simChip.visibility = View.GONE
                return
            }
            simChip.visibility = View.VISIBLE
            val line = simLines.firstOrNull { it.subscriptionId == selectedSub } ?: simLines.first()
            selectedSub = line.subscriptionId
            simChip.text = OeaSimDirectory.shortLabel(line)
            simChip.contentDescription = "Sending with ${OeaSimDirectory.shortLabel(line)}. Tap to change SIM."
        }

        private fun pickSim() {
            if (simLines.size < 2) return
            chooseSim(simLines, selectedSub) { picked ->
                selectedSub = picked
                rememberSub(address, picked)
                paintSimChip()
            }
        }

        private fun paintComposerState(body: String) {
            val ready = body.isNotBlank()
            sendButton.alpha = if (ready) 1f else 0.4f
            if (body.isEmpty()) {
                counter.visibility = View.GONE
                return
            }
            val info = runCatching { android.telephony.SmsMessage.calculateLength(body, false) }.getOrNull()
            if (info == null || info.size < 3) {
                counter.visibility = View.GONE
                return
            }
            val parts = info[0]
            val remaining = info[2]
            if (parts <= 1 && remaining > 25) {
                counter.visibility = View.GONE
            } else {
                counter.visibility = View.VISIBLE
                counter.text = if (parts <= 1) "$remaining" else "$remaining • $parts SMS"
            }
        }

        private fun sendCurrent() {
            val body = editor.text.toString().trim()
            if (body.isEmpty()) return
            sendSms(address, body, selectedSub) { sent ->
                if (sent) {
                    editor.setText("")
                    clearDraft()
                    if (OeaMessagingApp.isDefaultSmsApp(host)) {
                        reload()
                    } else {
                        // Android files non-default sends in its own time; look again shortly.
                        handler.postDelayed({ reload() }, 700L)
                        handler.postDelayed({ reload() }, 2200L)
                    }
                }
            }
        }

        private fun saveDraft() {
            if (!::editor.isInitialized) return
            val draft = editor.text.toString()
            val prefs = OeaMessagingApp.prefs(host)
            if (draft.isBlank()) prefs.edit().remove(draftKey(address)).apply()
            else prefs.edit().putString(draftKey(address), draft).apply()
        }

        private fun clearDraft() {
            OeaMessagingApp.prefs(host).edit().remove(draftKey(address)).apply()
        }

        private fun buildRows() {
            val now = System.currentTimeMillis()
            val start = (messages.size - visibleCount).coerceAtLeast(0)
            val built = ArrayList<ChatRow>()
            if (start > 0) built.add(ChatRow.Earlier(start))
            var lastDayStart = Long.MIN_VALUE
            var previous: OeaMessagingApp.SmsMessage? = null
            messages.drop(start).forEach { message ->
                val dayStart = OeaMessageDates.dayStart(message.date)
                if (dayStart != lastDayStart) {
                    built.add(ChatRow.Day(OeaMessageDates.separatorLabel(message.date, now)))
                    lastDayStart = dayStart
                    previous = null
                }
                val tight = previous != null &&
                    previous!!.incoming == message.incoming &&
                    message.date - previous!!.date in 0..120_000L
                built.add(ChatRow.Bubble(message, tight))
                previous = message
            }
            rows = built
        }

        // ------------------------------------------------------- conversation ui

        private inner class ChatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

            override fun getItemViewType(position: Int): Int = when (rows.getOrNull(position)) {
                is ChatRow.Earlier -> 0
                is ChatRow.Day -> 1
                else -> 2
            }

            override fun getItemCount(): Int = rows.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                return when (viewType) {
                    0 -> object : RecyclerView.ViewHolder(
                        FrameLayout(host).apply {
                            layoutParams = RecyclerView.LayoutParams(-1, -2)
                            setPadding(kit.dp(16), kit.dp(6), kit.dp(16), kit.dp(10))
                        }
                    ) {}
                    1 -> object : RecyclerView.ViewHolder(
                        LinearLayout(host).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER
                            layoutParams = RecyclerView.LayoutParams(-1, -2)
                            setPadding(0, kit.dp(12), 0, kit.dp(8))
                        }
                    ) {}
                    else -> object : RecyclerView.ViewHolder(
                        LinearLayout(host).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = RecyclerView.LayoutParams(-1, -2)
                            setPadding(kit.dp(12), 0, kit.dp(12), 0)
                        }
                    ) {}
                }
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                when (val row = rows.getOrNull(position)) {
                    is ChatRow.Earlier -> {
                        val frame = holder.itemView as FrameLayout
                        frame.removeAllViews()
                        frame.addView(kit.tonalButton("Load earlier messages (${row.hidden} more)", R.drawable.oic_history) {
                            visibleCount = (visibleCount + 100).coerceAtMost(messages.size)
                            buildRows()
                            notifyDataSetChanged()
                        }, FrameLayout.LayoutParams(-1, kit.dp(48)))
                    }
                    is ChatRow.Day -> {
                        val line = holder.itemView as LinearLayout
                        line.removeAllViews()
                        line.addView(kit.badge(row.label, palette.cardHigh, palette.textSecondary))
                    }
                    is ChatRow.Bubble -> bindBubble(holder.itemView as LinearLayout, row)
                    null -> Unit
                }
            }

            private fun bindBubble(column: LinearLayout, row: ChatRow.Bubble) {
                val message = row.message
                val sent = !message.incoming
                column.removeAllViews()
                column.setPadding(kit.dp(12), if (row.tightTop) kit.dp(1) else kit.dp(7), kit.dp(12), 0)
                column.gravity = if (sent) Gravity.END else Gravity.START
                val failed = message.type == Telephony.Sms.MESSAGE_TYPE_FAILED
                val bubble = TextView(host).apply {
                    text = message.body.ifBlank { "(empty message)" }
                    textSize = 15.5f
                    typeface = OeaType.regular
                    setLineSpacing(kit.dpf(2.5f), 1f)
                    setPadding(kit.dp(13), kit.dp(9), kit.dp(13), kit.dp(9))
                    maxWidth = bubbleMaxWidth
                    setTextColor(if (sent && !failed) palette.onAccent else palette.text)
                    background = kit.rounded(
                        when {
                            failed -> palette.card
                            sent -> palette.accent
                            else -> palette.card
                        },
                        20,
                        if (failed) palette.danger else palette.stroke,
                        if (sent && !failed) 0 else 1
                    )
                    isClickable = true
                    isFocusable = true
                }
                bubble.setOnClickListener { if (failed) showMessageDetails(message) }
                bubble.setOnLongClickListener {
                    showMessageDetails(message)
                    true
                }
                column.addView(bubble, LinearLayout.LayoutParams(-2, -2))
                val meta = buildMeta(message)
                val metaView = kit.text(meta, 10.5f, if (failed) palette.danger else palette.textTertiary).apply {
                    gravity = if (sent) Gravity.END else Gravity.START
                    setPadding(kit.dp(6), kit.dp(3), kit.dp(6), 0)
                }
                column.addView(metaView, LinearLayout.LayoutParams(-2, -2))
                bubble.contentDescription = buildString {
                    append(if (sent) "You said: " else "$displayName said: ")
                    append(message.body)
                    append(". ")
                    append(meta)
                    append(". Long-press for details, including the SIM it used.")
                }
            }

            private fun buildMeta(message: OeaMessagingApp.SmsMessage): String = buildString {
                append(OeaMessageDates.timeLabel(message.date))
                when (message.type) {
                    Telephony.Sms.MESSAGE_TYPE_DRAFT -> append(" • Draft")
                    Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED -> append(" • Sending…")
                    Telephony.Sms.MESSAGE_TYPE_FAILED -> append(" • Not sent — tap for options")
                    Telephony.Sms.MESSAGE_TYPE_SENT ->
                        if (message.status == Telephony.Sms.STATUS_COMPLETE) append(" • Delivered")
                }
                if (message.incoming && !message.read) append(" • Unread")
                if (simLines.size >= 2 && message.subscriptionId >= 0) {
                    simLines.firstOrNull { it.subscriptionId == message.subscriptionId }
                        ?.let { append(" • ${OeaSimDirectory.shortLabel(it)}") }
                }
            }
        }

        // --------------------------------------------------------- message details

        /**
         * The long-press sheet: what this message is, who it was with, when —
         * and, first among the rows, the SIM and network that handled it:
         * "Received on SIM 1" for incoming, "Sent with SIM 2" for outgoing.
         */
        private fun showMessageDetails(message: OeaMessagingApp.SmsMessage) {
            val lookup = OeaSimDirectory.lookup(host, message.subscriptionId)
            kit.sheet("Message details", null) { panel, sheetDialog ->
                val preview = LinearLayout(host).apply {
                    orientation = LinearLayout.VERTICAL
                    background = kit.rounded(palette.cardHigh, 16)
                    setPadding(kit.dp(14), kit.dp(10), kit.dp(14), kit.dp(10))
                }
                preview.addView(kit.text(message.body.take(300).ifBlank { "(empty message)" }, 14.5f, palette.text).apply {
                    setLineSpacing(kit.dpf(2f), 1f)
                })
                panel.addView(preview, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(10) })
                val detailsCol = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
                panel.addView(kit.boundedScroll(detailsCol, 0.5f), LinearLayout.LayoutParams(-1, -2))
                val group = kit.group(detailsCol)
                kit.row(
                    group, R.drawable.oic_info,
                    when {
                        message.type == Telephony.Sms.MESSAGE_TYPE_DRAFT -> "Draft"
                        message.type == Telephony.Sms.MESSAGE_TYPE_FAILED -> "Failed to send"
                        message.incoming -> "Received • Text (SMS)"
                        else -> "Sent • Text (SMS)"
                    },
                    if (message.incoming) "From ${if (displayName != address) "$displayName • $address" else address}"
                    else "To ${if (displayName != address) "$displayName • $address" else address}",
                    chevron = false
                )
                // The SIM row: the answer this sheet exists to give.
                val line = lookup.line
                when {
                    lookup.state == OeaSimDirectory.State.FOUND && line != null -> {
                        kit.row(
                            group, R.drawable.oic_sim_card,
                            (if (message.incoming) "Received on " else "Sent with ") + OeaSimDirectory.shortLabel(line),
                            buildString {
                                val name = line.displayName.ifBlank { line.carrierName }
                                if (name.isNotBlank() && !OeaSimDirectory.shortLabel(line).endsWith(name)) append(name)
                                line.number?.let { number ->
                                    if (isNotEmpty()) append(" • ")
                                    append(number)
                                }
                                if (isEmpty()) append(if (message.incoming) "The SIM this message arrived on" else "The SIM this message went out on")
                            },
                            chevron = false
                        )
                        OeaSimLabels.network(line.displayName, line.carrierName)?.let { network ->
                            kit.row(group, R.drawable.oic_public, "Network", network, chevron = false)
                        }
                    }
                    lookup.state == OeaSimDirectory.State.NOT_RECORDED -> kit.row(
                        group, R.drawable.oic_sim_card, "SIM not recorded",
                        "Android didn't store which SIM handled this message. That is common for older messages and for texts sent by other apps before dual-SIM recording.",
                        chevron = false
                    )
                    lookup.state == OeaSimDirectory.State.SIM_NOT_PRESENT -> kit.row(
                        group, R.drawable.oic_sim_card, "That SIM isn't in this phone now",
                        "Android recorded a SIM for this message, but it is not active at the moment, so its network can't be named. Put the SIM back and this fills in.",
                        chevron = false
                    )
                    lookup.state == OeaSimDirectory.State.PERMISSION_NEEDED -> kit.row(
                        group, R.drawable.oic_sim_card, "Allow Phone access to name the SIM",
                        "Android recorded which SIM this was; reading the SIM's name and network needs the Phone permission.",
                        chevron = false
                    ) {
                        sheetDialog.dismiss()
                        requestSimPermissions()
                    }
                    else -> kit.row(
                        group, R.drawable.oic_sim_card, "SIM details unavailable",
                        "This device reports no SIM subscriptions to apps.",
                        chevron = false
                    )
                }
                if (message.incoming) {
                    kit.row(group, R.drawable.oic_schedule, "Received", OeaMessageDates.fullLabel(message.date), chevron = false)
                    if (message.dateSent > 0L && message.dateSent != message.date) {
                        kit.row(group, R.drawable.oic_history, "Sent by sender", OeaMessageDates.fullLabel(message.dateSent), chevron = false)
                    }
                    kit.row(group, R.drawable.oic_check, "Status", if (message.read) "Read" else "Unread", chevron = false)
                } else {
                    kit.row(group, R.drawable.oic_schedule, "Sent", OeaMessageDates.fullLabel(message.date), chevron = false)
                    when (message.status) {
                        Telephony.Sms.STATUS_COMPLETE ->
                            kit.row(group, R.drawable.oic_check_circle_fill, "Delivery", "Delivered — the network confirmed it", chevron = false)
                        Telephony.Sms.STATUS_FAILED ->
                            kit.row(group, R.drawable.oic_warning_fill, "Delivery", "The network reported a delivery failure", chevron = false)
                        Telephony.Sms.STATUS_PENDING ->
                            kit.row(group, R.drawable.oic_history, "Delivery", "Still waiting for the network to confirm", chevron = false)
                    }
                }
                val parts = runCatching { android.telephony.SmsMessage.calculateLength(message.body, false) }.getOrNull()
                kit.row(
                    group, R.drawable.oic_text_fields, "Size",
                    buildString {
                        append("${message.body.length} character${if (message.body.length == 1) "" else "s"}")
                        if (parts != null && parts.isNotEmpty() && parts[0] > 1) append(" • ${parts[0]} SMS parts")
                    },
                    chevron = false
                )
                panel.addView(
                    kit.buttonRow(
                        kit.quietButton("Copy", R.drawable.oic_file_copy) {
                            sheetDialog.dismiss()
                            runCatching {
                                host.getSystemService(ClipboardManager::class.java)
                                    ?.setPrimaryClip(ClipData.newPlainText("SMS", message.body))
                                toast("Copied")
                            }
                        },
                        kit.tonalButton("Forward") {
                            sheetDialog.dismiss()
                            composeSms(null, message.body)
                        },
                        kit.quietButton("Share", R.drawable.oic_share_filled) {
                            sheetDialog.dismiss()
                            shareText(message.body, "Share message")
                        }
                    )
                )
                if (message.type == Telephony.Sms.MESSAGE_TYPE_FAILED) {
                    panel.addView(kit.primaryButton("Send again", R.drawable.oic_send) {
                        sheetDialog.dismiss()
                        val retrySub = when {
                            message.subscriptionId < 0 -> selectedSub
                            simLines.isEmpty() || simLines.any { it.subscriptionId == message.subscriptionId } -> message.subscriptionId
                            else -> selectedSub
                        }
                        sendSms(address, message.body, retrySub) { ok ->
                            if (ok) {
                                OeaMessagingApp.io.execute { OeaMessagingApp.deleteMessageIfDefault(host, message.id) }
                                handler.postDelayed({ reload() }, 500L)
                            }
                        }
                    }, LinearLayout.LayoutParams(-1, kit.dp(50)).apply { topMargin = kit.dp(6) })
                }
                val isDefault = OeaMessagingApp.isDefaultSmsApp(host)
                panel.addView(kit.button("Delete message", palette.cardHigh, palette.danger, R.drawable.oic_delete) {
                    sheetDialog.dismiss()
                    if (!isDefault) {
                        toast("Set OEA as default SMS app to delete messages")
                        return@button
                    }
                    kit.confirm("Delete this message?", message.body.take(100), "Delete", destructive = true) {
                        OeaMessagingApp.io.execute {
                            val done = OeaMessagingApp.deleteMessageIfDefault(host, message.id)
                            host.runOnUiThread {
                                if (done) reload() else toast("Android would not delete that message")
                            }
                        }
                    }
                }, LinearLayout.LayoutParams(-1, kit.dp(50)).apply { topMargin = kit.dp(6) })
            }
        }

        private fun conversationInfo() {
            kit.sheet(displayName, if (displayName != address) address else "SMS conversation") { panel, _ ->
                val infoCol = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
                panel.addView(kit.boundedScroll(infoCol, 0.66f), LinearLayout.LayoutParams(-1, -2))
                val group = kit.group(infoCol)
                kit.row(group, R.drawable.oic_info, "Messages", "${messages.size} in this conversation", chevron = false)
                val bySim = messages.filter { it.subscriptionId >= 0 }.groupBy { it.subscriptionId }
                if (bySim.isEmpty()) {
                    kit.row(
                        group, R.drawable.oic_sim_card, "SIMs used",
                        "Android recorded no SIM for these messages. Long-press a message for its own details.",
                        chevron = false
                    )
                } else {
                    bySim.forEach { (sub, own) ->
                        val incoming = own.count { it.incoming }
                        val outgoing = own.size - incoming
                        val label = simLines.firstOrNull { it.subscriptionId == sub }
                            ?.let { OeaSimDirectory.shortLabel(it) } ?: "A SIM not in this phone now"
                        kit.row(
                            group, R.drawable.oic_sim_card, label,
                            buildString {
                                if (incoming > 0) append("$incoming received")
                                if (incoming > 0 && outgoing > 0) append(" • ")
                                if (outgoing > 0) append("$outgoing sent")
                            },
                            chevron = false
                        )
                    }
                }
                messages.firstOrNull()?.let { first ->
                    kit.row(group, R.drawable.oic_history, "First message", OeaMessageDates.fullLabel(first.date), chevron = false)
                }
                messages.lastOrNull()?.let { last ->
                    kit.row(group, R.drawable.oic_schedule, "Latest message", OeaMessageDates.fullLabel(last.date), chevron = false)
                }
                kit.note(infoCol, "Long-press any single message to see exactly which SIM and network handled that one.")
            }
        }

        private fun moreOptions() {
            val prefs = OeaMessagingApp.prefs(host)
            fun inSet(key: String) = (prefs.getStringSet(key, emptySet()) ?: emptySet()).contains(address)
            fun toggleSet(key: String, on: Boolean, closeAfter: Boolean) {
                val values = (prefs.getStringSet(key, emptySet()) ?: emptySet()).toMutableSet()
                if (on) values.add(address) else values.remove(address)
                prefs.edit().putStringSet(key, values).apply()
                if (closeAfter) dialog.dismiss() else reloadThreads()
            }
            kit.actions(
                displayName, if (displayName != address) address else null,
                listOf(
                    OeaAppKit.Action(R.drawable.oic_call, "Call") {
                        runCatching { host.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$address"))) }
                    },
                    OeaAppKit.Action(R.drawable.oic_person, "Add to contacts", "Opens Android's new-contact form with this number filled in", disabled = displayName != address) {
                        runCatching {
                            host.startActivity(
                                Intent(ContactsContract.Intents.Insert.ACTION)
                                    .setType(ContactsContract.RawContacts.CONTENT_TYPE)
                                    .putExtra(ContactsContract.Intents.Insert.PHONE, address)
                            )
                        }
                    },
                    OeaAppKit.Action(R.drawable.oic_info, "Conversation details", "Counts, dates and the SIMs this conversation has used") { conversationInfo() },
                    OeaAppKit.Action(
                        R.drawable.oic_notifications_off, if (inSet(OeaMessagingApp.KEY_MUTED)) "Unmute" else "Mute notifications"
                    ) { toggleSet(OeaMessagingApp.KEY_MUTED, !inSet(OeaMessagingApp.KEY_MUTED), closeAfter = false) },
                    OeaAppKit.Action(
                        R.drawable.oic_folder, if (inSet(OeaMessagingApp.KEY_ARCHIVED)) "Unarchive" else "Archive"
                    ) { toggleSet(OeaMessagingApp.KEY_ARCHIVED, !inSet(OeaMessagingApp.KEY_ARCHIVED), closeAfter = true) },
                    OeaAppKit.Action(R.drawable.oic_share_filled, "Export conversation", "The whole transcript as text, SIMs included") {
                        val text = messages.joinToString("\n") { message ->
                            val who = if (message.incoming) displayName else "Me"
                            val sim = if (message.subscriptionId >= 0) {
                                simLines.firstOrNull { it.subscriptionId == message.subscriptionId }
                                    ?.let { " (${OeaSimDirectory.shortLabel(it)})" }.orEmpty()
                            } else ""
                            "[${OeaMessageDates.fullLabel(message.date)}] $who$sim: ${message.body}"
                        }
                        shareText(text, "Export conversation")
                    },
                    OeaAppKit.Action(
                        R.drawable.oic_block, if (inSet(OeaMessagingApp.KEY_BLOCKED)) "Unblock" else "Block",
                        "Hides this address from All; it does not stop carrier delivery",
                        destructive = !inSet(OeaMessagingApp.KEY_BLOCKED)
                    ) { toggleSet(OeaMessagingApp.KEY_BLOCKED, !inSet(OeaMessagingApp.KEY_BLOCKED), closeAfter = true) }
                )
            )
        }
    }

    companion object {
        private const val REQUEST_CONTACT = 1003
        private const val REQUEST_SMS_ROLE = 1004
        private const val REQUEST_PERMISSIONS = 1005
        private val TABS = listOf(
            "all" to "All",
            "unread" to "Unread",
            "archived" to "Archived",
            "blocked" to "Blocked",
            "spam" to "Spam"
        )
    }
}
