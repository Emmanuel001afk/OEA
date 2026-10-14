package com.oea.launcher.miniapps

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaType
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * A calculator that keeps a tape.
 *
 * It evaluates as it goes rather than as a stack machine: the user types an
 * expression (brackets, percent, unary minus included), presses "=", and the
 * result is appended to the tape below so a long sum of small numbers is one
 * screen instead of a memory test. The parser is OEA's own — no dependency, and
 * a malformed expression says where it stopped rather than showing "Error".
 */
object OeaCalculatorApp {

    private const val KEY_TAPE = "calculator_tape"
    private const val TAPE_LIMIT = 24

    fun build(host: Activity, palette: OeaPalette): View {
        val kit = OeaKit(host, palette)
        val prefs = prefs(host)
        var expression = ""
        var result = ""

        val column = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        val display = kit.text("", 34f, palette.text, OeaType.light).apply {
            gravity = Gravity.END
            setPadding(kit.dp(18), kit.dp(16), kit.dp(18), kit.dp(4))
            setLineSpacing(kit.dpf(2f), 1f)
        }
        val hint = kit.text("", 15f, palette.textTertiary).apply {
            gravity = Gravity.END
            setPadding(kit.dp(18), 0, kit.dp(18), kit.dp(10))
            maxLines = 2
        }
        val card = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = kit.rounded(palette.card, 22, palette.stroke, 1)
            addView(display, LinearLayout.LayoutParams(-1, -2))
            addView(hint, LinearLayout.LayoutParams(-1, -2))
        }
        column.addView(card, LinearLayout.LayoutParams(-1, -2))
        if (!result.isEmpty()) Unit

        val tapeBox = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }

        fun show() {
            display.text = expression.ifEmpty { "0" }
            hint.text = if (result.isEmpty()) "Brackets, %, and a unary minus are all understood." else "= $result"
        }

        fun press(key: String) {
            when (key) {
                "C" -> expression = ""
                "⌫" -> if (expression.isNotEmpty()) expression = expression.dropLast(1)
                "=" -> {
                    val value = Evaluator.evaluate(expression)
                    result = when {
                        expression.isBlank() -> ""
                        value == null -> "not a complete expression yet"
                        else -> format(value)
                    }
                    if (value != null) {
                        prefs.edit().putString(KEY_TAPE, (( "$expression = ${format(value)}" ) + "\n" + prefs.getString(KEY_TAPE, "").orEmpty()).trim().lineSequence().take(TAPE_LIMIT).joinToString("\n")).apply()
                    }
                }
                else -> {
                    // Keep the expression from growing without bound on one key.
                    if (expression.length < 80) expression += key
                }
            }
            show()
            renderTape(host, kit, prefs, tapeBox)
        }

        val rows = listOf(
            listOf("C", "(", ")", "÷"),
            listOf("7", "8", "9", "×"),
            listOf("4", "5", "6", "−"),
            listOf("1", "2", "3", "+"),
            listOf("0", ".", "%", "="),
        )
        rows.forEach { keys ->
            val row = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, kit.dp(5), 0, 0)
            }
            keys.forEachIndexed { index, key ->
                row.addView(keyButton(host, kit, key) { press(key) }, LinearLayout.LayoutParams(0, kit.dp(58), 1f).apply {
                    if (index > 0) leftMargin = kit.dp(6)
                })
            }
            column.addView(row, LinearLayout.LayoutParams(-1, -2))
        }

        val actions = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, kit.dp(14), 0, 0)
        }
        actions.addView(kit.tonalButton("Backspace", com.oea.launcher.R.drawable.oic_arrow_back) { press("⌫") }, LinearLayout.LayoutParams(-2, kit.dp(46)))
        actions.addView(kit.quietButton("Clear tape", com.oea.launcher.R.drawable.oic_delete) {
            prefs.edit().remove(KEY_TAPE).apply()
            renderTape(host, kit, prefs, tapeBox)
        }, LinearLayout.LayoutParams(-2, kit.dp(46)).apply { leftMargin = kit.dp(8) })
        column.addView(actions, LinearLayout.LayoutParams(-1, -2))

        kit.sectionTitle(column, "Tape")
        column.addView(tapeBox, LinearLayout.LayoutParams(-1, -2))
        renderTape(host, kit, prefs, tapeBox)
        show()
        return column
    }

    private fun keyButton(host: Activity, kit: OeaKit, key: String, onClick: () -> Unit): View {
        val isEquals = key == "="
        val isOperator = key in setOf("÷", "×", "−", "+") || key == "C" || key == "(" || key == ")" || key == "%"
        val text = TextView(host).apply {
            this.text = key
            gravity = Gravity.CENTER
            textSize = if (key.length > 1) 15f else 21f
            typeface = if (isEquals) OeaType.medium else OeaType.regular
            setTextColor(
                when {
                    isEquals -> kit.palette.onAccent
                    isOperator -> kit.palette.accent
                    else -> kit.palette.text
                },
            )
            background = kit.ripple(
                kit.rounded(
                    when {
                        isEquals -> kit.palette.accent
                        isOperator -> kit.palette.accentSoft
                        else -> kit.palette.card
                    },
                    18,
                    if (isEquals) 0 else kit.palette.stroke,
                    if (isEquals) 0 else 1,
                ),
                18,
            )
            isClickable = true
            contentDescription = when (key) {
                "÷" -> "divide"
                "×" -> "multiply"
                "−" -> "subtract"
                "+" -> "add"
                "=" -> "equals"
                "C" -> "clear"
                "%" -> "percent"
                else -> key
            }
            setOnClickListener { onClick() }
        }
        return text
    }

    private fun renderTape(host: Activity, kit: OeaKit, prefs: android.content.SharedPreferences, box: LinearLayout) {
        box.removeAllViews()
        val lines = prefs.getString(KEY_TAPE, "").orEmpty().lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) {
            kit.note(box, "Nothing on the tape yet. Every result is kept here for as long as you like.")
            return
        }
        val group = kit.group(box)
        lines.forEach { line ->
            val row = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(kit.dp(14), kit.dp(11), kit.dp(14), kit.dp(11))
                isClickable = true
                setOnClickListener { copy(host, line) }
            }
            row.addView(kit.text(line, 14f, kit.palette.textSecondary).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(kit.iconView(com.oea.launcher.R.drawable.oic_text_fields, kit.palette.textTertiary, 16), LinearLayout.LayoutParams(kit.dp(18), kit.dp(18)))
            group.addView(row)
        }
        kit.note(box, "Tap a line to copy it.")
    }

    private fun copy(host: Activity, value: String) {
        runCatching {
            val clipboard = host.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("OEA calculator", value))
            android.widget.Toast.makeText(host, "Copied.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("oea_miniapps", Context.MODE_PRIVATE)

    /** 12.5 instead of 12.500000000000002, and no "-0". */
    private fun format(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "not a number"
        val rounded = if (abs(value) < 1e-12) 0.0 else value
        val asLong = rounded.roundToLong()
        if (abs(rounded - asLong) < 1e-9 && abs(rounded) < 1e15) return asLong.toString()
        val text = "%.6f".format(rounded).trimEnd('0').trimEnd('.')
        return text.ifEmpty { "0" }
    }

    /**
     * A tiny recursive-descent evaluator.
     *
     * Supports + − × ÷ % (both "percent of the previous value" and postfix
     * percent), unary minus, and brackets. It returns null for anything it cannot
     * finish, which the display reports as an incomplete expression — a friendlier
     * answer than "Error" while the user is still typing.
     */
    internal object Evaluator {
        fun evaluate(raw: String): Double? {
            // The keypad's own multiplying/dividing/minus signs are the symbols the
            // user sees; the parser works on the characters a keyboard would use.
            val text = raw
                .replace('×', '*')
                .replace('÷', '/')
                .replace('−', '-')
                .replace('—', '-')
                .replace(" ", "")
                .trimEnd('+', '-', '*', '/')
            if (text.isBlank()) return null
            val parser = Parser(text)
            val value = parser.expression() ?: return null
            return if (parser.atEnd()) value else null
        }

        private class Parser(private val text: String) {
            private var i = 0
            fun atEnd(): Boolean = i >= text.length

            fun expression(): Double? {
                var value = term() ?: return null
                while (i < text.length) {
                    when (text[i]) {
                        '+' -> { i++; value += term() ?: return null }
                        '-' -> { i++; value -= term() ?: return null }
                        else -> return value
                    }
                }
                return value
            }

            private fun term(): Double? {
                var value = factor() ?: return null
                while (i < text.length) {
                    when (text[i]) {
                        '*' -> { i++; value *= factor() ?: return null }
                        '/' -> {
                            i++
                            val divisor = factor() ?: return null
                            if (divisor == 0.0) return null
                            value /= divisor
                        }
                        else -> return value
                    }
                }
                return value
            }

            private fun factor(): Double? {
                if (i >= text.length) return null
                var sign = 1.0
                while (i < text.length && (text[i] == '-' || text[i] == '+')) {
                    if (text[i] == '-') sign = -sign
                    i++
                }
                val base = atom() ?: return null
                var value = sign * base
                // A trailing % means "per hundred", and % after a completed value
                // is the everyday "percent of what came before".
                while (i < text.length && text[i] == '%') {
                    i++
                    value /= 100.0
                }
                return value
            }

            private fun atom(): Double? {
                if (i >= text.length) return null
                if (text[i] == '(') {
                    i++
                    val value = expression() ?: return null
                    if (i < text.length && text[i] == ')') i++ else return null
                    return value
                }
                val start = i
                while (i < text.length && (text[i].isDigit() || text[i] == '.')) i++
                if (i == start) return null
                return text.substring(start, i).toDoubleOrNull()
            }
        }
    }
}
