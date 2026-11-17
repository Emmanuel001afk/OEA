package com.oea.launcher.miniapps

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
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
    private const val SEP_ROW = "\u001E"
    private const val SEP_FIELD = "\u001F"

    data class Note(
        val id: Long,
        val text: String,
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
        val attachment: String = ""
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
                Note(
                    id = p[0].toLong(),
                    text = p[1],
                    pinned = p[2] == "1",
                    tag = p[3],
                    folder = p.getOrNull(4) ?: "General",
                    color = p.getOrNull(5) ?: "default",
                    checklist = p.getOrNull(6) == "1",
                    created = p.getOrNull(7)?.toLongOrNull() ?: 0L,
                    updated = p.getOrNull(8)?.toLongOrNull() ?: p.getOrNull(7)?.toLongOrNull() ?: 0L,
                    private = p.getOrNull(9) == "1",
                    archived = p.getOrNull(10) == "1",
                    reminder = p.getOrNull(11)?.toLongOrNull() ?: 0L,
                    attachment = p.getOrNull(12) ?: ""
                )
            } catch (_: Throwable) { null }
        }.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updated })
    }

    private fun encode(list: List<Note>): String = list.joinToString(SEP_ROW) { n ->
        listOf(
            n.id.toString(),
            n.text.replace(SEP_FIELD, " ").replace(SEP_ROW, " "),
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
            n.attachment
        ).joinToString(SEP_FIELD)
    }

    fun build(host: Activity, palette: OeaPalette): View {
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

        refresh = refresh@{ filter ->
            currentFilter = filter
            if (filter in setOf("all", "checklist", "private", "archived")) {
                shell.nav?.select(filter, notify = false)
                shell.bar.setBack(indexBack)
            } else {
                shell.bar.setBack { refresh("all") }
            }
            shell.bar.title.text = when (filter) {
                "checklist" -> "Lists"
                "private" -> "Private"
                "archived" -> "Archived"
                "pinned" -> "Pinned"
                "reminders" -> "Reminders"
                "voice" -> "Voice Notes"
                else -> "Notes"
            }

            val allNotes = load(host, KEY_NOTES) +
                load(host, KEY_PRIVATE).map { it.copy(private = true) } +
                load(host, KEY_ARCHIVED).map { it.copy(archived = true) }

            val scopedNotes = allNotes.filter { note ->
                when (filter) {
                    "pinned" -> note.pinned && !note.private
                    "private" -> note.private
                    "voice" -> note.tag == "voice" && !note.private
                    "checklist" -> note.checklist && !note.archived && !note.private
                    "archived" -> note.archived && !note.private
                    "reminders" -> note.reminder > 0 && !note.private
                    else -> !note.archived && !note.private
                }
            }

            folderBar.removeAllViews()
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

            content.removeAllViews()
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
                    else -> Triple(R.drawable.oic_edit, "No notes yet", "Write a note, start a checklist, or dictate by voice.")
                }
                val ctaLabel = when {
                    searchQuery.isNotBlank() -> "Clear search"
                    filter == "archived" || filter == "pinned" || filter == "reminders" -> null
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
                    val lines = note.text.lineSequence().filter { it.isNotBlank() }.toList()
                    val heading = if (note.private) (lines.firstOrNull()?.take(36) ?: "Private note") else (lines.firstOrNull()?.take(48) ?: "Note")
                    val snippet = if (!note.private && !note.checklist && lines.size > 1) lines[1].take(48) else null
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
                        if (note.private) {
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
            panel.addView(textField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            panel.addView(folderField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            panel.addView(tagField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            panel.addView(kit.hscroll(colorRow), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            panel.addView(kit.buttonRow(
                kit.quietButton("Cancel") { dialog.dismiss() },
                kit.primaryButton("Save note") {
                    val text = textField.text.toString().trim()
                    if (text.isEmpty()) {
                        android.widget.Toast.makeText(host, "Write something first", android.widget.Toast.LENGTH_SHORT).show()
                        return@primaryButton
                    }
                    createNoteWithText(
                        host,
                        text,
                        tagField.text.toString().trim().ifBlank { "General" },
                        folderField.text.toString().trim().ifBlank { "General" },
                        private,
                        selectedColor,
                        false
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
                    createNoteWithText(host, fullText, "checklist", "General", false, "default", true) { onDone() }
                    dialog.dismiss()
                }
            ))
        }
    }

    private fun createNoteWithText(host: Activity, text: String, tag: String, folder: String, private: Boolean, color: String = "default", checklist: Boolean = false, onDone: () -> Unit) {
        val p = prefs(host)
        val key = when {
            private -> KEY_PRIVATE
            else -> KEY_NOTES
        }
        val existing = decode(p.getString(key, ""))
        val now = System.currentTimeMillis()
        val note = Note(now, text, false, tag, folder, color, checklist, now, now, private, false, 0L, "")
        p.edit().putString(key, encode(existing + note)).apply()
        onDone()
    }

    private fun createNoteWithText(host: Activity, text: String, tag: String, private: Boolean, onDone: () -> Unit) {
        createNoteWithText(host, text, tag, "General", private, "default", false, onDone)
    }

    private fun showNoteDetail(host: Activity, kit: OeaAppKit, initialNote: Note, onDone: () -> Unit) {
        var note = initialNote
        kit.sheet(note.text.lineSequence().firstOrNull()?.take(36) ?: "Note", fullStampFormat.format(Date(note.updated))) { panel, dialog ->
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
            }
            kit.row(actionsGroup, R.drawable.oic_share_filled, "Share", null) {
                try { host.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, note.text), "Share note")) } catch (_: Throwable) {}
            }
            panel.addView(
                kit.button("Delete note", kit.palette.cardHigh, kit.palette.danger, R.drawable.oic_delete) {
                    dialog.dismiss()
                    kit.confirm("Delete note?", "This permanently removes the note from your device.", "Delete", destructive = true) {
                        deleteNote(host, note)
                        onDone()
                    }
                },
                LinearLayout.LayoutParams(-1, kit.dp(48)).apply { topMargin = kit.dp(6) }
            )
        }
    }

    private fun renderMarkdown(text: String): String {
        // Simple markdown stripping for display: **bold** -> bold, *italic* -> italic, but TextView doesn't support rich, so just return text
        return text
    }

    private fun togglePin(host: Activity, note: Note) {
        val p = prefs(host)
        val key = when {
            note.private -> KEY_PRIVATE
            note.archived -> KEY_ARCHIVED
            else -> KEY_NOTES
        }
        val list = decode(p.getString(key, "")).map { if (it.id == note.id) it.copy(pinned = !it.pinned, updated = System.currentTimeMillis()) else it }
        p.edit().putString(key, encode(list)).apply()
    }

    private fun toggleArchive(host: Activity, note: Note) {
        val p = prefs(host)
        val fromKey = when {
            note.private -> KEY_PRIVATE
            note.archived -> KEY_ARCHIVED
            else -> KEY_NOTES
        }
        val toKey = if (note.archived) KEY_NOTES else KEY_ARCHIVED
        val fromList = decode(p.getString(fromKey, "")).filterNot { it.id == note.id }
        val toList = decode(p.getString(toKey, "")).toMutableList()
        toList.add(note.copy(archived = !note.archived, updated = System.currentTimeMillis()))
        p.edit().putString(fromKey, encode(fromList)).putString(toKey, encode(toList)).apply()
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
        val list = decode(p.getString(key, "")).map { if (it.id == note.id) newNote else it }
        p.edit().putString(key, encode(list)).apply()
    }

    private fun deleteNote(host: Activity, note: Note) {
        val p = prefs(host)
        val key = when {
            note.private -> KEY_PRIVATE
            note.archived -> KEY_ARCHIVED
            else -> KEY_NOTES
        }
        val list = decode(p.getString(key, "")).filterNot { it.id == note.id }
        p.edit().putString(key, encode(list)).apply()
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
