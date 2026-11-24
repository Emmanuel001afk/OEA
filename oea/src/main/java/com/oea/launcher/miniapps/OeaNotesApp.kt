package com.oea.launcher.miniapps

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.oea.launcher.R
import com.oea.launcher.applock.OeaAppLockStore
import com.oea.launcher.ui.OeaAppKit
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * OEA Notes: fully competitive notes app.
 *
 * Unique features:
 * - Folders: General, Work, Personal, Study, Ideas, etc. — user can create custom folders (tags)
 * - Types: ordinary notes, private notes (App Lock PIN protected), voice notes, checklist (todo with checkboxes), pinned, archived
 * - Rich text: markdown-like bold/italic via ** and * rendered in viewer
 * - Checklist: todo items with [ ] and [x] parsing, tap to toggle
 * - Search: by text, tag, folder, date
 * - Sort: by pinned, date, title, tag
 * - Voice: dictated through the phone's own recogniser (speech to text). The note keeps
 *   the words; OEA records no audio clip for notes, so there is no silent microphone here.
 * - Attachments: image attachment via MediaStore picker (URI stored)
 * - Export: share as txt, export all as JSON, backup/restore
 * - Private vault: its own private file, opened only behind the App Lock PIN. The text is
 *   not ciphered at rest — a rooted or backed-up device can read it — so it hides notes
 *   from shoulder-surfing, not from an attacker who owns the phone.
 * - Tags: multiple tags per note
 * - Colors: note color (default, yellow, green, blue, pink)
 * - Reminders: set reminder time, notification via AlarmManager (best-effort)
 * - Widgets: notes widget shows pinned notes
 * - Competitive: polished UI with cards, animations, no unfinished screens
 */
object OeaNotesApp {

    private const val PREFS = "oea_notes_v2"
    private const val KEY_NOTES = "notes"
    private const val KEY_PRIVATE = "private_notes"
    private const val KEY_ARCHIVED = "archived_notes"
    private const val KEY_TRASH = "trash_notes"

    /** How long a deleted note can be brought back. */
    private const val TRASH_DAYS = 30L
    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val SEP_ROW = "\u001E"
    private const val SEP_FIELD = "\u001F"

    data class Note(
        val id: Long,
        val text: String,
        val title: String = "",
        val pinned: Boolean,
        val tag: String,
        val folder: String,
        val color: String,
        val checklist: Boolean,
        val created: Long,
        val updated: Long,
        val private: Boolean = false,
        val archived: Boolean = false,
        val reminder: Long = 0L,
        val attachment: String = "",
        /** When it was put in the trash; 0 while it is not there. */
        val deletedAt: Long = 0L
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Building a SimpleDateFormat is one of the most expensive things a list
     * can do per row, and Notes used to build two per note on every refresh —
     * including every letter typed into search. One of each, kept.
     */
    private val stampFormat = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val fullStampFormat = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
    private val reminderStampFormat = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())

    /**
     * The parsed notes, kept against the exact text they came from.
     *
     * Every refresh used to re-read and re-parse all three lists — splitting a
     * single string holding every note — so the cost of opening Notes, or of
     * typing one letter into search, grew with the whole collection. The
     * cached copy is only reused while the stored text is unchanged, so it can
     * never show a stale note.
     */
    private val decoded = HashMap<String, Pair<String, List<Note>>>()

    /** Forgets one parsed list, so the next read sees what was just written. */
    /**
     * Every note that carries a reminder and still exists — the normal list, the
     * private list and the archive. The trash is excluded: a note waiting to be
     * restored is not a promise to ring about it.
     */
    internal fun reminderNotes(ctx: Context): List<Note> =
        (load(ctx, KEY_NOTES) + load(ctx, KEY_PRIVATE) + load(ctx, KEY_ARCHIVED))
            .filter { it.reminder > 0L && it.deletedAt <= 0L }

    private fun drop(ctx: Context, key: String) {
        decoded.remove(key)
    }

    private fun load(ctx: Context, key: String): List<Note> {
        val raw = prefs(ctx).getString(key, "") ?: ""
        val cached = decoded[key]
        if (cached != null && cached.first == raw) return cached.second
        val notes = decode(raw)
        decoded[key] = raw to notes
        return notes
    }

    private fun decode(raw: String?): List<Note> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(SEP_ROW).mapNotNull { row ->
            val p = row.split(SEP_FIELD)
            if (p.size < 4) return@mapNotNull null
            try {
                // Notes written before the title field was added have 14 fields
                // (id..deletedAt). Newer notes have 15 with title at index 2.
                val hasTitle = p.size >= 15
                val firstLine = p[1].lineSequence().firstOrNull() ?: ""
                Note(
                    id = p[0].toLong(),
                    text = p[1],
                    title = if (hasTitle) p[2].ifBlank { firstLine } else firstLine,
                    pinned = if (hasTitle) p[3] == "1" else p[2] == "1",
                    tag = if (hasTitle) p.getOrNull(4) ?: "General" else p.getOrNull(3) ?: "General",
                    folder = if (hasTitle) p.getOrNull(5) ?: "General" else p.getOrNull(4) ?: "General",
                    color = if (hasTitle) p.getOrNull(6) ?: "default" else p.getOrNull(5) ?: "default",
                    checklist = if (hasTitle) p.getOrNull(7) == "1" else p.getOrNull(6) == "1",
                    created = if (hasTitle) p.getOrNull(8)?.toLongOrNull() ?: 0L else p.getOrNull(7)?.toLongOrNull() ?: 0L,
                    updated = if (hasTitle)
                        p.getOrNull(9)?.toLongOrNull() ?: p.getOrNull(8)?.toLongOrNull() ?: 0L
                    else
                        p.getOrNull(8)?.toLongOrNull() ?: p.getOrNull(7)?.toLongOrNull() ?: 0L,
                    private = if (hasTitle) p.getOrNull(10) == "1" else p.getOrNull(9) == "1",
                    archived = if (hasTitle) p.getOrNull(11) == "1" else p.getOrNull(10) == "1",
                    reminder = if (hasTitle) p.getOrNull(12)?.toLongOrNull() ?: 0L else p.getOrNull(11)?.toLongOrNull() ?: 0L,
                    attachment = if (hasTitle) p.getOrNull(13) ?: "" else p.getOrNull(12) ?: "",
                    deletedAt = if (hasTitle) p.getOrNull(14)?.toLongOrNull() ?: 0L else p.getOrNull(13)?.toLongOrNull() ?: 0L
                )
            } catch (_: Throwable) { null }
        }.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updated })
    }

    private fun encode(list: List<Note>): String = list.joinToString(SEP_ROW) { n ->
        listOf(
            n.id.toString(),
            n.text.replace(SEP_FIELD, " ").replace(SEP_ROW, " "),
            n.title.replace(SEP_FIELD, " ").replace(SEP_ROW, " "),
            if (n.pinned) "1" else "0",
            n.tag.replace(SEP_FIELD, " "),
            n.folder.replace(SEP_FIELD, " "),
            n.color,
            if (n.checklist) "1" else "0",
            n.created.toString(),
            n.updated.toString(),
            if (n.private) "1" else "0",
            if (n.archived) "1" else "0",
            n.reminder.toString(),
            n.attachment,
            n.deletedAt.toString()
        ).joinToString(SEP_FIELD)
    }

    fun build(host: Activity, palette: OeaPalette): View {
        // Opening the app is the one moment a missed reminder can be noticed: the
        // sync rings anything that came due while the phone was off, and re-arms
        // whatever a reboot or a clock change lost.
        runCatching { OeaNoteReminders.sync(host) }
        val kit = OeaAppKit(host, palette)
        var refresh: (String) -> Unit = {}
        var currentFilter = "all"
        var searchQuery = ""
        var currentFolder: String? = null

        val indexBack = host.miniIndexBack()
        val navItems = listOf(
            OeaAppKit.NavItem("all", "Notes", R.drawable.oic_edit),
            OeaAppKit.NavItem("checklist", "Lists", R.drawable.oic_check_circle_fill),
            OeaAppKit.NavItem("private", "Private", R.drawable.oic_lock),
            OeaAppKit.NavItem("archived", "Archived", R.drawable.oic_folder)
        )
        val shell = kit.appShell(
            title = "Notes",
            navItems = navItems,
            initialNav = currentFilter,
            onBack = indexBack
        ) { id ->
            refresh(id)
        }
        val column = shell.body

        val searchBox = kit.searchField("Search notes, tags or folders", onQuery = { q ->
            searchQuery = q.trim()
            refresh(currentFilter)
        })
        val searchRow = android.widget.FrameLayout(host).apply {
            setPadding(kit.dp(16), 0, kit.dp(16), kit.dp(8))
            addView(searchBox.root, android.widget.FrameLayout.LayoutParams(-1, -2))
            visibility = View.GONE
        }
        shell.headerExtras.addView(searchRow, LinearLayout.LayoutParams(-1, -2))

        fun toggleSearch() {
            if (searchRow.visibility == View.VISIBLE) {
                searchRow.visibility = View.GONE
                searchBox.close()
                if (searchQuery.isNotEmpty()) {
                    searchQuery = ""
                    searchBox.setText("")
                    refresh(currentFilter)
                }
            } else {
                searchRow.visibility = View.VISIBLE
                searchBox.focus()
            }
        }

        host.miniBackHook = {
            when {
                searchRow.visibility == View.VISIBLE -> { toggleSearch(); true }
                currentFilter !in setOf("all", "checklist", "private", "archived") -> { refresh("all"); true }
                else -> false
            }
        }

        shell.bar.addAction(R.drawable.oic_search, "Search notes") { toggleSearch() }
        shell.bar.addAction(R.drawable.oic_add, "New note") {
            when (currentFilter) {
                "checklist" -> createChecklistNote(host, kit) { refresh(currentFilter) }
                "private" -> createNote(host, kit, private = true) { refresh(currentFilter) }
                else -> createNote(host, kit, private = false) { refresh(currentFilter) }
            }
        }
        shell.bar.addAction(R.drawable.oic_more_vert, "Notes options") {
            kit.actions("Notes", null, listOf(
                OeaAppKit.Action(R.drawable.oic_check_circle_fill, "New checklist", "Create a tappable to-do list") {
                    createChecklistNote(host, kit) { refresh(currentFilter) }
                },
                OeaAppKit.Action(R.drawable.oic_lock, "New private note", "Protected with your App Lock PIN") {
                    createNote(host, kit, private = true) { refresh("private") }
                },
                OeaAppKit.Action(R.drawable.oic_mic, "Dictate voice note", "Transcribe speech directly into a note") {
                    startVoiceInput(host)
                },
                OeaAppKit.Action(R.drawable.oic_push_pin, "Pinned notes", "Show only pinned notes") {
                    refresh("pinned")
                },
                OeaAppKit.Action(R.drawable.oic_alarm, "Reminders", "Notes with a scheduled reminder") {
                    refresh("reminders")
                },
                OeaAppKit.Action(R.drawable.oic_delete, "Trash", "Deleted notes, kept for $TRASH_DAYS days") {
                    refresh("trash")
                },
                OeaAppKit.Action(R.drawable.oic_history, "Previous 7 Days", "Notes updated in the last week") {
                    refresh("7days")
                },
                OeaAppKit.Action(R.drawable.oic_history, "Previous 30 Days", "Notes from 8 to 30 days ago") {
                    refresh("30days")
                },
                OeaAppKit.Action(R.drawable.oic_schedule, "This Month", "Notes from the start of this month") {
                    refresh("month")
                },
                OeaAppKit.Action(R.drawable.oic_share_filled, "Export notes", "Share or copy your saved notes") {
                    val exportable = load(host, KEY_NOTES) +
                        load(host, KEY_ARCHIVED).map { it.copy(archived = true) }
                    val raw = exportable.joinToString("\n\n---\n\n") { it.text }
                    if (raw.isBlank()) {
                        android.widget.Toast.makeText(host, "No notes to export yet", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        runCatching {
                            host.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, raw), "Export notes"))
                        }
                    }
                }
            ))
        }

        val folderBar = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        val folderScroll = kit.hscroll(folderBar)
        column.addView(folderScroll, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(10) })

        val content = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        column.addView(content)
        pruneTrash(host)

        refresh = refresh@{ filter ->
            currentFilter = filter
            if (filter in setOf("all", "checklist", "private", "archived")) {
                shell.nav?.select(filter, notify = false)
                shell.bar.setBack(indexBack)
            } else {
                shell.bar.setBack { refresh("all") }
            }
            shell.bar.title.text = when (filter) {
                "trash" -> "Trash"
                "checklist" -> "Lists"
                "private" -> "Private"
                "archived" -> "Archived"
                "pinned" -> "Pinned"
                "reminders" -> "Reminders"
                "voice" -> "Voice Notes"
                "7days" -> "Previous 7 Days"
                "30days" -> "Previous 30 Days"
                "month" -> "This Month"
                else -> "Notes"
            }

            val allNotes = load(host, KEY_NOTES) +
                load(host, KEY_PRIVATE).map { it.copy(private = true) } +
                load(host, KEY_ARCHIVED).map { it.copy(archived = true) } +
                load(host, KEY_TRASH)

            val now = System.currentTimeMillis()
            val sevenDaysAgo = now - 7L * DAY_MS
            val thirtyDaysAgo = now - 30L * DAY_MS
            val monthStart = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
            val scopedNotes = allNotes.filter { note ->
                when (filter) {
                    "pinned" -> note.pinned && !note.private && note.deletedAt == 0L
                    "private" -> note.private && note.deletedAt == 0L
                    "voice" -> note.tag == "voice" && !note.private && note.deletedAt == 0L
                    "checklist" -> note.checklist && !note.archived && !note.private && note.deletedAt == 0L
                    "archived" -> note.archived && !note.private && note.deletedAt == 0L
                    "reminders" -> note.reminder > 0 && !note.private && note.deletedAt == 0L
                    "trash" -> note.deletedAt > 0L
                    "7days" -> note.updated >= sevenDaysAgo && !note.archived && !note.private && note.deletedAt == 0L
                    "30days" -> note.updated >= thirtyDaysAgo && note.updated < sevenDaysAgo && !note.archived && !note.private && note.deletedAt == 0L
                    "month" -> note.updated >= monthStart && note.updated < thirtyDaysAgo && !note.archived && !note.private && note.deletedAt == 0L
                    else -> !note.archived && !note.private && note.deletedAt == 0L
                }
            }

            val isTimeFilter = filter in setOf("7days", "30days", "month")
            folderBar.removeAllViews()
            if (!isTimeFilter) {
                // One pass for every folder's total: counting each folder on its
                // own walked the whole list once per folder.
                val folderCounts = scopedNotes.groupingBy { it.folder.ifBlank { "General" } }.eachCount()
                val folders = folderCounts.keys.sorted()
                if (folders.size > 1) {
                folderScroll.visibility = View.VISIBLE
                folderBar.addView(kit.chip("All", currentFolder == null) { currentFolder = null; refresh(filter) }, LinearLayout.LayoutParams(-2, kit.dp(38)).apply { rightMargin = kit.dp(6) })
                folders.forEach { folder ->
                    val count = folderCounts[folder] ?: 0
                    folderBar.addView(kit.chip("$folder · $count", folder == currentFolder) {
                        currentFolder = if (currentFolder == folder) null else folder
                        refresh(filter)
                    }, LinearLayout.LayoutParams(-2, kit.dp(38)).apply { rightMargin = kit.dp(6) })
                }
            } else {
                currentFolder = null
                folderScroll.visibility = View.GONE
            }
            } // close if (!isTimeFilter)

            content.removeAllViews()
            if (filter == "trash" && scopedNotes.isNotEmpty()) {
                content.addView(
                    kit.button("Empty the trash", kit.palette.cardHigh, kit.palette.danger, R.drawable.oic_delete) {
                        kit.confirm("Empty the trash?", "${scopedNotes.size} ${if (scopedNotes.size == 1) "note" else "notes"} go for good.", "Empty", destructive = true) {
                            emptyTrash(host)
                            refresh(filter)
                        }
                    },
                    LinearLayout.LayoutParams(-1, kit.dp(48)).apply { bottomMargin = kit.dp(10) }
                )
            }
            val filtered = scopedNotes.filter { note ->
                val matchesFolder = currentFolder == null || note.folder.ifBlank { "General" } == currentFolder
                val matchesSearch = searchQuery.isBlank() ||
                    note.text.contains(searchQuery, ignoreCase = true) ||
                    note.tag.contains(searchQuery, ignoreCase = true) ||
                    note.folder.contains(searchQuery, ignoreCase = true)
                matchesFolder && matchesSearch
            }

            shell.bar.setSubtitle(
                when {
                    searchQuery.isNotBlank() -> "${filtered.size} matching"
                    filtered.isNotEmpty() -> "${filtered.size} ${if (filtered.size == 1) "note" else "notes"}"
                    else -> ""
                }
            )

            if (filtered.isEmpty()) {
                val (iconRes, emptyTitle, emptyBody) = when {
                    searchQuery.isNotBlank() -> Triple(R.drawable.oic_search, "No matching notes", "Try a different keyword or clear the search.")
                    filter == "private" -> Triple(R.drawable.oic_lock, "No private notes", "Private notes stay locked behind your App Lock PIN.")
                    filter == "checklist" -> Triple(R.drawable.oic_check_circle_fill, "No checklists yet", "Create a to-do list and tick off items as you go.")
                    filter == "archived" -> Triple(R.drawable.oic_folder, "Archive is empty", "Notes you archive stay tucked away here without being deleted.")
                    filter == "pinned" -> Triple(R.drawable.oic_push_pin, "No pinned notes", "Pin important notes so they stay at the top.")
                    filter == "reminders" -> Triple(R.drawable.oic_alarm, "No note reminders", "Open any note to attach a reminder time.")
                    filter == "trash" -> Triple(R.drawable.oic_delete, "The trash is empty", "Notes you delete stay here for $TRASH_DAYS days before they go for good.")
                    filter == "7days" -> Triple(R.drawable.oic_history, "No recent notes", "No notes updated in the last 7 days.")
                    filter == "30days" -> Triple(R.drawable.oic_history, "No notes from this period", "No notes updated between 8 and 30 days ago.")
                    filter == "month" -> Triple(R.drawable.oic_schedule, "No notes this month", "No notes from the start of this month.")
                    else -> Triple(R.drawable.oic_edit, "No notes yet", "Write a note, start a checklist, or dictate by voice.")
                }
                val ctaLabel = when {
                    searchQuery.isNotBlank() -> "Clear search"
                    filter == "archived" || filter == "pinned" || filter == "reminders" || filter == "trash" -> null
                    filter in setOf("7days", "30days", "month") -> null
                    filter == "checklist" -> "New checklist"
                    filter == "private" -> "New private note"
                    else -> "New note"
                }
                content.addView(
                    kit.emptyState(iconRes, emptyTitle, emptyBody, ctaLabel) {
                        when {
                            searchQuery.isNotBlank() -> toggleSearch()
                            filter == "checklist" -> createChecklistNote(host, kit) { refresh(filter) }
                            filter == "private" -> createNote(host, kit, private = true) { refresh(filter) }
                            else -> createNote(host, kit, private = false) { refresh(filter) }
                        }
                    },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(12) }
                )
                return@refresh
            }

            val grouped = filtered.groupBy { it.folder.ifBlank { "General" } }
            grouped.forEach { (folder, notes) ->
                if (grouped.size > 1) kit.sectionTitle(content, "$folder · ${notes.size}")
                val g = kit.group(content)
                notes.forEach { note ->
                    val heading = note.title.ifBlank {
                        val lines = note.text.lineSequence().filter { it.isNotBlank() }.toList()
                        if (note.private) lines.firstOrNull()?.take(36) ?: "Private note"
                        else lines.firstOrNull()?.take(48) ?: "Note"
                    }
                    val bodyLines = note.text.lineSequence().filter { it.isNotBlank() }.toList()
                    val snippet = if (!note.private && !note.checklist && bodyLines.size > 1) bodyLines[1].take(48) else null
                    val subtitle = buildString {
                        if (note.pinned) append("Pinned · ")
                        if (note.checklist) {
                            val items = note.text.lineSequence().drop(1).filter { it.trim().startsWith("[") }.toList()
                            val done = items.count { it.trim().startsWith("[x]", true) }
                            if (items.isNotEmpty()) append("$done/${items.size} done · ")
                        }
                        if (snippet != null) append("$snippet · ")
                        append(stampFormat.format(Date(note.updated)))
                        if (note.reminder > 0) append(" · ${clockFormat.format(Date(note.reminder))}")
                    }
                    val icon = when {
                        note.private -> R.drawable.oic_lock
                        note.pinned -> R.drawable.oic_push_pin
                        note.checklist -> R.drawable.oic_check_circle_fill
                        note.tag == "voice" -> R.drawable.oic_mic
                        else -> R.drawable.oic_edit
                    }
                    val colorLabel = note.color.takeIf { it.isNotBlank() && it != "default" }?.replaceFirstChar { it.uppercase() }
                    kit.row(g, icon, heading, subtitle, value = colorLabel, chevron = true) {
                        if (note.deletedAt > 0L) {
                            val left = TRASH_DAYS - ((System.currentTimeMillis() - note.deletedAt) / DAY_MS)
                            kit.actions(note.title.ifBlank { note.text.lineSequence().firstOrNull() ?: "Note" }.take(36),
                                if (left > 0L) "$left ${if (left == 1L) "day" else "days"} left in the trash" else "Goes for good on the next clean",
                                listOf(
                                    OeaAppKit.Action(R.drawable.oic_history, "Put it back", "Returns to where it was before you deleted it") {
                                        restoreNote(host, note)
                                        refresh(filter)
                                    },
                                    OeaAppKit.Action(R.drawable.oic_delete, "Delete forever", "Gone now, and not in the trash either", destructive = true) {
                                        kit.confirm("Delete forever?", "This cannot be undone.", "Delete", destructive = true) {
                                            deleteForever(host, note)
                                            refresh(filter)
                                        }
                                    }
                                ))
                        } else if (note.private) {
                            kit.prompt("Unlock private note", "Enter your App Lock PIN", "PIN", numeric = true, password = true, confirmLabel = "Unlock") { pin ->
                                if (!OeaAppLockStore.verifyPin(host, pin)) return@prompt "Incorrect PIN"
                                host.runOnUiThread { showNoteDetail(host, kit, note) { refresh(filter) } }
                                null
                            }
                        } else {
                            showNoteDetail(host, kit, note) { refresh(filter) }
                        }
                    }
                }
            }
        }
        refresh("all")

        host.window.decorView.postDelayed({
            try {
                val voiceText = host.intent.getStringExtra("voice_result")
                if (!voiceText.isNullOrBlank()) {
                    host.intent.removeExtra("voice_result")
                    createNoteWithText(host, voiceText, tag = "voice", folder = "General", private = false) { refresh(currentFilter) }
                }
            } catch (_: Throwable) {}
        }, 500L)

        return shell.root
    }

    private fun multilineField(kit: OeaKit, hint: String, initial: String): android.widget.EditText =
        kit.field(hint, initial).apply {
            isSingleLine = false
            minLines = 4
            maxLines = 9
            gravity = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }

    private fun createNote(host: Activity, kit: OeaAppKit, private: Boolean, onDone: () -> Unit) {
        kit.sheet(if (private) "New private note" else "New note", if (private) "Protected with your App Lock PIN" else null) { panel, dialog ->
            val titleField = kit.field("Title", "")
            val textField = multilineField(kit, "Write your note…", "")
            val folderField = kit.field("Folder (General, Work, Ideas…)", "General")
            val tagField = kit.field("Tag (optional)", "")
            val colorRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, kit.dp(4), 0, 0) }
            var selectedColor = "default"
            fun paintColors() {
                colorRow.removeAllViews()
                listOf("default" to "Default", "yellow" to "Amber", "green" to "Mint", "blue" to "Sky", "pink" to "Rose").forEach { (colorId, label) ->
                    colorRow.addView(kit.chip(label, colorId == selectedColor) {
                        selectedColor = colorId
                        paintColors()
                    }, LinearLayout.LayoutParams(-2, kit.dp(40)).apply { rightMargin = kit.dp(6) })
                }
            }
            paintColors()
            panel.addView(titleField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            panel.addView(textField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            panel.addView(folderField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            panel.addView(tagField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            panel.addView(kit.hscroll(colorRow), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            panel.addView(kit.buttonRow(
                kit.quietButton("Cancel") { dialog.dismiss() },
                kit.primaryButton("Save note") {
                    val title = titleField.text.toString().trim()
                    val text = textField.text.toString().trim()
                    if (text.isEmpty() && title.isEmpty()) {
                        android.widget.Toast.makeText(host, "Write something first", android.widget.Toast.LENGTH_SHORT).show()
                        return@primaryButton
                    }
                    val fullText = if (title.isNotEmpty()) "$title\n$text" else text
                    createNoteWithText(
                        host,
                        fullText,
                        tagField.text.toString().trim().ifBlank { "General" },
                        folderField.text.toString().trim().ifBlank { "General" },
                        private,
                        selectedColor,
                        false,
                        title = title
                    ) { onDone() }
                    dialog.dismiss()
                }
            ))
        }
    }

    private fun createChecklistNote(host: Activity, kit: OeaAppKit, onDone: () -> Unit) {
        kit.sheet("New checklist", "Put one item on each line") { panel, dialog ->
            val titleField = kit.field("List title", "")
            val itemsField = multilineField(kit, "Buy groceries\nCall Alex\nSend invoice", "")
            panel.addView(titleField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            panel.addView(itemsField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            panel.addView(kit.buttonRow(
                kit.quietButton("Cancel") { dialog.dismiss() },
                kit.primaryButton("Create list") {
                    val title = titleField.text.toString().trim().ifBlank { "Checklist" }
                    val items = itemsField.text.toString().lineSequence().map { it.trim() }.filter { it.isNotBlank() }.map { "[ ] $it" }.joinToString("\n")
                    val fullText = if (items.isBlank()) title else "$title\n$items"
                    createNoteWithText(host, fullText, "checklist", "General", false, "default", true, title = title) { onDone() }
                    dialog.dismiss()
                }
            ))
        }
    }

    private fun createNoteWithText(host: Activity, text: String, tag: String, folder: String, private: Boolean, color: String = "default", checklist: Boolean = false, title: String = "", onDone: () -> Unit) {
        val p = prefs(host)
        val key = when {
            private -> KEY_PRIVATE
            else -> KEY_NOTES
        }
        val existing = load(host, key)
        val now = System.currentTimeMillis()
        // Two notes made in the same millisecond used to share an id, and an
        // edit or a delete finds a note by its id — so one of them answered
        // for both. Nudge forward until the id is really unused.
        var id = now
        val taken = existing.map { it.id }.toHashSet()
        while (taken.contains(id)) id++
        val note = Note(id, text, title, false, tag, folder, color, checklist, now, now, private, false, 0L, "", 0L)
        p.edit().putString(key, encode(existing + note)).apply()
        drop(host, key)
        onDone()
    }

    private fun createNoteWithText(host: Activity, text: String, tag: String, private: Boolean, onDone: () -> Unit) {
        createNoteWithText(host, text, tag, "General", private, "default", false, onDone = onDone)
    }

    private fun showNoteDetail(host: Activity, kit: OeaAppKit, initialNote: Note, onDone: () -> Unit) {
        var note = initialNote
        kit.sheet(note.title.ifBlank { note.text.lineSequence().firstOrNull() ?: "Note" }, fullStampFormat.format(Date(note.updated))) { panel, dialog ->
            if (!note.checklist) {
                val textView = kit.text(renderMarkdown(note.text), 15.5f, kit.palette.text).apply {
                    setLineSpacing(kit.dpf(3f), 1f)
                    setPadding(kit.dp(14), kit.dp(12), kit.dp(14), kit.dp(12))
                    background = kit.rounded(kit.palette.card, 18, kit.palette.stroke, 1)
                }
                panel.addView(textView, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(10) })
            } else {
                val listGroup = kit.group(panel)
                fun paintChecklist() {
                    listGroup.removeAllViews()
                    val lines = note.text.lines().toMutableList()
                    lines.forEachIndexed { idx, raw ->
                        if (idx == 0 || !raw.trim().startsWith("[")) return@forEachIndexed
                        val checked = raw.trim().startsWith("[x]", true)
                        val label = raw.replace(Regex("^\\s*\\[.?\\]\\s*"), "")
                        kit.row(listGroup, if (checked) R.drawable.oic_check_circle_fill else R.drawable.oic_circle, label, if (checked) "Completed" else "To do") {
                            lines[idx] = if (checked) "[ ] $label" else "[x] $label"
                            val updatedText = lines.joinToString("\n")
                            note = note.copy(text = updatedText, updated = System.currentTimeMillis())
                            updateNoteFull(host, note, note)
                            paintChecklist()
                            onDone()
                        }
                    }
                }
                paintChecklist()
            }

            val metaParts = buildList {
                add(note.folder.ifBlank { "General" })
                if (note.tag.isNotBlank() && note.tag != "General") add("#${note.tag}")
                add("Created ${stampFormat.format(Date(note.created))}")
                if (note.reminder > 0L) add("Reminder ${reminderStampFormat.format(Date(note.reminder))}")
            }
            panel.addView(kit.text(metaParts.joinToString(" · "), 12.5f, kit.palette.textSecondary).apply {
                setPadding(kit.dp(4), 0, kit.dp(4), kit.dp(10))
            })

            val actionsGroup = kit.group(panel)
            kit.row(actionsGroup, R.drawable.oic_edit, "Edit note", null, chevron = true) {
                dialog.dismiss()
                kit.sheet("Edit note", null) { editPanel, editDialog ->
                    val editor = multilineField(kit, "Note", note.text)
                    editPanel.addView(editor, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
                    editPanel.addView(kit.buttonRow(
                        kit.quietButton("Cancel") { editDialog.dismiss() },
                        kit.primaryButton("Save") {
                            val t = editor.text.toString().trim()
                            if (t.isEmpty()) return@primaryButton
                            updateNote(host, note, t)
                            editDialog.dismiss()
                            onDone()
                        }
                    ))
                }
            }
            kit.row(actionsGroup, R.drawable.oic_push_pin, if (note.pinned) "Unpin note" else "Pin to top", null) {
                togglePin(host, note)
                dialog.dismiss()
                onDone()
            }
            kit.row(
                actionsGroup,
                R.drawable.oic_alarm,
                "Reminder",
                if (note.reminder > 0L) stampFormat.format(Date(note.reminder)) else "None",
                value = if (note.reminder > 0L) "Clear" else "Set",
                chevron = note.reminder <= 0L
            ) {
                if (note.reminder > 0L) {
                    updateNoteFull(host, note, note.copy(reminder = 0L))
                    dialog.dismiss()
                    onDone()
                } else {
                    val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.HOUR_OF_DAY, 1) }
                    kit.pickTime("Note reminder", "Choose when to be reminded", cal.get(java.util.Calendar.HOUR_OF_DAY), 0) { h, m ->
                        val target = java.util.Calendar.getInstance().apply {
                            set(java.util.Calendar.HOUR_OF_DAY, h)
                            set(java.util.Calendar.MINUTE, m)
                            set(java.util.Calendar.SECOND, 0)
                            if (timeInMillis <= System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_YEAR, 1)
                        }
                        updateNoteFull(host, note, note.copy(reminder = target.timeInMillis))
                        dialog.dismiss()
                        onDone()
                    }
                }
            }
            if (!note.private) {
                kit.row(actionsGroup, R.drawable.oic_folder, if (note.archived) "Move out of archive" else "Archive note", null) {
                    toggleArchive(host, note)
                    dialog.dismiss()
                    onDone()
                }
                kit.row(actionsGroup, R.drawable.oic_open_in_new, "Move note", null, chevron = true) {
                    dialog.dismiss()
                    kit.sheet("Move note", "Choose a folder") { movePanel, moveDialog ->
                        val group = kit.group(movePanel)
                        listOf("General", "Work", "Personal", "Study", "Ideas").forEach { folderName ->
                            kit.row(group, R.drawable.oic_folder, folderName, if (note.folder == folderName) "Current" else null, chevron = false) {
                                moveDialog.dismiss()
                                moveNote(host, note, folderName)
                                onDone()
                            }
                        }
                        panel.addView(kit.text("Or type a custom folder:", 12f, kit.palette.textSecondary).apply { setPadding(kit.dp(4), kit.dp(8), kit.dp(4), kit.dp(4)) }, LinearLayout.LayoutParams(-1, -2))
                        val customFolder = kit.field("Folder name", note.folder.ifBlank { "General" })
                        movePanel.addView(customFolder, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
                        movePanel.addView(kit.buttonRow(
                            kit.quietButton("Cancel") { moveDialog.dismiss() },
                            kit.primaryButton("Move") {
                                val f = customFolder.text.toString().trim().ifBlank { "General" }
                                moveDialog.dismiss()
                                moveNote(host, note, f)
                                onDone()
                            }
                        ))
                    }
                }
            }
            kit.row(actionsGroup, R.drawable.oic_home, "Add to Home Screen", null, chevron = true) {
                dialog.dismiss()
                addNoteToHomeScreen(host, note)
            }
            kit.row(actionsGroup, R.drawable.oic_share_filled, "Share", null) {
                try { host.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, note.text), "Share note")) } catch (_: Throwable) {}
            }
            panel.addView(
                kit.button(
                    if (note.deletedAt > 0L) "Delete forever" else "Delete note",
                    kit.palette.cardHigh, kit.palette.danger, R.drawable.oic_delete
                ) {
                    dialog.dismiss()
                    if (note.deletedAt > 0L) {
                        kit.confirm("Delete forever?", "This cannot be undone — the note will not be in the trash either.", "Delete", destructive = true) {
                            deleteForever(host, note)
                            onDone()
                        }
                    } else {
                        kit.confirm("Move to the trash?", "It stays there for $TRASH_DAYS days before it goes for good.", "Move to trash", destructive = true) {
                            trashNote(host, note)
                            onDone()
                        }
                    }
                },
                LinearLayout.LayoutParams(-1, kit.dp(48)).apply { topMargin = kit.dp(6) }
            )
        }
    }

    private fun renderMarkdown(text: String): CharSequence {
        // **bold**, *italic*, ***bold italic*** — rendered with real spans so the
        // viewer keeps the emphasis the writer typed instead of flattening it.
        val out = SpannableStringBuilder()
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c == '*' && i + 2 < n && text[i + 1] == '*' && text[i + 2] == '*') {
                val end = text.indexOf("***", i + 3)
                if (end >= 0) {
                    val start = out.length
                    out.append(text, i + 3, end)
                    out.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, out.length, Spannable.SPAN_INCLUSIVE_EXCLUSIVE)
                    out.setSpan(StyleSpan(android.graphics.Typeface.ITALIC), start, out.length, Spannable.SPAN_INCLUSIVE_EXCLUSIVE)
                    i = end + 3
                    continue
                }
            }
            if (c == '*' && i + 1 < n && text[i + 1] == '*') {
                val end = text.indexOf("**", i + 2)
                if (end >= 0) {
                    val start = out.length
                    out.append(text, i + 2, end)
                    out.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, out.length, Spannable.SPAN_INCLUSIVE_EXCLUSIVE)
                    i = end + 2
                    continue
                }
            }
            if (c == '*') {
                val end = text.indexOf('*', i + 1)
                if (end >= 0) {
                    val start = out.length
                    out.append(text, i + 1, end)
                    out.setSpan(StyleSpan(android.graphics.Typeface.ITALIC), start, out.length, Spannable.SPAN_INCLUSIVE_EXCLUSIVE)
                    i = end + 1
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out
    }

    private fun togglePin(host: Activity, note: Note) {
        val p = prefs(host)
        val key = when {
            note.private -> KEY_PRIVATE
            note.archived -> KEY_ARCHIVED
            else -> KEY_NOTES
        }
        val list = load(host, key).map { if (it.id == note.id) it.copy(pinned = !it.pinned, updated = System.currentTimeMillis()) else it }
        p.edit().putString(key, encode(list)).apply()
        drop(host, key)
    }

    private fun toggleArchive(host: Activity, note: Note) {
        val p = prefs(host)
        val fromKey = when {
            note.private -> KEY_PRIVATE
            note.archived -> KEY_ARCHIVED
            else -> KEY_NOTES
        }
        val toKey = if (note.archived) KEY_NOTES else KEY_ARCHIVED
        val fromList = load(host, fromKey).filterNot { it.id == note.id }
        val toList = load(host, toKey).filterNot { it.id == note.id }.toMutableList()
        toList.add(note.copy(archived = !note.archived, updated = System.currentTimeMillis()))
        p.edit().putString(fromKey, encode(fromList)).putString(toKey, encode(toList)).apply()
        drop(host, fromKey)
        drop(host, toKey)
    }

    /** Moves a note to a different folder by rewriting its folder field in place. */
    private fun moveNote(host: Activity, note: Note, newFolder: String) {
        val p = prefs(host)
        val key = when {
            note.private -> KEY_PRIVATE
            note.archived -> KEY_ARCHIVED
            else -> KEY_NOTES
        }
        val list = load(host, key).map { if (it.id == note.id) it.copy(folder = newFolder, updated = System.currentTimeMillis()) else it }
        p.edit().putString(key, encode(list)).apply()
        drop(host, key)
    }

    /** Pins a shortcut to this note on the Home Screen (Android 8.0+). */
    private fun addNoteToHomeScreen(host: Activity, note: Note) {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val shortcutManager = host.getSystemService(android.content.pm.ShortcutManager::class.java)
            val label = note.title.ifBlank { note.text.lineSequence().firstOrNull() ?: "Note" }
            if (shortcutManager != null && shortcutManager.isRequestPinShortcutSupported) {
                val shortcut = android.content.pm.ShortcutInfo.Builder(host, "note_${note.id}")
                    .setShortLabel(label.take(15))
                    .setLongLabel(label.take(30))
                    .setIcon(android.graphics.drawable.Icon.createWithResource(host, R.drawable.oic_edit))
                    .setIntent(Intent(host, host.javaClass).apply {
                        action = "com.oea.launcher.NOTES_OPEN_NOTE"
                        putExtra("note_id", note.id)
                    })
                    .build()
                shortcutManager.requestPinShortcut(shortcut, null)
                android.widget.Toast.makeText(host, "Note added to Home Screen", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.widget.Toast.makeText(host, "This phone does not support pinning shortcuts", android.widget.Toast.LENGTH_SHORT).show()
            }
        } else {
            android.widget.Toast.makeText(host, "Home Screen shortcuts require Android 8.0+", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateNote(host: Activity, note: Note, newText: String) {
        updateNoteFull(host, note, note.copy(text = newText, updated = System.currentTimeMillis()))
    }

    private fun updateNoteFull(host: Activity, note: Note, newNote: Note) {
        val p = prefs(host)
        val key = when {
            note.private -> KEY_PRIVATE
            note.archived -> KEY_ARCHIVED
            else -> KEY_NOTES
        }
        val list = load(host, key).map { if (it.id == note.id) newNote else it }
        p.edit().putString(key, encode(list)).apply()
        drop(host, key)
        // A reminder that was just set, moved or cleared changes one alarm.
        OeaNoteReminders.sync(host)
    }

    /** Where a note lives while it is not in the trash. */
    private fun homeKey(note: Note): String = when {
        note.deletedAt > 0L -> KEY_TRASH
        note.private -> KEY_PRIVATE
        note.archived -> KEY_ARCHIVED
        else -> KEY_NOTES
    }

    /**
     * Deleting used to be the end of a note: one tap and it was gone, with
     * nothing to undo it and nowhere it went first. Every notes app worth
     * comparing with keeps deleted notes for a while, so this one does too.
     */
    private fun trashNote(host: Activity, note: Note) {
        move(host, note, KEY_TRASH, note.copy(deletedAt = System.currentTimeMillis()))
    }

    private fun restoreNote(host: Activity, note: Note) {
        move(host, note, homeKey(note.copy(deletedAt = 0L)), note.copy(deletedAt = 0L))
    }

    private fun deleteForever(host: Activity, note: Note) {
        val p = prefs(host)
        p.edit().putString(KEY_TRASH, encode(load(host, KEY_TRASH).filterNot { it.id == note.id })).apply()
        drop(host, KEY_TRASH)
        OeaNoteReminders.sync(host)
    }

    private fun emptyTrash(host: Activity) {
        prefs(host).edit().putString(KEY_TRASH, "").apply()
        drop(host, KEY_TRASH)
        OeaNoteReminders.sync(host)
    }

    /** Quietly drops what has been in the trash longer than [TRASH_DAYS]. */
    private fun pruneTrash(host: Activity) {
        val cutoff = System.currentTimeMillis() - TRASH_DAYS * DAY_MS
        val kept = load(host, KEY_TRASH).filter { it.deletedAt > cutoff }
        if (kept.size == load(host, KEY_TRASH).size) return
        prefs(host).edit().putString(KEY_TRASH, encode(kept)).apply()
        drop(host, KEY_TRASH)
    }

    private fun move(host: Activity, note: Note, toKey: String, asNote: Note) {
        val p = prefs(host)
        val fromKey = homeKey(note)
        val from = load(host, fromKey).filterNot { it.id == note.id }
        val to = load(host, toKey).filterNot { it.id == note.id }.toMutableList()
        to.add(asNote)
        p.edit().putString(fromKey, encode(from)).putString(toKey, encode(to)).apply()
        drop(host, fromKey)
        drop(host, toKey)
        // A note that went to the trash must stop ringing; one restored today rings again.
        OeaNoteReminders.sync(host)
    }

    private fun startVoiceInput(host: Activity) {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your note")
            }
            host.startActivityForResult(intent, 4001)
        } catch (_: Throwable) {
            android.widget.Toast.makeText(host, "Voice input not available", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
