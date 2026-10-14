package com.oea.launcher.miniapps

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.oea.launcher.R
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaType

/**
 * Quick notes, kept in OEA's own storage.
 *
 * A note is a line of text with an optional pin — deliberately not a rich-text
 * editor: the point is to write something down from the launcher in a couple of
 * seconds and find it again. Notes live in their own preferences file, so a note
 * that goes wrong cannot damage a layout, a theme or a setting.
 */
object OeaNotesApp {

    private const val PREFS = "oea_miniapps"
    private const val KEY_NOTES = "notes_v1"
    private const val FIELD = "\u001F"
    private const val ROW = "\u001E"

    /** One note: its text and whether it is pinned to the top. */
    internal data class Note(val text: String, val pinned: Boolean)

    internal fun decode(raw: String?): List<Note> = raw.orEmpty()
        .split(ROW)
        .filter { it.isNotBlank() }
        .mapNotNull { row ->
            val parts = row.split(FIELD)
            val text = parts.getOrNull(0)?.trim().orEmpty()
            if (text.isEmpty()) null else Note(text, parts.getOrNull(1) == "1")
        }
        .sortedWith(compareByDescending<Note> { it.pinned }.thenBy { it.text.lowercase() })

    internal fun encode(notes: List<Note>): String = notes.joinToString(ROW) { note ->
        note.text.replace(FIELD, ' ').replace(ROW, ' ') + FIELD + if (note.pinned) "1" else "0"
    }

    fun build(host: Activity, palette: OeaPalette): View {
        val kit = OeaKit(host, palette)
        val prefs = host.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val column = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }

        val read: () -> List<Note> = { decode(prefs.getString(KEY_NOTES, "")) }
        val write: (List<Note>) -> Unit = { notes -> prefs.edit().putString(KEY_NOTES, encode(notes)).apply() }
        // The body is rebuilt from the store every time something changes: one
        // source of truth, so the list can never disagree with what is saved.
        var refresh: () -> Unit = {}

        val rows: (List<Note>) -> View = { notes ->
            val group = kit.group(column)
            notes.forEach { note ->
                val row = LinearLayout(host).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(kit.dp(14), kit.dp(8), kit.dp(8), kit.dp(8))
                }
                val body = LinearLayout(host).apply {
                    orientation = LinearLayout.VERTICAL
                    isClickable = true
                    setOnClickListener {
                        kit.prompt("Edit note", if (note.pinned) "Pinned" else "Not pinned", "Note", note.text) { value ->
                            val fresh = value.trim()
                            if (fresh.isEmpty()) {
                                "A note cannot be empty — delete it instead."
                            } else {
                                // Replace in place, keeping the pin and the order.
                                write(read().map { if (it.text == note.text) Note(fresh, it.pinned) else it })
                                refresh()
                                null
                            }
                        }
                    }
                }
                body.addView(
                    kit.text(note.text, 15f, kit.palette.text).apply {
                        maxLines = 5
                        setLineSpacing(kit.dpf(2f), 1f)
                    },
                    LinearLayout.LayoutParams(-1, -2),
                )
                row.addView(body, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(
                    kit.circleIconButton(
                        if (note.pinned) R.drawable.oic_push_pin else R.drawable.oic_star,
                        if (note.pinned) "Unpin" else "Pin to the top",
                        kit.palette.cardHigh,
                        if (note.pinned) kit.palette.accent else kit.palette.textSecondary,
                    ) {
                        write(read().map { if (it.text == note.text) Note(it.text, !it.pinned) else it })
                        refresh()
                    },
                    LinearLayout.LayoutParams(kit.dp(38), kit.dp(38)),
                )
                row.addView(
                    kit.circleIconButton(R.drawable.oic_delete, "Delete note", kit.palette.cardHigh, kit.palette.danger) {
                        kit.confirm("Delete this note?", "It is removed from this phone.", "Delete", destructive = true) {
                            write(read().filterNot { it.text == note.text })
                            refresh()
                        }
                    },
                    LinearLayout.LayoutParams(kit.dp(38), kit.dp(38)).apply { leftMargin = kit.dp(6) },
                )
                group.addView(row)
            }
            group
        }

        refresh = {
            column.removeAllViews()
            val notes = read()
            val actions = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(
                kit.primaryButton("New note", R.drawable.oic_add) {
                    kit.prompt("New note", "Saved on this phone only.", "Write it here", "") { value ->
                        val text = value.trim()
                        if (text.isEmpty()) "Write something first." else {
                            write(read() + Note(text, pinned = false))
                            refresh()
                            null
                        }
                    }
                },
                LinearLayout.LayoutParams(-2, kit.dp(46)),
            )
            if (notes.size > 1) {
                actions.addView(kit.quietButton("Sort A–Z", R.drawable.oic_sort_by_alpha) {
                    write(notes.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.text.lowercase() }))
                    refresh()
                }, LinearLayout.LayoutParams(-2, kit.dp(46)).apply { leftMargin = kit.dp(8) })
            }
            column.addView(actions, LinearLayout.LayoutParams(-1, -2))

            if (notes.isEmpty()) {
                kit.note(
                    column,
                    "No notes yet. A note is a line of text — an address, a plate number, a shopping list — and it is here the next time you open the launcher.",
                )
                return@refresh
            }
            val pinned = notes.filter { it.pinned }
            if (pinned.isNotEmpty()) {
                kit.sectionTitle(column, "Pinned")
                rows(pinned)
            }
            val rest = notes.filterNot { it.pinned }
            if (rest.isNotEmpty()) {
                kit.sectionTitle(column, if (pinned.isEmpty()) "Notes" else "Everything else")
                rows(rest)
            }
        }
        refresh()
        return column
    }
}
