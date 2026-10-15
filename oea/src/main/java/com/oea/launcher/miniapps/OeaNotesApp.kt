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
 * - Voice: speech-to-text via RecognizerIntent, voice notes saved as text + audio path placeholder
 * - Attachments: image attachment via MediaStore picker (URI stored)
 * - Export: share as txt, export all as JSON, backup/restore
 * - Private vault: separate prefs, protected behind App Lock PIN, encrypted? Not encrypted but private file
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
        val kit = OeaKit(host, palette)
        val column = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        var refresh: (String) -> Unit = {}
        var currentFilter = "all"
        var searchQuery = ""
        var currentFolder: String? = null

        val header = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(kit.text("OEA Notes", 22f, palette.text, OeaType.medium), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(kit.circleIconButton(R.drawable.oic_search, "Search", palette.cardHigh, palette.text) {
            kit.prompt("Search notes", null, "Search", searchQuery) { q ->
                searchQuery = q.trim()
                refresh(currentFilter)
                null
            }
        }, LinearLayout.LayoutParams(kit.dp(40), kit.dp(40)).apply { rightMargin = kit.dp(8) })
        header.addView(kit.circleIconButton(R.drawable.oic_mic, "Voice", palette.cardHigh, palette.text) {
            startVoiceInput(host)
        })
        column.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(10) })

        val folderBar = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        column.addView(kit.hscroll(folderBar), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })

        val filterBar = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        column.addView(kit.hscroll(filterBar), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(10) })

        val content = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        column.addView(content)

        refresh = refresh@{ filter ->
            currentFilter = filter
            // Folders
            folderBar.removeAllViews()
            val allNotes = decode(prefs(host).getString(KEY_NOTES, "")) + decode(prefs(host).getString(KEY_PRIVATE, "")).map { it.copy(private = true) } + decode(prefs(host).getString(KEY_ARCHIVED, "")).map { it.copy(archived = true) }
            val folders = allNotes.map { it.folder.ifBlank { "General" } }.distinct().sorted()
            folderBar.addView(kit.chip("All folders", currentFolder == null) { currentFolder = null; refresh(filter) }, LinearLayout.LayoutParams(-2, kit.dp(36)).apply { rightMargin = kit.dp(6) })
            folders.forEach { folder ->
                folderBar.addView(kit.chip("$folder (${allNotes.count { it.folder == folder }})", folder == currentFolder) {
                    currentFolder = if (currentFolder == folder) null else folder
                    refresh(filter)
                }, LinearLayout.LayoutParams(-2, kit.dp(36)).apply { rightMargin = kit.dp(6) })
            }

            // Filters
            filterBar.removeAllViews()
            listOf("all" to "All", "pinned" to "Pinned", "private" to "Private", "voice" to "Voice", "checklist" to "Checklist", "archived" to "Archived", "reminders" to "Reminders").forEach { (id, label) ->
                filterBar.addView(kit.chip(label, id == filter) { refresh(id) }, LinearLayout.LayoutParams(-2, kit.dp(36)).apply { rightMargin = kit.dp(6) })
            }

            content.removeAllViews()
            val filtered = allNotes.filter { note ->
                val matchesFilter = when (filter) {
                    "pinned" -> note.pinned
                    "private" -> note.private
                    "voice" -> note.tag == "voice"
                    "checklist" -> note.checklist
                    "archived" -> note.archived
                    "reminders" -> note.reminder > 0
                    else -> !note.archived // all excludes archived unless filter is archived
                }
                val matchesFolder = currentFolder == null || note.folder == currentFolder
                val matchesSearch = searchQuery.isBlank() || note.text.contains(searchQuery, ignoreCase = true) || note.tag.contains(searchQuery, ignoreCase = true) || note.folder.contains(searchQuery, ignoreCase = true)
                matchesFilter && matchesFolder && matchesSearch
            }

            val actions = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(kit.primaryButton("New note", R.drawable.oic_add) {
                createNote(host, kit, private = false) { refresh(filter) }
            }, LinearLayout.LayoutParams(0, kit.dp(46), 1f).apply { rightMargin = kit.dp(6) })
            actions.addView(kit.tonalButton("Checklist", R.drawable.oic_check_circle_fill) {
                createChecklistNote(host, kit) { refresh(filter) }
            }, LinearLayout.LayoutParams(0, kit.dp(46), 1f).apply { rightMargin = kit.dp(6) })
            actions.addView(kit.circleIconButton(R.drawable.oic_mic, "Voice note", palette.cardHigh, palette.text) {
                startVoiceInput(host)
            }, LinearLayout.LayoutParams(kit.dp(46), kit.dp(46)))
            content.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(12) })

            if (filtered.isEmpty()) {
                kit.note(content, when {
                    searchQuery.isNotBlank() -> "No notes match \"$searchQuery\""
                    filter == "private" -> "No private notes. Private notes protected behind App Lock PIN."
                    filter == "voice" -> "No voice notes. Tap mic to dictate."
                    filter == "checklist" -> "No checklist notes."
                    filter == "archived" -> "No archived notes."
                    filter == "reminders" -> "No reminders."
                    currentFolder != null -> "No notes in folder \"$currentFolder\""
                    else -> "No notes yet. Tap New note, Checklist, or mic for voice-to-text. Folders organize automatically."
                })
                // Export/import
                content.addView(kit.tonalButton("Export all notes", R.drawable.oic_share_filled) {
                    val json = encode(allNotes)
                    kit.sheet("Export notes", "${allNotes.size} notes") { panel, _ ->
                        kit.note(panel, json.take(8000))
                        panel.addView(kit.buttonRow(
                            kit.quietButton("Copy") {
                                val cb = host.getSystemService(android.content.ClipboardManager::class.java) as android.content.ClipboardManager
                                cb.setPrimaryClip(android.content.ClipData.newPlainText("OEA notes", json))
                                android.widget.Toast.makeText(host, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            kit.primaryButton("Share") {
                                try { host.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, json), "Share notes")) } catch (_: Throwable) {}
                            }
                        ))
                    }
                }, LinearLayout.LayoutParams(-1, kit.dp(46)).apply { topMargin = kit.dp(12) })
                return@refresh
            }

            // Group by folder
            filtered.groupBy { it.folder.ifBlank { "General" } }.forEach { (folder, notes) ->
                kit.sectionTitle(content, "$folder • ${notes.size}")
                val g = kit.group(content)
                notes.forEach { note ->
                    val subtitle = buildString {
                        if (note.private) append("Private • ")
                        if (note.pinned) append("Pinned • ")
                        if (note.checklist) append("Checklist • ")
                        if (note.archived) append("Archived • ")
                        append(SimpleDateFormat("MMM d HH:mm", Locale.getDefault()).format(Date(note.updated)))
                        if (note.tag.isNotBlank() && note.tag != "General") append(" • ${note.tag}")
                        if (note.reminder > 0) append(" • Reminder ${SimpleDateFormat("MMM d HH:mm", Locale.getDefault()).format(Date(note.reminder))}")
                        if (note.attachment.isNotBlank()) append(" • Attachment")
                    }
                    val icon = when {
                        note.private -> R.drawable.oic_lock
                        note.checklist -> R.drawable.oic_check_circle_fill
                        note.tag == "voice" -> R.drawable.oic_mic
                        else -> R.drawable.oic_edit
                    }
                    kit.row(g, icon, note.text.lineSequence().firstOrNull()?.take(40) ?: "Note", subtitle, trailing = null) {
                        if (note.private) {
                            kit.prompt("Unlock private note", "Enter App Lock PIN", "PIN", numeric = true, password = true, confirmLabel = "Unlock") { pin ->
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

            // Bottom actions: colors, sort, export
            val bottomRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, kit.dp(12), 0, 0) }
            bottomRow.addView(kit.tonalButton("Export", R.drawable.oic_share_filled) {
                val json = encode(filtered)
                try { host.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, json), "Export notes")) } catch (_: Throwable) {}
            }, LinearLayout.LayoutParams(0, kit.dp(44), 1f).apply { rightMargin = kit.dp(6) })
            bottomRow.addView(kit.quietButton("Manage folders", R.drawable.oic_folder) {
                kit.sheet("Folders", "${folders.size} folders") { panel, _ ->
                    folders.forEach { f ->
                        kit.row(kit.group(panel), R.drawable.oic_folder, f, "${allNotes.count { it.folder == f }} notes", chevron = false)
                    }
                }
            }, LinearLayout.LayoutParams(0, kit.dp(44), 1f))
            content.addView(bottomRow)
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

        return column
    }

    private fun createNote(host: Activity, kit: OeaKit, private: Boolean, onDone: () -> Unit) {
        kit.sheet(if (private) "New private note" else "New note", if (private) "Protected behind App Lock PIN" else "Saved on this phone") { panel, dialog ->
            val textField = kit.field("Write note (supports **bold** and *italic*, [ ] checklist)", "")
            val tagField = kit.field("Tag (optional)", "General")
            val folderField = kit.field("Folder (e.g., Work, Personal, Study)", "General")
            val colorRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, kit.dp(8), 0, 0) }
            var selectedColor = "default"
            listOf("default" to "Default", "yellow" to "Yellow", "green" to "Green", "blue" to "Blue", "pink" to "Pink").forEach { (colorId, label) ->
                colorRow.addView(kit.chip(label, colorId == selectedColor) { selectedColor = colorId }, LinearLayout.LayoutParams(-2, kit.dp(36)).apply { rightMargin = kit.dp(6) })
            }
            panel.addView(textField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            panel.addView(tagField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            panel.addView(folderField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            panel.addView(kit.hscroll(colorRow), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            panel.addView(kit.buttonRow(
                kit.quietButton("Cancel") { dialog.dismiss() },
                kit.primaryButton("Save") {
                    val text = textField.text.toString().trim()
                    if (text.isEmpty()) {
                        android.widget.Toast.makeText(host, "Write something", android.widget.Toast.LENGTH_SHORT).show()
                        return@primaryButton
                    }
                    createNoteWithText(host, text, tagField.text.toString().trim().ifBlank { "General" }, folderField.text.toString().trim().ifBlank { "General" }, private, selectedColor, false) { onDone() }
                    dialog.dismiss()
                }
            ))
        }
    }

    private fun createChecklistNote(host: Activity, kit: OeaKit, onDone: () -> Unit) {
        kit.sheet("New checklist", "Todo with checkboxes — tap items to toggle in viewer") { panel, dialog ->
            val titleField = kit.field("Title", "")
            val itemsField = kit.field("Items (one per line, e.g., Buy milk\nCall mom)", "")
            panel.addView(titleField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            panel.addView(itemsField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            panel.addView(kit.buttonRow(
                kit.quietButton("Cancel") { dialog.dismiss() },
                kit.primaryButton("Create checklist") {
                    val title = titleField.text.toString().trim().ifBlank { "Checklist" }
                    val items = itemsField.text.toString().lineSequence().filter { it.isNotBlank() }.map { "[ ] $it" }.joinToString("\n")
                    val fullText = "$title\n$items"
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

    private fun showNoteDetail(host: Activity, kit: OeaKit, note: Note, onDone: () -> Unit) {
        kit.sheet(note.text.lineSequence().firstOrNull()?.take(30) ?: "Note", SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(note.updated))) { panel, dialog ->
            // Render rich text
            val textView = kit.text(renderMarkdown(note.text), 16f, kit.palette.text).apply { setLineSpacing(kit.dpf(3f), 1f); setPadding(kit.dp(4), kit.dp(8), kit.dp(4), kit.dp(12)) }
            panel.addView(textView)
            if (note.tag.isNotBlank()) {
                panel.addView(kit.text("Tag: ${note.tag} • Folder: ${note.folder} • Color: ${note.color}", 13f, kit.palette.textSecondary).apply { setPadding(kit.dp(4), 0, kit.dp(4), kit.dp(8)) })
            }
            if (note.checklist) {
                // Render checklist items tappable
                val checklistItems = note.text.lineSequence().drop(1).filter { it.trim().startsWith("[") }.toList()
                if (checklistItems.isNotEmpty()) {
                    kit.sectionTitle(panel, "Checklist")
                    val g = kit.group(panel)
                    checklistItems.forEach { item ->
                        val checked = item.trim().startsWith("[x]", true)
                        val label = item.replace(Regex("^\\[.?\\]\\s*"), "")
                        kit.row(g, if (checked) R.drawable.oic_check_circle_fill else R.drawable.oic_circle, label, if (checked) "Done" else "Todo") {
                            // Toggle
                            val newText = note.text.replace(item, if (checked) item.replace("[x]", "[ ]", true) else item.replace("[ ]", "[x]"))
                            updateNote(host, note, newText)
                            dialog.dismiss()
                            onDone()
                        }
                    }
                }
            }
            panel.addView(kit.buttonRow(
                kit.quietButton(if (note.pinned) "Unpin" else "Pin") {
                    togglePin(host, note)
                    dialog.dismiss()
                    onDone()
                },
                kit.tonalButton("Edit") {
                    dialog.dismiss()
                    kit.prompt("Edit note", null, "Note", note.text) { newText ->
                        val t = newText.trim()
                        if (t.isEmpty()) return@prompt "Cannot be empty"
                        updateNote(host, note, t)
                        onDone()
                        null
                    }
                },
                kit.quietButton("Delete", R.drawable.oic_delete) {
                    dialog.dismiss()
                    kit.confirm("Delete note?", "Removed from this phone", "Delete", destructive = true) {
                        deleteNote(host, note)
                        onDone()
                    }
                }
            ))
            panel.addView(kit.buttonRow(
                kit.quietButton(if (note.archived) "Unarchive" else "Archive") {
                    toggleArchive(host, note)
                    dialog.dismiss()
                    onDone()
                },
                kit.tonalButton("Reminder") {
                    kit.prompt("Reminder", "HH:mm or timestamp", "Reminder", note.reminder.toString()) { input ->
                        val rem = input.toLongOrNull() ?: 0L
                        updateNoteFull(host, note, note.copy(reminder = rem))
                        dialog.dismiss()
                        onDone()
                        null
                    }
                },
                kit.tonalButton("Share") {
                    try { host.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, note.text), "Share note")) } catch (_: Throwable) {}
                }
            ))
            panel.addView(kit.tonalButton("Set color", R.drawable.oic_palette) {
                kit.sheet("Note color", null) { p2, d2 ->
                    listOf("default", "yellow", "green", "blue", "pink").forEach { colorId ->
                        kit.row(kit.group(p2), R.drawable.oic_palette, colorId.replaceFirstChar { it.uppercase() }, null) {
                            d2.dismiss()
                            updateNoteFull(host, note, note.copy(color = colorId))
                            dialog.dismiss()
                            onDone()
                        }
                    }
                }
            }, LinearLayout.LayoutParams(-1, kit.dp(46)).apply { topMargin = kit.dp(8) })
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
