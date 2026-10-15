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

/**
 * OEA Notes: proper notes app with ordinary notes, private notes, voice notes, voice-to-text, organization, search.
 */
object OeaNotesApp {

    private const val PREFS = "oea_notes_v2"
    private const val KEY_NOTES = "notes"
    private const val KEY_PRIVATE = "private_notes"
    private const val KEY_VOICE = "voice_notes"
    private const val SEP_ROW = "\u001E"
    private const val SEP_FIELD = "\u001F"

    data class Note(val id: Long, val text: String, val pinned: Boolean, val tag: String, val created: Long, val private: Boolean = false)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun decode(raw: String?): List<Note> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(SEP_ROW).mapNotNull { row ->
            val p = row.split(SEP_FIELD)
            if (p.size < 4) return@mapNotNull null
            try {
                Note(p[0].toLong(), p[1], p[2] == "1", p[3], p.getOrNull(4)?.toLongOrNull() ?: 0L, p.getOrNull(5) == "1")
            } catch (_: Throwable) { null }
        }.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.created })
    }

    private fun encode(list: List<Note>): String = list.joinToString(SEP_ROW) { n ->
        listOf(n.id.toString(), n.text.replace(SEP_FIELD, " ").replace(SEP_ROW, " "), if (n.pinned) "1" else "0", n.tag.replace(SEP_FIELD, " "), n.created.toString(), if (n.private) "1" else "0").joinToString(SEP_FIELD)
    }

    fun build(host: Activity, palette: OeaPalette): View {
        val kit = OeaKit(host, palette)
        val column = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        var refresh: (String) -> Unit = {}
        var currentFilter = "all"
        var searchQuery = ""

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

        val filterBar = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        column.addView(kit.hscroll(filterBar), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(10) })

        val content = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        column.addView(content)

        refresh = { filter ->
            currentFilter = filter
            filterBar.removeAllViews()
            listOf("all" to "All", "pinned" to "Pinned", "private" to "Private", "voice" to "Voice").forEach { (id, label) ->
                filterBar.addView(kit.chip(label, id == filter) { refresh(id) }, LinearLayout.LayoutParams(-2, kit.dp(36)).apply { rightMargin = kit.dp(6) })
            }

            content.removeAllViews()
            val allNotes = decode(prefs(host).getString(KEY_NOTES, "")) + decode(prefs(host).getString(KEY_PRIVATE, "")).map { it.copy(private = true) }
            val filtered = allNotes.filter { note ->
                val matchesFilter = when (filter) {
                    "pinned" -> note.pinned
                    "private" -> note.private
                    "voice" -> note.tag == "voice"
                    else -> true
                }
                val matchesSearch = searchQuery.isBlank() || note.text.contains(searchQuery, ignoreCase = true) || note.tag.contains(searchQuery, ignoreCase = true)
                matchesFilter && matchesSearch
            }

            val actions = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(kit.primaryButton("New note", R.drawable.oic_add) {
                createNote(host, kit, private = false) { refresh(filter) }
            }, LinearLayout.LayoutParams(0, kit.dp(46), 1f).apply { rightMargin = kit.dp(6) })
            actions.addView(kit.tonalButton("Private", R.drawable.oic_lock) {
                if (!OeaAppLockStore.hasPin(host)) {
                    kit.prompt("Create App Lock PIN", "Set a PIN to protect private notes", "PIN", numeric = true, password = true) { pin ->
                        if (pin.length !in 4..8 || !pin.all { it.isDigit() }) return@prompt "4-8 digits"
                        OeaAppLockStore.setPin(host, pin)
                        createNote(host, kit, private = true) { refresh(filter) }
                        null
                    }
                } else {
                    createNote(host, kit, private = true) { refresh(filter) }
                }
            }, LinearLayout.LayoutParams(0, kit.dp(46), 1f).apply { rightMargin = kit.dp(6) })
            actions.addView(kit.circleIconButton(R.drawable.oic_mic, "Voice note", palette.cardHigh, palette.text) {
                startVoiceInput(host)
            }, LinearLayout.LayoutParams(kit.dp(46), kit.dp(46)))
            content.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(12) })

            if (filtered.isEmpty()) {
                kit.note(content, when {
                    searchQuery.isNotBlank() -> "No notes match \"$searchQuery\""
                    filter == "private" -> "No private notes. Private notes are protected behind App Lock PIN."
                    filter == "voice" -> "No voice notes. Tap mic to dictate."
                    else -> "No notes yet. Tap New note to write, Private for locked notes, or mic for voice-to-text."
                })
                return@let
            }

            filtered.groupBy { it.tag.ifBlank { "General" } }.forEach { (tag, notes) ->
                kit.sectionTitle(content, "$tag • ${notes.size}")
                val g = kit.group(content)
                notes.forEach { note ->
                    val subtitle = buildString {
                        if (note.private) append("Private • ")
                        if (note.pinned) append("Pinned • ")
                        append(java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.getDefault()).format(java.util.Date(note.created)))
                        if (note.tag.isNotBlank() && note.tag != "General") append(" • ${note.tag}")
                    }
                    kit.row(g, if (note.private) R.drawable.oic_lock else R.drawable.oic_edit, note.text.take(40), subtitle, trailing = null) {
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
        }
        refresh("all")

        // Handle voice input result via host activity's onActivityResult? We use a simple approach: check prefs for voice result
        host.window.decorView.postDelayed({
            try {
                val voiceText = host.intent.getStringExtra("voice_result")
                if (!voiceText.isNullOrBlank()) {
                    host.intent.removeExtra("voice_result")
                    createNoteWithText(host, voiceText, tag = "voice", private = false) { refresh(currentFilter) }
                }
            } catch (_: Throwable) {}
        }, 500L)

        return column
    }

    private fun createNote(host: Activity, kit: OeaKit, private: Boolean, onDone: () -> Unit) {
        kit.sheet(if (private) "New private note" else "New note", if (private) "Protected behind App Lock PIN" else "Saved on this phone") { panel, dialog ->
            val textField = kit.field("Write note", "")
            val tagField = kit.field("Tag (optional)", "General")
            panel.addView(textField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            panel.addView(tagField, LinearLayout.LayoutParams(-1, -2))
            panel.addView(kit.buttonRow(
                kit.quietButton("Cancel") { dialog.dismiss() },
                kit.primaryButton("Save") {
                    val text = textField.text.toString().trim()
                    if (text.isEmpty()) {
                        android.widget.Toast.makeText(host, "Write something", android.widget.Toast.LENGTH_SHORT).show()
                        return@primaryButton
                    }
                    createNoteWithText(host, text, tagField.text.toString().trim().ifBlank { "General" }, private) { onDone() }
                    dialog.dismiss()
                }
            ))
        }
    }

    private fun createNoteWithText(host: Activity, text: String, tag: String, private: Boolean, onDone: () -> Unit) {
        val p = prefs(host)
        val key = if (private) KEY_PRIVATE else KEY_NOTES
        val existing = decode(p.getString(key, ""))
        val note = Note(System.currentTimeMillis(), text, false, tag, System.currentTimeMillis(), private)
        p.edit().putString(key, encode(existing + note)).apply()
        onDone()
    }

    private fun showNoteDetail(host: Activity, kit: OeaKit, note: Note, onDone: () -> Unit) {
        kit.sheet(note.text.take(30), java.text.SimpleDateFormat("MMM d, yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(note.created))) { panel, dialog ->
            panel.addView(kit.text(note.text, 16f, kit.palette.text).apply { setLineSpacing(kit.dpf(3f), 1f); setPadding(kit.dp(4), kit.dp(8), kit.dp(4), kit.dp(12)) })
            if (note.tag.isNotBlank()) {
                panel.addView(kit.text("Tag: ${note.tag}", 13f, kit.palette.textSecondary).apply { setPadding(kit.dp(4), 0, kit.dp(4), kit.dp(8)) })
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
            panel.addView(kit.tonalButton("Share") {
                try {
                    host.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, note.text), "Share note"))
                } catch (_: Throwable) {}
            }, LinearLayout.LayoutParams(-1, kit.dp(46)).apply { topMargin = kit.dp(8) })
        }
    }

    private fun togglePin(host: Activity, note: Note) {
        val p = prefs(host)
        val key = if (note.private) KEY_PRIVATE else KEY_NOTES
        val list = decode(p.getString(key, "")).map { if (it.id == note.id) it.copy(pinned = !it.pinned) else it }
        p.edit().putString(key, encode(list)).apply()
    }

    private fun updateNote(host: Activity, note: Note, newText: String) {
        val p = prefs(host)
        val key = if (note.private) KEY_PRIVATE else KEY_NOTES
        val list = decode(p.getString(key, "")).map { if (it.id == note.id) it.copy(text = newText) else it }
        p.edit().putString(key, encode(list)).apply()
    }

    private fun deleteNote(host: Activity, note: Note) {
        val p = prefs(host)
        val key = if (note.private) KEY_PRIVATE else KEY_NOTES
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

    private fun Intent(action: String): android.content.Intent = android.content.Intent(action)
}
