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
        val latestType: Int = 0,
        /** Android's `thread_id` for the newest message, or -1 when it has none. */
        val threadId: Long = -1L,
        /** Every `thread_id` rows of this conversation carried — dual SIM splits them. */
        val threadIds: Set<Long> = emptySet(),
        /** Every raw `address` string these rows used: +234…, 0708…, "312 ", "312". */
        val rawAddresses: Set<String> = emptySet()
    ) {
        /** Every address form this conversation has been seen under, trimmed. */
        fun lookupAddresses(): Set<String> {
            val out = LinkedHashSet<String>()
            rawAddresses.map { it.trim() }.filterTo(out) { it.isNotEmpty() }
            address.trim().takeIf { it.isNotEmpty() }?.let { out.add(it) }
            return out
        }

        /**
         * The key pin/mute/read state is stored under, so `+2347089007507` and
         * `07089007507` keep one shared state instead of two competing ones.
         */
        val canonicalKey: String get() = OeaSmsMatcher.canonicalAddressKey(address)
    }

    data class SmsMessage(
        val id: Long,
        val address: String,
        val body: String,
        val date: Long,
        val dateSent: Long,
        val type: Int,
        val read: Boolean,
        val status: Int,
        val subscriptionId: Int,
        /** True for an MMS text part. Such rows carry a negative id so they never collide with SMS ids. */
        val mms: Boolean = false
    ) {
        val incoming: Boolean get() = type == Telephony.Sms.MESSAGE_TYPE_INBOX
    }

    /** One row of the phone's contact directory, loaded once and reused everywhere. */
    data class ContactEntry(val name: String, val number: String)

    internal const val PREFS = "oea_messaging_v2"
    internal const val KEY_ARCHIVED = "archived_threads"
    internal const val KEY_BLOCKED = "blocked_addresses"
    internal const val KEY_MUTED = "muted_addresses"
    internal const val KEY_PINNED = "pinned_threads"
    internal const val KEY_SPAM_KEYWORDS = "spam_keywords"
    internal const val KEY_STARRED = "starred_messages"
    /** Local "marked read" overrides: they apply even when OEA is not the default SMS app. */
    internal const val KEY_MARKED_READ = "marked_read_threads"
    internal const val KEY_MARKED_UNREAD = "marked_unread_threads"

    internal val io = Executors.newSingleThreadExecutor { Thread(it, "OeaMessaging").apply { isDaemon = true } }
    /** The inbox never waits behind a conversation query, and vice versa. */
    internal val inboxIo = Executors.newSingleThreadExecutor { Thread(it, "OeaMessaging-inbox").apply { isDaemon = true } }
    internal val chatIo = Executors.newSingleThreadExecutor { Thread(it, "OeaMessaging-chat").apply { isDaemon = true } }
    internal val contactIo = Executors.newSingleThreadExecutor { Thread(it, "OeaMessaging-contacts").apply { isDaemon = true } }

    // ------------------------------------------------------------- warm cache

    /**
     * The last good inbox snapshot and contact directory. Reopening Messages
     * paints these immediately and refreshes behind them, so the list is never
     * an empty screen that later fills in (and never flashes "No messages yet"
     * while a scan is running).
     */
    @Volatile private var cachedThreads: List<SmsThread> = emptyList()
    @Volatile private var cachedNames: Map<String, String> = emptyMap()
    @Volatile private var cachedContactList: List<ContactEntry> = emptyList()
    @Volatile private var contactsLoaded = false

    internal fun snapshotThreads(): List<SmsThread> = cachedThreads
    internal fun snapshotNames(): Map<String, String> = cachedNames
    internal fun snapshotContacts(): List<ContactEntry> = cachedContactList
    internal fun hasContactsLoaded(): Boolean = contactsLoaded

    internal fun rememberSnapshot(threads: List<SmsThread>, names: Map<String, String>) {
        cachedThreads = threads
        cachedNames = names
    }

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

    /**
     * The whole phone directory in **one** query.
     *
     * Resolving names used to cost one `PhoneLookup` round-trip per conversation,
     * which is what made an inbox of a few hundred threads take seconds to paint.
     * One pass over `CommonDataKinds.Phone` gives Messaging everything it needs for
     * the list, for Start Chat and for instant search, and it runs off the UI thread.
     */
    internal fun loadContacts(ctx: Context): List<ContactEntry> {
        if (!hasPermission(ctx, android.Manifest.permission.READ_CONTACTS)) return emptyList()
        val out = ArrayList<ContactEntry>()
        val seen = HashSet<String>()
        runCatching {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC"
            )
        }.getOrNull()?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (nameIndex >= 0 && numberIndex >= 0) {
                while (cursor.moveToNext()) {
                    val number = cursor.getString(numberIndex)?.trim().orEmpty()
                    val name = cursor.getString(nameIndex)?.trim().orEmpty()
                    if (number.isEmpty() || name.isEmpty()) continue
                    val key = OeaSmsMatcher.canonicalAddressKey(number)
                    if (key.isEmpty()) continue
                    if (seen.add(key)) out.add(ContactEntry(name, number))
                }
            }
        }
        contactsLoaded = true
        cachedContactList = out
        return out
    }

    /** Contacts already loaded this session, so Start Chat opens without a query at all. */
    internal fun contactsNow(ctx: Context): List<ContactEntry> =
        if (contactsLoaded) cachedContactList else loadContacts(ctx)

    /** Canonical key -> saved name, built once from the directory. */
    private fun contactKeyMap(contacts: List<ContactEntry>): Map<String, String> {
        val byKey = HashMap<String, String>(contacts.size)
        contacts.forEach { entry ->
            val key = OeaSmsMatcher.canonicalAddressKey(entry.number)
            if (key.isNotEmpty()) byKey.putIfAbsent(key, entry.name)
        }
        return byKey
    }

    /**
     * Resolves names for every conversation address at once, matching the way
     * the index groups threads: `+2347089007507` and `07089007507` are one person.
     */
    internal fun resolveNames(ctx: Context, addresses: Collection<String>, contacts: List<ContactEntry>): Map<String, String> {
        val out = HashMap<String, String>(addresses.size)
        if (contacts.isEmpty()) {
            if (hasPermission(ctx, android.Manifest.permission.READ_CONTACTS)) {
                addresses.forEach { out[it] = contactName(ctx, it) }
            }
            return out
        }
        val byKey = contactKeyMap(contacts)
        addresses.forEach { address ->
            byKey[OeaSmsMatcher.canonicalAddressKey(address)]?.let { out[address] = it }
        }
        return out
    }

    /** True when [address] belongs to a saved contact, using the cached directory. */
    internal fun isKnownContact(ctx: Context, address: String): Boolean {
        val key = OeaSmsMatcher.canonicalAddressKey(address)
        if (key.isEmpty()) return false
        if (contactsLoaded) return contactKeyMap(cachedContactList).containsKey(key)
        return contactName(ctx, address) != address
    }

    // ------------------------------------------------------ read-state overrides

    /**
     * Local read/unread marks.
     *
     * Only the default SMS app may write `read` into the provider, so "mark as
     * read" silently did nothing on most phones. These overrides are stored per
     * canonical address and applied when the list is built, so the badge always
     * obeys the user, and they are dropped again the moment the provider itself
     * can be written to.
     */
    private fun overrideSet(ctx: Context, key: String): Set<String> =
        prefs(ctx).getStringSet(key, emptySet()) ?: emptySet()

    internal fun markedRead(ctx: Context, address: String): Boolean =
        overrideSet(ctx, KEY_MARKED_READ).contains(OeaSmsMatcher.canonicalAddressKey(address))

    internal fun markedUnread(ctx: Context, address: String): Boolean =
        overrideSet(ctx, KEY_MARKED_UNREAD).contains(OeaSmsMatcher.canonicalAddressKey(address))

    internal fun setMarkedRead(ctx: Context, address: String, on: Boolean) {
        val key = OeaSmsMatcher.canonicalAddressKey(address)
        if (key.isEmpty()) return
        val prefs = prefs(ctx)
        val read = (prefs.getStringSet(KEY_MARKED_READ, emptySet()) ?: emptySet()).toMutableSet()
        val unread = (prefs.getStringSet(KEY_MARKED_UNREAD, emptySet()) ?: emptySet()).toMutableSet()
        if (on) { read.add(key); unread.remove(key) } else { read.remove(key); unread.add(key) }
        prefs.edit().putStringSet(KEY_MARKED_READ, read).putStringSet(KEY_MARKED_UNREAD, unread).apply()
    }

    internal fun clearReadOverrides(ctx: Context, address: String) {
        val key = OeaSmsMatcher.canonicalAddressKey(address)
        if (key.isEmpty()) return
        val prefs = prefs(ctx)
        val read = (prefs.getStringSet(KEY_MARKED_READ, emptySet()) ?: emptySet()).toMutableSet()
        val unread = (prefs.getStringSet(KEY_MARKED_UNREAD, emptySet()) ?: emptySet()).toMutableSet()
        if (read.remove(key) || unread.remove(key)) {
            prefs.edit().putStringSet(KEY_MARKED_READ, read).putStringSet(KEY_MARKED_UNREAD, unread).apply()
        }
    }

    /** Applies the local overrides to a freshly-read thread. */
    internal fun withReadOverrides(ctx: Context, thread: SmsThread): SmsThread {
        val key = thread.canonicalKey
        val readOverride = overrideSet(ctx, KEY_MARKED_READ).contains(key)
        val unreadOverride = overrideSet(ctx, KEY_MARKED_UNREAD).contains(key)
        if (!readOverride && !unreadOverride) return thread
        return if (readOverride) thread.copy(read = true, unreadCount = 0)
        else thread.copy(read = false, unreadCount = thread.unreadCount.coerceAtLeast(1))
    }

    /**
     * Starred messages are stored as `"<thread key>#<message id>"`.
     *
     * The provider numbers SMS ids globally, but a star has to survive the
     * message being deleted elsewhere and it has to be findable again without
     * scanning the whole table — keeping the thread key with the id makes both
     * possible, and a bare id would not.
     */
    internal fun starKey(threadKey: String, id: Long): String = "$threadKey#$id"

    internal fun starredKeys(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_STARRED, emptySet()) ?: emptySet()

    internal fun isMessageStarred(ctx: Context, threadKey: String, id: Long): Boolean =
        starredKeys(ctx).contains(starKey(threadKey, id))

    internal fun setMessageStarred(ctx: Context, threadKey: String, id: Long, on: Boolean) {
        val key = starKey(threadKey, id)
        val values = (prefs(ctx).getStringSet(KEY_STARRED, emptySet()) ?: emptySet()).toMutableSet()
        if (on) values.add(key) else values.remove(key)
        prefs(ctx).edit().putStringSet(KEY_STARRED, values).apply()
    }

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
            Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE,
            Telephony.Sms.DATE_SENT, Telephony.Sms.TYPE, Telephony.Sms.READ, Telephony.Sms.STATUS, "sub_id"
        )
        val basic = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
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
            val threadIndex = c.getColumnIndex(Telephony.Sms.THREAD_ID)
            if (addressIndex < 0 || bodyIndex < 0 || dateIndex < 0) return@use
            while (c.moveToNext()) {
                // Unread means an unread *incoming* message; my own sent rows
                // carry read=1 in practice, but never let one fake a badge.
                val type = c.intAt(typeIndex, Telephony.Sms.MESSAGE_TYPE_INBOX)
                val isRead = c.intAt(readIndex, 1) != 0 || type != Telephony.Sms.MESSAGE_TYPE_INBOX
                index.add(
                    c.stringAt(addressIndex), c.stringAt(bodyIndex), c.longAt(dateIndex, 0L),
                    isRead, c.intAt(subIndex, -1), type, c.longAt(threadIndex, -1L)
                )
            }
        }
        return index.threads().map { withReadOverrides(ctx, it) }
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

    /**
     * Every address string the provider uses for one correspondent.
     *
     * The same person arrives as `+2347089007507`, `07089007507` and `0708 900
     * 7507`; one bank arrives as both `312` and `"312 "`. Matching on the single
     * address shown in the list is what made conversations open blank, so this
     * collects every variant the provider holds and verifies each candidate with
     * [OeaSmsMatcher.sameAddress] (never with a bare `LIKE` alone).
     */
    internal fun resolveThreadAddresses(ctx: Context, seeds: Collection<String>): Set<String> {
        val wanted = seeds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (wanted.isEmpty()) return emptySet()
        val found = LinkedHashSet(wanted)
        val clauses = ArrayList<String>()
        val args = ArrayList<String>()
        wanted.forEach { seed ->
            clauses.add("${Telephony.Sms.ADDRESS} = ?")
            args.add(seed)
            // Last-7-digit suffix catches E.164, local and national forms of one number.
            OeaSmsMatcher.sqlPhoneSuffix(seed)?.let { suffix ->
                clauses.add("${Telephony.Sms.ADDRESS} LIKE ?")
                args.add("%$suffix")
            }
            // Substring on the cleaned digits catches padded shortcodes (" 312", "312  ").
            val clean = OeaSmsMatcher.cleanDialable(seed)
            if (clean.length >= 3 && clean != seed) {
                clauses.add("${Telephony.Sms.ADDRESS} LIKE ?")
                args.add("%$clean%")
            }
            if (OeaSmsMatcher.isAlphanumericSender(seed)) {
                clauses.add("${Telephony.Sms.ADDRESS} LIKE ?")
                args.add(seed)
            }
        }
        runCatching {
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS),
                clauses.joinToString(" OR "), args.toTypedArray(), null
            )
        }.getOrNull()?.use { cursor ->
            while (cursor.moveToNext()) {
                val candidate = cursor.getString(0)?.trim().orEmpty()
                if (candidate.isEmpty()) continue
                if (wanted.any { OeaSmsMatcher.sameAddress(candidate, it) }) found.add(candidate)
            }
        }
        return found
    }

    /** Reads one cursor row into a message, tolerating columns the OEM renamed. */
    private fun readSmsRow(c: Cursor, index: SmsColumnIndex, fallbackAddress: String): SmsMessage =
        SmsMessage(
            id = c.longAt(index.id, 0L),
            address = c.stringAt(index.address).ifBlank { fallbackAddress },
            body = c.stringAt(index.body),
            date = c.longAt(index.date, 0L),
            dateSent = c.longAt(index.dateSent, 0L),
            type = c.intAt(index.type, Telephony.Sms.MESSAGE_TYPE_INBOX),
            read = c.intAt(index.read, 1) != 0,
            status = c.intAt(index.status, Telephony.Sms.STATUS_NONE),
            subscriptionId = c.intAt(index.sub, -1)
        )

    private class SmsColumnIndex(
        val id: Int, val address: Int, val body: Int, val date: Int, val dateSent: Int,
        val type: Int, val read: Int, val status: Int, val sub: Int, val thread: Int
    ) {
        companion object {
            fun of(c: Cursor) = SmsColumnIndex(
                id = c.getColumnIndex(Telephony.Sms._ID),
                address = c.getColumnIndex(Telephony.Sms.ADDRESS),
                body = c.getColumnIndex(Telephony.Sms.BODY),
                date = c.getColumnIndex(Telephony.Sms.DATE),
                dateSent = c.getColumnIndex(Telephony.Sms.DATE_SENT),
                type = c.getColumnIndex(Telephony.Sms.TYPE),
                read = c.getColumnIndex(Telephony.Sms.READ),
                status = c.getColumnIndex(Telephony.Sms.STATUS),
                sub = c.getColumnIndex("sub_id"),
                thread = c.getColumnIndex(Telephony.Sms.THREAD_ID)
            )
        }
    }

    /**
     * One conversation, unioned from every way Android may have filed it:
     * the provider's own `thread_id`, every exact or variant address form, and —
     * only if all of that comes back empty — a bounded in-Kotlin scan verified
     * with [OeaSmsMatcher.sameAddress]. MMS text parts are merged in last.
     */
    internal fun queryConversation(ctx: Context, addresses: Set<String>, threadIds: Set<Long>): List<SmsMessage> {
        val wanted = addresses.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val ids = threadIds.filter { it > 0L }.distinct()
        if (wanted.isEmpty() && ids.isEmpty()) return emptyList()

        val byId = LinkedHashMap<Long, SmsMessage>()
        val clauses = ArrayList<String>()
        val args = ArrayList<String>()
        ids.forEach { id ->
            clauses.add("${Telephony.Sms.THREAD_ID} = ?")
            args.add(id.toString())
        }
        wanted.forEach { address ->
            clauses.add("${Telephony.Sms.ADDRESS} = ?")
            args.add(address)
            OeaSmsMatcher.sqlPhoneSuffix(address)?.let { suffix ->
                clauses.add("${Telephony.Sms.ADDRESS} LIKE ?")
                args.add("%$suffix")
            }
            val clean = OeaSmsMatcher.cleanDialable(address)
            if (clean.length >= 3 && clean != address) {
                clauses.add("${Telephony.Sms.ADDRESS} LIKE ?")
                args.add("%$clean%")
            }
        }
        if (clauses.isNotEmpty()) {
            runCatching {
                querySms(ctx, clauses.joinToString(" OR "), args.toTypedArray(), "${Telephony.Sms.DATE} ASC, ${Telephony.Sms._ID} ASC")
            }.getOrNull()?.use { c ->
                val index = SmsColumnIndex.of(c)
                if (index.id >= 0 && index.body >= 0 && index.date >= 0) {
                    while (c.moveToNext()) {
                        val rowAddress = c.stringAt(index.address)
                        val rowThread = c.longAt(index.thread, -1L)
                        // A thread_id row belongs here wholesale (it may be a group MMS);
                        // an address-matched row only counts when it is really the same person.
                        val belongs = ids.contains(rowThread) ||
                            rowAddress.isBlank() ||
                            wanted.any { OeaSmsMatcher.sameAddress(rowAddress, it) }
                        if (!belongs) continue
                        val message = readSmsRow(c, index, wanted.firstOrNull().orEmpty())
                        if (message.id > 0L) byId[message.id] = message
                    }
                }
            }
        }
        // Last resort: a bounded scan for phones whose provider answered the
        // indexed queries oddly. Still exact about *who* counts as the same person.
        if (byId.isEmpty() && wanted.isNotEmpty()) {
            runCatching { scanForConversation(ctx, wanted, 4000) }.getOrDefault(emptyList()).forEach { message ->
                if (message.id > 0L) byId[message.id] = message
            }
        }
        val merged = byId.values.toMutableList()
        if (ids.isNotEmpty()) merged.addAll(queryMmsMessages(ctx, ids, wanted.firstOrNull().orEmpty()))
        return merged.sortedWith(compareBy({ it.date }, { it.id }))
    }

    /** Opens a conversation from its indexed thread — the path the list uses. */
    internal fun queryConversation(ctx: Context, thread: SmsThread): List<SmsMessage> {
        val seeds = thread.lookupAddresses()
        val addresses = runCatching { resolveThreadAddresses(ctx, seeds) }.getOrDefault(seeds).toSet() + seeds
        return queryConversation(ctx, addresses, thread.threadIds)
    }

    /** Opens a conversation from a bare address (deep links, notifications, quick reply). */
    internal fun queryConversation(ctx: Context, address: String): List<SmsMessage> {
        val seeds = setOf(address.trim()).filter { it.isNotEmpty() }.toSet()
        if (seeds.isEmpty()) return emptyList()
        val addresses = runCatching { resolveThreadAddresses(ctx, seeds) }.getOrDefault(seeds).toSet() + seeds
        val threadIds = runCatching { threadIdsFor(ctx, addresses) }.getOrDefault(emptySet())
        return queryConversation(ctx, addresses, threadIds)
    }

    /** The provider thread_ids used by any of [addresses]. */
    internal fun threadIdsFor(ctx: Context, addresses: Set<String>): Set<Long> {
        val wanted = addresses.map { it.trim() }.filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return emptySet()
        val out = LinkedHashSet<Long>()
        val clauses = ArrayList<String>()
        val args = ArrayList<String>()
        wanted.forEach { address ->
            clauses.add("${Telephony.Sms.ADDRESS} = ?")
            args.add(address)
            OeaSmsMatcher.sqlPhoneSuffix(address)?.let { suffix ->
                clauses.add("${Telephony.Sms.ADDRESS} LIKE ?")
                args.add("%$suffix")
            }
        }
        runCatching {
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS),
                clauses.joinToString(" OR "), args.toTypedArray(), null
            )
        }.getOrNull()?.use { c ->
            val threadIndex = c.getColumnIndex(Telephony.Sms.THREAD_ID)
            val addressIndex = c.getColumnIndex(Telephony.Sms.ADDRESS)
            if (threadIndex < 0) return@use
            while (c.moveToNext()) {
                val rowAddress = c.getString(addressIndex).orEmpty()
                if (addressIndex >= 0 && rowAddress.isNotBlank() && wanted.none { OeaSmsMatcher.sameAddress(rowAddress, it) }) continue
                val id = c.getLong(threadIndex)
                if (id > 0L) out.add(id)
            }
        }
        return out
    }

    /** Bounded fallback scan, newest rows first, verified with [OeaSmsMatcher.sameAddress]. */
    private fun scanForConversation(ctx: Context, wanted: Set<String>, limit: Int): List<SmsMessage> {
        val out = ArrayList<SmsMessage>()
        runCatching {
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI, null, null, null, "${Telephony.Sms.DATE} DESC LIMIT $limit"
            )
        }.getOrNull()?.use { c ->
            val index = SmsColumnIndex.of(c)
            if (index.id < 0 || index.body < 0 || index.date < 0) return@use
            while (c.moveToNext()) {
                val rowAddress = c.stringAt(index.address)
                if (rowAddress.isBlank() || wanted.none { OeaSmsMatcher.sameAddress(rowAddress, it) }) continue
                val message = readSmsRow(c, index, wanted.firstOrNull().orEmpty())
                if (message.id > 0L) out.add(message)
            }
        }
        return out
    }

    /**
     * The text of MMS messages in these threads, so a picture message does not
     * show up as a conversation that "has nothing in it".
     *
     * MMS rows are given negative ids: the provider numbers MMS separately from
     * SMS, so `id = 42` means different things in the two tables and a shared
     * numbering would let "delete this message" remove the wrong one.
     */
    internal fun queryMmsMessages(ctx: Context, threadIds: List<Long>, fallbackAddress: String): List<SmsMessage> {
        val out = ArrayList<SmsMessage>()
        val mmsUri = Telephony.Mms.CONTENT_URI
        threadIds.forEach { threadId ->
            val ids = ArrayList<Pair<Long, Long>>() // mms _id -> date millis
            runCatching {
                ctx.contentResolver.query(
                    mmsUri,
                    arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE),
                    "${Telephony.Mms.THREAD_ID} = ?",
                    arrayOf(threadId.toString()),
                    "${Telephony.Mms.DATE} ASC"
                )
            }.getOrNull()?.use { c ->
                val idIndex = c.getColumnIndex(Telephony.Mms._ID)
                val dateIndex = c.getColumnIndex(Telephony.Mms.DATE)
                while (c.moveToNext()) {
                    val id = if (idIndex >= 0) c.getLong(idIndex) else -1L
                    // MMS dates are seconds, not milliseconds.
                    val date = (if (dateIndex >= 0) c.getLong(dateIndex) else 0L) * 1000L
                    if (id > 0L) ids.add(id to date)
                }
            }
            ids.forEach { (mmsId, date) ->
                val text = readMmsText(ctx, mmsId)
                if (!text.isNullOrBlank()) {
                    out.add(
                        SmsMessage(
                            id = -mmsId,
                            address = fallbackAddress,
                            body = text,
                            date = date,
                            dateSent = date,
                            type = Telephony.Sms.MESSAGE_TYPE_INBOX,
                            read = true,
                            status = Telephony.Sms.STATUS_NONE,
                            subscriptionId = -1,
                            mms = true
                        )
                    )
                }
            }
        }
        return out
    }

    /** The `text/plain` parts of one MMS, from the inline column or its data file. */
    private fun readMmsText(ctx: Context, mmsId: Long): String? {
        val partUri = Uri.parse("content://mms/part")
        val parts = StringBuilder()
        runCatching {
            ctx.contentResolver.query(
                partUri, arrayOf("_id", "ct", "text", "_data"), "mid = ?", arrayOf(mmsId.toString()), null
            )
        }.getOrNull()?.use { c ->
            val idIndex = c.getColumnIndex("_id")
            val ctIndex = c.getColumnIndex("ct")
            val textIndex = c.getColumnIndex("text")
            val dataIndex = c.getColumnIndex("_data")
            while (c.moveToNext()) {
                val type = if (ctIndex >= 0) c.getString(ctIndex).orEmpty() else ""
                if (!type.equals("text/plain", ignoreCase = true) && !type.startsWith("text/", ignoreCase = true)) continue
                val inline = if (textIndex >= 0 && !c.isNull(textIndex)) c.getString(textIndex) else null
                if (!inline.isNullOrBlank()) {
                    if (parts.isNotEmpty()) parts.append('\n')
                    parts.append(inline)
                    continue
                }
                val data = if (dataIndex >= 0 && !c.isNull(dataIndex)) c.getString(dataIndex) else null
                val partId = if (idIndex >= 0) c.getLong(idIndex) else -1L
                if (partId < 0) continue
                val streamed = runCatching {
                    ctx.contentResolver.openInputStream(Uri.withAppendedPath(partUri, partId.toString()))
                        ?.bufferedReader()?.use { it.readText() }
                }.getOrNull() ?: runCatching {
                    if (!data.isNullOrBlank()) java.io.File(data).readText() else null
                }.getOrNull()
                if (!streamed.isNullOrBlank()) {
                    if (parts.isNotEmpty()) parts.append('\n')
                    parts.append(streamed)
                }
            }
        }
        return parts.toString().trim().ifEmpty { null }
    }

    /**
     * Marks a whole conversation read (or unread).
     *
     * Writes every address form the provider holds, so a thread split across
     * `+234…` and `0708…` goes read completely instead of half of it. When OEA
     * is not the default SMS app the provider refuses the write, so the local
     * override is set instead and the badge still obeys the user.
     */
    internal fun markConversationRead(ctx: Context, address: String, read: Boolean = true) {
        val addresses = runCatching { resolveThreadAddresses(ctx, setOf(address)) }.getOrDefault(setOf(address))
            .let { if (it.isEmpty()) setOf(address) else it }
        if (isDefaultSmsApp(ctx)) {
            val values = ContentValues().apply {
                put(Telephony.Sms.READ, if (read) 1 else 0)
                if (read) put(Telephony.Sms.SEEN, 1)
            }
            addresses.forEach { candidate ->
                runCatching {
                    ctx.contentResolver.update(
                        Telephony.Sms.CONTENT_URI, values,
                        "${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.READ} = ?",
                        arrayOf(candidate, if (read) "0" else "1")
                    )
                }
            }
            runCatching {
                val ids = threadIdsFor(ctx, addresses)
                if (ids.isNotEmpty()) {
                    ctx.contentResolver.update(
                        Telephony.Sms.CONTENT_URI, values,
                        "${Telephony.Sms.THREAD_ID} IN (${ids.joinToString(",") { "?" }})",
                        ids.map { it.toString() }.toTypedArray()
                    )
                }
            }
            clearReadOverrides(ctx, address)
        } else {
            setMarkedRead(ctx, address, read)
        }
    }

    internal fun markConversationReadIfDefault(ctx: Context, address: String, read: Boolean = true) =
        markConversationRead(ctx, address, read)

    /** Marks every conversation read. Returns how many threads were still unread. */
    internal fun markAllRead(ctx: Context, threads: List<SmsThread>): Int {
        val unread = threads.filter { !it.read }
        unread.forEach { markConversationRead(ctx, it.address, true) }
        return unread.size
    }

    internal fun deleteConversationIfDefault(ctx: Context, address: String): Boolean {
        if (!isDefaultSmsApp(ctx)) return false
        val addresses = runCatching { resolveThreadAddresses(ctx, setOf(address)) }.getOrDefault(setOf(address))
            .let { if (it.isEmpty()) setOf(address) else it }
        var ok = false
        addresses.forEach { candidate ->
            ok = runCatching {
                ctx.contentResolver.delete(Telephony.Sms.CONTENT_URI, "${Telephony.Sms.ADDRESS} = ?", arrayOf(candidate)) >= 0
            }.getOrDefault(false) || ok
        }
        val ids = runCatching { threadIdsFor(ctx, addresses) }.getOrDefault(emptySet())
        ids.forEach { id ->
            runCatching {
                ctx.contentResolver.delete(
                    Telephony.Sms.CONTENT_URI, "${Telephony.Sms.THREAD_ID} = ?", arrayOf(id.toString())
                )
            }
        }
        clearReadOverrides(ctx, address)
        return ok
    }

    /** Deletes one message. Negative ids are MMS (see [queryMmsMessages]). */
    internal fun deleteMessageIfDefault(ctx: Context, id: Long): Boolean {
        if (!isDefaultSmsApp(ctx)) return false
        if (id < 0L) {
            return runCatching {
                ctx.contentResolver.delete(
                    Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, (-id).toString()), null, null
                ) > 0
            }.getOrDefault(false)
        }
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

    /**
     * Sends one SMS from anywhere (the conversation, a quick reply, the floating
     * window) and reports the result on the UI thread.
     *
     * Delivery is watched through the same `SMS_SENT` / `SMS_DELIVERED`
     * broadcasts the conversation uses, so a message that leaves OEA but is
     * rejected by the network says so instead of sitting in the thread looking
     * sent. The split into parts is the manager's own, so long texts arrive whole.
     */
    internal fun sendNow(
        ctx: Context,
        to: String,
        body: String,
        subscriptionId: Int,
        sentAction: String? = null,
        deliveredAction: String? = null,
        onResult: ((Boolean, String?) -> Unit)? = null
    ) {
        if (!hasSendSmsPermission(ctx)) {
            onResult?.invoke(false, "SEND_SMS permission missing")
            return
        }
        io.execute {
            val outcome = runCatching {
                val manager = smsManagerFor(ctx, subscriptionId)
                val parts = manager.divideMessage(body)
                if (parts == null || parts.size <= 1) {
                    manager.sendTextMessage(
                        to, null, body,
                        pendingIntent(ctx, sentAction, 101),
                        pendingIntent(ctx, deliveredAction, 102)
                    )
                } else {
                    val sent = ArrayList<android.app.PendingIntent>(parts.size)
                    val delivered = ArrayList<android.app.PendingIntent>(parts.size)
                    repeat(parts.size) {
                        // pendingIntent() yields null when the caller did not ask
                        // for a report, and it does so for every part or none.
                        pendingIntent(ctx, sentAction, 200 + it)?.let { sent.add(it) }
                        pendingIntent(ctx, deliveredAction, 300 + it)?.let { delivered.add(it) }
                    }
                    manager.sendMultipartTextMessage(to, null, parts, sent, delivered)
                }
                true
            }
            if (outcome.isSuccess) {
                recordSentIfDefault(ctx, to, body, subscriptionId)
            }
            val error = outcome.exceptionOrNull()?.message
            val main = Handler(Looper.getMainLooper())
            main.post { onResult?.invoke(outcome.isSuccess, error) }
        }
    }

    private fun pendingIntent(ctx: Context, action: String?, code: Int): android.app.PendingIntent? {
        if (action.isNullOrBlank()) return null
        val intent = Intent(action).setPackage(ctx.packageName)
        val flags = if (Build.VERSION.SDK_INT >= 23) {
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        } else android.app.PendingIntent.FLAG_UPDATE_CURRENT
        return android.app.PendingIntent.getBroadcast(ctx, code, intent, flags)
    }

    /**
     * The newest conversations for the floating Quick Messages tool.
     *
     * Reads the warm snapshot: the floating window must paint on the first frame,
     * never trigger a fresh provider scan of its own.
     */
    internal fun quickThreads(limit: Int = 12): List<SmsThread> = cachedThreads.take(limit)

    /** Unread conversations only, for the floating Quick Messages badge. */
    internal fun quickUnreadCount(): Int = cachedThreads.count { !it.read }
}

/** The conversation-list screen. Owns its state so a host rebuild forgets nothing it shouldn't. */
internal class MessagingScreen(val host: Activity, val palette: OeaPalette) {

    private val kit = OeaAppKit(host, palette)
    val root: FrameLayout = kit.overlay()
    private val indexBack = host.miniIndexBack()
    private val column = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
    private val bar = kit.appBar("Messages", "Reading messages…", onBack = indexBack)
    private val searchBox = kit.searchField("Search messages or contacts", onQuery = { value ->
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
        setPadding(kit.dp(14), 0, kit.dp(14), kit.dp(4))
    }
    private val nav: OeaAppKit.BottomNav = kit.bottomNav(
        listOf(
            OeaAppKit.NavItem("all", "Inbox", R.drawable.oic_send),
            OeaAppKit.NavItem("unread", "Unread", R.drawable.oic_notifications),
            OeaAppKit.NavItem("pinned", "Pinned", R.drawable.oic_push_pin),
            OeaAppKit.NavItem("archived", "Archived", R.drawable.oic_folder)
        ),
        "all"
    ) { id ->
        tab = id
        applyFilter()
    }
    private val content = FrameLayout(host)
    private val listView = kit.list(0).apply { setPadding(kit.dp(8), kit.dp(2), kit.dp(8), kit.dp(88)) }
    private val snack = kit.snack(root)
    private val handler = Handler(Looper.getMainLooper())

    private var alive = true
    private var tab = "all"
    private var category = "all"
    private var query = ""
    private var allThreads: List<OeaMessagingApp.SmsThread> = emptyList()
    private var names: Map<String, String> = emptyMap()
    private var contacts: List<OeaMessagingApp.ContactEntry> = emptyList()
    private var bodyMatches: Set<String> = emptySet()
    private var visible: List<ThreadRow> = emptyList()
    private var loadFailed = false
    /** True until the first provider read has landed: the list must never say "no messages" while scanning. */
    private var loading = true
    /** Coalesces overlapping reloads; a late, stale read can never overwrite a newer one. */
    private var reloadGeneration = 0
    private var stateCard: View? = null
    private var conversation: Conversation? = null
    private var contactPicked: ((String) -> Unit)? = null
    private var observer: ContentObserver? = null
    private var searchPending: Runnable? = null
    private var reloadPending: Runnable? = null
    private var selectionMode = false
    private val selected = LinkedHashSet<String>()
    private var selectionBar: OeaAppKit.SelectionBar? = null
    private val categoryChips = LinearLayout(host).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(kit.dp(14), 0, kit.dp(14), kit.dp(6))
    }
    private val chipScroll = android.widget.HorizontalScrollView(host).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(categoryChips, FrameLayout.LayoutParams(-2, -2))
        visibility = View.GONE
    }

    private val CATEGORIES = listOf(
        "all" to "All",
        "personal" to "Personal",
        "otp" to "OTPs",
        "transactions" to "Transactions",
        "offers" to "Offers"
    )

    /** One row of a conversation. Here, not in [Conversation]: a sealed class may not live in an inner class. */
    private sealed class ChatRow {
        data class Earlier(val hidden: Int) : ChatRow()
        data class Day(val label: String) : ChatRow()
        data class Bubble(val message: OeaMessagingApp.SmsMessage, val tightTop: Boolean, val starred: Boolean) : ChatRow()
    }

    /** A thread plus everything its row paints, resolved off the main thread. */
    private data class ThreadRow(
        val thread: OeaMessagingApp.SmsThread,
        val displayName: String,
        val draft: String?,
        val pinned: Boolean,
        val muted: Boolean,
        val spam: Boolean,
        val category: String,
        val knownContact: Boolean
    ) {
        val key: String get() = thread.canonicalKey
    }

    private val threadsAdapter = ThreadsAdapter()

    init {
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        column.addView(bar.root, LinearLayout.LayoutParams(-1, -2))
        column.addView(searchRow, LinearLayout.LayoutParams(-1, -2))
        column.addView(chipScroll, LinearLayout.LayoutParams(-1, -2))
        column.addView(setupHost, LinearLayout.LayoutParams(-1, -2))
        column.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        column.addView(nav.root, LinearLayout.LayoutParams(-1, -2))
        content.addView(listView, FrameLayout.LayoutParams(-1, -1))
        listView.adapter = threadsAdapter
        renderCategoryChips()
        with(kit) {
            bar.addAction(R.drawable.oic_search, "Search messages") { toggleSearch() }
            bar.addAction(R.drawable.oic_edit, "New message") { composeSms(null) }
            bar.addAction(R.drawable.oic_more_vert, "More options") { showMore() }
        }
        val fab = kit.primaryButton("Start chat", R.drawable.oic_edit) { composeSms(null) }
        fab.elevation = kit.dpf(6f)
        content.addView(fab, FrameLayout.LayoutParams(-2, kit.dp(52), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, kit.dp(16), kit.dp(16))
        })
        selectionBar = kit.selectionBar(
            root,
            listOf(
                R.drawable.oic_check to "Read",
                R.drawable.oic_notifications_off to "Mute",
                R.drawable.oic_push_pin to "Pin",
                R.drawable.oic_folder to "Archive",
                R.drawable.oic_delete to "Delete",
                R.drawable.oic_block to "Block"
            ),
            bottomClearanceDp = 84
        ) { exitSelection() }
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = OeaInsets.bars(insets)
            column.setPadding(bars[0], 0, bars[2], bars[3])
            insets
        }
    }

    /** Google-Messages-style category filters across the top of the inbox. */
    private fun renderCategoryChips() {
        categoryChips.removeAllViews()
        CATEGORIES.forEach { (id, label) ->
            val chip = kit.chip(label, id == category) {
                if (category == id) return@chip
                category = id
                renderCategoryChips()
                applyFilter()
            }
            categoryChips.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = kit.dp(8) })
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
        val backHook: () -> Boolean = {
            when {
                conversation != null -> { conversation?.close(); true }
                selectionMode -> { exitSelection(); true }
                searchRow.visibility == View.VISIBLE -> { toggleSearch(); true }
                category != "all" -> { category = "all"; renderCategoryChips(); applyFilter(); true }
                tab == "blocked" || tab == "spam" -> { tab = "all"; applyFilter(); true }
                else -> false
            }
        }
        host.miniBackHook = backHook
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
                if (host.miniBackHook === backHook) host.miniBackHook = null
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
        primeFromCache()
        reloadThreads()
        loadContactDirectory()
        openIncoming()
    }

    /**
     * Paints the last known inbox on the very first frame.
     *
     * Opening Messages used to show a blank list for as long as the provider
     * scan took, and — worse — briefly show "No messages yet" before the rows
     * arrived. Reopening now shows what was there a moment ago and refreshes
     * behind it.
     */
    private fun primeFromCache() {
        if (allThreads.isNotEmpty()) return
        val cached = OeaMessagingApp.snapshotThreads()
        if (cached.isEmpty()) return
        allThreads = cached
        names = OeaMessagingApp.snapshotNames()
        loading = false
        applyFilter()
    }

    /** Loads the phone directory once, off the UI thread, and re-resolves names. */
    private fun loadContactDirectory() {
        if (!OeaMessagingApp.hasPermission(host, android.Manifest.permission.READ_CONTACTS)) return
        OeaMessagingApp.contactIo.execute {
            val directory = runCatching { OeaMessagingApp.loadContacts(host) }.getOrDefault(emptyList())
            host.runOnUiThread {
                if (!alive || host.isDestroyed) return@runOnUiThread
                contacts = directory
                if (allThreads.isNotEmpty()) {
                    names = OeaMessagingApp.resolveNames(host, allThreads.map { it.address }, directory)
                    OeaMessagingApp.rememberSnapshot(allThreads, names)
                    applyFilter()
                }
            }
        }
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

    /**
     * Opening a conversation from outside: an `sms:`/`smsto:` hand-off, a share
     * carrying `address`/`sms_body`, or a tap on one of OEA's own notifications
     * (which pass `"oea.sms.address"`).
     *
     * The addressed conversation must open even if the provider files that
     * person under a different address form, which is why the address is
     * resolved through the index before the thread is looked up.
     */
    private fun openIncoming() {
        val intent = host.intent ?: return
        val dataAddress = intent.data?.schemeSpecificPart?.substringBefore('?')?.trim()?.let { Uri.decode(it) }.orEmpty()
        val extraAddress = intent.getStringExtra("oea.sms.address")
            ?: intent.getStringExtra("address")
            ?: intent.getStringArrayExtra("address")?.firstOrNull()
        val address = extraAddress?.trim().takeIf { !it.isNullOrBlank() } ?: dataAddress
        val body = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: intent.getStringExtra("sms_body")
        val hasAddress = address.isNotBlank()
        val isSmsIntent = intent.action == Intent.ACTION_SENDTO || intent.action == Intent.ACTION_VIEW ||
            intent.action == Intent.ACTION_SEND || hasAddress
        if (!isSmsIntent) return
        host.setIntent(Intent(Intent.ACTION_MAIN))
        root.post {
            if (!alive) return@post
            if (hasAddress) openConversation(address, body) else composeSms(null, body)
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

    /**
     * Reloads the inbox on its own executor.
     *
     * Three things made this screen feel slow, and each is fixed here:
     *  * names used to cost one `PhoneLookup` query per thread — now one cached
     *    directory is reused (see [loadContactDirectory]);
     *  * a reload fired by the content observer could be overtaken by a newer
     *    one, so a stale result painted over a fresh one — now every reload
     *    carries a generation and an older one is dropped on arrival;
     *  * the list went blank while reading — now the previous rows stay until
     *    the new ones are ready, and [loading] drives a real "Reading…" state.
     */
    private fun reloadThreads() {
        if (!OeaMessagingApp.hasReadSmsPermission(host)) {
            allThreads = emptyList()
            loadFailed = false
            loading = false
            renderSetup()
            applyFilter()
            return
        }
        val generation = ++reloadGeneration
        loading = allThreads.isEmpty()
        OeaMessagingApp.inboxIo.execute {
            val result = runCatching { OeaMessagingApp.queryThreads(host) }
            val threads = result.getOrDefault(emptyList())
            val resolved = OeaMessagingApp.resolveNames(host, threads.map { it.address }, contacts)
            host.runOnUiThread {
                if (!alive || host.isDestroyed) return@runOnUiThread
                if (generation != reloadGeneration) return@runOnUiThread
                allThreads = threads
                names = resolved
                loadFailed = result.isFailure
                loading = false
                OeaMessagingApp.rememberSnapshot(threads, resolved)
                applyFilter()
                renderSetup()
            }
        }
    }

    private fun spamKeywords(): List<String> =
        (OeaMessagingApp.prefs(host).getString(OeaMessagingApp.KEY_SPAM_KEYWORDS, "lottery,win,prize,urgent")
            ?: "lottery,win,prize,urgent")
            .split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }

    /**
     * Pin, mute, archive and block are stored per *canonical* address, so the
     * `+234…` and `0708…` rows of one person share one state. Sets saved by
     * earlier versions used the raw address, so both are accepted here.
     */
    private fun inAddressSet(values: Set<String>, thread: OeaMessagingApp.SmsThread): Boolean =
        values.contains(thread.canonicalKey) || values.contains(thread.address) ||
            thread.rawAddresses.any { values.contains(it) }

    private fun applyFilter() {
        val prefs = OeaMessagingApp.prefs(host)
        val archived = prefs.getStringSet(OeaMessagingApp.KEY_ARCHIVED, emptySet()) ?: emptySet()
        val blocked = prefs.getStringSet(OeaMessagingApp.KEY_BLOCKED, emptySet()) ?: emptySet()
        val muted = prefs.getStringSet(OeaMessagingApp.KEY_MUTED, emptySet()) ?: emptySet()
        val pinned = prefs.getStringSet(OeaMessagingApp.KEY_PINNED, emptySet()) ?: emptySet()
        val keywords = spamKeywords()
        fun OeaMessagingApp.SmsThread.isSpam() =
            keywords.any { word -> snippet.lowercase().contains(word) || address.lowercase().contains(word) }

        val rows = ArrayList<ThreadRow>(allThreads.size)
        allThreads.forEach { thread ->
            val name = names[thread.address] ?: thread.address
            val known = name != thread.address
            val threadCategory = OeaSmsMatcher.categoryOf(thread.address, thread.snippet, known)
            val tabMatch = when (tab) {
                "unread" -> !thread.read && !inAddressSet(archived, thread) && !inAddressSet(blocked, thread)
                "pinned" -> inAddressSet(pinned, thread) && !inAddressSet(blocked, thread)
                "archived" -> inAddressSet(archived, thread)
                "blocked" -> inAddressSet(blocked, thread)
                "spam" -> thread.isSpam()
                else -> !inAddressSet(archived, thread) && !inAddressSet(blocked, thread)
            }
            val categoryMatch = category == "all" || threadCategory == category
            val searchMatch = query.isBlank() ||
                thread.address.contains(query, true) ||
                name.contains(query, true) ||
                thread.snippet.contains(query, true) ||
                bodyMatches.contains(thread.address)
            if (tabMatch && categoryMatch && searchMatch) {
                rows.add(
                    ThreadRow(
                        thread = thread,
                        displayName = name,
                        draft = prefs.getString(draftKey(thread.address), null)?.ifBlank { null },
                        pinned = inAddressSet(pinned, thread),
                        muted = inAddressSet(muted, thread),
                        spam = thread.isSpam(),
                        category = threadCategory,
                        knownContact = known
                    )
                )
            }
        }
        visible = rows.sortedWith(compareByDescending<ThreadRow> { it.pinned }.thenByDescending { it.thread.date })

        val isSecondaryView = tab == "blocked" || tab == "spam"
        chipScroll.visibility = if (isSecondaryView || query.isNotBlank()) View.GONE else View.VISIBLE
        if (!isSecondaryView) nav.select(tab, notify = false)
        nav.root.visibility = if (isSecondaryView) View.GONE else View.VISIBLE
        bar.setBack(
            if (isSecondaryView) ({ tab = "all"; applyFilter() })
            else if (category != "all") ({ category = "all"; renderCategoryChips(); applyFilter() })
            else indexBack
        )
        bar.title.text = when (tab) {
            "unread" -> "Unread"
            "pinned" -> "Pinned"
            "archived" -> "Archived"
            "blocked" -> "Blocked"
            "spam" -> "Spam"
            else -> "Messages"
        }
        val totalUnread = allThreads.sumOf { it.unreadCount }
        val categoryLabel = CATEGORIES.firstOrNull { it.first == category }?.second?.takeIf { category != "all" }
        bar.setSubtitle(
            when {
                !OeaMessagingApp.hasReadSmsPermission(host) -> "Grant SMS access to view conversations"
                loadFailed -> "Couldn't read message history"
                loading -> "Reading messages…"
                query.isNotBlank() -> "${visible.size} matching"
                isSecondaryView -> "${visible.size} conversation${if (visible.size == 1) "" else "s"}"
                allThreads.isEmpty() -> "No messages yet"
                else -> buildString {
                    if (totalUnread > 0) append("$totalUnread unread • ")
                    append("${allThreads.size} conversation${if (allThreads.size == 1) "" else "s"}")
                    categoryLabel?.let { append(" • $it") }
                }
            }
        )
        threadsAdapter.notifyDataSetChanged()
        renderEmptyState()
        renderSelectionBar()
    }

    // ------------------------------------------------------------- multi-select

    /**
     * Google-Messages-style selection: hold a row to start, then act on many at
     * once. Every action writes through the canonical key, and the ones only the
     * default SMS app may perform say so instead of silently doing nothing.
     */
    private fun enterSelection(row: ThreadRow? = null) {
        if (selectionMode) return
        selectionMode = true
        selected.clear()
        row?.let { selected.add(it.key) }
        renderSelectionBar()
        threadsAdapter.notifyDataSetChanged()
    }

    private fun exitSelection() {
        if (!selectionMode) return
        selectionMode = false
        selected.clear()
        renderSelectionBar()
        threadsAdapter.notifyDataSetChanged()
    }

    private fun toggleSelected(row: ThreadRow) {
        if (!selected.remove(row.key)) selected.add(row.key)
        renderSelectionBar()
        val position = visible.indexOfFirst { it.key == row.key }
        if (position >= 0) threadsAdapter.notifyItemChanged(position, PAYLOAD_SELECTION)
    }

    private fun renderSelectionBar() {
        val bar = selectionBar ?: return
        if (!selectionMode) {
            bar.setCount(0, 0)
            return
        }
        bar.setCount(selected.size, visible.size)
        bar.allLabel = if (selected.size == visible.size && visible.isNotEmpty()) "None" else "All"
        bar.selectAll {
            if (selected.size == visible.size) selected.clear()
            else visible.forEach { selected.add(it.key) }
            renderSelectionBar()
            threadsAdapter.notifyDataSetChanged()
        }
        val isDefault = OeaMessagingApp.isDefaultSmsApp(host)
        bar.setAction(0, R.drawable.oic_check_circle_fill, "Read") { actOnSelected(read = true) }
        bar.setAction(1, R.drawable.oic_notifications_off, "Mute") { actOnSelected(mute = true) }
        bar.setAction(2, R.drawable.oic_push_pin, "Pin") { actOnSelected(pin = true) }
        bar.setAction(3, R.drawable.oic_folder, "Archive") { actOnSelected(archive = true) }
        bar.setAction(4, R.drawable.oic_delete, if (isDefault) "Delete" else "Delete (needs default)") {
            actOnSelected(delete = true)
        }
        bar.setAction(5, R.drawable.oic_block, "Block") { actOnSelected(block = true) }
    }

    private fun actOnSelected(
        read: Boolean = false,
        mute: Boolean = false,
        pin: Boolean = false,
        archive: Boolean = false,
        delete: Boolean = false,
        block: Boolean = false
    ) {
        val rows = visible.filter { selected.contains(it.key) }
        if (rows.isEmpty()) return
        when {
            delete -> kit.confirm(
                "Delete ${rows.size} conversation${if (rows.size == 1) "" else "s"}?",
                "Every SMS with ${if (rows.size == 1) rows[0].displayName else "these addresses"} is removed from this phone. This cannot be undone.",
                "Delete", destructive = true
            ) { runSelectedAction(rows, delete = true) }
            block -> kit.confirm(
                "Block ${rows.size} number${if (rows.size == 1) "" else "s"}?",
                "Blocked addresses are hidden from All. Blocking here does not stop delivery at carrier level.",
                "Block", destructive = true
            ) { runSelectedAction(rows, block = true) }
            else -> runSelectedAction(rows, read, mute, pin, archive)
        }
    }

    private fun runSelectedAction(
        rows: List<ThreadRow>,
        read: Boolean = false,
        mute: Boolean = false,
        pin: Boolean = false,
        archive: Boolean = false,
        delete: Boolean = false,
        block: Boolean = false
    ) {
        OeaMessagingApp.io.execute {
            rows.forEach { row ->
                val thread = row.thread
                when {
                    delete -> OeaMessagingApp.deleteConversationIfDefault(host, thread.address)
                    block -> toggleStoredSet(OeaMessagingApp.KEY_BLOCKED, thread, true)
                    archive -> toggleStoredSet(OeaMessagingApp.KEY_ARCHIVED, thread, true)
                    pin -> toggleStoredSet(OeaMessagingApp.KEY_PINNED, thread, true)
                    mute -> toggleStoredSet(OeaMessagingApp.KEY_MUTED, thread, true)
                    read -> OeaMessagingApp.markConversationRead(host, thread.address, true)
                }
            }
            host.runOnUiThread {
                if (!alive || host.isDestroyed) return@runOnUiThread
                exitSelection()
                reloadThreads()
                val count = rows.size
                snack.show(
                    when {
                        delete -> if (OeaMessagingApp.isDefaultSmsApp(host)) "$count deleted" else "Set OEA as the default SMS app to delete"
                        block -> "$count blocked"
                        archive -> "$count archived"
                        pin -> "$count pinned"
                        mute -> "$count muted"
                        else -> "$count marked read"
                    },
                    OeaKit.Tone.SUCCESS
                )
            }
        }
    }

    /** Adds or removes one canonical address from a stored set. Thread-safe enough for the io pool. */
    private fun toggleStoredSet(key: String, thread: OeaMessagingApp.SmsThread, on: Boolean) {
        val prefs = OeaMessagingApp.prefs(host)
        val values = (prefs.getStringSet(key, emptySet()) ?: emptySet()).toMutableSet()
        if (on) values.add(thread.canonicalKey) else values.remove(thread.canonicalKey)
        prefs.edit().putStringSet(key, values).apply()
    }

    /** Every starred message, gathered from the threads that own them. */
    private fun showStarred() {
        val keys = OeaMessagingApp.starredKeys(host)
        if (keys.isEmpty()) {
            kit.sheet("Starred messages", "Nothing starred yet") { panel, _ ->
                kit.note(panel, "Long-press a message inside a conversation and choose Star. Starred texts collect here.")
            }
            return
        }
        val byThread = LinkedHashMap<String, MutableSet<Long>>()
        keys.forEach { key ->
            val parts = key.split('#')
            val id = parts.getOrNull(1)?.toLongOrNull() ?: return@forEach
            byThread.getOrPut(parts[0]) { linkedSetOf() }.add(id)
        }
        val jobs = byThread.entries.map { (threadKey, ids) ->
            threadKey to allThreads.firstOrNull { it.canonicalKey == threadKey } to ids
        }
        kit.sheet("Starred messages", "${keys.size} starred") { panel, _ ->
            val column = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
            panel.addView(kit.boundedScroll(column, 0.7f), LinearLayout.LayoutParams(-1, -2))
            column.addView(kit.progressRow("Reading starred messages…"))
            OeaMessagingApp.chatIo.execute {
                val found = ArrayList<Pair<String, OeaMessagingApp.SmsMessage>>()
                jobs.forEach { (pair, ids) ->
                    val (threadKey, thread) = pair
                    if (thread == null) return@forEach
                    val messages = runCatching { OeaMessagingApp.queryConversation(host, thread) }.getOrDefault(emptyList())
                    messages.filter { ids.contains(it.id) }.forEach { found.add(threadKey to it) }
                }
                host.runOnUiThread {
                    if (!alive || host.isDestroyed) return@runOnUiThread
                    column.removeAllViews()
                    if (found.isEmpty()) {
                        kit.note(column, "Those starred messages are no longer on this phone.")
                        return@runOnUiThread
                    }
                    found.sortedByDescending { it.second.date }.forEach { (threadKey, message) ->
                        val owner = allThreads.firstOrNull { it.canonicalKey == threadKey }
                        val label = owner?.let { displayNameFor(it.address) } ?: message.address
                        kit.row(
                            column, R.drawable.oic_star_fill, label,
                            "${message.body.take(160)} • ${OeaMessageDates.fullLabel(message.date)}",
                            chevron = false
                        ) {
                            owner?.let { openConversation(it.address) }
                        }
                    }
                }
            }
        }
    }

    private fun renderEmptyState() {
        stateCard?.let { content.removeView(it) }
        stateCard = null
        listView.visibility = View.VISIBLE
        if (visible.isNotEmpty()) return
        val card = when {
            // While a read is in flight the list says so — it must never claim the phone has no messages.
            loading -> kit.progressRow("Reading messages…")
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
            tab == "unread" -> kit.emptyState(R.drawable.oic_check_circle_fill, "All caught up", "No unread conversations right now.")
            tab == "pinned" -> kit.emptyState(R.drawable.oic_push_pin, "No pinned chats", "Hold any conversation to pin it for quick access.")
            tab == "archived" -> kit.emptyState(R.drawable.oic_folder, "Nothing archived", "Hold a conversation and choose Archive to keep it out of your inbox.")
            tab == "blocked" -> kit.emptyState(R.drawable.oic_block, "Nobody blocked", "Blocked addresses are hidden from All. Blocking here does not stop delivery at carrier level.")
            tab == "spam" -> kit.emptyState(R.drawable.oic_shield, "No spam detected", "OEA flags conversations matching your spam keywords: ${spamKeywords().joinToString()}.")
            category != "all" -> {
                val label = CATEGORIES.firstOrNull { it.first == category }?.second ?: "This filter"
                kit.emptyState(
                    R.drawable.oic_view_list, "Nothing in $label",
                    "No conversation here matches. Switch the chips above back to All to see the whole inbox.",
                    "Show all"
                ) { category = "all"; renderCategoryChips(); applyFilter() }
            }
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
        val totalUnread = allThreads.count { !it.read }
        kit.actions(
            "Messages", null,
            listOf(
                OeaAppKit.Action(
                    R.drawable.oic_check_circle_fill, "Mark all as read",
                    if (totalUnread > 0) "$totalUnread unread conversation${if (totalUnread == 1) "" else "s"}"
                    else "Nothing unread right now",
                    disabled = totalUnread == 0
                ) {
                    OeaMessagingApp.io.execute {
                        val done = OeaMessagingApp.markAllRead(host, allThreads)
                        host.runOnUiThread {
                            if (!alive) return@runOnUiThread
                            reloadThreads()
                            snack.show(
                                if (done == 0) "Nothing to mark" else "$done conversation${if (done == 1) "" else "s"} marked read",
                                OeaKit.Tone.SUCCESS
                            )
                        }
                    }
                },
                OeaAppKit.Action(
                    R.drawable.oic_check, "Select conversations",
                    "Mark read, mute, pin, archive or delete several at once"
                ) { enterSelection() },
                OeaAppKit.Action(R.drawable.oic_star_fill, "Starred messages", "Messages you flagged inside a conversation") {
                    showStarred()
                },
                OeaAppKit.Action(R.drawable.oic_shield, "Spam folder", "Flagged conversations matching your keywords") {
                    tab = "spam"
                    applyFilter()
                },
                OeaAppKit.Action(R.drawable.oic_block, "Blocked numbers", "Addresses hidden from your inbox") {
                    tab = "blocked"
                    applyFilter()
                },
                OeaAppKit.Action(R.drawable.oic_tune, "Setup & permissions", "SMS access, default SMS app and SIM labels") { setupSheet() },
                OeaAppKit.Action(R.drawable.oic_text_fields, "Spam keywords", spamKeywords().joinToString()) {
                    val current = OeaMessagingApp.prefs(host).getString(OeaMessagingApp.KEY_SPAM_KEYWORDS, "lottery,win,prize,urgent") ?: ""
                    kit.prompt("Spam keywords", "Comma separated words flagged as spam.", "Keywords", current) { input ->
                        OeaMessagingApp.prefs(host).edit().putString(OeaMessagingApp.KEY_SPAM_KEYWORDS, input).apply()
                        applyFilter()
                        null
                    }
                },
                OeaAppKit.Action(R.drawable.oic_refresh, "Refresh inbox", "Reload conversations from the device") { reloadThreads() },
                OeaAppKit.Action(R.drawable.oic_share_filled, "Export visible list", "Share conversations in this view as text") {
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
        val thread = row.thread
        val prefs = OeaMessagingApp.prefs(host)
        fun stored(key: String) = (prefs.getStringSet(key, emptySet()) ?: emptySet()).let { inAddressSet(it, thread) }
        val archived = stored(OeaMessagingApp.KEY_ARCHIVED)
        val blocked = stored(OeaMessagingApp.KEY_BLOCKED)
        val isDefault = OeaMessagingApp.isDefaultSmsApp(host)
        fun toggleSet(key: String, on: Boolean) {
            toggleStoredSet(key, thread, on)
            applyFilter()
        }
        kit.actions(
            row.displayName,
            "${thread.count} message${if (thread.count == 1) "" else "s"} • ${OeaMessageDates.fullLabel(thread.date)}",
            listOf(
                OeaAppKit.Action(R.drawable.oic_open_in_new, "Open conversation") { openConversation(address) },
                OeaAppKit.Action(R.drawable.oic_check, "Select this conversation", "Start multi-select from here") {
                    enterSelection(row)
                },
                OeaAppKit.Action(
                    R.drawable.oic_push_pin, if (row.pinned) "Unpin" else "Pin to top",
                    if (row.pinned) "This conversation sorts by date again" else "Keeps this conversation above the rest"
                ) { toggleSet(OeaMessagingApp.KEY_PINNED, !row.pinned) },
                OeaAppKit.Action(
                    R.drawable.oic_check_circle_fill, if (thread.read) "Mark as unread" else "Mark as read",
                    if (isDefault) "Applies to every message with this address"
                    else "Also written to Android when OEA is the default SMS app"
                ) {
                    OeaMessagingApp.io.execute {
                        OeaMessagingApp.markConversationRead(host, address, read = !thread.read)
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
            val overflow: ImageView

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
                overflow = rowRoot.getChildAt(2) as ImageView
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
            rowRoot.addView(
                ImageView(host).apply {
                    setImageDrawable(kit.icon(R.drawable.oic_more_vert, palette.textTertiary))
                    scaleType = ImageView.ScaleType.CENTER
                    background = kit.ripple(kit.oval(Color.TRANSPARENT), 18)
                    isClickable = true
                    isFocusable = true
                    contentDescription = "Conversation options"
                },
                LinearLayout.LayoutParams(kit.dp(36), kit.dp(36)).apply { marginStart = kit.dp(2) }
            )
            return Holder(rowRoot)
        }

        override fun getItemCount(): Int = visible.size

        override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
            if (payloads.any { it == PAYLOAD_SELECTION }) {
                val row = visible.getOrNull(position) ?: return
                paintSelection(holder, row)
                return
            }
            onBindViewHolder(holder, position)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = visible.getOrNull(position) ?: return
            val thread = row.thread
            val unread = !thread.read
            holder.avatarHost.removeAllViews()
            holder.avatarHost.addView(avatarView(row.displayName, thread.address), FrameLayout.LayoutParams(-1, -1))
            holder.name.text = row.displayName
            holder.name.typeface = if (unread) OeaType.medium else OeaType.regular
            holder.name.setTextColor(palette.text)
            holder.time.text = OeaMessageDates.threadLabel(thread.date, System.currentTimeMillis())
            holder.time.setTextColor(if (unread) palette.accent else palette.textTertiary)
            holder.time.typeface = if (unread) OeaType.medium else OeaType.regular
            holder.pinIcon.visibility = if (row.pinned && !selectionMode) View.VISIBLE else View.GONE
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
            holder.overflow.setOnClickListener {
                visible.getOrNull(holder.bindingAdapterPosition)?.let { threadActions(it) }
            }
            holder.rowRoot.setOnClickListener {
                visible.getOrNull(holder.bindingAdapterPosition)?.let { current ->
                    if (selectionMode) toggleSelected(current) else openConversation(current.thread.address)
                }
            }
            // Long-press starts multi-select, the way every Messages app does it.
            holder.rowRoot.setOnLongClickListener {
                val current = visible.getOrNull(holder.bindingAdapterPosition) ?: return@setOnLongClickListener true
                if (!selectionMode) enterSelection(current) else toggleSelected(current)
                true
            }
            paintSelection(holder, row)
        }

        /** Selection chrome only: repainted on a payload bind so a tap never re-renders the row. */
        private fun paintSelection(holder: Holder, row: ThreadRow) {
            val on = selectionMode && selected.contains(row.key)
            holder.rowRoot.background = when {
                on -> kit.ripple(kit.rounded(palette.accentSoft, 20), 20)
                else -> kit.ripple(kit.rounded(Color.TRANSPARENT, 20), 20)
            }
            holder.overflow.visibility = if (selectionMode) View.GONE else View.VISIBLE
            if (!selectionMode) return
            holder.avatarHost.removeAllViews()
            holder.avatarHost.addView(
                if (on) {
                    FrameLayout(host).apply {
                        background = kit.oval(palette.accent)
                        addView(
                            ImageView(host).apply { setImageDrawable(kit.icon(R.drawable.oic_check, palette.onAccent)) },
                            FrameLayout.LayoutParams(kit.dp(26), kit.dp(26), Gravity.CENTER)
                        )
                    }
                } else {
                    FrameLayout(host).apply {
                        background = kit.oval(Color.TRANSPARENT, palette.stroke, 2)
                        addView(avatarView(row.displayName, row.thread.address), FrameLayout.LayoutParams(-1, -1).apply {
                            setMargins(kit.dp(3), kit.dp(3), kit.dp(3), kit.dp(3))
                        })
                    }
                },
                FrameLayout.LayoutParams(-1, -1)
            )
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

    /**
     * Start Chat — a full screen, the way a compose screen should be.
     *
     * The old version was a bottom sheet with a bare "To" field, which meant
     * starting a conversation meant knowing a phone number by heart and typing
     * it into a slot the keyboard half covered. This is the whole screen:
     *  * a `To:` field that accepts a name or a number, with a `123`/`ABC`
     *    toggle so a contact name is not fought by a phone keypad;
     *  * an instant "Send to <number>" row the moment the digits are dialable;
     *  * the phone's contact directory, cached and searchable, with a shortcut
     *    into Android's own Contacts app for a deeper lookup.
     */
    fun composeSms(initialAddress: String?, initialBody: String? = null) {
        val dialog = Dialog(host, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val screen = LinearLayout(host).apply {
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
            kit.circleIconButton(R.drawable.oic_close, "Cancel", Color.TRANSPARENT, palette.text) { dialog.dismiss() },
            LinearLayout.LayoutParams(kit.dp(48), kit.dp(48))
        )
        topBar.addView(
            kit.text("Start chat", 18f, palette.text, OeaType.medium),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = kit.dp(6) }
        )
        topBar.addView(
            kit.circleIconButton(R.drawable.oic_person, "Open the Contacts app", Color.TRANSPARENT, palette.text) {
                runCatching {
                    host.startActivity(Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI))
                }.onFailure { toast("No contacts app could be opened") }
            },
            LinearLayout.LayoutParams(kit.dp(48), kit.dp(48))
        )
        screen.addView(topBar, LinearLayout.LayoutParams(-1, -2))

        // ---- To: field + keyboard toggle
        val toRow = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(kit.dp(14), kit.dp(10), kit.dp(14), kit.dp(10))
            setBackgroundColor(palette.background)
        }
        toRow.addView(kit.text("To", 15f, palette.textSecondary, OeaType.medium), LinearLayout.LayoutParams(-2, -2).apply {
            marginEnd = kit.dp(10)
        })
        val toField = EditText(host).apply {
            hint = "Name or number"
            setText(initialAddress.orEmpty())
            setSelection(text.length)
            setTextColor(palette.text)
            setHintTextColor(palette.textTertiary)
            textSize = 16f
            typeface = OeaType.regular
            background = null
            inputType = InputType.TYPE_CLASS_PHONE
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        toRow.addView(toField, LinearLayout.LayoutParams(0, -2, 1f))
        val keypadToggle = kit.text("123", 13f, palette.accent, OeaType.medium).apply {
            setPadding(kit.dp(10), kit.dp(6), kit.dp(10), kit.dp(6))
            background = kit.ripple(kit.rounded(palette.cardHigh, 14), 14)
            isClickable = true
            isFocusable = true
            contentDescription = "Switch the keyboard between numbers and letters"
        }
        toRow.addView(keypadToggle, LinearLayout.LayoutParams(-2, -2).apply { marginStart = kit.dp(8) })
        screen.addView(toRow, LinearLayout.LayoutParams(-1, -2))
        screen.addView(View(host).apply { setBackgroundColor(palette.stroke) }, LinearLayout.LayoutParams(-1, 1))

        // ---- results
        val results = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        val scroller = android.widget.ScrollView(host).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(results, FrameLayout.LayoutParams(-1, -2))
        }
        screen.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))

        var digitsMode = true
        fun applyKeyboardMode() {
            toField.inputType = if (digitsMode) InputType.TYPE_CLASS_PHONE
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            keypadToggle.text = if (digitsMode) "123" else "ABC"
            val caret = toField.text.length
            toField.setSelection(caret)
        }
        keypadToggle.setOnClickListener {
            digitsMode = !digitsMode
            applyKeyboardMode()
        }
        applyKeyboardMode()

        fun matches(term: String): List<Pair<String, String>> {
            // (address, title) — contacts first, then conversations that match.
            val out = ArrayList<Pair<String, String>>()
            if (term.isBlank()) {
                allThreads.take(8).forEach { thread ->
                    out.add(thread.address to (names[thread.address] ?: thread.address))
                }
                if (out.isEmpty()) {
                    contacts.take(30).forEach { out.add(it.number to it.name) }
                }
                return out
            }
            contacts.forEach { entry ->
                if (entry.name.contains(term, true) || entry.number.contains(term)) out.add(entry.number to entry.name)
                if (out.size >= 40) return out
            }
            allThreads.forEach { thread ->
                val title = names[thread.address] ?: thread.address
                if (title.contains(term, true) || thread.address.contains(term)) {
                    if (out.none { OeaSmsMatcher.sameAddress(it.first, thread.address) }) out.add(thread.address to title)
                }
            }
            return out
        }

        fun render(term: String) {
            results.removeAllViews()
            val trimmed = term.trim()
            // Only offer "send to" for something dialable — a contact *name* is
            // not a destination, and shortcodes like "312" are only three digits.
            val dialable = trimmed.isNotBlank() &&
                !OeaSmsMatcher.isAlphanumericSender(trimmed) &&
                OeaSmsMatcher.cleanDialable(trimmed).length >= 3
            if (dialable) {
                kit.row(results, R.drawable.oic_send, "Send to $trimmed", "Open a conversation with this number") {
                    dialog.dismiss()
                    openConversation(trimmed, initialBody)
                }
            }
            val list = matches(trimmed)
            if (list.isEmpty()) {
                kit.note(results, "No contact or conversation matches “$trimmed”.")
                return
            }
            results.addView(kit.listHeader(if (trimmed.isBlank()) "Recent" else "Matches"))
            list.forEach { (address, title) ->
                val subtitle = if (title == address) "Number" else address
                kit.row(results, R.drawable.oic_person, title, subtitle) {
                    dialog.dismiss()
                    openConversation(address, initialBody)
                }
            }
            if (trimmed.isNotBlank()) {
                kit.row(results, R.drawable.oic_open_in_new, "Search all contacts", "Opens Android's contacts app") {
                    runCatching { host.startActivity(Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)) }
                }
            }
        }

        toField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = render(s?.toString().orEmpty())
        })
        toField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                val value = toField.text.toString().trim()
                if (value.length >= 3) {
                    dialog.dismiss()
                    openConversation(value, initialBody)
                }
                true
            } else false
        }

        screen.setOnApplyWindowInsetsListener { _, insets ->
            val bars = OeaInsets.bars(insets)
            topBar.setPadding(kit.dp(8) + bars[0], bars[1] + kit.dp(6), kit.dp(8) + bars[2], kit.dp(8))
            scroller.setPadding(bars[0], 0, bars[2], bars[3] + OeaInsets.ime(insets))
            insets
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(screen)
        dialog.setOnDismissListener { contactPicked = null }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(palette.background))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            OeaInsets.edgeToEdge(this, lightBars = !palette.dark)
        }
        dialog.show()
        render(initialAddress.orEmpty())
        toField.requestFocus()
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

    /**
     * Sends one SMS through one SIM, splitting long text into parts the way the
     * manager itself counts them. [onDone] always runs on the UI thread.
     *
     * The send is watched through the `SMS_SENT` / `SMS_DELIVERED` broadcasts the
     * open conversation listens for, so a message the network rejects surfaces as
     * failed instead of looking sent.
     */
    internal fun sendSms(to: String, body: String, subscriptionId: Int, onDone: (Boolean) -> Unit) {
        if (!OeaMessagingApp.hasSendSmsPermission(host)) {
            requestPermission(android.Manifest.permission.SEND_SMS)
            onDone(false)
            return
        }
        OeaMessagingApp.sendNow(host, to, body, subscriptionId, ACTION_SMS_SENT, ACTION_SMS_DELIVERED) { ok, error ->
            if (!alive || host.isDestroyed) return@sendNow
            if (ok) {
                rememberSub(to, subscriptionId)
                onDone(true)
                scheduleReload()
            } else {
                toast("Could not send: ${error ?: "Android rejected the request"}")
                onDone(false)
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

    /**
     * Finds the indexed thread for an address, however the address is written.
     *
     * A deep link, a notification or a Start Chat entry arrives as a bare string
     * that may or may not be the exact form the provider stored, so the match is
     * [OeaSmsMatcher.sameAddress] and not string equality. When nothing is in the
     * index yet (a brand-new number) a synthetic thread is built and filled in on
     * first load — the conversation must still open.
     */
    internal fun threadFor(address: String): OeaMessagingApp.SmsThread {
        val trimmed = address.trim()
        return allThreads.firstOrNull { thread ->
            thread.address == trimmed ||
                thread.rawAddresses.any { OeaSmsMatcher.sameAddress(it, trimmed) } ||
                OeaSmsMatcher.sameAddress(thread.address, trimmed)
        } ?: OeaMessagingApp.SmsThread(
            address = trimmed,
            snippet = "",
            date = 0L,
            count = 0,
            read = true,
            rawAddresses = setOf(trimmed)
        )
    }

    internal fun openConversation(address: String, initialText: String? = null) {
        conversation?.dismissQuietly()
        val opened = Conversation(threadFor(address), initialText)
        conversation = opened
        opened.show()
    }

    internal fun conversationClosed(closed: Conversation) {
        if (conversation === closed) conversation = null
        reloadThreads()
    }

    internal fun displayNameFor(address: String): String = names[address] ?: address

    /** Newest conversations, for the floating Quick Messages tool in the launcher. */
    internal fun quickThreads(limit: Int = 12): List<OeaMessagingApp.SmsThread> = allThreads.take(limit)

    /** Sends a quick reply from anywhere (floating window, notification), then refreshes the inbox. */
    internal fun sendQuickSms(to: String, body: String, subscriptionId: Int = -1, onDone: ((Boolean) -> Unit)? = null) {
        OeaMessagingApp.sendNow(host, to, body, subscriptionId) { ok, error ->
            if (!alive || host.isDestroyed) return@sendNow
            if (ok) {
                scheduleReload()
                snack.show("Message sent to ${displayNameFor(to)}", OeaKit.Tone.SUCCESS)
            } else {
                snack.show("Could not send: ${error ?: "Android rejected the request"}", OeaKit.Tone.WARNING)
            }
            onDone?.invoke(ok)
        }
    }

    // ============================================================== conversation

    /**
     * One conversation, full-screen: bubbles, day pills, a roomy composer with a
     * compact SIM badge on dual-SIM phones, live send/delivery status you can tap
     * to retry, and long-press details naming the SIM and network behind every
     * single message.
     */
    internal inner class Conversation(
        private val initialThread: OeaMessagingApp.SmsThread,
        private val initialText: String?
    ) {
        private val dialog = Dialog(host, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        /** The thread as last indexed; refreshed on every load so new rows are found. */
        private var thread: OeaMessagingApp.SmsThread = initialThread
        private val address: String get() = thread.address
        private val displayName: String get() = displayNameFor(address)
        private var messages: List<OeaMessagingApp.SmsMessage> = emptyList()
        private var rows: List<ChatRow> = emptyList()
        private var visibleCount = 100
        private var selectedSub = -1
        private var simLines: List<OeaSimDirectory.Line> = emptyList()
        private var firstLoad = true
        private var dismissed = false
        private var expanded = false
        private var loadGeneration = 0
        private var chatQuery = ""
        private var replyTo: OeaMessagingApp.SmsMessage? = null
        /**
         * The network's own verdict on the last send. `SmsManager` reports it out
         * of band, so without this a message the carrier refused would sit in the
         * thread looking exactly like one that arrived.
         */
        private val sentReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
                if (resultCode == Activity.RESULT_OK) {
                    scheduleRefresh()
                } else {
                    host.runOnUiThread {
                        if (!dismissed) say("Message not sent — tap the failed bubble to retry", OeaKit.Tone.WARNING)
                    }
                }
            }
        }
        private val deliveredReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) = scheduleRefresh()
        }

        private val bubbleMaxWidth: Int
            get() = (host.resources.displayMetrics.widthPixels * 0.78f).toInt()

        private lateinit var recycler: RecyclerView
        private lateinit var editor: EditText
        private lateinit var counter: TextView
        private lateinit var sendButton: View
        private lateinit var simChip: TextView
        private lateinit var expandButton: ImageView
        private lateinit var subtitleView: TextView
        private lateinit var composerHost: LinearLayout
        private lateinit var quoteBar: LinearLayout
        private lateinit var quickRow: LinearLayout
        private lateinit var jumpButton: View
        private val adapter = ChatAdapter()

        /** Back / predictive-back: the conversation closes before the host does. */
        fun close() {
            if (!dismissed && dialog.isShowing) runCatching { dialog.dismiss() }
        }

        private fun scheduleRefresh() {
            handler.postDelayed({ reload() }, 600L)
        }

        fun show() {
            selectedSub = initialSubFor(address, emptyList())
            simLines = OeaSimDirectory.activeLines(host)
            runCatching {
                host.registerReceiver(sentReceiver, android.content.IntentFilter(ACTION_SMS_SENT))
                host.registerReceiver(deliveredReceiver, android.content.IntentFilter(ACTION_SMS_DELIVERED))
            }
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

            // ---- messages, with a jump-to-bottom button over them
            val messagesHost = FrameLayout(host)
            recycler = RecyclerView(host).apply {
                layoutManager = LinearLayoutManager(host)
                adapter = this@Conversation.adapter
                itemAnimator = null
                setPadding(0, kit.dp(8), 0, kit.dp(10))
                clipToPadding = false
                overScrollMode = View.OVER_SCROLL_NEVER
            }
            messagesHost.addView(recycler, FrameLayout.LayoutParams(-1, -1))
            jumpButton = kit.circleIconButton(
                R.drawable.oic_expand_more, "Jump to the latest message", palette.cardHigh, palette.text
            ) { scrollToBottom(true) }
            jumpButton.visibility = View.GONE
            jumpButton.elevation = kit.dpf(4f)
            messagesHost.addView(
                jumpButton,
                FrameLayout.LayoutParams(kit.dp(44), kit.dp(44), Gravity.BOTTOM or Gravity.END).apply {
                    setMargins(0, 0, kit.dp(14), kit.dp(10))
                }
            )
            rootLayout.addView(messagesHost, LinearLayout.LayoutParams(-1, 0, 1f))

            // ---- composer
            //
            // The old composer put a full "SIM 1 • MTN Nigeria" chip beside the
            // field, which squeezed a 5-line-max text box into the remaining
            // third of the screen. The SIM badge is now a compact "SIM 1" pill
            // that only appears on a dual-SIM phone, the field grows on its own
            // up to six lines, and one tap on the corner icon opens it to twelve.
            composerHost = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(kit.dp(10), kit.dp(8), kit.dp(10), kit.dp(8))
                setBackgroundColor(palette.card)
                elevation = kit.dpf(6f)
            }
            quoteBar = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = kit.rounded(palette.cardHigh, 14, palette.accent, 1)
                setPadding(kit.dp(12), kit.dp(8), kit.dp(8), kit.dp(8))
                visibility = View.GONE
            }
            composerHost.addView(quoteBar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(6) })
            quickRow = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                visibility = View.GONE
            }
            composerHost.addView(quickRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(6) })

            val inputRow = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
            }
            val plusButton = kit.circleIconButton(
                R.drawable.oic_add, "More options", palette.cardHigh, palette.text
            ) { toggleQuickRow() }
            inputRow.addView(plusButton, LinearLayout.LayoutParams(kit.dp(42), kit.dp(42)).apply { marginEnd = kit.dp(6) })

            val fieldHost = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                background = kit.rounded(palette.background, 24, palette.stroke, 1)
            }
            editor = EditText(host).apply {
                hint = "Text message"
                setText(initialText ?: OeaMessagingApp.prefs(host).getString(draftKey(address), null).orEmpty())
                setSelection(text.length)
                setTextColor(palette.text)
                setHintTextColor(palette.textTertiary)
                textSize = 16f
                typeface = OeaType.regular
                background = null
                setPadding(kit.dp(14), kit.dp(11), kit.dp(14), kit.dp(9))
                minLines = 1
                maxLines = 6
                gravity = Gravity.TOP
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            }
            fieldHost.addView(editor, LinearLayout.LayoutParams(-1, -2))
            val fieldFooter = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            simChip = kit.text("", 11f, palette.accent, OeaType.medium).apply {
                setPadding(kit.dp(12), kit.dp(4), kit.dp(6), kit.dp(4))
                background = kit.ripple(kit.rounded(palette.accentSoft, 12), 12)
                isClickable = true
                isFocusable = true
                visibility = View.GONE
                setOnClickListener { pickSim() }
            }
            fieldFooter.addView(simChip, LinearLayout.LayoutParams(-2, -2))
            fieldFooter.addView(View(host), LinearLayout.LayoutParams(0, 0, 1f))
            counter = kit.text("", 10.5f, palette.textTertiary).apply {
                gravity = Gravity.END
                setPadding(kit.dp(8), kit.dp(4), kit.dp(12), kit.dp(4))
                visibility = View.GONE
            }
            fieldFooter.addView(counter, LinearLayout.LayoutParams(-2, -2))
            expandButton = ImageView(host).apply {
                setImageDrawable(kit.icon(R.drawable.oic_open_in_full, palette.textTertiary))
                scaleType = ImageView.ScaleType.CENTER
                background = kit.ripple(kit.oval(Color.TRANSPARENT), 16)
                isClickable = true
                isFocusable = true
                contentDescription = "Expand the message box"
                setOnClickListener { setExpanded(!expanded) }
            }
            fieldFooter.addView(expandButton, LinearLayout.LayoutParams(kit.dp(32), kit.dp(32)))
            fieldHost.addView(fieldFooter, LinearLayout.LayoutParams(-1, -2))
            inputRow.addView(fieldHost, LinearLayout.LayoutParams(0, -2, 1f).apply {
                gravity = Gravity.BOTTOM
            })
            sendButton = kit.circleIconButton(R.drawable.oic_send, "Send", palette.accent, palette.onAccent) { sendCurrent() }
            inputRow.addView(sendButton, LinearLayout.LayoutParams(kit.dp(48), kit.dp(48)).apply {
                marginStart = kit.dp(6)
                gravity = Gravity.BOTTOM
            })
            composerHost.addView(inputRow, LinearLayout.LayoutParams(-1, -2))
            rootLayout.addView(composerHost, LinearLayout.LayoutParams(-1, -2))

            buildQuickRow()
            paintSimChip()

            editor.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    paintComposerState(s?.toString().orEmpty())
                    saveDraft()
                }
            })
            editor.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendCurrent()
                    true
                } else false
            }
            paintComposerState(editor.text.toString())
            recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    paintJumpButton()
                }
            })

            rootLayout.setOnApplyWindowInsetsListener { _, insets ->
                val bars = OeaInsets.bars(insets)
                val imeBottom = OeaInsets.ime(insets)
                topBar.setPadding(kit.dp(8) + bars[0], bars[1] + kit.dp(6), kit.dp(8) + bars[2], kit.dp(8))
                composerHost.setPadding(
                    kit.dp(10) + bars[0], kit.dp(8),
                    kit.dp(10) + bars[2],
                    if (imeBottom > 0) kit.dp(6) else bars[3] + kit.dp(6)
                )
                recycler.setPadding(0, kit.dp(8), 0, kit.dp(10))
                insets
            }
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setContentView(rootLayout)
            dialog.setOnDismissListener {
                dismissed = true
                saveDraft()
                runCatching { host.unregisterReceiver(sentReceiver) }
                runCatching { host.unregisterReceiver(deliveredReceiver) }
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

        /** Grows the editor from its comfortable 6 lines to a roomy 12, and back. */
        private fun setExpanded(value: Boolean) {
            expanded = value
            editor.maxLines = if (value) 12 else 6
            expandButton.setImageDrawable(
                kit.icon(if (value) R.drawable.oic_fullscreen_exit else R.drawable.oic_open_in_full, palette.textTertiary)
            )
            expandButton.contentDescription = if (value) "Shrink the message box" else "Expand the message box"
            editor.requestFocus()
        }

        private fun paintJumpButton() {
            val atBottom = !recycler.canScrollVertically(1)
            val shouldShow = !atBottom && rows.isNotEmpty()
            val wanted = if (shouldShow) View.VISIBLE else View.GONE
            if (jumpButton.visibility != wanted) jumpButton.visibility = wanted
        }

        private fun scrollToBottom(smooth: Boolean) {
            val last = rows.lastIndex
            if (last < 0) return
            if (smooth) recycler.smoothScrollToPosition(last)
            else recycler.post { (recycler.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(last, 0) }
            jumpButton.visibility = View.GONE
        }

        /** The `+` tray: quick replies, insert a contact, paste, and clear the draft. */
        private fun buildQuickRow() {
            quickRow.removeAllViews()
            listOf(
                "OK" to { insertAtCaret("OK") },
                "Yes" to { insertAtCaret("Yes") },
                "Call you back" to { insertAtCaret("I'll call you back shortly.") },
                "On my way" to { insertAtCaret("On my way.") }
            ).forEach { (label, action) ->
                val chip = kit.chip(label, false) { action() }
                quickRow.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = kit.dp(6) })
            }
            quickRow.addView(kit.chip("Contact", false, R.drawable.oic_person) { pickContactNumber() },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = kit.dp(6) })
            quickRow.addView(kit.chip("Paste", false, R.drawable.oic_file_copy) { pasteIntoEditor() },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = kit.dp(6) })
            quickRow.addView(kit.chip("Clear", false, R.drawable.oic_close) {
                editor.setText("")
                clearDraft()
            }, LinearLayout.LayoutParams(-2, -2))
        }

        private fun toggleQuickRow() {
            quickRow.visibility = if (quickRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        private fun insertAtCaret(text: String) {
            val start = editor.selectionStart.coerceAtLeast(0)
            val end = editor.selectionEnd.coerceAtLeast(start)
            editor.text.replace(start, end, text)
            editor.setSelection((start + text.length).coerceAtMost(editor.text.length))
        }

        private fun pasteIntoEditor() {
            val clip = runCatching { host.getSystemService(ClipboardManager::class.java)?.primaryClip }.getOrNull()
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(host)?.toString().orEmpty()
            if (text.isBlank()) {
                toast("Nothing to paste")
                return
            }
            insertAtCaret(text)
        }

        private fun pickContactNumber() {
            if (!OeaMessagingApp.hasPermission(host, android.Manifest.permission.READ_CONTACTS)) {
                requestPermission(android.Manifest.permission.READ_CONTACTS)
                return
            }
            val directory = OeaMessagingApp.contactsNow(host)
            if (directory.isEmpty()) {
                toast("No contacts with numbers were found")
                return
            }
            kit.choose(
                "Insert a contact's number", "Their number is added where the cursor is",
                directory.map { entry -> "${entry.name} • ${entry.number}" }.take(60), 0
            ) { index ->
                directory.getOrNull(index)?.let { insertAtCaret(it.number) }
            }
        }

        /** Shows "Replying to …" above the composer, with the quoted text. */
        private fun setReplyTo(message: OeaMessagingApp.SmsMessage?) {
            replyTo = message
            quoteBar.removeAllViews()
            if (message == null) {
                quoteBar.visibility = View.GONE
                return
            }
            quoteBar.visibility = View.VISIBLE
            val column = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
            column.addView(kit.text("Replying to ${if (message.incoming) displayName else "yourself"}", 11.5f, palette.accent, OeaType.medium))
            column.addView(kit.text(message.body.take(90), 13f, palette.textSecondary).apply { maxLines = 2 })
            quoteBar.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
            quoteBar.addView(
                kit.circleIconButton(R.drawable.oic_close, "Cancel reply", Color.TRANSPARENT, palette.textSecondary) {
                    setReplyTo(null)
                },
                LinearLayout.LayoutParams(kit.dp(34), kit.dp(34))
            )
        }

        fun dismissQuietly() {
            if (!dismissed && dialog.isShowing) runCatching { dialog.dismiss() }
        }

        fun reload() {
            if (dismissed) return
            val generation = ++loadGeneration
            OeaMessagingApp.chatIo.execute {
                OeaMessagingApp.markConversationReadIfDefault(host, address)
                val freshThread = threadFor(address)
                val loaded = runCatching { OeaMessagingApp.queryConversation(host, freshThread) }.getOrDefault(emptyList())
                host.runOnUiThread {
                    if (dismissed || host.isDestroyed) return@runOnUiThread
                    if (generation != loadGeneration) return@runOnUiThread
                    val wasAtBottom = !::recycler.isInitialized || !recycler.canScrollVertically(1) || firstLoad
                    thread = freshThread
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
                    paintJumpButton()
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
            // Compact on purpose: the full "SIM 1 • MTN Nigeria" label used to
            // crowd the text field down to a sliver.
            simChip.text = OeaSimDirectory.compactLabel(line)
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

        /**
         * Sends what is in the composer, with the reply quote prepended when there
         * is one, and watches the result through the `SMS_SENT` / `SMS_DELIVERED`
         * broadcasts registered in [show].
         *
         * A message the network refuses is then visible as a failed bubble that
         * can be tapped to retry, instead of a bubble that simply claims success.
         */
        private fun sendCurrent() {
            val typed = editor.text.toString().trim()
            if (typed.isEmpty()) return
            val quoted = replyTo?.let { "“${it.body.take(120)}”\n" }.orEmpty()
            val body = quoted + typed
            sendSms(address, body, selectedSub) { sent ->
                if (sent) {
                    editor.setText("")
                    clearDraft()
                    setReplyTo(null)
                    if (expanded) setExpanded(false)
                    scrollToBottom(true)
                    if (OeaMessagingApp.isDefaultSmsApp(host)) {
                        reload()
                    } else {
                        // Android files non-default sends in its own time; look again shortly.
                        handler.postDelayed({ reload() }, 700L)
                        handler.postDelayed({ reload() }, 2200L)
                    }
                } else {
                    say("Not sent — tap the failed message to try again", OeaKit.Tone.WARNING)
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
            val wanted = chatQuery.trim()
            val source = if (wanted.isBlank()) messages else messages.filter { it.body.contains(wanted, true) }
            val start = (source.size - visibleCount).coerceAtLeast(0)
            val built = ArrayList<ChatRow>()
            if (start > 0) built.add(ChatRow.Earlier(start))
            var lastDayStart = Long.MIN_VALUE
            var previous: OeaMessagingApp.SmsMessage? = null
            source.drop(start).forEach { message ->
                val dayStart = OeaMessageDates.dayStart(message.date)
                if (dayStart != lastDayStart) {
                    built.add(ChatRow.Day(OeaMessageDates.separatorLabel(message.date, now)))
                    lastDayStart = dayStart
                    previous = null
                }
                val tight = previous != null &&
                    previous!!.incoming == message.incoming &&
                    message.date - previous!!.date in 0..120_000L
                built.add(ChatRow.Bubble(message, tight, OeaMessagingApp.isMessageStarred(host, thread.canonicalKey, message.id)))
                previous = message
            }
            rows = built
        }

        /** Turns URLs, phone numbers and emails inside a message into real links. */
        private fun linkedText(body: String, onAccent: Boolean): CharSequence {
            val spannable = android.text.SpannableString(body)
            val added = runCatching {
                android.text.util.Linkify.addLinks(
                    spannable,
                    android.text.util.Linkify.WEB_URLS or android.text.util.Linkify.PHONE_NUMBERS or android.text.util.Linkify.EMAIL_ADDRESSES
                )
            }.getOrDefault(false)
            if (!added) return body
            val color = if (onAccent) palette.onAccent else palette.accent
            spannable.getSpans(0, spannable.length, android.text.style.URLSpan::class.java).forEach { span ->
                val start = spannable.getSpanStart(span)
                val end = spannable.getSpanEnd(span)
                spannable.setSpan(
                    android.text.style.ForegroundColorSpan(color), start, end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            return spannable
        }

        /** Sends a failed message again on the SIM that can still send it. */
        private fun retry(message: OeaMessagingApp.SmsMessage) {
            val retrySub = when {
                message.subscriptionId < 0 -> selectedSub
                simLines.isEmpty() || simLines.any { it.subscriptionId == message.subscriptionId } -> message.subscriptionId
                else -> selectedSub
            }
            sendSms(address, message.body, retrySub) { ok ->
                if (ok) {
                    OeaMessagingApp.io.execute { OeaMessagingApp.deleteMessageIfDefault(host, message.id) }
                    handler.postDelayed({ reload() }, 600L)
                }
            }
        }

        /** In-conversation search: filters the bubbles without leaving the chat. */
        private fun searchInConversation() {
            kit.prompt("Search this conversation", "Only messages containing the text are shown.", "Text", chatQuery) { input ->
                chatQuery = input.trim()
                buildRows()
                adapter.notifyDataSetChanged()
                if (chatQuery.isNotBlank()) say(
                    if (rows.isEmpty()) "No message here matches" else "Showing ${rows.count { it is ChatRow.Bubble }} matching",
                    OeaKit.Tone.INFO
                )
                null
            }
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
                // Links inside a message are tappable, and a failed message is
                // tapped to retry rather than leaving the user stuck.
                bubble.movementMethod = android.text.method.LinkMovementMethod.getInstance()
                bubble.text = linkedText(message.body.ifBlank { "(empty message)" }, sent && !failed)
                bubble.linksClickable = true
                bubble.setOnClickListener { if (failed) retry(message) }
                bubble.setOnLongClickListener {
                    showMessageDetails(message)
                    true
                }
                column.addView(bubble, LinearLayout.LayoutParams(-2, -2))

                // A one-tap "copy code" chip on anything that carries a code.
                val otp = OeaSmsMatcher.extractOtpCode(message.body)
                if (otp != null) {
                    val chip = kit.chip("Copy code $otp", false, R.drawable.oic_file_copy) {
                        runCatching {
                            host.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("code", otp))
                            toast("Code $otp copied")
                        }
                    }
                    column.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { topMargin = kit.dp(4) })
                }
                if (row.starred) {
                    val starRow = LinearLayout(host).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = if (sent) Gravity.END else Gravity.START
                        setPadding(kit.dp(8), kit.dp(2), kit.dp(8), 0)
                    }
                    starRow.addView(
                        ImageView(host).apply { setImageDrawable(kit.icon(R.drawable.oic_star_fill, palette.accent)) },
                        LinearLayout.LayoutParams(kit.dp(13), kit.dp(13))
                    )
                    starRow.addView(kit.text("Starred", 10.5f, palette.accent, OeaType.medium).apply {
                        setPadding(kit.dp(4), 0, 0, 0)
                    })
                    column.addView(starRow, LinearLayout.LayoutParams(-2, -2))
                }

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
                val starred = OeaMessagingApp.isMessageStarred(host, thread.canonicalKey, message.id)
                OeaSmsMatcher.extractOtpCode(message.body)?.let { code ->
                    panel.addView(kit.primaryButton("Copy code $code", R.drawable.oic_file_copy) {
                        sheetDialog.dismiss()
                        runCatching {
                            host.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("code", code))
                            toast("Code $code copied")
                        }
                    }, LinearLayout.LayoutParams(-1, kit.dp(50)).apply { bottomMargin = kit.dp(6) })
                }
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
                panel.addView(
                    kit.buttonRow(
                        kit.tonalButton(if (starred) "Unstar" else "Star", if (starred) R.drawable.oic_star_fill else R.drawable.oic_star) {
                            OeaMessagingApp.setMessageStarred(host, thread.canonicalKey, message.id, !starred)
                            sheetDialog.dismiss()
                            buildRows()
                            adapter.notifyDataSetChanged()
                            toast(if (starred) "Star removed" else "Starred")
                        },
                        kit.tonalButton("Reply", R.drawable.oic_send) {
                            sheetDialog.dismiss()
                            setReplyTo(message)
                            editor.requestFocus()
                        },
                        kit.quietButton("Export", R.drawable.oic_file_download) {
                            sheetDialog.dismiss()
                            shareText(
                                "[$displayName • ${OeaMessageDates.fullLabel(message.date)}]\n${message.body}",
                                "Export message"
                            )
                        }
                    )
                )
                if (message.type == Telephony.Sms.MESSAGE_TYPE_FAILED) {
                    panel.addView(kit.primaryButton("Send again", R.drawable.oic_send) {
                        sheetDialog.dismiss()
                        retry(message)
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
                    OeaAppKit.Action(R.drawable.oic_search, "Search in conversation", "Show only the messages containing your text") {
                        searchInConversation()
                    },
                    OeaAppKit.Action(
                        R.drawable.oic_check_circle_fill,
                        if (OeaMessagingApp.markedUnread(host, address)) "Mark as read" else "Mark as unread",
                        "Also written to Android when OEA is the default SMS app"
                    ) {
                        OeaMessagingApp.io.execute {
                            OeaMessagingApp.markConversationRead(host, address, read = OeaMessagingApp.markedUnread(host, address))
                            host.runOnUiThread { if (alive) reloadThreads() }
                        }
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

        /** Change payload for the threads list, so a selection tap repaints one row without a flicker. */
        private const val PAYLOAD_SELECTION = "selection"

        /** Broadcasts the SMS manager fires, watched while a conversation is open. */
        private const val ACTION_SMS_SENT = "com.oea.launcher.SMS_SENT"
        private const val ACTION_SMS_DELIVERED = "com.oea.launcher.SMS_DELIVERED"
    }
}
